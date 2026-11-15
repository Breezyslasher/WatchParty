// Browse: search JustWatch for a movie or show, see its page, pick a season
// and episode, and start it for everyone on one of its services — the same
// lookups and the same links as the Android app's Browse without Yamtrack,
// so a title started from a browser opens the same way on TVs and phones.

import { JustWatch, discoveryPlayLinks, sharedLinks, runtimeText } from './justwatch.js';
import { regularEpisode, isHboMax } from './hbomax.js';
import { serviceOf, SERVICE_NAMES } from './links.js';

const COUNTRIES = ['US', 'CA', 'GB', 'IE', 'AU', 'NZ', 'DE', 'AT', 'CH', 'FR', 'BE', 'NL', 'ES', 'IT', 'PT',
  'SE', 'NO', 'DK', 'FI', 'PL', 'BR', 'MX', 'AR', 'IN', 'JP', 'KR', 'ZA'];

/** el('div', {class: 'x', onclick: f}, child, 'text', …) */
function el(tag, attrs = {}, ...children) {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (v == null || v === false) continue;
    if (k.startsWith('on')) e.addEventListener(k.slice(2), v);
    else if (k === 'class') e.className = v;
    else e.setAttribute(k, v === true ? '' : v);
  }
  for (const c of children.flat()) if (c != null && c !== false) e.append(c);
  return e;
}

function date(s) {
  if (!s) return null;
  const d = new Date(s + 'T00:00:00');
  return Number.isNaN(d.getTime()) ? s : d.toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' });
}

/**
 * Sets up the Browse card. [start](link, label) shares a title with the
 * room; [inRoom]() says whether there is one to share with.
 */
export function initBrowse({ start, inRoom }) {
  const $ = id => document.getElementById(id);
  let type = 'MOVIE';
  let generation = 0;   // searches and title pages: a newer one discards older answers
  let seasonGen = 0;    // a title page's season picks
  let jw = new JustWatch(defaultCountry());

  const country = $('country');
  country.replaceChildren(...COUNTRIES.map(c => el('option', { value: c }, c)));
  if (!COUNTRIES.includes(jw.country)) country.prepend(el('option', { value: jw.country }, jw.country));
  country.value = jw.country;
  chrome.storage.local.get('country').then(s => {
    if (s.country) {
      jw = new JustWatch(s.country);
      country.value = s.country;
    }
  });
  country.addEventListener('change', () => {
    jw = new JustWatch(country.value);
    chrome.storage.local.set({ country: country.value });
    if ($('query').value.trim()) search();
  });

  for (const b of document.querySelectorAll('#types button')) {
    b.addEventListener('click', () => {
      type = b.dataset.type;
      for (const o of document.querySelectorAll('#types button')) o.classList.toggle('on', o === b);
      $('query').placeholder = type === 'SHOW' ? 'Search TV shows' : 'Search movies';
      if ($('query').value.trim()) search();
    });
  }
  $('search').addEventListener('click', search);
  $('query').addEventListener('keydown', e => { if (e.key === 'Enter') search(); });

  function defaultCountry() {
    const region = (navigator.language || 'en-US').split('-')[1];
    return region && /^[A-Za-z]{2}$/.test(region) ? region.toUpperCase() : 'US';
  }

  function hint(text, warn = false) {
    $('browseHint').textContent = text;
    $('browseHint').classList.toggle('warn', warn);
    $('browseHint').hidden = !text;
  }

  /** JustWatch (and HBO Max, for its ASL check): Firefox asks once; Chrome has them from install. */
  function access() {
    return chrome.permissions.request({ origins: ['https://apis.justwatch.com/*', 'https://*.hbomax.com/*'] })
      .catch(() => true);
  }

  async function search() {
    const text = $('query').value.trim();
    if (!text) return;
    const my = ++generation;
    if (!(await access())) {
      hint('Watch Party needs access to JustWatch to search.', true);
      return;
    }
    showResults();
    $('results').replaceChildren();
    hint('Searching…');
    try {
      const titles = await jw.search(text, type);
      if (my !== generation) return;
      hint(titles.length ? '' : 'No matches.');
      $('results').replaceChildren(...titles.map(posterCard));
    } catch (e) {
      if (my === generation) hint(`Couldn't search: ${e.message}`, true);
    }
  }

  function posterCard(t) {
    return el('button', { class: 'poster', onclick: () => openTitle(t), title: t.title },
      t.poster ? el('img', { src: t.poster, alt: '', loading: 'lazy' }) : el('div', { class: 'noimg' }),
      el('span', { class: 'ptitle' }, t.title),
      el('span', { class: 'pmeta' }, [t.objectType === 'SHOW' ? 'TV' : 'Movie', t.year || null].filter(Boolean).join(' · ')));
  }

  function showResults() {
    $('results').hidden = false;
    $('title').hidden = true;
    $('title').replaceChildren();
  }

  // ── a title's page ──────────────────────────────────────────────────────

  async function openTitle(t) {
    const my = ++generation;
    const show = t.objectType === 'SHOW';
    hint('');
    $('results').hidden = true;
    const page = $('title');
    page.hidden = false;
    const facts = el('div', { class: 'facts' });
    const genres = el('div', { class: 'tags' });
    const about = el('p', { class: 'about' });
    const body = el('div', { class: 'tbody' }, el('p', { class: 'faint' }, 'Loading…'));
    const more = el('div');
    page.replaceChildren(
      el('button', { class: 'link', onclick: () => { generation++; showResults(); } }, '← Results'),
      el('div', { class: 'hero' },
        t.poster ? el('img', { class: 'big', src: t.poster, alt: '' }) : el('div', { class: 'big noimg' }),
        el('div', { class: 'heroText' },
          el('div', { class: 'faint' }, [show ? 'TV show' : 'Movie', t.year || null].filter(Boolean).join(' · ')),
          el('h3', {}, t.title), facts, genres, about)),
      body, more);
    page.scrollIntoView({ block: 'start', behavior: 'smooth' });

    jw.page(t.id).then(p => {
      if (my !== generation || !p) return;
      facts.replaceChildren(
        p.score != null ? el('span', { class: 'score' }, el('b', {}, p.score.toFixed(1)), ` ${p.scoreSource}`) : null,
        el('span', {}, [show ? (p.date && `First aired ${date(p.date)}`) : date(p.date),
          p.runtime && runtimeText(p.runtime), p.certification].filter(Boolean).join(' · ')));
      genres.replaceChildren(...p.genres.slice(0, 5).map(g => el('span', { class: 'tag' }, g)));
      about.textContent = p.synopsis || '';
      if (p.similar.length) {
        more.replaceChildren(el('div', { class: 'label space' }, 'More like this'),
          el('div', { class: 'rail' }, p.similar.map(posterCard)));
      }
    }).catch(() => { /* the page works without it */ });

    try {
      if (!show) {
        const offers = await jw.offers(t.id);
        if (my !== generation) return;
        body.replaceChildren(el('div', { class: 'label space' }, 'Start for everyone on'), services(offers, t.title, 'Movie'));
      } else {
        const seasons = await jw.seasons(t.id);
        if (my !== generation) return;
        if (!seasons.length) {
          body.replaceChildren(el('p', { class: 'faint' }, `No seasons found for "${t.title}".`));
          return;
        }
        const chips = el('div', { class: 'chips' });
        const episodes = el('div', { class: 'episodes' });
        body.replaceChildren(el('div', { class: 'label space' }, 'Season'), chips, episodes);
        const pickSeason = async (s, chip) => {
          for (const c of chips.children) c.classList.toggle('on', c === chip);
          const mySeason = ++seasonGen;
          episodes.replaceChildren(el('p', { class: 'faint' }, 'Loading…'));
          try {
            const eps = await jw.episodes(s.id);
            if (mySeason !== seasonGen || my !== generation) return;
            episodes.replaceChildren(...(eps.length ? eps.map(e => episodeRow(t, s, e, eps))
              : [el('p', { class: 'faint' }, 'No episodes.')]));
          } catch (e) {
            if (mySeason === seasonGen && my === generation) episodes.replaceChildren(el('p', { class: 'warn' }, `Couldn't load it: ${e.message}`));
          }
        };
        const chipFor = new Map();
        for (const s of seasons) {
          const chip = el('button', { class: 'chip' }, s.number === 0 ? 'Specials' : `Season ${s.number}`);
          chip.addEventListener('click', () => pickSeason(s, chip));
          chips.append(chip);
          chipFor.set(s, chip);
        }
        // season 1 first (Specials are season 0)
        const first = seasons.find(s => s.number > 0) || seasons[0];
        pickSeason(first, chipFor.get(first));
      }
    } catch (e) {
      if (my === generation) body.replaceChildren(el('p', { class: 'warn' }, `Couldn't look it up: ${e.message}`));
    }
  }

  function episodeRow(t, s, e, all) {
    const sub = `S${s.number} · E${e.number}` + (e.title ? ` · ${e.title}` : '');
    const where = el('div', { class: 'where' });
    const row = el('div', { class: 'episode' },
      el('button', { class: 'ep', onclick: () => {
        const open = !where.hasChildNodes();
        for (const w of row.parentElement.querySelectorAll('.where')) w.replaceChildren();
        if (!open) return;
        // a link the next (or previous) episode shares is the show's page, not this episode
        const other = all.find(o => o.number === e.number + 1) || all.find(o => o.number === e.number - 1);
        const showLevel = other ? sharedLinks(e.offers, other.offers) : new Set();
        where.replaceChildren(services(e.offers, t.title, sub, showLevel, { season: s.number, episode: e.number }));
      } }, el('b', {}, `Episode ${e.number}`), el('span', { class: 'faint' }, e.title || '')),
      where);
    return row;
  }

  /** The services for a movie or an episode, each with Start. */
  function services(offers, showTitle, sub, showLevel = new Set(), episode = null) {
    offers = discoveryPlayLinks(offers);
    if (!offers.length) return el('p', { class: 'faint' }, `No streaming service has this in ${jw.country}.`);
    const note = el('p', { class: 'faint small' });
    const list = el('div', { class: 'services' }, offers.map(o => {
      const service = serviceOf(o.link);
      const pageOnly = showLevel.has(o.link);
      const btn = el('button', { class: 'primary' }, 'Start');
      btn.addEventListener('click', async () => {
        if (!inRoom()) {
          note.textContent = 'Join a room first (above), then start it here.';
          note.classList.add('warn');
          return;
        }
        btn.disabled = true;
        let link = o.link;
        // JustWatch's HBO Max link can be the "(with ASL)" edition: use the regular one
        if (episode && isHboMax(link)) {
          note.textContent = 'Checking HBO Max…';
          link = await regularEpisode(link, showTitle, episode.season, episode.episode);
        }
        const label = episode ? `${showTitle} · ${sub}` : showTitle;
        try {
          await start(link, label);
          note.textContent = `Started for everyone: ${label} on ${o.clearName}`;
          note.classList.remove('warn');
        } catch (e) {
          note.textContent = `Couldn't start it: ${e.message}`;
          note.classList.add('warn');
        }
        btn.disabled = false;
      });
      return el('div', { class: 'service' },
        o.icon ? el('img', { src: o.icon, alt: '' }) : el('div', { class: 'noicon' }),
        el('div', { class: 'sname' }, el('b', {}, o.clearName),
          el('span', { class: 'faint small' }, pageOnly ? "Opens the show's page: pick the episode"
            : service ? `Syncs here as ${SERVICE_NAMES[service]}` : "Opens here, but this site can't be synced")),
        btn);
    }));
    return el('div', {}, list, note);
  }
}
