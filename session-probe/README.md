# Session Probe (Android TV)

A small test app that answers one question per app: **could a Watch Party app
on Android TV control this app's playback well enough to keep a room in sync?**

It reads the media sessions other apps publish (the same thing the system
uses for "now playing" and remote control), and runs the commands a sync app
would need: pause, play, seek, and checking the reported position.

Nothing here talks to a relay. It's only a feasibility check.

Runs on Android 5.0 or newer (phones, Android TV, Google TV).

## Build and install

1. Open this folder in Android Studio (Ladybug or newer) and let it sync.
   It needs Android SDK 35; Studio will offer to install it.
2. Turn on developer options and **USB/network debugging** on the TV, then
   connect: `adb connect <tv-ip>:5555` (accept the prompt on the TV).
3. Run the `app` configuration, or from a terminal:
   ```
   ./gradlew installDebug
   ```

It shows up on the home screen as **Session Probe**.

If you install a prebuilt APK instead, use `adb install -r SessionProbe.apk`
rather than a file manager or Send Files to TV: the on-screen installer only
ever says "App not installed", while `adb install` prints the actual reason.

## Grant access (once)

Android only lets an app see other apps' media sessions if it has
*notification access*. Most TVs hide that settings screen, so use adb:

```
adb shell cmd notification allow_listener dev.watchparty.sessionprobe/dev.watchparty.sessionprobe.ProbeListenerService
```

Type it as one piece: no space after the `/`.

On Android 8 and older, if that says the command isn't found, use this
instead (it replaces any other notification listeners, which TVs rarely have):

```
adb shell settings put secure enabled_notification_listeners dev.watchparty.sessionprobe/dev.watchparty.sessionprobe.ProbeListenerService
```

The app shows the right command for your device if access is missing. To undo
it: `adb shell cmd notification disallow_listener <same component>`, or
uninstall the app.

## Running a test

There are two ways, depending on whether the app keeps playing in the
background.

**Apps that stop when you leave them** (most TV apps, e.g. Disney+):

1. Open Session Probe and press **Test what's playing in 20 s**.
2. Within 20 seconds, switch to the app and start playback. Leave the remote
   alone.
3. The probe finds whatever is playing and runs the checks while the app stays
   in front: pause, seek +30 s, seek back, play, then watches the position for
   3 s. Afterwards it brings itself back with the log.

**Apps that keep playing in the background** (music apps, many phone apps):

1. Start playback, then open Session Probe. Each app with an active session
   gets a row showing its state, position and the commands it advertises.
2. Press **Test (opens app)** on its row. It brings the app to the front and
   runs the same checks. **Test in 20 s** does the same without launching
   anything.
3. Pause / Play / −30 s / +30 s on each row send a single command by hand.

Only one test runs at a time. Watch logs live from a computer with:

```
adb logcat -s SessionProbe
```

## Reading the result

Each run ends with a line like:

```
RESULT com.disney.disneyplus: can open by: nothing · pause ✓ · seek ✓ · play ✓ · position tracking ✓
```

| Check | What it means for a watch party |
|---|---|
| **pause ✓ / play ✓** | Basic play/pause sync works. |
| **seek ✓** | The app jumped to within 2.5 s of the requested spot. Needed for joining mid-video and for drift correction. |
| **position tracking ✓** | The reported position kept pace with real time (within 1 s over 3 s). Without this, the app can't tell when someone has drifted. |
| **can open by** | Whether another app can start a *specific* title inside it (media id, URI or search). "nothing" means everyone picks the title themselves, and the sync app only lines up the playback. |
| **stopped: no media session** | The app doesn't publish a session (or dropped it). A sync app can't drive it. |

## Remote control from adb

Session Probe also takes commands from adb **without coming to the front**, so
an app that stops when it leaves the screen keeps playing. It's the same call
a watch party app would make to line everyone up:

```
adb shell am broadcast -n dev.watchparty.sessionprobe/.ControlReceiver --es cmd seek --es to 1:00:00
```

| Command | Extras | Does |
|---|---|---|
| `status` | — | prints the app, title, state and position |
| `seek` | `--es to 1:05:30` (or `65:30`, or seconds) | jumps to that time |
| `skip` | `--es by 600` (or `-30`) | jumps forward/back by seconds |
| `pause` / `play` | — | pause / resume |

It acts on whatever is playing; add `--es pkg com.disney.disneyplus` to pick
an app. The reply is printed on the `Broadcast completed: … data="…"` line and
logged under `adb logcat -s SessionProbe`. The receiver is guarded by
`android.permission.DUMP`, which adb's shell has and normal apps can't get.

## Starting a title from outside (deep links)

Media sessions can only control what's already playing. To *start* a title, a
sync app sends the app a link — the same links a web page uses to open an app.

Send them with the fresh-start flags `0x10008000` (new task + clear task).
Without them, an app that's already open often gets the link and ignores it
(`am start` warns "intent has been delivered to currently running top-most
instance").

```
adb shell am start -W -f 0x10008000 -a android.intent.action.VIEW -d "LINK" com.disney.disneyplus
```

**Disney+** (confirmed on a Sony Android TV 9):

| Link | Result |
|---|---|
| `https://www.disneyplus.com/play/<id>` | **starts playing** that title |
| `https://www.disneyplus.com/browse/entity-<id>` | opens that title's page |

Both use the same `<id>`: open the title on disneyplus.com and copy it from
the address bar. The `?contextId=…` that the website adds isn't needed. The
app registers `https://www.disneyplus.com/*` and `disneyplus://…` with no path
restriction (`adb shell dumpsys package com.disney.disneyplus`), so routing
happens inside the app. A cold start via a plain link after `am force-stop`
landed on the TV home screen instead — use the flags.

**Vita_plex**: `vitaplex://`, `plex://` and `app.plex.tv` links with
`/library/metadata/<key>` open the item's details page; its media-session
play-from-search / media-id only cover the music library. Nothing starts a
video from outside yet.

## Results so far

| App | Device | pause | seek | play | tracking | start a title |
|---|---|---|---|---|---|---|
| Vita_plex (`org.VitaPlex.app`) | phone | ✓ | ✓ (0.5 s) | ✓ | ✓ (0.0 s) | details page only (link) |
| Disney+ (`com.disney.disneyplus`) | phone | ✓ | ✓ | ✓ | ✓ | — |
| Disney+ (`com.disney.disneyplus`) | Sony Android TV 9 (32-bit) | ✓ | ✓ (0.0 s) | ✓ | ✓ (0.4 s drift) | ✓ `/play/<id>` link |

So on that TV a Disney+ watch party can be fully automatic: send everyone the
`/play/<id>` link, wait until each media session reports playing, seek them
all to the host's position, then keep correcting drift.

## Troubleshooting

- **"App not installed" on the TV**: run `adb install -r` to see the real
  error. On the Sony TV above it was
  `Requested internal only, but not enough space` — the TV was nearly full.
  `adb shell pm trim-caches 999G` clears app caches, and Send Files to TV
  leaves every APK it received in `/sdcard/Download`.
- **"Invalid listener - must be a ComponentName"**: the grant command has a
  space in the component; retype it as one piece.
- **"No active media sessions"**: nothing is playing, or the app stopped when
  you left it — use **Test what's playing in 20 s**.

## What's in here

- `MainActivity.kt` — the whole UI and the test sequence.
- `ControlReceiver.kt` — the adb remote control (seek, skip, play, pause).
- `ProbeListenerService.kt` — an empty notification listener. It exists only
  so the system grants media-session access; it reads no notifications.
- No dependencies beyond the Android framework, no network permission.
