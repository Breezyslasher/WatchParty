# Watch Party for browsers

A browser extension (Chrome, Edge and other Chromium browsers; Firefox 128+)
that joins a [Watch Party](../README.md) room — the same relay and room codes
as the Android TV / phone app and the Kodi addon — and keeps a streaming site
playing in the browser in sync with everyone else.

**Status: 0.3.0, not yet tried on a real streaming site.** It looks and works
like the Android app: the same rail and screens. Tested end to end
in Chromium with stand-in pages (`test/e2e.mjs`, below); Firefox only passed
`web-ext lint`.

## Install

Not in an extension store yet; load it from this folder:

- **Chrome / Edge:** open `chrome://extensions`, turn on **Developer mode**,
  **Load unpacked**, and pick the `extension/` folder.
- **Firefox:** open `about:debugging#/runtime/this-firefox`, **Load Temporary
  Add-on…**, and pick `extension/manifest.json` (it's removed when Firefox
  restarts until the extension is signed).

## Use

Click the Watch Party button in the toolbar: the Watch Party page opens in a
tab, laid out like the Android app — a rail with **Room**, **Browse** and
**Paste a link**, then the room form (or the room's members), and the screen
beside it.

1. In the rail, enter the relay address, the room code and a name, and press
   **Join room**. The browser asks once for access to the relay (and, in
   Firefox, to the streaming sites and JustWatch).
2. **Browse**: search for a movie or a TV show, open it, and press **Watch**
   (a movie) or pick the season and episode; the services open in a dialog.
   With one service it starts at once, as on Android. Or **Paste a link**.
3. Everyone's TV, phone or browser opens the title. In the browser it opens in
   its own tab (the "player tab") and follows the room: play, pause and seek
   on any device and the browser follows; pause or seek in the browser and
   everyone follows. **Room** shows what's playing.

Keep the Watch Party page open while watching: it keeps the browser in sync.
The browser's Back goes back from a title page, like the remote's Back.

## How it works

- `room.html` / `room.js` — the page: the rail (MainActivity's), navigation,
  and the loop that once a second reports the player tab's video and follows
  the room. The tick comes from a
  worker (`ticker.js`), because Chrome slows a background page's own timers to
  once a minute after five minutes. The tab is also marked not to be
  discarded.
- `sync-core.js` — the sync rules, a port of the Android app's `SyncCore.kt`
  (the same rules as the Kodi addon): correct drift over 3 s, ignore the echo
  of our own commands for 4 s, leave a just-opened title alone until it has
  played 3 s (at most 20 s), and don't report its loading as buffering.
- `relay.js` — the relay protocol v3 (join / poll / command / leave, clock
  offset), as the Android app and Kodi addon speak it.
- `links.js` — which site a link is, and the link to share: the Android app's
  `MediaLink` rules (Netflix `/watch/<id>`, Disney+ `/play/<id>`,
  `play.hbomax.com`, Apple TV `?action=play`, Peacock's play deeplink, …), so
  TVs and phones start the same title. A shared item names the service's
  Android app, which Watch Party's Android app needs to open it.
- `screens.js`, `ui.js`, `room.css` — the Android app's screens (`Screens.kt`,
  `ServiceDialog.kt`) and components (`Ui.kt`): Room, Browse (search; no
  Yamtrack library), the title page with its season and episode columns and
  More like this, the "Start for everyone on" dialog, Paste a link; the same
  colours, Figtree and sizes (0.6 × the TV design's).
- `justwatch.js`, `hbomax.js` — search and title pages: a port of the Android
  app's Browse without Yamtrack (`JustWatch.kt`, `HboMax.kt`):
  the same JustWatch queries and the same choice of each service's link
  (Netflix's web `/watch/<episode>`, else the Android TV link as https, …), the
  HBO Max "(with ASL)" swap, Discovery+ movies via HBO Max's video id, and
  services that only have the show's page marked. So a title started in a
  browser is the exact link a TV or phone would share, and opens the same way
  on all of them. The country comes from the browser's language (US
  otherwise) and can be changed. JustWatch answered requests with a Chrome and
  a Firefox extension's `Origin` (checked from GitHub Actions, 2026-11-15).
  Links TVs share in a TV-only form open in their web form in the browser
  (Starz's `starz.com/movies/<id>` → `/us/en/play/<id>`, as on phones).
- `content.js` — on the streaming sites: finds the title's `<video>` (also in
  open shadow roots; the largest one with media) and reports its position and
  state, and plays, pauses and seeks it.
- `netflix-main.js` — Netflix only, in the page's own world: play, pause and
  seek through Netflix's player API (`netflix.appContext…videoPlayer`), since
  setting a Netflix `<video>`'s time directly is known to break its player.
  If that API isn't there, the `<video>` is used.

Sites: Netflix, Disney+, HBO Max, Prime Video, Hulu, Peacock, Paramount+,
Apple TV, YouTube, Crunchyroll, Tubi, Pluto TV, Starz, Plex, Discovery+.
A link to another site still opens, but can't be synced.

**Kodi members** share files (`smb://`, library paths) that a browser can't
open: the room page says so. Kodi skips the browser's https links the same
way it skips the Android app's.

## Not yet confirmed

- **Any real streaming site.** Each site's player is different; the
  `<video>` element works on many, but some may need their own handling (as
  Netflix does), may show ads in a different `<video>`, or may block `play()`
  without a click (a click on the player tab fixes that once).
- Netflix's player API calls — from Netflix's page scripts, unchecked here.
- Whether links shared by TVs (e.g. Starz's `starz.com/movies/<id>`, Peacock's
  deeplink) start playback on the websites, or only open the title's page; if
  a page opens, press Play and sync takes over.
- Firefox.

## Tests

```
npm test                                   # sync rules, links (node)
WATCHPARTY_TEST_RELAY=http://127.0.0.1:8765 npm test     # + the relay client against a real relay
WATCHPARTY_TEST_RELAY=http://127.0.0.1:8765 node test/e2e.mjs [screenshots-dir]
```

Start a relay first for the last two (`python3 relay_standalone.py --port
8765` from the Kodi addon). The end-to-end test needs Playwright and ffmpeg:
it loads the extension into Chromium, answers `https://www.disneyplus.com/…`
and `https://www.netflix.com/…` with a test page playing a generated video,
and has a scripted member start a title, seek, pause and play; then pauses and
seeks in the browser (and checks the room, the browser and the relay's drift
agree after it), and starts a title with Paste a link. It also answers
JustWatch's API with responses shaped like the live ones, searches, opens a
movie's page and presses Watch, picks a show's episode in the services
dialog, checks the room gets the Android app's link and label (`Andor · S1 ·
E1 · Kassa`), and that the browser's Back leaves a title page. Passed against a relay on
`127.0.0.1` and on a LAN address over plain http (2026-11-15).
