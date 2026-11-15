// Search, title pages, seasons, episodes and where to watch, from JustWatch:
// a port of the Android app's JustWatch.kt — the same queries (checked
// against the live API with tools/justwatch/jw.py) and the same choice of
// link per service, so a title picked in a browser is shared as exactly the
// link a TV or phone would share, and every device starts it the same way.

import { normalize } from './links.js';

const API = 'https://apis.justwatch.com/graphql';
const IMAGES = 'https://images.justwatch.com';
const STREAMING = new Set(['FLATRATE', 'FREE', 'ADS', 'FAST']);

const TITLE_FIELDS = `id objectType
  content(country: $c, language: $l) { title originalReleaseYear posterUrl externalIds { tmdbId } }`;

const SEARCH = `query S($f: TitleFilter!, $c: Country!, $l: Language!, $first: Int!) {
  popularTitles(country: $c, filter: $f, first: $first, sortBy: POPULAR, sortRandomSeed: 0) {
    edges { node { ${TITLE_FIELDS} } }
  }
}`;

const PAGE = `query T($id: ID!, $c: Country!, $l: Language!) {
  node(id: $id) { ... on MovieOrShow {
    content(country: $c, language: $l) {
      title originalReleaseDate runtime shortDescription posterUrl ageCertification
      genres { translation(language: $l) }
      scoring { imdbScore imdbVotes tmdbScore }
    }
    similarTitlesV2(country: $c, first: 20) { edges { node { ${TITLE_FIELDS} } } }
  } }
}`;

const OFFER_FIELDS = `
  web: offers(country: $c, platform: WEB) {
    monetizationType standardWebURL deeplinkURL(platform: WEB) package { technicalName clearName icon(profile: S100, format: PNG) }
  }
  atv: offers(country: $c, platform: ANDROID_TV) {
    monetizationType deeplinkURL(platform: ANDROID_TV) package { technicalName clearName }
  }`;

const TITLE_OFFERS = `query O($id: ID!, $c: Country!) {
  node(id: $id) { ... on MovieOrShowOrSeasonOrEpisode { ${OFFER_FIELDS} } }
}`;

const SEASONS = `query S($id: ID!, $c: Country!, $l: Language!) {
  node(id: $id) { ... on Show {
    seasons(sortDirection: ASC) { id content(country: $c, language: $l) { seasonNumber title } }
  } }
}`;

const SEASON_EPISODES = `query E($id: ID!, $c: Country!, $l: Language!) {
  node(id: $id) { ... on Season {
    episodes(sortDirection: ASC) {
      id content(country: $c, language: $l) { episodeNumber title }
      ${OFFER_FIELDS}
    }
  } }
}`;

// ── network ───────────────────────────────────────────────────────────────

export class JustWatch {
  constructor(country = 'US', fetchImpl = (...a) => fetch(...a)) {
    this.country = country;
    this.fetch = fetchImpl;
  }

  /** Titles matching query, most popular first. objectType: "MOVIE" or "SHOW". */
  async search(query, objectType, first = 30) {
    return parseTitles(await this.post(SEARCH, {
      f: { searchQuery: query, objectTypes: [objectType] }, c: this.country, l: 'en', first,
    }));
  }

  /** A title's page: description, genres, rating, similar titles. */
  async page(id) {
    return parsePage(await this.post(PAGE, { id, c: this.country, l: 'en' }));
  }

  async offers(id) {
    const r = await this.post(TITLE_OFFERS, { id, c: this.country });
    return parseOffers(data(r).node);
  }

  async seasons(showId) {
    return parseSeasons(await this.post(SEASONS, { id: showId, c: this.country, l: 'en' }));
  }

  async episodes(seasonId) {
    return parseEpisodes(await this.post(SEASON_EPISODES, { id: seasonId, c: this.country, l: 'en' }));
  }

  async post(query, variables) {
    let res;
    try {
      res = await this.fetch(API, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ query, variables }),
        signal: AbortSignal.timeout(10000),
      });
    } catch (e) {
      throw new Error(`JustWatch unreachable (${e.message || e})`);
    }
    if (res.status === 429) throw new Error('JustWatch is busy (too many requests) — try again in a minute');
    try {
      return await res.json();
    } catch (e) {
      throw new Error(`JustWatch sent HTTP ${res.status}`);
    }
  }
}

// ── pure parts (tested in node) ───────────────────────────────────────────

function data(response) {
  const errors = response && response.errors;
  if (Array.isArray(errors) && errors.length) throw new Error('JustWatch: ' + (errors[0].message || 'error'));
  return (response && response.data) || {};
}

const text = v => (typeof v === 'string' && v.length ? v : (typeof v === 'number' ? String(v) : null));

/** "/poster/329366258/{profile}/thunderbolts.{format}" → its profile's jpg. */
export function posterUrl(path, profile = 's332') {
  if (!path) return null;
  const filled = path.replace('{profile}', profile).replace('{format}', 'jpg');
  return filled.startsWith('/') ? IMAGES + filled : filled;
}

function title(node) {
  const content = node && node.content;
  if (!content) return null;
  return {
    id: String(node.id || ''),
    objectType: String(node.objectType || ''),
    title: String(content.title || ''),
    year: Number(content.originalReleaseYear) || 0,
    tmdbId: text(content.externalIds && content.externalIds.tmdbId),
    poster: posterUrl(content.posterUrl),
  };
}

export function parseTitles(response) {
  const edges = (data(response).popularTitles || {}).edges || [];
  return edges.map(e => title(e && e.node)).filter(Boolean);
}

const score = v => (typeof v === 'number' && v > 0 ? v : null);

export function parsePage(response) {
  const node = data(response).node;
  const content = node && node.content;
  if (!content) return null;
  const scoring = content.scoring || {};
  const tmdb = score(scoring.tmdbScore);
  const imdb = score(scoring.imdbScore);
  return {
    title: String(content.title || ''),
    poster: posterUrl(content.posterUrl, 's592'),
    synopsis: text(content.shortDescription),
    genres: (content.genres || []).map(g => text(g && g.translation)).filter(Boolean),
    score: tmdb ?? imdb,
    scoreSource: tmdb != null ? 'TMDB' : 'IMDb',
    date: text(content.originalReleaseDate),
    runtime: Number(content.runtime) > 0 ? Number(content.runtime) : null,
    certification: text(content.ageCertification),
    similar: (((node.similarTitlesV2 || {}).edges) || []).map(e => title(e && e.node)).filter(Boolean),
  };
}

export function parseSeasons(response) {
  return ((data(response).node || {}).seasons || []).map(s => ({
    id: String(s.id || ''),
    number: Number(s.content && s.content.seasonNumber) || 0,
    title: text(s.content && s.content.title) || '',
  }));
}

export function parseEpisodes(response) {
  return ((data(response).node || {}).episodes || []).map(e => ({
    id: String(e.id || ''),
    number: Number(e.content && e.content.episodeNumber) || 0,
    title: text(e.content && e.content.title) || '',
    offers: parseOffers(e),
  }));
}

/**
 * One offer per service from a node's web and atv lists (streaming only, no
 * rent or buy), each with the link that starts it on a TV — the Android
 * app's choice, so both share the same link.
 */
export function parseOffers(node) {
  if (!node) return [];
  const streaming = list => (list || []).filter(o => o && STREAMING.has(o.monetizationType));
  const web = streaming(node.web);
  const atv = streaming(node.atv);
  const technical = o => (o.package && o.package.technicalName) || '';
  const names = new Map();
  for (const o of [...web, ...atv]) if (!names.has(technical(o))) names.set(technical(o), (o.package && o.package.clearName) || '');
  const out = [];
  for (const [tech, clear] of names) {
    const w = web.find(o => technical(o) === tech);
    const a = atv.find(o => technical(o) === tech);
    const link = linkFor(tech, w && text(w.deeplinkURL), a && text(a.deeplinkURL), w && text(w.standardWebURL));
    if (!link) continue;
    const icon = text(((w || a).package || {}).icon);
    // "Netflix" and "Netflix Standard with Ads" are the same app and link
    if (!out.some(o => o.link === link)) {
      out.push({ technicalName: tech, clearName: clear, link, icon: icon && icon.startsWith('/') ? IMAGES + icon : icon });
    }
  }
  return out;
}

/**
 * The link that starts the title on a TV. Netflix: its WEB deeplink (the
 * episode's own /watch/<id>; the ANDROID_TV one is the show's). Others: the
 * ANDROID_TV deeplink as an https link, else the WEB deeplink, else the web
 * page. Then the Android app's per-service fixes.
 */
export function linkFor(technicalName, webDeeplink, atvDeeplink, standardWeb) {
  const netflixWatch = webDeeplink && technicalName.startsWith('netflix') && webDeeplink.includes('netflix.com/watch/')
    ? webDeeplink : null;
  const raw = netflixWatch
    || (atvDeeplink && webLinkOf(atvDeeplink))
    || (webDeeplink && webLinkOf(webDeeplink))
    || (standardWeb && webLinkOf(standardWeb));
  if (!raw) return null;
  return normalize(raw, { strip: false }) || raw;
}

/** "intent://host/path?q#Intent;…;scheme=https;end" → "https://host/path?q";
 *  plain http(s) links pass through; other schemes → null. */
export function webLinkOf(deeplink) {
  if (!deeplink.startsWith('intent://')) return /^https?:\/\//.test(deeplink) ? deeplink : null;
  const hash = deeplink.indexOf('#Intent;');
  if (hash < 0) return null;
  const tail = deeplink.slice(hash);
  const at = tail.indexOf(';scheme=');
  if (at < 0) return null;
  const end = tail.indexOf(';', at + 1);
  const scheme = end < 0 ? tail.slice(at + 8) : tail.slice(at + 8, end);
  if (scheme !== 'https' && scheme !== 'http') return null;
  return scheme + '://' + deeplink.slice('intent://'.length, hash);
}

/** Links two episodes share: a service with the same link for every episode
 *  (Pluto TV's show page, for some shows) has no episode link. */
export function sharedLinks(episode, otherEpisode) {
  const other = new Set(otherEpisode.map(o => o.link));
  return new Set(episode.map(o => o.link).filter(l => other.has(l)));
}

/** Discovery+ movie links are its movie page, which doesn't play; HBO Max's
 *  video id plays in Discovery+ too (the same catalog), so when the title is
 *  on both, Discovery+ gets that play link. */
export function discoveryPlayLinks(offers) {
  let video = null;
  for (const o of offers) {
    const m = o.link.match(/(?:hbo)?max\.com\/video\/watch\/([0-9a-f-]{36})/);
    if (m) { video = m[1]; break; }
  }
  if (!video) return offers;
  return offers.map(o => (/discoveryplus\.com\/movie\/[0-9a-f-]{36}/.test(o.link)
    ? { ...o, link: `https://play.discoveryplus.com/video/watch/${video}` } : o));
}

/** 127 → "2h 7m", 120 → "2h", 57 → "57m". */
export function runtimeText(minutes) {
  if (minutes < 60) return `${minutes}m`;
  if (minutes % 60 === 0) return `${minutes / 60}h`;
  return `${Math.floor(minutes / 60)}h ${minutes % 60}m`;
}
