// Against the real relay (Kodi addon's relay_standalone.py), like the Android
// app's RelayClientTest. WATCHPARTY_TEST_RELAY=http://127.0.0.1:8765
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { RelayClient, normalizeBase } from '../extension/relay.js';
import { roomItem } from '../extension/links.js';

const RELAY = process.env.WATCHPARTY_TEST_RELAY;

test('relay addresses', () => {
  assert.equal(normalizeBase('192.168.1.5:8765/'), 'http://192.168.1.5:8765');
  assert.equal(normalizeBase('https://relay.example.com'), 'https://relay.example.com');
});

test('open, follow and pause through the relay', { skip: !RELAY && 'set WATCHPARTY_TEST_RELAY' }, async () => {
  const code = 'WEB' + Math.floor(Math.random() * 1e6);
  const host = new RelayClient(RELAY, code);
  const guest = new RelayClient(RELAY, code);
  await host.join('browser');
  await guest.join('guest');
  const link = 'https://www.netflix.com/watch/81721676';
  const opened = await host.command('open', 0, roomItem(link, 'Thunderbolts*', 'netflix'));
  assert.equal(opened.item.file, link);
  assert.equal(opened.item.service, 'netflix');
  assert.ok(opened.setByMe);
  const seen = await guest.poll({ positionSec: 0, paused: true, file: '', caching: false, onItem: false, corrections: 0 });
  assert.equal(seen.item.label, 'Thunderbolts*');
  assert.equal(seen.setByMe, false);
  assert.equal(seen.members.length, 2);
  await guest.command('pause', 42);
  const after = await host.poll({ positionSec: 42, paused: true, file: 'netflix', caching: false, onItem: true, corrections: 0 });
  assert.ok(after.paused);
  assert.equal(after.positionSec, 42);
  assert.ok(Math.abs(host.serverNowSec() - Date.now() / 1000) < 5);
  await host.leave();
  await guest.leave();
});
