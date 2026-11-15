# Watch Party for Android TV

Joins a [Watch Party](https://github.com/Breezyslasher/kodi-addons) room —
the same relay and rooms as the Kodi addon — and keeps the movie playing in
another app (Disney+, Netflix, …) in sync with everyone else.

**Status: 0.7.2.** Tested on a Sony Android TV 9 and a Chromecast HD (Google
TV, Android 14): joining rooms, announcing titles, and **Set up access** on
both (automatic on the Sony, the settings switch with the remote on the
Chromecast). New in 0.7 and not yet tried on a device: **Browse Yamtrack**,
and phones (see [Phones](#phones)).
Syncing two TVs in one room end to end is still being tested.
The sync logic, link handling, relay client and adb client are unit-tested
(the relay tests against the real relay), and every app link below was
confirmed by hand on the TV with adb.

## How it works

Android lets an app with notification access read and control other apps'
media sessions — the same thing the system uses for "now playing" and remote
buttons. [Session Probe](../tools/session-probe) showed this works on a real TV:
Disney+ on a Sony Android TV 9 paused, played and seeked exactly on command.

1. **Starting a title.** Someone pastes a link in *Start for everyone*. Every
   TV in the room opens it in that app, with the "fresh start" flags
   (`FLAG_ACTIVITY_NEW_TASK | CLEAR_TASK`) — without them an app that's
   already open ignores the link.
2. **Following.** Once a second each TV reports its position to the relay and
   gets the room's state back. If it's more than 3 s off, it seeks (at most
   every 4 s); if the room paused or resumed, it does the same.
3. **Leading.** Pause, play or seek with your own remote and the app sends it
   to the room, so everyone follows you. Its own corrections are ignored for
   4 s so they don't bounce back.

The rules mirror the Kodi addon's engine; see `SyncCore.kt`.

## Install

Build with Android Studio (`./gradlew installDebug`), or install a prebuilt
APK:

```
adb install -r WatchPartyTV.apk
```

If installing fails, `adb install` prints the reason — on the Sony TV it was
a full disk (`Requested internal only, but not enough space`).

### Grant access to what's playing (once)

Open Watch Party and press **Set up access**. It tries two ways, in order:

1. **Automatic setup, no computer.** If the TV's debugging is on, Watch Party
   connects to the TV's own adb service and grants itself access (and log
   access, for sharing Disney+ titles). The TV asks "Allow USB debugging?" —
   choose *Always allow* and OK. Afterwards debugging can be turned off again.
   This works when the TV's adb listens on the network: Android's adbd listens
   on TCP 5555 when the device has no USB debugging port (most TVs, e.g. the
   Sony Android TV 9) or when Network debugging is on. A TV with a USB debugging
   port (Chromecast with Google TV) only offers *Wireless debugging*, which
   needs a pairing code and isn't supported by automatic setup.
2. **The settings switch.** If automatic setup can't run and the TV has a
   notification-access screen, Watch Party opens it: turn Watch Party on.

   **Why Watch Party's row reads "Allows syncing what's playing":** TV Settings
   sorts this list by the access service's label (shown under the app name).
   Google TV lists its own listener first — "Google TV, Required by system",
   which Settings disables — and a disabled row can't take focus, so on the
   Chromecast HD (Android 14) focus stayed on the screen's outer panel and the
   remote couldn't reach any row (`uiautomator dump`: Watch Party's row
   `enabled="true"` but nothing in the list focused). Starting the label with
   "A" sorts Watch Party first, where the remote can select it (0.6.2, tested
   with the remote only).

   If the remote still can't reach the list on some TV, click Watch Party's
   switch once with a **USB or Bluetooth mouse**; after that the remote works
   too.

   (Android 13+ also flags apps installed from a file — Send Files to TV, a
   file manager — with `ACCESS_RESTRICTED_SETTINGS: deny`. On the Chromecast
   HD that flag did not disable the row; the focus problem above was the
   cause.)

If the TV has no settings screen and no network adb (some TVs hide the screen;
the Sony has none), turn on debugging (Settings → Device Preferences → About:
press **Build** 7 times; then Developer options → USB/Network debugging) and
press **Set up access** again — it shows these steps itself.

From a computer instead (Android 11+: Wireless debugging → `adb pair`, then
`adb connect`):

```
adb shell cmd notification allow_listener dev.watchparty.tv/dev.watchparty.tv.SessionAccessService
```

(On Android 8 and older: `adb shell settings put secure enabled_notification_listeners dev.watchparty.tv/dev.watchparty.tv.SessionAccessService`.)

Updates installed over the app (same signing key) keep the access; only an
uninstall clears it.

## Phones

The same APK runs on Android phones and tablets (it isn't TV-only), so a phone
can be a member of the room or pick what to watch with **Browse Yamtrack**:

- **Different apps for the same service:** Netflix is `com.netflix.ninja` on
  Android TV and `com.netflix.mediaclient` on phones. A room item names the app
  of whoever started it; if that app isn't installed, Watch Party uses the app
  that opens the link on this device, for opening it and for following its
  playback. Disney+ (`com.disney.disneyplus`) and HBO Max (`com.wbd.stream`)
  use the same package on both.
- **Access:** phones have a USB debugging port, so automatic setup can't reach
  adb over the network; **Set up access** opens the notification-access
  switch instead. On Android 13+, a sideloaded app's switch is blocked until
  *Settings → Apps → Watch Party → ⋮ → Allow restricted settings*.
- **Not yet tried on a phone:** Watch Party 0.7.x itself. Session Probe
  controlled Disney+ and Vita_plex on a phone, so syncing works there; which
  links start playback in the phone apps (e.g. Netflix without the TV app's
  `source=30` extra) is untested.

## Use

1. Open **Watch Party**, enter the relay address (e.g. `192.168.1.5:8765` or
   `https://relay.example.com`), the room code and a name, and press
   **Join room**. It stays in the room while you watch in other apps; the
   status line shows the room and your drift.
2. To start something, paste its link in **Start for everyone** (typing on a
   TV is easiest from the Google TV remote app on a phone) and press the
   button.
3. **Leave room** stops syncing.

Kodi members can be in the same room; they skip app links ("can't be played
on this device"), and the TV app skips Kodi files.

## Browse Yamtrack (pick an episode, no link needed)

Press **Browse Yamtrack** on the main screen. The first time, enter your
Yamtrack server address and API token (typing is easiest with the Google TV
app's phone keyboard). Then:

1. **Movies** or **TV shows**, filtered by **Watching / Planning / Paused /
   Completed / All** — your Yamtrack library — or **Search** for anything.
2. Pick a title. For a show, pick the season, then the episode.
3. Pick a service. Services whose app isn't on this TV are marked; other TVs
   in the room may still have it.
4. Every TV in the room starts it, the same as **Start for everyone**.

Needs a Yamtrack server with the API (`api/v1/`, Yamtrack's `dev` branch or
the `feat/add-api` work); it's only read: `GET api/v1/media/{movie|tv}/`
(your library), `GET api/v1/search/{movie|tv}/`, with
`Authorization: Bearer <token>`. Friends without Yamtrack don't need it.

**Where the links come from:**

- **Movies:** Yamtrack's own providers endpoint
  (`GET api/v1/media/movie/tmdb/{id}/providers/?region=`), using each
  service's `tv_link` (else `link`) — Yamtrack looks them up on JustWatch and
  caches them, when the server has `JUSTWATCH_ENABLED` on. If it returns no
  links (JustWatch off, or an older server), Watch Party asks JustWatch itself.
- **Episodes:** seasons and episodes come from Yamtrack
  (`…/tv/tmdb/{id}/seasons/`, `…/{season}/episodes/`), and each episode's links
  from Yamtrack's episode providers
  (`GET api/v1/media/tv/tmdb/{id}/{season}/{episode}/providers/?region=`,
  feat/justwatch-links `4bc82c5`): the episode's own `link`, and for Netflix a
  `tv_link` that is the episode's `/watch/<id>`. Its JustWatch query was run
  against the live API (WatchParty's lookup workflow). If it returns no links,
  Watch Party asks JustWatch directly, matched by the TMDB id.
- Titles Yamtrack doesn't have from TMDB use JustWatch directly for everything.

JustWatch has a link for **each episode** (checked with `tools/justwatch/jw.py` on GitHub Actions,
US, 2026-11-15):

| Service | Episode link | Each episode different? |
|---|---|---|
| Disney+ | `disneyplus.com/play/<episode id>` | ✓ |
| HBO Max | `play.hbomax.com/video/watch/<episode id>` | ✓ |
| Apple TV | `tv.apple.com/us/episode/<name>/umc.cmc.<id>?showId=…` | ✓ |
| Amazon | `watch.amazon.com/watch?asin=<episode>` | ✓ |
| Netflix | Android TV link: the show's `netflix.com/watch/<id>` | ✗ — so Watch Party uses Netflix's **web** link, the episode's own `/watch/<id>` |

Movie links are the ones in [Which link to share](#which-link-to-share).
**Not yet confirmed on a TV:** that episode links start the right episode
(Netflix's web `/watch/<episode id>`, Apple TV's `/episode/` links), and
Amazon links in general. JustWatch is unofficial and rate-limited; lookups
happen only when you pick a title.

## Which link to share

Share the address from the app's **website while the title is playing** —
that's the link most likely to start playback rather than just open a page.

Tested on a Sony Android TV 9 (32-bit). The app converts what you paste, so
page links and tracking parameters (`?utm_…`, `?trackId…`) are fine:

| App | Package | Share | The TV launches | Result |
|---|---|---|---|---|
| Disney+ | `com.disney.disneyplus` | `disneyplus.com/play/<id>` or `/browse/entity-<id>` | `https://www.disneyplus.com/play/<id>` | ✓ starts playing |
| Netflix | `com.netflix.ninja` | `netflix.com/watch/<id>` or `/title/<id>` | `https://www.netflix.com/watch/<id>` + extra `source=30` | ✓ starts playing after a few seconds |
| HBO Max | `com.wbd.stream` | `hbomax.com/video/watch/<id>` | `https://play.hbomax.com/video/watch/<id>` (the app only claims `play.hbomax.com` / `play.max.com`) | ✓ opens the movie |
| Starz | (found by Android) | `starz.com/us/en/play/<id>` or `/movies/<id>` | `https://www.starz.com/us/en/play/<id>` | ✓ starts playing |
| Apple TV | `com.apple.atve.androidtv.appletv` | `tv.apple.com/<cc>/movie/<name>/<umc.cmc.id>` | same link + `?action=play` | ✓ starts playing after the profile picker |
| Crunchyroll | `com.crunchyroll.crunchyroid` | `crunchyroll.com/watch/<id>/…` | `crunchyroll://watch/<id>` | opens the app only — the app claims no web links |

HBO Max show pages use a *different* id from the watch link, so for HBO Max
copy the link while it plays. Netflix needs the `source=30` extra: the same
link without it only opened the app.

Any other app's link is passed through unchanged. If it only opens the
title's page, everyone presses Play and sync takes over.

Other apps work the same way if they register their website's links. If a
link only opens the title's page, everyone presses Play and sync takes over.

### Testing a new app's link

From a computer, with the app installed on the TV:

```
adb shell pm list packages | grep -iE "netflix|hbo|max|wbd|starz|crunchy|paramount|cbs"
adb shell am start -W -f 0x10008000 -a android.intent.action.VIEW -d "WATCH_LINK" PACKAGE
```

Note whether it starts playing, opens the title's page, or just opens the
app. Then check the app can be controlled: Session Probe's
**Test what's playing in 20 s** must pass pause, seek and play.

## Code

- `SyncCore.kt` — all sync decisions, no Android code (unit-tested).
- `RelayClient.kt` — the relay protocol v3 (join/poll/command/leave, clock offset).
- `MediaLink.kt` — link normalization (only confirmed formats are rewritten).
- `JustWatch.kt` — where to watch a title, its seasons and episodes, and each one's link (unit-tested).
- `YamtrackClient.kt` — read-only Yamtrack API client: library and search (unit-tested).
- `BrowseActivity.kt` — "Browse Yamtrack": list, search, season/episode/service pickers.
- `Posters.kt` — poster thumbnails for the list.
- `Apps.kt` — which installed app opens a link.
- `SelfSetup.kt`, `AdbClient.kt` — "Set up access": grants notification access through the TV's own adb.
- `SyncService.kt` — foreground service: relay loop, media sessions, launching links.
- `MainActivity.kt` — join/leave, status, "Start for everyone", setup.
- `SessionAccessService.kt` — empty notification listener (unlocks media sessions).

Tests: `./gradlew test`. To include the relay tests, start a relay
(`python3 relay_standalone.py --port 8765` from the Kodi addon) and run with
`WATCHPARTY_TEST_RELAY=http://127.0.0.1:8765`.
