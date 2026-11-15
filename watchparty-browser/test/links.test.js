// The Android app's MediaLink rules (MediaLinkTest's cases) and the browser's own.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { normalize, serviceOf, cleanTitle, roomItem, androidPackage } from '../extension/links.js';

test('links become the forms the Android app shares', () => {
  const cases = [
    ['https://www.disneyplus.com/en-gb/browse/entity-F51DCE23-7D8F-490A-9F0C-BE1B5432C2A9?x=1',
      'https://www.disneyplus.com/play/f51dce23-7d8f-490a-9f0c-be1b5432c2a9'],
    ['https://www.netflix.com/title/81721676?trackId=255824129', 'https://www.netflix.com/watch/81721676'],
    ['https://www.netflix.com/watch/81721676?trackId=1&tctx=0', 'https://www.netflix.com/watch/81721676'],
    ['https://www.hbomax.com/video/watch/c07ae042-082e-4b53-ba86-b6ac5e3004db/0fc1d4b1-0f6a-4b5b-9c39-6a6e2ee44b3f',
      'https://play.hbomax.com/video/watch/c07ae042-082e-4b53-ba86-b6ac5e3004db/0fc1d4b1-0f6a-4b5b-9c39-6a6e2ee44b3f'],
    ['https://www.starz.com/us/en/movies/71196', 'https://www.starz.com/us/en/play/71196'],
    ['https://tv.apple.com/us/movie/f1/umc.cmc.3t6dvnnr87zwd4wmvpdx5came?ctx_brand=tvs.sbd.4000',
      'https://tv.apple.com/us/movie/f1/umc.cmc.3t6dvnnr87zwd4wmvpdx5came?action=play'],
    ['https://tv.apple.com/us/episode/pilot/umc.cmc.1?showId=umc.cmc.2&playableId=tvs.sbd.9001:x&ctx=1',
      'https://tv.apple.com/us/episode/pilot/umc.cmc.1?showId=umc.cmc.2&playableId=tvs.sbd.9001:x&action=play'],
    ['https://crunchyroll.pxf.io/xk92Nv?u=https%3A%2F%2Fwww.crunchyroll.com%2Fwatch%2FGRDQPM1ZY%2Fepisode&subId3=1',
      'https://www.crunchyroll.com/watch/GRDQPM1ZY/episode'],
    ['https://www.discoveryplus.com/video/watch/ef8dbdd7-9527-47a2-b322-9be4d25bdcbf',
      'https://play.discoveryplus.com/video/watch/ef8dbdd7-9527-47a2-b322-9be4d25bdcbf'],
    // others: tracking dropped, the rest kept
    ['https://www.youtube.com/watch?v=dQw4w9WgXcQ&utm_source=x', 'https://www.youtube.com/watch?v=dQw4w9WgXcQ'],
    ['https://www.hulu.com/watch/abc-123', 'https://www.hulu.com/watch/abc-123'],
  ];
  for (const [given, wanted] of cases) assert.equal(normalize(given), wanted, given);
  assert.equal(normalize('smb://nas/movie.mkv'), null);
});

test("Peacock's web links become the deeplink that plays", () => {
  const link = normalize('https://www.peacocktv.com/watch/asset/movies/the-grinch/3c1b0ac5-0f5f-3a6f-a5e2-36fd42a63f15');
  assert.ok(link.startsWith('https://www.peacocktv.com/deeplink?deeplinkData='));
  assert.deepEqual(JSON.parse(decodeURIComponent(link.split('=')[1])),
    { pvid: '3c1b0ac5-0f5f-3a6f-a5e2-36fd42a63f15', type: 'PROGRAMME', action: 'PLAY' });
});

test('services by site', () => {
  assert.equal(serviceOf('https://www.netflix.com/watch/1'), 'netflix');
  assert.equal(serviceOf('https://play.max.com/video/watch/x'), 'hbomax');
  assert.equal(serviceOf('https://www.amazon.com/gp/video/detail/B0X'), 'primevideo');
  assert.equal(serviceOf('https://www.amazon.com/dp/B0X'), null);
  assert.equal(serviceOf('https://notnetflix.com/x'), null);
  assert.equal(serviceOf('smb://nas/x'), null);
});

test('tab titles lose the site name', () => {
  assert.equal(cleanTitle('Thunderbolts* | Disney+', 'disneyplus'), 'Thunderbolts*');
  assert.equal(cleanTitle('Watch The Last of Us | HBO Max', 'hbomax'), 'The Last of Us');
  assert.equal(cleanTitle('Spider-Man: Far From Home - Netflix', 'netflix'), 'Spider-Man: Far From Home');
  assert.equal(cleanTitle('Spider-Man: Far From Home', 'netflix'), 'Spider-Man: Far From Home');
  assert.equal(cleanTitle('', 'netflix'), 'Netflix title');
});

test("room items name an Android app, as the Android app's do", () => {
  assert.deepEqual(roomItem('https://www.netflix.com/watch/1', 'X', 'netflix'),
    { file: 'https://www.netflix.com/watch/1', label: 'X', title: 'X', ids: { android: 'com.netflix.ninja' } });
  assert.equal(androidPackage('disneyplus'), 'com.disney.disneyplus');
  assert.deepEqual(roomItem('https://example.com/x', 'Y', null), { file: 'https://example.com/x', label: 'Y', title: 'Y' });
});
