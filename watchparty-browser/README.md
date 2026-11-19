# Watch Party for browsers

A browser extension (Chrome, Edge and other Chromium browsers; Firefox 128+)
that joins a [Watch Party](../README.md) room — the same relay and room codes
as the Android TV / phone app and the Kodi addon — and keeps a streaming site
playing in the browser in sync with everyone else.

**Status: 0.6.3, not yet tried on a real streaming site or with a real Kodi.** It looks and works
like the Android app: the same rail and screens. Tested end to end
in Chromium with stand-in pages (`test/e2e.mjs`, below); Firefox only passed
`web-ext lint`.

## Install

Not in an extension store yet; load it from this folder:

- **Chrome / Edge:** open `chrome://extensions`, turn on **Developer mode**,
  **Load unpacked**, and pick the `extension/` folder.
- **Firefox, for good:** Firefox only installs add-ons Mozilla has signed
  (an unsigned one is "not verified"). The **Sign Firefox extension** workflow
  (`.github/workflows/sign-firefox.yml`, run from the repository's Actions
  tab) has Mozilla sign it as an *unlisted* add-on — not on
  addons.mozilla.org's site, installable only from the file — and saves the
  signed `.xpi` as the run's artifact; open that file in Firefox to install
  it. It needs an addons.mozilla.org account's API key (Developer Hub →
  **Manage API Keys**) saved as the repository secrets `AMO_JWT_ISSUER` and
  `AMO_JWT_SECRET`. Mozilla signs each version once, so raise `version` in
  `manifest.json` before signing changes. Firefox's install prompt lists what
  the extension sends out (`data_collection_permissions`): your name (to the
  relay), the titles you watch and their links (browsing activity), play,
  pause and seek (website activity), and Browse searches (to JustWatch).
- **Firefox, for testing:** open `about:debugging#/runtime/this-firefox`,
  **Load Temporary Add-on…**, and pick `extension/manifest.json` or the
  zipped extension (it's removed when Firefox restarts).

**No install, no syncing: the relay's remote.** The relay also serves
`/remote` (e.g. `https://relay.example.com/remote?room=MOVIE7`): a web page
that runs the room from any browser — Browse, start a title for everyone,
play, pause, seek, go to a time, stop — without playing anything itself (a web
page can't drive another site's video; that's what this extension is for).
Its screens are this extension's own: the relay
([watchparty-relay](../watchparty-relay)) serves `links.js`, `justwatch.js`,
`hbomax.js`, `relay.js`, `ui.js`, `screens.js` and `room.css` straight from
this folder (and its Docker image copies them in). After changing them, run
`tools/vendor-kodi.sh <path to service.watchparty>` so the Kodi addon's
built-in relay has them too.

## Use

Click the Watch Party button in the toolbar: the Watch Party page opens in a
tab, laid out like the Android app — a rail with **Room** and **Browse**,
then the room form (or the room's members), and the screen
beside it.

1. In the rail, enter the relay address, the room code and a name, and press
   **Join room**. The browser asks once for access to the relay (and, in
   Firefox, to the streaming sites and JustWatch).
2. **Browse**: search for a movie or a TV show, open it, and press **Watch**
   (a movie) or pick the season and episode; the services open in a dialog.
   With one service it starts at once, as on Android. A link (`https://…`)
   typed into the search box shows as one result with **Start for
   everyone**, for what search can't find.
3. Everyone's TV, phone or browser opens the title. In the browser it opens in
   its own tab (the "player tab") and follows the room: play, pause and seek
   on any device and the browser follows; pause or seek in the browser and
   everyone follows. **Room** shows what's playing, with the room's controls
   (the remote's): the clock, play / pause, skip back or forward 10 / 30 s,
   go to a time, and stop for everyone.
4. **This browser** (in the rail, once in a room): **Watch here** opens and
   follows the room's title in a tab; **Remote only** just runs the room —
   Browse and the controls — and plays nothing here, like the Android app's
   Remote only (e.g. a laptop running the party for the TVs).

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
  More like this, the "Start for everyone on" dialog, a link in search; the same
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

**Kodi members** (the bridge, as in the Android app — see its README's *Kodi
in the same room*): a title started from Browse carries its identity in the
Kodi addon's fields, so Kodi plays its library copy. A Kodi member's library
file comes with the title's identity; the browser finds it on JustWatch (by
TMDB / IMDb id, title and year, or show, season and episode) and opens it on
the first of its services the browser can sync, then follows the room ("From
Kodi · on Disney+"). A file and a streaming copy can start a few seconds
apart (logos, recaps); sync keeps that offset. Kodi items without an identity
can't play in a browser, and the page says so. A title playing in one of
Kodi's streaming addons (Netflix, YouTube, SlyGuy's Disney+, Hulu and
Paramount+, Prime Video, Peacock via SkyOTT, Apple TV, Tubi, Crunchyroll)
opens on that service's website, from the addon's
path (`pluginLink` in `links.js`); Kodi's own stream address is never opened.

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

Start a relay first for the last two (`python3
../watchparty-relay/relay_standalone.py --port 8765`). The end-to-end test needs Playwright and ffmpeg:
it loads the extension into Chromium, answers `https://www.disneyplus.com/…`
and `https://www.netflix.com/…` with a test page playing a generated video,
and has a scripted member start a title, seek, pause and play; then pauses and
seeks in the browser (and checks the room, the browser and the relay's drift
agree after it), and starts a title from a link typed into Browse's search. It also answers
JustWatch's API with responses shaped like the live ones, searches, opens a
movie's page and presses Watch, picks a show's episode in the services
dialog, checks the room gets the Android app's link and label (`Andor · S1 ·
E1 · Kassa`) and the title's identity for Kodi, and that the browser's Back
leaves a title page. Then a scripted Kodi member starts a library file
(`nfs://…/thunderbolts.mkv` with its TMDB id): the browser finds it, opens it
on (stand-in) Disney+, waits out its start-up and seeks once to the room. Passed against a relay on
`127.0.0.1` and on a LAN address over plain http (2026-11-15).
