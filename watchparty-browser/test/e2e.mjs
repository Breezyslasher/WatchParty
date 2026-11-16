// End to end, in Chromium with the extension loaded: a scripted room member
// (relay.js in node) starts a title; the extension opens it in a player tab
// and follows play / pause / seek; a pause and seek in the browser reach the
// room; a link pasted into Browse's search, and Browse (search, title page, Watch, the services
// dialog, season and episode) start titles with the Android app's links.
//
// Streaming sites are stood in for by a test page with a real <video>
// (Playwright answers https://www.disneyplus.com/… and …netflix.com/… with
// it), so this tests the extension, not the sites' own players.
//
//   WATCHPARTY_TEST_RELAY=http://127.0.0.1:8765 node test/e2e.mjs [shots-dir]
import { createRequire } from 'node:module';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { RelayClient } from '../extension/relay.js';

const require = createRequire(import.meta.url);
const { chromium } = require('playwright');

const RELAY = process.env.WATCHPARTY_TEST_RELAY || 'http://127.0.0.1:8765';
const SHOTS = process.argv[2] || '';
const here = path.dirname(fileURLToPath(import.meta.url));
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'wp-e2e-'));

// the extension, plus the test relay's origin as a granted host (a real user grants it at Join)
const ext = path.join(tmp, 'extension');
fs.cpSync(path.join(here, '..', 'extension'), ext, { recursive: true });
const manifest = JSON.parse(fs.readFileSync(path.join(ext, 'manifest.json'), 'utf8'));
manifest.host_permissions.push(`${new URL(RELAY).protocol}//${new URL(RELAY).hostname}/*`);
fs.writeFileSync(path.join(ext, 'manifest.json'), JSON.stringify(manifest));

// a 3-minute test video
const video = path.join(tmp, 'test.webm');
execFileSync('ffmpeg', ['-loglevel', 'error', '-f', 'lavfi', '-i', 'testsrc=duration=180:size=320x180:rate=10',
  '-c:v', 'libvpx', '-b:v', '80k', '-g', '10', video]);
const videoBytes = fs.readFileSync(video);
const page = title => `<!doctype html><title>${title}</title><body style="margin:0;background:#000">
  <video id="v" src="/test.webm" autoplay muted playsinline style="width:100vw;height:100vh"></video></body>`;

const sleep = ms => new Promise(r => setTimeout(r, ms));
async function until(what, check, timeoutMs = 20000) {
  const end = Date.now() + timeoutMs;
  let last;
  while (Date.now() < end) {
    try {
      last = await check();
      if (last) return last;
    } catch (e) {
      last = e.message;
    }
    await sleep(250);
  }
  throw new Error(`timed out: ${what} (last: ${JSON.stringify(last)})`);
}
let failures = 0;
function ok(cond, what) {
  console.log(`${cond ? 'ok  ' : 'FAIL'} ${what}`);
  if (!cond) failures++;
}

const context = await chromium.launchPersistentContext(path.join(tmp, 'profile'), {
  headless: true,
  channel: 'chromium',   // full Chromium (new headless): the headless shell has no extensions
  args: [`--disable-extensions-except=${ext}`, `--load-extension=${ext}`, '--autoplay-policy=no-user-gesture-required'],
  viewport: { width: 1440, height: 860 },
});
// stand-ins for the sites; the video with Range support so it can seek
await context.route(/^https:\/\/www\.(disneyplus|netflix)\.com\//, async route => {
  const url = new URL(route.request().url());
  if (url.pathname === '/test.webm') {
    const range = /bytes=(\d+)-(\d*)/.exec(route.request().headers().range || '');
    const start = range ? Number(range[1]) : 0;
    const end = range && range[2] ? Number(range[2]) : videoBytes.length - 1;
    return route.fulfill({
      status: range ? 206 : 200,
      headers: {
        'Content-Type': 'video/webm', 'Accept-Ranges': 'bytes',
        ...(range ? { 'Content-Range': `bytes ${start}-${end}/${videoBytes.length}` } : {}),
      },
      body: videoBytes.subarray(start, end + 1),
    });
  }
  return route.fulfill({ status: 200, contentType: 'text/html', body: page(url.hostname.includes('netflix') ? 'Iron Man - Netflix' : 'Thunderbolts* | Disney+') });
});

// JustWatch's API, answered with responses shaped like the live API's (see justwatch.test.js)
const icon = fs.readFileSync(path.join(ext, 'icons', 'icon128.png'));
await context.route(/^https:\/\/images\.justwatch\.com\//, route => route.fulfill({ status: 200, contentType: 'image/png', body: icon }));
const intent = url => `intent://${url.replace('https://', '')}#Intent;launchFlags=0x00800000;scheme=https;end`;
const offers = (disneyId, extra = []) => ({
  web: [{ monetizationType: 'FLATRATE', deeplinkURL: `https://www.disneyplus.com/play/${disneyId}`, standardWebURL: null,
    package: { technicalName: 'disneyplus', clearName: 'Disney Plus', icon: '/icon/313118777/{profile}/disneyplus.{format}' } }, ...extra],
  atv: [{ monetizationType: 'FLATRATE', deeplinkURL: intent(`https://www.disneyplus.com/play/${disneyId}`),
    package: { technicalName: 'disneyplus', clearName: 'Disney Plus' } }],
});
const pluto = { monetizationType: 'FREE', deeplinkURL: null, standardWebURL: 'https://pluto.tv/us/shows/andor',
  package: { technicalName: 'plutotv', clearName: 'Pluto TV', icon: '/icon/1/{profile}/pluto.{format}' } };
const jwNode = (id, objectType, title, year, tmdbId) =>
  ({ id, objectType, content: { title, originalReleaseYear: year, posterUrl: `/poster/1/{profile}/${id}.{format}`, externalIds: { tmdbId } } });
const jwRequests = [];
await context.route('https://apis.justwatch.com/graphql', async route => {
  const { query, variables } = route.request().postDataJSON();
  jwRequests.push(route.request().headers()['origin'] || '');
  let data;
  if (query.includes('popularTitles')) {
    data = { popularTitles: { edges: variables.f.objectTypes[0] === 'SHOW'
      ? [{ node: jwNode('ts281017', 'SHOW', 'Andor', 2022, '83867') }]
      : [{ node: jwNode('tm1238268', 'MOVIE', 'Thunderbolts*', 2025, '986056') }, { node: jwNode('tm1633861', 'MOVIE', 'Thunderbolts* | A Special Look', 2025, '1477884') }] } };
  } else if (query.includes('similarTitlesV2')) {
    const show = variables.id.startsWith('ts');
    data = { node: { content: { title: show ? 'Andor' : 'Thunderbolts*', originalReleaseDate: show ? '2022-09-21' : '2025-04-30',
      runtime: show ? 45 : 127, ageCertification: show ? 'TV-14' : 'PG-13', posterUrl: `/poster/1/{profile}/${variables.id}.{format}`,
      shortDescription: show ? 'The tale of the burgeoning rebellion against the Empire.' : 'After finding themselves ensnared in a death trap, an unconventional team of antiheroes must go on a dangerous mission.',
      genres: [{ translation: 'Action & Adventure' }, { translation: 'Science-Fiction' }],
      scoring: { imdbScore: 7.1, imdbVotes: 313489, tmdbScore: show ? 8.4 : 7.294 } },
      similarTitlesV2: { edges: ['Captain America: Brave New World', 'Deadpool & Wolverine', 'TRON: Ares'].map((t, i) => ({ node: jwNode(`tm9${i}`, 'MOVIE', t, 2025, null) })) } } };
  } else if (query.includes('seasons(')) {
    data = { node: { seasons: [{ id: 'tss1', content: { seasonNumber: 1, title: 'Season 1' } }, { id: 'tss2', content: { seasonNumber: 2, title: 'Season 2' } }] } };
  } else if (query.includes('episodes(')) {
    const titles = ['Kassa', 'That Would Be Me', 'Reckoning'];
    data = { node: { episodes: titles.map((t, i) => ({ id: `tse${i + 1}`, content: { episodeNumber: i + 1, title: t },
      ...offers(`9131036${i}-d44b-44a4-bdc4-71f999472826`, [pluto]) })) } };
  } else if (query.includes('offers(')) {
    data = { node: offers('f51dce23-7d8f-490a-9f0c-be1b5432c2a9') };
  }
  await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ data }) });
});

const worker = context.serviceWorkers()[0] || await context.waitForEvent('serviceworker');
const extId = new URL(worker.url()).host;
ok(!!extId, `extension loaded (${extId})`);

const code = 'E2E' + Math.floor(Math.random() * 1e6);
const roomPage = await context.newPage();
await roomPage.goto(`chrome-extension://${extId}/room.html`);
await roomPage.fill('#relay', RELAY);
await roomPage.fill('#room', code);
await roomPage.fill('#name', 'Laptop');
if (SHOTS) {
  fs.mkdirSync(SHOTS, { recursive: true });
  await roomPage.screenshot({ path: path.join(SHOTS, '1-room-idle.png') });
}
await roomPage.click('#join');
await until('joined', () => roomPage.isVisible('.roomCode'));
ok(true, 'joined the room from the room page');

// the scripted member: polls like a device that isn't playing anything
const other = new RelayClient(RELAY, code);
await other.join('Living room TV');
let state = null;
let polling = true;
(async () => {
  while (polling) {
    try {
      state = await other.poll({ positionSec: 0, paused: true, file: '', caching: false, onItem: false, corrections: 0 });
    } catch (e) { /* keep going */ }
    await sleep(500);
  }
})();
await sleep(1500);
ok(state && state.members.length === 2, 'both members are in the room');

// 1. someone starts a title: the browser opens it in a player tab
const link = 'https://www.disneyplus.com/play/f51dce23-7d8f-490a-9f0c-be1b5432c2a9';
await other.command('open', 0, { file: link, label: 'Thunderbolts*', title: 'Thunderbolts*', ids: { android: 'com.disney.disneyplus' } });
const player = await until('player tab opened', () => context.pages().find(p => p.url() === link));
ok(true, 'the title opened in a new tab');
const t = () => player.evaluate(() => document.getElementById('v').currentTime);
const paused = () => player.evaluate(() => document.getElementById('v').paused);
await until('video playing', async () => !(await paused()) && (await t()) > 0.5);
ok(true, 'it plays');

// 2. the room seeks: the browser follows (after its 3 s start-up)
await other.command('seek', 60);
await until('followed the seek to 60 s', async () => Math.abs((await t()) - 62) < 6);
ok(true, `followed a seek to 60 s (now ${(await t()).toFixed(1)} s)`);

// 3. the room pauses and resumes
await other.command('pause', 70);
await until('paused at 70 s', async () => (await paused()) && Math.abs((await t()) - 70) < 3, 15000);
ok(true, `followed a pause at 70 s (at ${(await t()).toFixed(1)} s)`);
await other.command('play', 70);
await until('playing again', async () => !(await paused()), 15000);
ok(true, 'followed play');

// 4. pause and seek in the browser reach the room
await sleep(5000);   // past the suppression of our own last command
await player.evaluate(() => document.getElementById('v').pause());
await until('room paused by the browser', () => state && state.paused, 15000);
ok(true, `a pause in the browser paused the room (at ${state.positionSec.toFixed(1)} s)`);
await sleep(5000);
await player.evaluate(() => { const v = document.getElementById('v'); v.currentTime = 120; });
await until('room at 120 s', () => state && Math.abs(state.positionSec - 120) < 3, 15000);
ok(true, `a seek in the browser moved the room (to ${state.positionSec.toFixed(1)} s)`);
await sleep(6000);
const roomAt = state.positionSec;
const browserAt = await t();
ok(Math.abs(roomAt - 120) < 3 && Math.abs(browserAt - 120) < 3,
  `6 s later the room (${roomAt.toFixed(1)} s) and the browser (${browserAt.toFixed(1)} s) are both still at 120 s`);
const me = state.members.find(m => m.name === 'Laptop');
ok(me && me.driftSec != null && Math.abs(me.driftSec) < 3, `the relay sees the browser in sync (drift ${me && me.driftSec})`);

// 5. A link pasted into Browse's search: the room gets the link the Android app would share
await roomPage.bringToFront();
if (SHOTS) await roomPage.screenshot({ path: path.join(SHOTS, '2-room-playing.png') });
ok(!(await roomPage.isVisible('.nav:has-text("Paste a link")')), 'no Paste a link screen');
await roomPage.click('.nav:has-text("Browse")');
await roomPage.fill('#query', 'https://www.netflix.com/title/70264888?trackId=14170286');
await roomPage.press('#query', 'Enter');
await until('the link result', () => roomPage.isVisible('#startLink'));
ok((await roomPage.textContent('.linkTitle')) === 'Netflix title', 'Search shows the link as one result, not a search');
if (SHOTS) await roomPage.screenshot({ path: path.join(SHOTS, '6-paste.png') });
await roomPage.click('#startLink');
await until('room has the Netflix title', () => state && state.item && state.item.file === 'https://www.netflix.com/watch/70264888');
ok(state.item.label === 'Netflix title', `pasted "${state.item.label}" as ${state.item.file}`);
const raw = await (await fetch(`${RELAY}/poll`, { method: 'POST', headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ room: code, member_id: other.memberId, position: 0, paused: true, file: '', caching: false, on_item: false, corrections: 0 }) })).json();
ok(raw.state.item.ids && raw.state.item.ids.android === 'com.netflix.ninja', 'the item names an Android app, so TVs and phones open it');
await until('the browser opened it', () => context.pages().some(p => p.url() === 'https://www.netflix.com/watch/70264888'));
ok(true, 'and the browser opened it in its player tab');
await roomPage.bringToFront();
await until('Room shows it', () => roomPage.isVisible('.nowTitle:has-text("Netflix title")'));
ok(true, 'the Room screen shows it');

// 6. Browse: search JustWatch, Watch a movie — one service, so it starts at once (as on Android)
await roomPage.click('.nav:has-text("Browse")');
await roomPage.fill('#query', 'Thunderbolts');
await roomPage.press('#query', 'Enter');
await until('search results', () => roomPage.$$eval('.grid .poster', ps => ps.length === 2));
ok(true, 'searched JustWatch (movies)');
if (SHOTS) await roomPage.screenshot({ path: path.join(SHOTS, '3-browse.png') });
await roomPage.click('.grid .poster');
await until('title page details', () => roomPage.isVisible('.rating:has-text("7.3")'));
ok(true, 'the title page shows the rating, facts and genres');
if (SHOTS) await roomPage.screenshot({ path: path.join(SHOTS, '4-title-movie.png'), fullPage: true });
await roomPage.click('button:has-text("Watch")');
const movie = 'https://www.disneyplus.com/play/f51dce23-7d8f-490a-9f0c-be1b5432c2a9';
await until('room has the movie', () => state && state.item && state.item.file === movie);
ok(state.item.label === 'Thunderbolts*', `Watch started "${state.item.label}" as ${state.item.file}`);
const rawMovie = (await (await fetch(`${RELAY}/poll`, { method: 'POST', headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ room: code, member_id: other.memberId, position: 0, paused: true, file: '', caching: false, on_item: false, corrections: 0 }) })).json()).state.item;
ok(rawMovie.type === 'movie' && rawMovie.title === 'Thunderbolts*' && rawMovie.year === 2025 && rawMovie.ids.tmdb === '986056',
  `it carries the title's identity for Kodi (${JSON.stringify({ type: rawMovie.type, title: rawMovie.title, year: rawMovie.year, ids: rawMovie.ids })})`);
await until('the browser opened it', () => context.pages().some(p => p.url() === movie));
await roomPage.bringToFront();
await until('Room shows it', () => roomPage.isVisible('.nowLabel:has-text("Disney+")'));
ok(true, 'and Room shows it as Now playing · Disney+');

// 7. a show: season and episode columns, the services dialog, the Android label
await roomPage.click('.nav:has-text("Browse")');
await roomPage.click('.tab:has-text("TV shows")');
await roomPage.fill('#query', 'Andor');
await roomPage.press('#query', 'Enter');
await until('show result', () => roomPage.$$eval('.grid .poster', ps => ps.length === 1));
await roomPage.click('.grid .poster');
await until('episodes', () => roomPage.isVisible('.listButton:has-text("Kassa")'));
if (SHOTS) await roomPage.screenshot({ path: path.join(SHOTS, '5-title-show.png') });
await roomPage.click('.listButton:has-text("Kassa")');
await until('services dialog', () => roomPage.isVisible('.dialog .service:has-text("Disney+")'));
ok(await roomPage.isVisible(".dialog .service:has-text(\"Opens the show's page\")"), "Pluto's show-level link is marked, as on Android");
if (SHOTS) await roomPage.screenshot({ path: path.join(SHOTS, '5b-services-dialog.png') });
await roomPage.click('.dialog .service:has-text("Disney+")');
await until('room has the episode', () => state && state.item && state.item.file.includes('91310360'));
ok(state.item.label === 'Andor · S1 · E1 · Kassa', `started "${state.item.label}"`);
ok(jwRequests.length > 0 && jwRequests.every(o => o.startsWith('chrome-extension://')), `JustWatch was asked from the extension (Origin ${jwRequests[0]})`);
// 8. the bridge: a Kodi member starts a library file; the browser finds it on JustWatch and plays it
await other.command('open', 30, { file: 'nfs://nas/films/thunderbolts.mkv', label: 'Thunderbolts*', type: 'movie',
  title: 'Thunderbolts*', year: 2025, ids: { tmdb: '986056', imdb: 'tt20969586' } });
const kodiMovie = 'https://www.disneyplus.com/play/f51dce23-7d8f-490a-9f0c-be1b5432c2a9';
await until('the browser opened the Kodi title', async () => {
  const pages = context.pages().filter(p => p.url() === kodiMovie);
  return pages.length && pages[0];
});
const kodiPlayer = context.pages().find(p => p.url() === kodiMovie);
await until('playing', () => kodiPlayer.evaluate(() => !document.getElementById('v').paused));
ok(true, 'a Kodi library title (Thunderbolts*, by its TMDB id) opened on Disney+ in the browser');
await roomPage.bringToFront();
await roomPage.click('.nav:has-text("Room")');
await until('Room shows it as from Kodi', () => roomPage.isVisible('.nowSub:has-text("From Kodi")'));
ok(true, 'Room shows it: "From Kodi · on Disney+"');
// it starts at 0, is left alone for its 3 s start-up, then seeks once to the room (opened at 30 s)
const railDrift = () => roomPage.$eval('.member:has-text("Laptop") .drift', e => parseFloat(e.textContent));
await until('in sync with the Kodi title', async () => Math.abs(await railDrift()) < 3 &&
  (await kodiPlayer.evaluate(() => document.getElementById('v').currentTime)) > 30, 25000);
ok(true, `and syncs to the room after its start-up (video at ${(await kodiPlayer.evaluate(() => document.getElementById('v').currentTime)).toFixed(1)} s, drift ${await railDrift()} s)`);
if (SHOTS) await roomPage.screenshot({ path: path.join(SHOTS, '7-from-kodi.png') });

// 9. a Kodi addon's title: Kodi shares the addon's path (its file is Kodi's own local stream)
const addonEpisode = 'https://www.disneyplus.com/play/91310360-d44b-44a4-bdc4-71f999472826';
await other.command('open', 0, { file: 'http://127.0.0.1:52103/manifest.mpd', label: 'Andor · Kassa',
  plugin: 'plugin://slyguy.disney.plus/?_=play&deeplink_id=91310360-d44b-44a4-bdc4-71f999472826&profile_id=p1' });
await until('the browser opened the addon title', () => context.pages().some(p => p.url() === addonEpisode));
ok(!context.pages().some(p => p.url().startsWith('http://127.0.0.1:52103')), "a Kodi addon's title (SlyGuy Disney+) opened on Disney+, not Kodi's local stream");

// the browser's Back goes back from a title page
await roomPage.bringToFront();
await roomPage.click('.nav:has-text("Browse")');
await roomPage.click('.grid .poster');
await until('title page again', () => roomPage.isVisible('h1:has-text("Andor")'));
await roomPage.goBack();
await until('back on Browse', () => roomPage.isVisible('#query'));
ok(true, "the browser's Back returns from a title page to Browse");

polling = false;
await other.leave();
await roomPage.click('#join');
await until('left', () => roomPage.isVisible('#relay'));
ok(true, 'left the room');
await context.close();
fs.rmSync(tmp, { recursive: true, force: true });
console.log(failures ? `${failures} failed` : 'all passed');
process.exit(failures ? 1 : 0);
