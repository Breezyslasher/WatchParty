# Watch Party for Android TV

Joins a [Watch Party](https://github.com/Breezyslasher/kodi-addons) room —
the same relay and rooms as the Kodi addon — and keeps the movie playing in
another app (Disney+, Netflix, …) in sync with everyone else.

**Status: 0.4.** Joins rooms and announces titles on a Sony Android TV 9; starting titles from the app and syncing are still being tested. The sync logic, link handling and
relay client are unit-tested (the relay tests against the real relay), and
every app link below was confirmed by hand on the TV with adb.

## How it works

Android lets an app with notification access read and control other apps'
media sessions — the same thing the system uses for "now playing" and remote
buttons. [Session Probe](../session-probe) showed this works on a real TV:
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

Then grant access to what's playing, once. Watch Party shows a **Grant access** button
when the TV has a settings screen for it (many Android 11+ / Google TV devices); press it
and switch Watch Party on. On Android 13+ the switch is greyed out if the APK was
installed by a file-transfer app such as Send Files to TV (Android's "restricted setting");
installing with `adb install` (or Samsung's My Files on a phone) avoids that. Many TVs hide
the screen entirely (the Sony Android TV 9 has none) — then use adb:

```
adb shell cmd notification allow_listener dev.watchparty.tv/dev.watchparty.tv.SessionAccessService
```

(On Android 8 and older: `adb shell settings put secure enabled_notification_listeners dev.watchparty.tv/dev.watchparty.tv.SessionAccessService`.)

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

## Share what's playing (no link needed)

Watch something in another app, come back to Watch Party and press
**Share what I was watching**. It remembers what you last played (TV apps
stop when you leave them) and asks to share it at that position:

- **Disney+** — starts automatically on every TV *if* you opened the title's
  page before playing it. Disney+'s log records that page's id, which is what
  `/play/<id>` needs (confirmed for Iron Man and Thunderbolts*). Needs log
  access, once:
  ```
  adb shell pm grant dev.watchparty.tv android.permission.READ_LOGS
  ```
- **Other apps that publish a title** (Disney+ without the log, others to be
  confirmed) — every TV opens that app and shows "Pick *title* and press
  Play"; sync takes over once it plays.
- **Apple TV** publishes no title at all (only the duration) — paste its link.

What each app publishes was checked with Session Probe's `meta` command:
Apple TV gives only the duration; Disney+ gives title and duration, no id.

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
- `NowPlaying.kt` — "Share what's playing": Disney+ ids from its log, title-only items otherwise.
- `SyncService.kt` — foreground service: relay loop, media sessions, launching links.
- `MainActivity.kt` — join/leave, status, "Start for everyone".
- `SessionAccessService.kt` — empty notification listener (unlocks media sessions).

Tests: `./gradlew test`. To include the relay tests, start a relay
(`python3 relay_standalone.py --port 8765` from the Kodi addon) and run with
`WATCHPARTY_TEST_RELAY=http://127.0.0.1:8765`.
