// The bridge: Kodi's identity fields on browser items (Kodi plays its
// library copy), and Kodi items found on JustWatch (the Android app's
// Bridge, ported). Same cases as watchparty-tv's BridgeTest / RelayClientTest.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { roomItem, parseIdentity } from '../extension/links.js';
import { SyncCore, OTHER, PLAYING } from '../extension/sync-core.js';
import { JustWatch, pickTitle } from '../extension/justwatch.js';

test("items carry the title's identity in Kodi's fields", () => {
  assert.deepEqual(roomItem('https://www.disneyplus.com/play/x', 'Thunderbolts*', 'disneyplus',
    { type: 'movie', title: 'Thunderbolts*', year: 2025, ids: { tmdb: '986056' } }),
  { file: 'https://www.disneyplus.com/play/x', label: 'Thunderbolts*', title: 'Thunderbolts*', type: 'movie', year: 2025,
    ids: { android: 'com.disney.disneyplus', tmdb: '986056' } });
  const ep = roomItem('https://www.disneyplus.com/play/y', 'Andor · S1 · E1 · Kassa', 'disneyplus',
    { type: 'episode', show: 'Andor', season: 1, episode: 1, ids: {} });
  assert.deepEqual([ep.type, ep.show, ep.season, ep.episode, ep.title], ['episode', 'Andor', 1, 1, 'Andor · S1 · E1 · Kassa']);
  // no identity, or not enough of one: as before
  assert.equal(roomItem('https://x.example/a', 'A', null, { type: 'episode', show: 'Andor' }).type, undefined);
});

test("Kodi's items are read", () => {
  assert.deepEqual(parseIdentity({ file: 'nfs://nas/films/thunderbolts.mkv', type: 'movie', title: 'Thunderbolts*', year: 2025,
    ids: { tmdb: '986056', imdb: 'tt20969586' } }),
  { type: 'movie', title: 'Thunderbolts*', year: 2025, show: null, season: null, episode: null, ids: { tmdb: '986056', imdb: 'tt20969586' } });
  assert.equal(parseIdentity({ file: 'smb://nas/tv/andor/s01e01.mkv', type: 'episode', show: 'Andor', season: 1, episode: 1 }).show, 'Andor');
  assert.equal(parseIdentity({ file: 'x', type: 'song', title: 'Karma Police' }), null);
  assert.equal(parseIdentity({ file: 'x', label: 'x' }), null);
});

test('a Kodi title is looked up once, then followed with the start-up grace', () => {
  const core = new SyncCore();
  const identity = parseIdentity({ type: 'movie', title: 'Thunderbolts*', year: 2025, ids: { tmdb: '986056' } });
  const item = { file: 'nfs://nas/films/thunderbolts.mkv', label: 'Thunderbolts*', service: null, identity };
  const room = it => ({ seq: 1, item: it, positionSec: 100, paused: false, speed: 1, setAtSec: 1000, setByMe: false, members: [] });
  assert.deepEqual(core.tick(room(item), { service: null, positionMs: -1, play: OTHER }, 0, 1000),
    [{ type: 'resolve', file: item.file, label: 'Thunderbolts*', identity }]);
  assert.deepEqual(core.tick(room(item), { service: null, positionMs: -1, play: OTHER }, 1000, 1001), []);
  // found on Disney+ here: the page sets the item's service; then it's followed
  const found = { ...item, service: 'disneyplus' };
  assert.deepEqual(core.tick(room(found), { service: 'disneyplus', positionMs: 0, play: PLAYING }, 5000, 1005), []);
  assert.deepEqual(core.tick(room(found), { service: 'disneyplus', positionMs: 3000, play: PLAYING }, 8000, 1008), [{ type: 'seek', ms: 108000 }]);
});

test("Kodi's titles by TMDB id, IMDb id, or title and year", () => {
  const titles = [
    { id: 'tm1', objectType: 'MOVIE', title: 'Dune', year: 2021, tmdbId: '438631', imdbId: 'tt1160419' },
    { id: 'tm2', objectType: 'MOVIE', title: 'Dune', year: 1984, tmdbId: '841', imdbId: 'tt0087182' },
  ];
  assert.equal(pickTitle(titles, 'Dune', null, 'tt0087182').id, 'tm2');
  assert.equal(pickTitle(titles, 'Dune', null, null, 1984).id, 'tm2');
  assert.equal(pickTitle(titles, 'Dune', null, null, null).id, 'tm1');
  assert.equal(pickTitle(titles, 'Dune', '1', null, 2021), null);
});

test("a Kodi episode is found by its show's title, season and episode", async () => {
  const asked = [];
  const disney = n => ({ monetizationType: 'FLATRATE', deeplinkURL: `https://www.disneyplus.com/play/${n}`, standardWebURL: null,
    package: { technicalName: 'disneyplus', clearName: 'Disney Plus' } });
  const pluto = { monetizationType: 'FREE', deeplinkURL: null, standardWebURL: 'https://pluto.tv/us/shows/andor',
    package: { technicalName: 'plutotv', clearName: 'Pluto TV' } };
  const fake = async (_url, init) => {
    const { query, variables } = JSON.parse(init.body);
    asked.push(query.includes('popularTitles') ? `search ${variables.f.searchQuery} ${variables.f.objectTypes[0]}` : query.split('(')[0].trim());
    let data;
    if (query.includes('popularTitles')) data = { popularTitles: { edges: [{ node: { id: 'ts281017', objectType: 'SHOW', content: { title: 'Andor', originalReleaseYear: 2022, externalIds: { tmdbId: '83867' } } } }] } };
    else if (query.includes('seasons(')) data = { node: { seasons: [{ id: 'tss1', content: { seasonNumber: 1 } }] } };
    else data = { node: { episodes: [1, 2].map(n => ({ id: `tse${n}`, content: { episodeNumber: n, title: '' }, web: [disney(n), pluto], atv: [] })) } };
    return { status: 200, json: async () => ({ data }) };
  };
  const jw = new JustWatch('US', fake);
  const offers = await jw.offersFor(parseIdentity({ type: 'episode', show: 'Andor', season: 1, episode: 2, ids: { tvdb: '1' } }));
  assert.deepEqual(offers.map(o => o.link), ['https://www.disneyplus.com/play/2']);   // Pluto's show page left out
  assert.equal(asked[0], 'search Andor SHOW');
  assert.deepEqual(await jw.offersFor(parseIdentity({ type: 'episode', show: 'Andor', season: 3, episode: 1 })), []);
});
