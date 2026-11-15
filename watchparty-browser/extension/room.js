// The room page: joins a relay room, then once a second reports the player
// tab's state and follows the room (sync-core.js decides; this file carries
// it out). Titles open in their own tab, the "player tab"; content.js there
// reads and drives the video.

import { SyncCore, PLAYING, PAUSED, BUFFERING, OTHER } from './sync-core.js';
import { RelayClient, RelayError, normalizeBase } from './relay.js';
import { serviceOf, normalize, cleanTitle, roomItem, SERVICE_NAMES } from './links.js';

const $ = id => document.getElementById(id);
const NONE = { service: null, play: OTHER, positionMs: -1, title: '' };

let client = null;
let core = null;
let joined = false;
let busy = false;
let lastRoom = null;
let playerTabId = null;
let myName = '';
let note = '';
let noteUntil = 0;   // a note shows for a few seconds, then the room's state
let error = '';

// ── setup ────────────────────────────────────────────────────────────────

chrome.storage.local.get(['relay', 'room', 'name']).then(saved => {
  $('relay').value = saved.relay || '';
  $('room').value = saved.room || '';
  $('name').value = saved.name || '';
});

// Chrome can discard a background tab to save memory; this one must keep running
chrome.tabs.getCurrent().then(tab => {
  if (tab) chrome.tabs.update(tab.id, { autoDiscardable: false }).catch(() => {});
}).catch(() => {});

const ticker = new Worker('ticker.js');
ticker.onmessage = () => step();

$('join').addEventListener('click', join);
$('leave').addEventListener('click', leave);
$('shareTab').addEventListener('click', shareTab);
$('shareLink').addEventListener('click', shareLink);
$('link').addEventListener('keydown', e => { if (e.key === 'Enter') shareLink(); });
for (const id of ['relay', 'room', 'name']) {
  $(id).addEventListener('keydown', e => { if (e.key === 'Enter') join(); });
}
document.addEventListener('visibilitychange', () => { if (!document.hidden) refreshTabs(); });
chrome.tabs.onUpdated.addListener(() => refreshTabs());
chrome.tabs.onRemoved.addListener(id => {
  if (id === playerTabId) playerTabId = null;
  refreshTabs();
});
window.addEventListener('pagehide', () => {
  // leave the room when the page closes (best-effort; the relay also drops silent members)
  if (joined && client && client.memberId) {
    fetch(client.base + '/leave', {
      method: 'POST', keepalive: true, headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ member_id: client.memberId, room: client.room }),
    }).catch(() => {});
  }
});

/** Host permission patterns for the streaming sites (Firefox asks for them; Chrome grants them at install). */
function siteOrigins() {
  return chrome.runtime.getManifest().content_scripts.flatMap(c => c.matches);
}

async function join() {
  const relay = $('relay').value.trim();
  const code = $('room').value.trim().toUpperCase();
  myName = $('name').value.trim() || 'Browser';
  if (!relay || !code) {
    setStatus('Enter the relay and the room code.', true);
    return;
  }
  const base = normalizeBase(relay);
  let origin;
  try {
    const u = new URL(base);
    origin = `${u.protocol}//${u.hostname}/*`;   // match patterns have no port
  } catch (e) {
    setStatus("That relay address isn't valid.", true);
    return;
  }
  // asked first, while the click still counts as the user's: access to the relay and the sites
  let granted = false;
  try {
    granted = await chrome.permissions.request({ origins: [origin, ...siteOrigins()] });
  } catch (e) {
    setStatus(`Couldn't ask for access: ${e.message}`, true);
    return;
  }
  if (!granted) {
    setStatus('Watch Party needs access to the relay and the streaming sites to sync.', true);
    return;
  }
  chrome.storage.local.set({ relay, room: code, name: myName });
  $('join').disabled = true;
  setStatus('Joining…');
  client = new RelayClient(relay, code);
  core = new SyncCore();
  try {
    lastRoom = await client.join(myName);
  } catch (e) {
    $('join').disabled = false;
    client = null;
    setStatus(`Couldn't join: ${e.message}`, true);
    return;
  }
  joined = true;
  error = '';
  note = '';
  $('join').disabled = false;
  $('joinForm').hidden = true;
  $('inRoom').hidden = false;
  $('start').hidden = false;
  $('roomCode').textContent = code;
  $('dot').classList.add('on');
  refreshTabs();
  render(lastRoom, NONE);
}

async function leave() {
  joined = false;
  const c = client;
  client = null;
  core = null;
  lastRoom = null;
  if (c) await c.leave();
  $('joinForm').hidden = false;
  $('inRoom').hidden = true;
  $('start').hidden = true;
  $('dot').classList.remove('on');
  setStatus('Not in a room');
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
    lastRoom = room;
    error = '';
    for (const a of core.tick(room, local, Date.now(), client.serverNowSec())) await perform(a, room);
    render(room, local);
  } catch (e) {
    if (e instanceof RelayError && e.status === 410 && client) {
      // the relay forgot us (it restarted, or we were silent too long)
      try {
        lastRoom = await client.join(myName);
        core = new SyncCore();
      } catch (e2) {
        error = `Rejoining failed: ${e2.message}`;
      }
    } else {
      error = e.message || String(e);
    }
    setStatus(error, true);
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
    case 'open': {
      setNote(`Opening ${room.item && room.item.label ? room.item.label : a.link}`);
      await openInPlayerTab(a.link);
      break;
    }
    case 'play':
    case 'pause':
    case 'seek':
      if (playerTabId != null) {
        await chrome.tabs.sendMessage(playerTabId, { wp: a.type, ms: a.ms }).catch(() => {});
      }
      break;
    case 'send':
      await client.command(a.cmd, a.positionSec);
      break;
    case 'note':
      setNote(a.text);
      break;
  }
}

async function openInPlayerTab(link) {
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

// ── starting a title ─────────────────────────────────────────────────────

async function refreshTabs() {
  if (!joined) return;
  const select = $('tabs');
  if (document.activeElement === select) return;
  const tabs = (await chrome.tabs.query({})).filter(t => serviceOf(t.url || ''));
  tabs.sort((a, b) => (b.lastAccessed || 0) - (a.lastAccessed || 0));
  const chosen = select.value;
  select.replaceChildren(...tabs.map(t => {
    const o = document.createElement('option');
    o.value = String(t.id);
    const service = serviceOf(t.url);
    o.textContent = `${SERVICE_NAMES[service]} — ${cleanTitle(t.title, service)}`;
    return o;
  }));
  if (!tabs.length) {
    const o = document.createElement('option');
    o.textContent = 'No streaming site open in this browser';
    o.disabled = true;
    select.append(o);
  }
  const keep = [chosen, String(playerTabId)].find(v => tabs.some(t => String(t.id) === v));
  if (keep) select.value = keep;
  $('shareTab').disabled = !tabs.length;
}

async function shareTab() {
  if (!joined) return;
  const id = Number($('tabs').value);
  let tab;
  try {
    tab = await chrome.tabs.get(id);
  } catch (e) {
    setStatus('That tab is gone.', true);
    refreshTabs();
    return;
  }
  const service = serviceOf(tab.url || '');
  const link = normalize(tab.url || '');
  if (!service || !link) {
    setStatus("That tab isn't on a streaming site Watch Party knows.", true);
    return;
  }
  playerTabId = tab.id;
  let s = null;
  try {
    s = await chrome.tabs.sendMessage(tab.id, { wp: 'state' });
  } catch (e) {
    // a page without the content script yet (opened before the extension): share it anyway
  }
  const label = cleanTitle((s && s.title) || tab.title, service);
  const positionSec = s && s.positionMs > 0 ? s.positionMs / 1000 : 0;
  try {
    // this tab already has it: don't reload it when the room names it
    if (s && s.play !== OTHER) core.alreadyOpen(link, Date.now());
    lastRoom = await client.command('open', positionSec, roomItem(link, label, service));
    setNote(`Started for everyone: ${label}`);
    render(lastRoom, NONE);
  } catch (e) {
    setStatus(`Couldn't share: ${e.message}`, true);
  }
}

async function shareLink() {
  if (!joined) return;
  const link = normalize($('link').value);
  if (!link) {
    setStatus('Paste a title\'s web link (https://…).', true);
    return;
  }
  const service = serviceOf(link);
  const label = service ? `${SERVICE_NAMES[service]} title` : new URL(link).hostname;
  try {
    lastRoom = await client.command('open', 0, roomItem(link, label, service));
    $('link').value = '';
    setNote(`Started for everyone: ${label}`);
  } catch (e) {
    setStatus(`Couldn't share: ${e.message}`, true);
  }
}

// ── showing the room ─────────────────────────────────────────────────────

function render(room, local) {
  if (!room) return;
  const item = room.item;
  $('nowPlaying').textContent = item ? (item.label || item.file) : 'Nothing yet';
  $('nowService').textContent = item
    ? (item.service ? SERVICE_NAMES[item.service] + (room.paused ? ' · paused' : '') : 'Not a site this browser can play')
    : 'Share a tab, or someone else starts a title';
  $('members').replaceChildren(...room.members.map(m => {
    const li = document.createElement('li');
    const who = document.createElement('span');
    who.textContent = m.name || 'Someone';
    const what = document.createElement('span');
    what.className = 'what';
    what.textContent = !m.onItem ? 'not on it yet'
      : m.caching ? 'buffering'
      : (m.paused ? 'paused' : 'playing') + (m.driftSec != null ? ` · ${m.driftSec >= 0 ? '+' : ''}${m.driftSec.toFixed(1)}s` : '');
    li.append(who, what);
    return li;
  }));
  const here = local.service && item && local.service === item.service
    ? ({ PLAYING: 'playing here', PAUSED: 'paused here', BUFFERING: 'loading here', OTHER: 'opening here' })[local.play]
    : '';
  setStatus(error || (Date.now() < noteUntil ? note : '') || (here ? `In sync · ${here}` : 'In the room'), !!error);
}

function setNote(text) {
  note = text;
  noteUntil = Date.now() + 8000;
}

function setStatus(text, warn = false) {
  $('status').textContent = text;
  $('status').classList.toggle('warn', warn);
}

// for the end-to-end test
window.__watchParty = { get playerTabId() { return playerTabId; }, get room() { return lastRoom; } };
