// The Android app's SyncCoreTest, ported: the browser follows the same rules.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { SyncCore, expectedSec, PLAYING, PAUSED, BUFFERING, OTHER, OPEN_GRACE_MS } from '../extension/sync-core.js';

const link = 'https://www.disneyplus.com/play/f51dce23-7d8f-490a-9f0c-be1b5432c2a9';
const item = { file: link, label: 'Thunderbolts*', service: 'disneyplus' };

/** Room playing from pos since server time setAt. */
const room = ({ pos = 100, paused = false, setAt = 1000, it = item, mine = false } = {}) =>
  ({ seq: 1, item: it, positionSec: pos, paused, speed: 1, setAtSec: setAt, setByMe: mine, members: [] });
const local = (sec, play = PLAYING, service = 'disneyplus') =>
  ({ service, positionMs: Math.round(sec * 1000), play, title: 'Thunderbolts*' });

/** A core that opened item long enough ago to be out of the start-up grace. */
function opened() {
  const core = new SyncCore();
  assert.deepEqual(core.tick(room(), local(0, OTHER, null), 0, 1000), [{ type: 'open', link }]);
  return [core, OPEN_GRACE_MS + 1000];
}

test('a new item opens its link', () => { opened(); });

test('nothing while the site is still loading', () => {
  const [core, t] = opened();
  assert.deepEqual(core.tick(room(), local(-1, OTHER, null), t, 1000), []);
});

test('in sync does nothing', () => {
  const [core, t] = opened();
  assert.deepEqual(core.tick(room(), local(110.5), t, 1010), []);
});

test('drift is corrected once per cooldown', () => {
  const [core, t] = opened();
  assert.deepEqual(core.tick(room(), local(125), t, 1010), [{ type: 'seek', ms: 110000 }]);
  assert.deepEqual(core.tick(room(), local(126), t + 1000, 1011), []);
  assert.equal(core.corrections, 1);
});

test('the room pausing pauses us; playing resumes us', () => {
  let [core, t] = opened();
  assert.deepEqual(core.tick(room({ pos: 300, paused: true }), local(300), t, 1010), [{ type: 'pause' }]);
  [core, t] = opened();
  assert.deepEqual(core.tick(room(), local(110, PAUSED), t, 1010), [{ type: 'play' }]);
});

test("the user's pause and seek are sent to the room", () => {
  let [core, t] = opened();
  core.tick(room(), local(110), t, 1010);
  assert.deepEqual(core.tick(room(), local(111, PAUSED), t + 1000, 1011), [{ type: 'send', cmd: 'pause', positionSec: 111 }]);
  [core, t] = opened();
  core.tick(room(), local(110), t, 1010);
  assert.deepEqual(core.tick(room(), local(1900), t + 1000, 1011), [{ type: 'send', cmd: 'seek', positionSec: 1900 }]);
});

test('our own correction is not mistaken for a user seek', () => {
  const [core, t] = opened();
  assert.deepEqual(core.tick(room(), local(125), t, 1010), [{ type: 'seek', ms: 110000 }]);
  assert.ok(core.tick(room(), local(111), t + 1000, 1011).every(a => a.type !== 'send'));
});

test("someone else's pause is followed, not echoed", () => {
  const [core, t] = opened();
  core.tick(room(), local(110), t, 1010);
  assert.deepEqual(core.tick(room({ pos: 111, paused: true }), local(111), t + 1000, 1011), [{ type: 'pause' }]);
  assert.deepEqual(core.tick(room({ pos: 111, paused: true }), local(111, PAUSED), t + 2000, 1012), []);
});

test('Kodi files are skipped with one note', () => {
  const core = new SyncCore();
  const kodi = { file: 'smb://nas/movies/Iron Man.mkv', label: 'Iron Man', service: null };
  const first = core.tick(room({ it: kodi }), local(0, OTHER, null), 0, 1000);
  assert.equal(first.length, 1);
  assert.equal(first[0].type, 'note');
  assert.deepEqual(core.tick(room({ it: kodi }), local(0, OTHER, null), 1000, 1001), []);
});

test("a site this browser can't sync still opens, with a note", () => {
  const core = new SyncCore();
  const other = { file: 'https://example.com/movie', label: 'A movie', service: null };
  const actions = core.tick(room({ it: other }), local(0, OTHER, null), 0, 1000);
  assert.deepEqual(actions.map(a => a.type), ['open', 'note']);
});

test('the announcer already watching is not restarted', () => {
  const core = new SyncCore();
  assert.ok(core.tick(room({ mine: true }), local(100), 0, 1000).every(a => a.type !== 'open'));
});

test("a paused room's expected position doesn't advance", () => {
  assert.equal(expectedSec(room({ pos: 300, paused: true }), 5000), 300);
  assert.equal(expectedSec(room(), 1010), 110);
});

test('a just-opened title is left alone while it starts', () => {
  const core = new SyncCore();
  assert.deepEqual(core.tick(room(), local(0, OTHER, null), 0, 1000), [{ type: 'open', link }]);
  assert.deepEqual(core.tick(room(), local(0), 5000, 1005), []);
  assert.deepEqual(core.tick(room(), local(0.5, PAUSED), 6000, 1006), []);
  assert.deepEqual(core.tick(room(), local(1), 7000, 1007), []);
  assert.deepEqual(core.tick(room(), local(2), 9000, 1009), []);
  assert.deepEqual(core.tick(room(), local(3), 10000, 1010), [{ type: 'seek', ms: 110000 }]);
});

test('a site that never plays is followed after the grace', () => {
  const core = new SyncCore();
  core.tick(room(), local(0, OTHER, null), 0, 1000);
  assert.deepEqual(core.tick(room(), local(0, PAUSED), 10000, 1010), []);
  assert.ok(core.tick(room(), local(0, PAUSED), OPEN_GRACE_MS + 1000, 1021).some(a => a.type === 'play'));
});

test('starting up is not buffering for the room', () => {
  const core = new SyncCore();
  assert.equal(core.isStarting(0), false);
  core.tick(room(), local(0, OTHER, null), 0, 1000);
  assert.equal(core.isStarting(2000), true);
  core.tick(room(), local(0, BUFFERING), 2000, 1002);
  assert.equal(core.isStarting(3000), true);
  core.tick(room(), local(0.5), 4000, 1004);
  assert.equal(core.isStarting(6000), true);
  core.tick(room(), local(2.5), 6000, 1006);
  assert.equal(core.isStarting(7000), false);
  const other = new SyncCore();
  other.tick(room(), local(0, OTHER, null), 0, 1000);
  assert.equal(other.isStarting(OPEN_GRACE_MS + 1), false);
});

test('a user seek right after our correction is sent to the room', () => {
  // on a TV, skipping ahead 4 s after a catch-up seek was seeked back
  const [core, t] = opened();
  assert.deepEqual(core.tick(room(), local(125), t, 1010), [{ type: 'seek', ms: 110000 }]);
  assert.ok(core.tick(room(), local(111), t + 1000, 1011).every(a => a.type !== 'send'));
  assert.deepEqual(core.tick(room(), local(112), t + 2000, 1012), []);
  assert.deepEqual(core.tick(room(), local(1343), t + 3000, 1013), [{ type: 'send', cmd: 'seek', positionSec: 1343 }]);
});

test('a user seek soon after opening is sent once the title has started', () => {
  const core = new SyncCore();
  assert.deepEqual(core.tick(room(), local(0, OTHER, null), 0, 1000), [{ type: 'open', link }]);
  // the site resumes where this browser left off: not the user, too early to touch
  assert.deepEqual(core.tick(room(), local(1811), 1000, 1001), []);
  assert.deepEqual(core.tick(room(), local(1812), 2000, 1002), []);
  const caughtUp = core.tick(room(), local(1815), 5000, 1005);
  assert.ok(caughtUp.some(a => a.type === 'seek') && caughtUp.every(a => a.type !== 'send'));
  assert.ok(core.tick(room(), local(106), 6000, 1006).every(a => a.type !== 'send'));
  assert.deepEqual(core.tick(room(), local(107), 7000, 1007), []);
  assert.deepEqual(core.tick(room(), local(2182), 8000, 1008), [{ type: 'send', cmd: 'seek', positionSec: 2182 }]);
});

test('our own seek landing late, after the room moved on, is not a user seek', () => {
  // the room seeked to 60, then paused at 70 before the tab said it was at 60 (e2e, 2026-11-19)
  const item = { file: 'L', link: 'L', label: 'T', service: 'disneyplus', identity: null };
  const room = (pos, paused, setAt) => ({ seq: 1, item, positionSec: pos, paused, speed: 1, setAtSec: setAt, setByMe: false, members: [] });
  const local = (sec, play = PLAYING) => ({ service: 'disneyplus', play, positionMs: sec * 1000, title: 'T' });
  const core = new SyncCore();
  core.tick(room(0, false, 0), { service: null, play: OTHER, positionMs: -1, title: '' }, 0, 0);
  for (let t = 1; t <= 25; t++) core.tick(room(0, false, 0), local(t), t * 1000, t);
  assert.deepEqual(core.tick(room(60, false, 26), local(26), 26000, 26), [{ type: 'seek', ms: 60000 }]);
  core.tick(room(60, false, 26), local(27), 27000, 27);   // the tab hasn't said yet
  // the room pauses at 70; the tab now says 61 (landed, played a second): pause and go to 70, no "seek 61"
  const acts = core.tick(room(70, true, 28), local(61), 28000, 28);
  assert.ok(!acts.some(a => a.type === 'send'), JSON.stringify(acts));
  // a real skip by the user later still counts
  for (let t = 29; t <= 40; t++) core.tick(room(70, true, 28), local(70, PAUSED), t * 1000, t);
  assert.deepEqual(core.tick(room(70, true, 28), local(200, PAUSED), 41000, 41), [{ type: 'send', cmd: 'seek', positionSec: 200 }]);
});
