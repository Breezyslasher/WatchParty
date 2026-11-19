// The Watch Party page, laid out like the Android app (MainActivity.kt):
// a rail with Room and Browse, the room form or its members,
// and the status line; the chosen screen beside it (screens.js). Once a
// second it reports the player tab's state and follows the room
// (sync-core.js decides; this file carries it out). Titles play in their own
// tab, the "player tab"; content.js there reads and drives the video.

import { SyncCore, PLAYING, BUFFERING, OTHER } from './sync-core.js';
import { RelayClient, RelayError, normalizeBase } from './relay.js';
import { serviceOf, roomItem, browserLink, preferAddon } from './links.js';
import { JustWatch } from './justwatch.js';
import { el, label, toast } from './ui.js';
import { roomScreen, browseScreen, titleScreen, serviceName } from './screens.js';
import { regularEpisode, isHboMax } from './hbomax.js';

const $ = id => document.getElementById(id);
const NONE = { service: null, play: OTHER, positionMs: -1, title: '' };

let client = null;
let core = null;
let joined = false;
let joining = false;
let busy = false;
let lastRoom = null;
let playerTabId = null;
let myName = '';
let status = 'Not in a room';
let statusWarn = false;
let note = '';
let noteUntil = 0;   // a note shows for a few seconds, then the room's state
/** What this browser started, by link: title, sub, service, image (for the Room screen). */
const playing = new Map();
/** Kodi items found on a streaming site here: their file → { service, link, name }. */
const resolved = new Map();

function defaultCountry() {
  const region = (navigator.language || 'en-US').split('-')[1];
  return region && /^[A-Za-z]{2}$/.test(region) ? region.toUpperCase() : 'US';
}

// ── what the screens use ─────────────────────────────────────────────────

const app = {
  get joined() { return joined; },
  get room() { return lastRoom; },
  playing,
  country: defaultCountry(),
  jw: new JustWatch(defaultCountry()),
  setCountry(c) {
    app.country = c;
    app.jw = new JustWatch(c);
    chrome.storage.local.set({ country: c });
  },
  /** JustWatch (and HBO Max, for its ASL check): Firefox asks once; Chrome has them from install. */
  justWatchAccess() {
    return chrome.permissions.request({ origins: ['https://apis.justwatch.com/*', 'https://*.hbomax.com/*'] }).catch(() => true);
  },
  /** Share a title with the room (with its identity, Kodi plays its library
   *  copy); this browser then opens it like everyone else. */
  async start(link, label, shown, identity = null) {
    lastRoom = await client.command('open', 0, roomItem(link, label, serviceOf(link), identity));
    playing.set(link, shown);
    go('room');
    toast(`Sent to the room: ${shown.title}`);
    render(lastRoom, NONE);
  },
  go: s => go(s),
  openTitle: t => openTitle(t),
};

// ── layout: the rail and the screens ─────────────────────────────────────

const screens = { room: roomScreen(app), browse: browseScreen(app) };
let current = 'room';
const pages = [];   // title pages opened from Browse (Back returns through them)

const navs = {};
const field = (labelText, id, placeholder, caps = false) => el('label', { class: 'field-label' }, labelText,
  el('input', { class: 'field' + (caps ? ' caps' : ''), id, placeholder, autocomplete: 'off', spellcheck: 'false' }));
const form = el('div', {}, label('Join a room'),
  field('Relay', 'relay', 'relay.example.com'), field('Room code', 'room', '', true), field('Your name', 'name', 'Laptop'));
const roomCode = el('div', { class: 'roomCode' });
const members = el('div', { class: 'members' });
const info = el('div', { hidden: true }, label('In room'), roomCode, members);
const joinButton = el('button', { class: 'primary', id: 'join', onclick: toggleJoin }, 'Join room');
const statusLine = el('div', { class: 'status' }, status);
$('rail').append(
  el('div', { class: 'brand' }, 'Watch Party'),
  el('nav', { class: 'navs' }, [['room', 'Room'], ['browse', 'Browse']].map(([key, text]) => {
    navs[key] = el('button', { class: 'nav', onclick: () => go(key) }, text);
    return navs[key];
  })),
  el('div', { class: 'divider' }),
  el('div', { class: 'railBody' }, form, info, joinButton),
  el('div', { class: 'railSpacer' }),
  statusLine,
  el('div', { class: 'keep' }, 'Keep this tab open while you watch: it keeps this browser in sync.'));
for (const id of ['relay', 'room', 'name']) $(id).addEventListener('keydown', e => { if (e.key === 'Enter') toggleJoin(); });

function show(view, focus) {
  const content = $('content');
  content.replaceChildren(view);
  for (const [key, b] of Object.entries(navs)) b.classList.toggle('on', key === current);
  window.scrollTo(0, 0);
  if (focus) focus.focus({ preventScroll: true });
}

function go(key) {
  for (const p of pages) p.close();
  pages.length = 0;
  current = key;
  history.replaceState({ depth: 0 }, '');
  const s = screens[key];
  show(s.view, document.activeElement && document.activeElement.closest('.rail') ? null : s.defaultFocus());
}

function openTitle(t) {
  const page = titleScreen(app, t);
  pages.push(page);
  current = 'browse';
  history.pushState({ depth: pages.length }, '');
  show(page.view, page.defaultFocus());
}

// the browser's Back goes back through title pages to Browse, as the remote's Back does
window.addEventListener('popstate', () => {
  const page = pages.pop();
  if (page) page.close();
  const top = pages[pages.length - 1];
  show(top ? top.view : screens[current].view, null);
});

chrome.storage.local.get(['relay', 'room', 'name', 'country']).then(saved => {
  $('relay').value = saved.relay || '';
  $('room').value = saved.room || '';
  $('name').value = saved.name || '';
  if (saved.country) {
    app.setCountry(saved.country);
    const c = $('country');
    if (c) c.value = saved.country;
  }
});
go('room');

// Chrome can discard a background tab to save memory; this one must keep running
chrome.tabs.getCurrent().then(tab => {
  if (tab) chrome.tabs.update(tab.id, { autoDiscardable: false }).catch(() => {});
}).catch(() => {});
chrome.tabs.onRemoved.addListener(id => { if (id === playerTabId) playerTabId = null; });
window.addEventListener('pagehide', () => {
  // leave the room when the page closes (best-effort; the relay also drops silent members)
  if (joined && client && client.memberId) {
    fetch(client.base + '/leave', {
      method: 'POST', keepalive: true, headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ member_id: client.memberId, room: client.room }),
    }).catch(() => {});
  }
});

const ticker = new Worker('ticker.js');
ticker.onmessage = () => step();

// ── joining ──────────────────────────────────────────────────────────────

/** Host permission patterns for the streaming sites (Firefox asks for them; Chrome grants them at install). */
function siteOrigins() {
  return chrome.runtime.getManifest().content_scripts.flatMap(c => c.matches);
}

function toggleJoin() {
  if (joined) leave(); else join();
}

async function join() {
  if (joining) return;
  const relay = $('relay').value.trim();
  const code = $('room').value.trim().toUpperCase();
  myName = $('name').value.trim() || 'Browser';
  if (!relay || !code) {
    setStatus('Enter the relay and the room code.', true);
    return;
  }
  let origin;
  try {
    const u = new URL(normalizeBase(relay));
    origin = `${u.protocol}//${u.hostname}/*`;   // match patterns have no port
  } catch (e) {
    setStatus("That relay address isn't valid.", true);
    return;
  }
  // asked first, while the click still counts as the user's: the relay, the sites, JustWatch
  let granted = false;
  try {
    granted = await chrome.permissions.request({
      origins: [origin, ...siteOrigins(), 'https://apis.justwatch.com/*'],
    });
  } catch (e) {
    setStatus(`Couldn't ask for access: ${e.message}`, true);
    return;
  }
  if (!granted) {
    setStatus('Watch Party needs access to the relay and the streaming sites to sync.', true);
    return;
  }
  chrome.storage.local.set({ relay, room: code, name: myName });
  joining = true;
  joinButton.disabled = true;
  setStatus('Joining…');
  client = new RelayClient(relay, code);
  core = new SyncCore();
  try {
    lastRoom = await client.join(myName);
  } catch (e) {
    client = null;
    setStatus(`Couldn't join: ${e.message}`, true);
    return;
  } finally {
    joining = false;
    joinButton.disabled = false;
  }
  joined = true;
  note = '';
  roomCode.textContent = code;
  toast(`Joined ${code}`);
  render(lastRoom, NONE);
}

async function leave() {
  joined = false;
  const c = client;
  client = null;
  core = null;
  lastRoom = null;
  render(null, NONE);
  setStatus('Not in a room');
  if (c) {
    await c.leave();
    toast(`Left ${c.room}`);
  }
}

// ── the loop ─────────────────────────────────────────────────────────────

async function step() {
  if (!joined || busy) return;
  busy = true;
  try {
    const previous = lastRoom;
    const local = await localState();
    const room = await client.poll({
      positionSec: local.positionMs >= 0 ? local.positionMs / 1000 : 0,
      paused: local.play !== PLAYING,
      file: local.service || '',
      // loading a just-opened title isn't buffering the party should wait for
      caching: local.play === BUFFERING && !core.isStarting(Date.now()),
      onItem: !!previous && core.onItem(previous, local),
      corrections: core.corrections,
    });
    if (!joined) return;
    // a Kodi item found on a site here plays to that site's tab
    const found = room.item && !room.item.service && resolved.get(room.item.file);
    if (found) room.item.service = found.service;
    lastRoom = room;
    statusWarn = false;
    for (const a of core.tick(room, local, Date.now(), client.serverNowSec())) await perform(a, room);
    render(room, local);
  } catch (e) {
    if (e instanceof RelayError && e.status === 410 && client) {
      // the relay forgot us (it restarted, or we were silent too long)
      try {
        lastRoom = await client.join(myName);
        core = new SyncCore();
      } catch (e2) {
        setStatus(`Rejoining failed: ${e2.message}`, true);
      }
    } else if (joined) {
      setStatus(e.message || String(e), true);
    }
  } finally {
    busy = false;
  }
}

/** The player tab's state: its site, and its video's position and play state. */
async function localState() {
  if (playerTabId == null) return NONE;
  let tab;
  try {
    tab = await chrome.tabs.get(playerTabId);
  } catch (e) {
    playerTabId = null;
    return NONE;
  }
  const service = serviceOf(tab.url || tab.pendingUrl || '');
  if (!service) return { ...NONE, title: tab.title || '' };
  let s = null;
  try {
    s = await chrome.tabs.sendMessage(playerTabId, { wp: 'state' });
  } catch (e) {
    // still loading, or the content script isn't there (yet)
  }
  if (!s) return { service, play: OTHER, positionMs: -1, title: tab.title || '' };
  return { service, play: s.play, positionMs: s.positionMs, title: s.title || tab.title || '' };
}

async function perform(a, room) {
  switch (a.type) {
    case 'open':
      setNote(`Opening ${room.item && room.item.label ? room.item.label : a.link}`);
      await openInPlayerTab(a.link);
      break;
    case 'play':
    case 'pause':
    case 'seek':
      if (playerTabId != null) await chrome.tabs.sendMessage(playerTabId, { wp: a.type, ms: a.ms }).catch(() => {});
      break;
    case 'send':
      await client.command(a.cmd, a.positionSec);
      break;
    case 'note':
      setNote(a.text);
      break;
    case 'resolve':
      resolve(a);
      break;
  }
}

/**
 * A Kodi member's title (a library file): find it on JustWatch and open it
 * on the first of its services this browser can sync (JustWatch's order),
 * like the Android app's Bridge. The file and the stream may differ by a few
 * seconds (logos, recaps), which sync then holds.
 */
async function resolve(a) {
  const id = a.identity;
  const name = a.label || (id.type === 'movie' ? id.title : `${id.show} S${id.season} E${id.episode}`);
  setNote(`Finding "${name}" on a streaming site…`);
  try {
    // the service Kodi's addon plays first (a Starz addon title: Starz, not Prime Video)
    const offers = preferAddon(await app.jw.offersFor(id), a.addon);
    const offer = offers.find(o => serviceOf(o.link));
    if (!offer) {
      setNote(offers.length ? `"${name}" isn't on a site this browser can sync (${offers.map(o => o.clearName).join(', ')})`
        : `No streaming service has "${name}" in ${app.country}`);
      return;
    }
    let link = offer.link;
    if (id.type === 'episode' && isHboMax(link)) link = await regularEpisode(link, id.show, id.season, id.episode);
    if (!joined || !lastRoom || !lastRoom.item || lastRoom.item.file !== a.file) return;   // the room moved on
    resolved.set(a.file, { service: serviceOf(link), link, name: offer.clearName });
    lastRoom.item.service = serviceOf(link);
    playing.set(a.file, { title: name, sub: `From Kodi · on ${serviceName(offer)}`, service: serviceName(offer), image: null });
    setNote(`Playing "${name}" on ${serviceName(offer)}`);
    await openInPlayerTab(link);
  } catch (e) {
    setNote(`Couldn't look up "${name}": ${e.message}`);
  }
}

async function openInPlayerTab(roomLink) {
  const link = browserLink(roomLink);
  if (playerTabId != null) {
    try {
      await chrome.tabs.update(playerTabId, { url: link, active: true });
      return;
    } catch (e) {
      playerTabId = null;
    }
  }
  const tab = await chrome.tabs.create({ url: link, active: true });
  playerTabId = tab.id;
}

// ── showing the room ─────────────────────────────────────────────────────

let shownMembers = '';

function render(room, local) {
  form.hidden = joined;
  info.hidden = !joined;
  joinButton.textContent = joined ? 'Leave room' : 'Join room';
  const list = joined && room ? room.members : [];
  const key = JSON.stringify(list.map(m => [m.name, m.caching, m.onItem, m.paused, m.driftSec == null ? null : m.driftSec.toFixed(1)]));
  if (key !== shownMembers) {
    shownMembers = key;
    members.replaceChildren(...list.map(m => el('div', { class: 'member' },
      el('div', { class: 'who' }, el('div', { class: 'name' }, m.name || 'Someone'),
        el('div', { class: 'what' }, m.caching ? 'Buffering' : !m.onItem ? 'Not on the title yet' : m.paused ? 'Paused' : 'Watching')),
      el('div', { class: 'drift mono' }, m.driftSec == null ? '—' : `${m.driftSec >= 0 ? '+' : ''}${m.driftSec.toFixed(1)}s`))));
  }
  screens.room.tick(room);
  if (!joined || !room) return;
  if (statusWarn) return;
  const item = room.item;
  const here = local.service && item && local.service === item.service
    ? ({ PLAYING: 'playing here', PAUSED: 'paused here', BUFFERING: 'loading here', OTHER: 'opening here' })[local.play] : '';
  setStatus((Date.now() < noteUntil ? note : '') || `In room ${client.room}` + (here ? ` · ${here}` : ''));
}

function setNote(text) {
  note = text;
  noteUntil = Date.now() + 8000;
}

function setStatus(text, warn = false) {
  status = text;
  statusWarn = warn;
  statusLine.textContent = text;
  statusLine.classList.toggle('warn', warn);
}

// for the end-to-end test
window.__watchParty = { get playerTabId() { return playerTabId; }, get room() { return lastRoom; }, resolved };
