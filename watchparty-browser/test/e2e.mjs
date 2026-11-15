// End to end, in Chromium with the extension loaded: a scripted room member
// (relay.js in node) starts a title; the extension opens it in a player tab
// and follows play / pause / seek; a pause and seek in the browser reach the
// room; and "Share this tab" announces another tab without reloading it.
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
  viewport: { width: 1100, height: 760 },
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

const worker = context.serviceWorkers()[0] || await context.waitForEvent('serviceworker');
const extId = new URL(worker.url()).host;
ok(!!extId, `extension loaded (${extId})`);

const code = 'E2E' + Math.floor(Math.random() * 1e6);
const roomPage = await context.newPage();
await roomPage.goto(`chrome-extension://${extId}/room.html`);
await roomPage.fill('#relay', RELAY);
await roomPage.fill('#room', code);
await roomPage.fill('#name', 'Laptop');
await roomPage.click('#join');
await until('joined', () => roomPage.isVisible('#inRoom'));
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

// 5. share another tab: the room gets its normalized link, the tab isn't reloaded
const nf = await context.newPage();
await nf.goto('https://www.netflix.com/watch/70264888?trackId=14170286');
await until('netflix stand-in playing', () => nf.evaluate(() => document.getElementById('v').currentTime > 1));
await nf.evaluate(() => { document.getElementById('v').currentTime = 30; window.__marker = 'same page'; });
await roomPage.bringToFront();
await until('tab listed', () => roomPage.$$eval('#tabs option', os => os.some(o => o.textContent.includes('Iron Man'))));
const options = await roomPage.$$eval('#tabs option', os => os.map(o => [o.value, o.textContent]));
await roomPage.selectOption('#tabs', options.find(([, text]) => text.includes('Iron Man'))[0]);
await roomPage.click('#shareTab');
await until('room has the Netflix title', () => state && state.item && state.item.file === 'https://www.netflix.com/watch/70264888');
ok(state.item.label === 'Iron Man', `shared "${state.item.label}" as ${state.item.file}`);
ok(Math.abs(state.positionSec - 30) < 4, `from where the tab was (${state.positionSec.toFixed(1)} s)`);
await sleep(4000);
ok(await nf.evaluate(() => window.__marker === 'same page'), 'the shared tab was not reloaded');
const raw = await (await fetch(`${RELAY}/poll`, { method: 'POST', headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ room: code, member_id: other.memberId, position: 0, paused: true, file: '', caching: false, on_item: false, corrections: 0 }) })).json();
ok(raw.state.item.ids && raw.state.item.ids.android === 'com.netflix.ninja', 'the item names an Android app, so TVs and phones open it');

if (SHOTS) {
  fs.mkdirSync(SHOTS, { recursive: true });
  await roomPage.setViewportSize({ width: 760, height: 900 });
  await roomPage.screenshot({ path: path.join(SHOTS, 'room.png') });
}

polling = false;
await other.leave();
await roomPage.click('#leave');
await until('left', () => roomPage.isVisible('#joinForm'));
ok(true, 'left the room');
await context.close();
fs.rmSync(tmp, { recursive: true, force: true });
console.log(failures ? `${failures} failed` : 'all passed');
process.exit(failures ? 1 : 0);
