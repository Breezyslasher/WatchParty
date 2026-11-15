// The Android app's JustWatchTest and HboMaxTest cases, ported: the same
// fixtures (trimmed from real JustWatch responses, tools/justwatch/jw.py on
// GitHub Actions, US, 2026-11-15) must give the same links in both.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseTitles, parsePage, parseSeasons, parseEpisodes, parseOffers, webLinkOf, sharedLinks,
  discoveryPlayLinks, runtimeText, posterUrl } from '../extension/justwatch.js';
import { regularEpisode, aslSeriesId, regularSeriesId, episodeId } from '../extension/hbomax.js';

const offer = (type, technical, clear, deeplink, web = null) =>
  ({ monetizationType: type, deeplinkURL: deeplink, standardWebURL: web, package: { technicalName: technical, clearName: clear } });

test('search results with their poster and TMDB id', () => {
  const titles = parseTitles({ data: { popularTitles: { edges: [
    { node: { id: 'tm1238268', objectType: 'MOVIE', content: { title: 'Thunderbolts*', originalReleaseYear: 2025,
      posterUrl: '/poster/329366258/{profile}/thunderbolts.{format}', externalIds: { tmdbId: '986056' } } } },
    { node: { id: 'ts156200', objectType: 'SHOW', content: { title: 'Severance', originalReleaseYear: 2014, externalIds: { tmdbId: null } } } },
  ] } } });
  assert.deepEqual(titles[0], { id: 'tm1238268', objectType: 'MOVIE', title: 'Thunderbolts*', year: 2025, tmdbId: '986056',
    poster: 'https://images.justwatch.com/poster/329366258/s332/thunderbolts.jpg', imdbId: null });
  assert.equal(titles[1].tmdbId, null);
  assert.equal(titles[1].poster, null);
  assert.throws(() => parseTitles({ errors: [{ message: 'rate limited' }] }), /rate limited/);
});

test("Netflix episodes use the episode's own web link, not the show's", () => {
  const episodes = parseEpisodes({ data: { node: { episodes: [{
    id: 'tse1126259', content: { episodeNumber: 1, title: 'Chapter One: The Vanishing of Will Byers' },
    web: [
      offer('FLATRATE', 'netflix', 'Netflix', 'https://www.netflix.com/watch/82946792', 'https://www.netflix.com/title/82939566'),
      offer('FLATRATE', 'netflix', 'Netflix', 'https://www.netflix.com/watch/80077368', 'https://www.netflix.com/title/80057281'),
      offer('FLATRATE', 'netflixbasicwithads', 'Netflix Standard with Ads', 'https://www.netflix.com/watch/82946792'),
    ],
    atv: [offer('FLATRATE', 'netflix', 'Netflix',
      'intent://www.netflix.com/watch/82939566#Intent;launchFlags=0x00800000;scheme=https;package=com.netflix.ninja;S.source=30;end')],
  }] } } });
  assert.equal(episodes.length, 1);
  assert.equal(episodes[0].number, 1);
  assert.deepEqual(episodes[0].offers.map(o => [o.technicalName, o.link]), [['netflix', 'https://www.netflix.com/watch/82946792']]);
});

test('Android TV links become playable https links, as on Android', () => {
  const offers = parseOffers({
    web: [offer('FLATRATE', 'disneyplus', 'Disney Plus', 'https://www.disneyplus.com/play/91310366-d44b-44a4-bdc4-71f999472826',
      'https://www.disneyplus.com/browse/entity-faba988a-a9f5-45f2-a074-0775a7d6f67a')],
    atv: [
      offer('FLATRATE', 'disneyplus', 'Disney Plus',
        'intent://www.disneyplus.com/play/91310366-d44b-44a4-bdc4-71f999472826#Intent;launchFlags=0x00800000;scheme=https;end'),
      offer('FLATRATE', 'max', 'HBO Max',
        'intent://play.hbomax.com/video/watch/dae9e532-3714-4f2e-b758-fb9a13def902?utm_source=universal_search#Intent;launchFlags=0x00800000;scheme=https;end'),
      offer('FLATRATE', 'appletvplus', 'Apple TV',
        'intent://tv.apple.com/us/episode/good-news-about-hell/umc.cmc.s80mx1ic96pu6ewupz8pfasf?showId=umc.cmc.1srk2goyh2q2zdxcx605w8vtx&playableId=tvs.sbd.4000%3AVSEVR0560101#Intent;launchFlags=0x00800000;scheme=https;end'),
      offer('FLATRATE', 'amazonprime', 'Amazon Prime Video',
        'intent://watch.amazon.com/watch?asin=B0CH88VRKB&time=0&territory=US#Intent;package=com.amazon.amazonvideo.livingroom;scheme=https;end'),
    ],
  });
  assert.deepEqual(offers.map(o => o.technicalName), ['disneyplus', 'max', 'appletvplus', 'amazonprime']);
  assert.equal(offers[0].link, 'https://www.disneyplus.com/play/91310366-d44b-44a4-bdc4-71f999472826');
  assert.equal(offers[1].link, 'https://play.hbomax.com/video/watch/dae9e532-3714-4f2e-b758-fb9a13def902');
  assert.equal(offers[2].link, 'https://tv.apple.com/us/episode/good-news-about-hell/umc.cmc.s80mx1ic96pu6ewupz8pfasf' +
    '?showId=umc.cmc.1srk2goyh2q2zdxcx605w8vtx&playableId=tvs.sbd.4000%3AVSEVR0560101&action=play');
  // a link without a rule is kept exactly (as the Android app shares it)
  assert.equal(offers[3].link, 'https://watch.amazon.com/watch?asin=B0CH88VRKB&time=0&territory=US');
});

test('rent and buy are left out; the web page when there is no deeplink', () => {
  assert.deepEqual(parseOffers({
    web: [offer('RENT', 'itunes', 'Apple TV Store', null, 'https://tv.apple.com/us/movie/x/umc.cmc.1')],
    atv: [offer('BUY', 'amazon', 'Amazon Video', 'intent://watch.amazon.com/watch?asin=1#Intent;scheme=https;end')],
  }), []);
  assert.deepEqual(parseOffers({
    web: [offer('FLATRATE', 'tnt', 'TNT', null, 'https://www.tntdrama.com/movies/dune-theatrical')],
    atv: [offer('FLATRATE', 'tnt', 'TNT', null)],
  }).map(o => o.link), ['https://www.tntdrama.com/movies/dune-theatrical']);
});

test('custom schemes are not playable links', () => {
  assert.equal(webLinkOf('intent://play/123#Intent;scheme=fawesome;package=com.fawesome;end'), null);
  assert.equal(webLinkOf('fawesome://play/123'), null);
  assert.equal(webLinkOf('https://x.example/a'), 'https://x.example/a');
});

test('seasons, a title page, the show-level links and Discovery+ movies', () => {
  assert.deepEqual(parseSeasons({ data: { node: { seasons: [
    { id: 'tss62569', content: { seasonNumber: 1, title: 'Season 1' } },
    { id: 'tss70830', content: { seasonNumber: 2, title: null } }] } } }),
  [{ id: 'tss62569', number: 1, title: 'Season 1' }, { id: 'tss70830', number: 2, title: '' }]);

  const p = parsePage({ data: { node: {
    content: { title: 'The Last of Us', originalReleaseDate: '2023-01-15', runtime: 57, shortDescription: 'Twenty years…',
      posterUrl: '/poster/301981224/{profile}/the-last-of-us.{format}', ageCertification: 'TV-MA',
      genres: [{ translation: 'Action & Adventure' }, { translation: 'Drama' }],
      scoring: { imdbScore: 8.4, imdbVotes: 756329, tmdbScore: 8.394 } },
    similarTitlesV2: { edges: [{ node: { id: 'ts88632', objectType: 'SHOW', content: { title: 'Chernobyl', originalReleaseYear: 2019,
      posterUrl: '/poster/130849429/{profile}/chernobyl.{format}', externalIds: { tmdbId: '87108' } } } }] },
  } } });
  assert.equal(p.poster, 'https://images.justwatch.com/poster/301981224/s592/the-last-of-us.jpg');
  assert.deepEqual([p.score, p.scoreSource, p.runtime, p.certification], [8.394, 'TMDB', 57, 'TV-MA']);
  assert.deepEqual(p.similar.map(t => t.title), ['Chernobyl']);
  assert.equal(parsePage({ data: { node: { content: { title: 'X', ageCertification: '', scoring: { imdbScore: 7.1, tmdbScore: null } } } } }).scoreSource, 'IMDb');

  const pluto = { link: 'https://pluto.tv/us/shows/the-king-of-queens' };
  const ep1 = [{ link: 'https://www.disneyplus.com/play/a' }, pluto];
  const ep2 = [{ link: 'https://www.disneyplus.com/play/b' }, pluto];
  assert.deepEqual([...sharedLinks(ep1, ep2)], [pluto.link]);

  const discovery = discoveryPlayLinks([
    { link: 'https://play.hbomax.com/video/watch/ef8dbdd7-9527-47a2-b322-9be4d25bdcbf' },
    { link: 'https://www.discoveryplus.com/movie/1b2a3c4d-1111-2222-3333-444455556666' },
  ]);
  assert.equal(discovery[1].link, 'https://play.discoveryplus.com/video/watch/ef8dbdd7-9527-47a2-b322-9be4d25bdcbf');
  assert.equal(runtimeText(127), '2h 7m');
  assert.equal(runtimeText(120), '2h');
  assert.equal(posterUrl(null), null);
});

// HboMaxTest's fragments of HBO Max's public pages (page probe workflow, 2026-11-15)
const asl = '1f339dc7-e71d-4679-b0a6-58a85ab5d6d0';
const regular = '93ba22b1-833e-47ba-ae94-8ee7b9eefa9a';
const aslPage = `{"__typename":"Feature","featureId":"931163ce-bfbc-4bc5-b487-eea2ecf9ccd4","url":null,"imageUrlLink":"/video/watch/d0d982d3-590b-4fd3-a8f5-5af7a15e9d78","category":"Feature","genres":["Comedy"],"offeringDates":{"startDate":"2026-01-01T00:00:00Z","endDate":"2030-01-01T00:00:00Z"},"title":{"full_original":null,"short_original":null,"short":"A Movie (with ASL)","full":"A Movie (with ASL)"}},{"__typename":"Series","seriesId":"${asl}","hbomaxURL":null,"hbomaxId":"${asl}","category":"Series","seriesTitleId":null,"imageUrlLink":"/show/${asl}","genres":["Drama","Fantasy & Sci-Fi","Dystopian"],"brand":["HBOTV"],"ratingCode":[],"offeringDates":{"startDate":"2025-03-31T07:01:00Z","endDate":"2028-02-28T04:59:00Z"},"title":{"full_original":null,"short_original":null,"short":"The Last of Us (with ASL)","full":"The Last of Us (with ASL)"},"summary":{"short":"Featuring American Sign Language (ASL) interpretation."}}`;
const aslSeason = `"episodeUrl":"/show/${asl}/s1/e1-when-youre-lost-in-the-darkness-with-asl/dae9e532-3714-4f2e-b758-fb9a13def902","idref23":{"collectionId":"10684","firstItem":{"title":{"short":"The Last of Us","full":"The Last of Us"}},"title":{"short":"The Last of Us (with ASL) - YMAL"},"items":[{"__typename":"Series","seriesId":"${regular}","hbomaxURL":null,"hbomaxId":"${regular}","category":"Series","seriesTitleId":null,"imageUrlLink":"/show/${regular}","genres":["Drama"],"brand":["HBOTV"],"ratingCode":[],"offeringDates":{"startDate":"2023-04-21T00:00:00Z","endDate":"2040-05-25T03:59:00Z"},"title":{"full_original":null,"short_original":null,"short":"The Last of Us","full":"The Last of Us"}}]}`;
const regularSeason = `"episodeUrl":"/show/${regular}/s1/e10-other/00000000-0000-0000-0000-000000000010","episodeUrl":"/show/${regular}/s1/e1-when-youre-lost-in-the-darkness/c07ae042-082e-4b53-ba86-b6ac5e3004db","episodeUrl":"/show/${regular}/s1/e2-infected/50b6f9e5-a151-4b87-b51d-ac71a279912f"`;
const pages = {
  'https://www.hbomax.com/accessibility/asl': aslPage,
  [`https://www.hbomax.com/show/${asl}/s1`]: aslSeason,
  [`https://www.hbomax.com/show/${regular}/s1`]: regularSeason,
};

test("HBO Max's ASL edition is swapped for the regular episode", async () => {
  assert.equal(aslSeriesId(aslPage, 'The Last of Us'), asl);
  assert.equal(aslSeriesId(aslPage, 'The Last'), null);
  assert.equal(regularSeriesId(aslSeason, 'The Last of Us', asl), regular);
  assert.equal(episodeId(regularSeason, regular, 1, 1), 'c07ae042-082e-4b53-ba86-b6ac5e3004db');
  assert.equal(episodeId(regularSeason, regular, 1, 3), null);
  const fetchText = async url => { if (!(url in pages)) throw new Error('offline'); return pages[url]; };
  assert.equal(await regularEpisode('https://play.hbomax.com/video/watch/dae9e532-3714-4f2e-b758-fb9a13def902', 'The Last of Us', 1, 1, fetchText),
    'https://play.hbomax.com/video/watch/c07ae042-082e-4b53-ba86-b6ac5e3004db');
  const other = 'https://play.hbomax.com/video/watch/aaaaaaaa-1111-2222-3333-444444444444';
  assert.equal(await regularEpisode(other, 'Succession', 1, 1, fetchText), other);
  assert.equal(await regularEpisode(other, 'The Last of Us', 1, 1, async () => { throw new Error('offline'); }), other);
  assert.equal(await regularEpisode('https://www.disneyplus.com/play/x', 'The Last of Us', 1, 1, async () => { throw new Error('no'); }),
    'https://www.disneyplus.com/play/x');
});
