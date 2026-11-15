# Watch Party for Android TV

Joins a [Watch Party](https://github.com/Breezyslasher/kodi-addons) room —
the same relay and rooms as the Kodi addon — and keeps the movie playing in
another app (Disney+, Netflix, …) in sync with everyone else.

**Status: 0.9.26.** 0.7.2 was tested on a Sony Android TV 9 and a Chromecast HD
(Google TV, Android 14): joining rooms, announcing titles, and **Set up access**
on both (automatic on the Sony, the settings switch with the remote on the
Chromecast). 0.8 is a new look (same behaviour) and 0.9 adds title and person
pages; their layouts were checked in rendered screenshots at 1080p and phone
size, not yet with a remote on a TV or against a live Yamtrack server.
Not yet tried on a device: **Browse Yamtrack**, and phones (see [Phones](#phones)).
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
- **Not yet tried on a phone:** Watch Party itself (0.7.x; 0.8's phone layout was only checked in screenshots). Session Probe
  controlled Disney+ and Vita_plex on a phone, so syncing works there; which
  links start playback in the phone apps (e.g. Netflix without the TV app's
  `source=30` extra) is untested.

## Use

The screen has a rail on the left (**Room**, **Browse Yamtrack**, **Paste a
link**, and the room itself) and the chosen screen on the right. On a phone
the rail folds into a bar at the top.

1. In the rail, enter the relay address (e.g. `192.168.1.5:8765` or
   `https://relay.example.com`), the room code and a name, and press
   **Join room**. It stays in the room while you watch in other apps; the
   rail lists everyone in the room with what they're doing and their drift.
2. To start something, use **Browse Yamtrack** (below) or **Paste a link**
   and **Start for everyone** (typing on a TV is easiest from the Google TV
   remote app on a phone). **Room** shows what's playing.
3. **Leave room** stops syncing. **Back** goes Picker → Browse → Room, and
   leaves the app from Room.

Kodi members can be in the same room; they skip app links ("can't be played
on this device"), and the TV app skips Kodi files.

## Browse Yamtrack (pick an episode, no link needed)

Press **Browse Yamtrack**. The first time, enter your
Yamtrack server address and API token (typing is easiest with the Google TV
app's phone keyboard). Then:

1. **Movies** or **TV shows**, filtered by **Watching / Planning / Paused /
   Completed / All** — your Yamtrack library — or **Search** for a movie or a
   TV show (pick which with the chips above the search box). Filters with
   nothing in them are hidden (counted with one `limit=1` request per
   status), and if nothing is being watched it opens the first list that has
   something.
2. Pick a title. Its page shows the rating from the server's source (TMDB's,
   not your own score), the release or first-air date, runtime, seasons,
   genres and the description (press OK on it to read all of it). For a
   show, pick the season, then the episode (side by side; stacked on a
   phone); for a movie, press **Watch**. Further down: the **Cast** — pick someone to see
   their photo, biography and the titles they're known for — and **More like
   this**. Every title there opens its own page; **Back** returns to the
   page before.
3. The services open in a dialog in the middle of the screen, with their
   logos (Yamtrack's, else JustWatch's icon): pick one. Services whose app
   isn't on this TV are dimmed; other TVs in the room may still have it.
   Back closes the dialog. When there's only one service and its app is on
   this TV (and you're in a room), it starts right away. A service that has
   only the show's page, not the episode (the next episode gets the same
   link — Pluto TV for some shows), says "Opens the show's page: pick the
   episode" and isn't started automatically.
4. Every TV in the room starts it, the same as **Start for everyone**.

Needs a Yamtrack server with the API (`api/v1/`, Yamtrack's `dev` branch or
the `feat/add-api` work); it's only read: `GET api/v1/media/{movie|tv}/`
(your library), `GET api/v1/search/{movie|tv}/`, and for a title's page
`GET api/v1/media/{type}/{source}/{id}/` (with `cast/` and
`recommendations/`) and `GET api/v1/people/{source}/{id}/` (with
`known_for/`), with `Authorization: Bearer <token>`. Friends without Yamtrack don't need it.

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

**HBO Max "(with ASL)" editions:** HBO Max lists some shows twice, the
show and a separate "(with ASL)" series with American Sign Language
interpretation. JustWatch's HBO Max episode links for The Last of Us are the
ASL edition's (S1 E1 `dae9e532…` is "When You're Lost in the Darkness (with
ASL)"; checked 2026-11-15 with the page probe workflow). When you pick an HBO
Max episode, Watch Party checks HBO Max's public ASL page
(`hbomax.com/accessibility/asl`) for "<Title> (with ASL)"; if it's there, it
finds the regular series (the ASL series' "You May Also Like") and that
season's episode (`hbomax.com/show/<id>/s<N>`), and shares the regular
episode's link. Anything unexpected keeps JustWatch's link. ✓ Confirmed
(2026-11-15): The Last of Us plays the regular edition. Movies aren't
checked yet.

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
| Prime Video (phone) | Prime Video | `watch.amazon.com/watch?asin=…` / `?gti=…` (JustWatch; Amazon Channels like "Starz Amazon Channel" use the same) | same | ✓ movies and episodes start playing (Samsung phone, 2026-11-15) |
| Netflix (phone) | `com.netflix.mediaclient` | `netflix.com/watch/<id>` (an episode: its own web `/watch/<id>`) | same | ✓ movies and episodes start playing (Samsung phone, 2026-11-15) |
| Disney+ (phone) | `com.disney.disneyplus` | the same links | same | ✓ movies and episodes start playing (Samsung phone, 2026-11-15) |
| Hulu (phone) | (found by Android) | JustWatch's links | same | ✓ movies and episodes start playing (Samsung phone, 2026-11-15) |
| Pluto TV (phone) | `tv.pluto.android` | `pluto.tv/us/on-demand/movies/<id>`; an episode `pluto.tv/on-demand/series/<show>/season/<n>/episode/<name>`, but for some shows only the show's page `pluto.tv/us/shows/<show>/` (JustWatch, e.g. The King of Queens, The Twilight Zone) | same | ✓ movies and episode links start playing; a show link opens the show's page (Samsung phone, 2026-11-15) |
| Apple TV (phone) | `com.apple.atve.androidtv.appletv` | movies as above; an episode `tv.apple.com/<cc>/episode/<name>/<umc id>?showId=…&playableId=…` | the same + `action=play` | ✓ movies and episodes start playing (episodes only with `action=play`; without it they opened their page) (Samsung phone, 2026-11-15) |
| Tubi | (found by Android) | `tubitv.com/movies/<id>`; an episode `tubitv.com/tv-shows/<episode id>` (JustWatch's links) | same | ✓ starts playing (movies and episodes; on a Samsung phone, 2026-11-15) |
| Paramount+ | (found by Android) | `paramountplus.com/movies/<name>/<id>`; an episode `paramountplus.com/shows/<show>/video/<id>/<name>` (JustWatch's links) | same | ✓ starts playing (movies and episodes; on a Samsung phone, 2026-11-15) |
| Crunchyroll | `com.crunchyroll.crunchyroid` | `crunchyroll.com/watch/<id>/…` | `crunchyroll://watch/<id>` | opens the app only — the app claims no web links |
| Crunchyroll (phone) | `com.crunchyroll.crunchyroid` | the same; JustWatch's link is an affiliate redirect (`crunchyroll.pxf.io/…?u=<the real link>`, unwrapped) | `www.crunchyroll.com/watch/<episode id>` as is — the phone app claims `/watch/*` and `/<lang>/watch/*` (dumpsys) | ✓ movies and episodes start playing (Samsung phone, 2026-11-15) |

HBO Max show pages use a *different* id from the watch link, so for HBO Max
copy the link while it plays. On a phone, HBO Max ignores a link while it's
already open (after a movie it shows its home screen, even in a fresh task
and with playback stopped); the same link sent again opens it. So if HBO Max
isn't playing a *different* title 8 s after the link (its session can still
be the movie from before), Watch Party sends it once more and says so on the
status line (4 s was
sometimes too soon; adb on a
Samsung phone, 2026-11-15; its filters claim all of `play.hbomax.com`).
HBO Max only has a media session while its player is open (`dumpsys
media_session`: a media3 session, PLAYING, with the episode's title), so
the resend only happens when it had one before the link — HBO Max open in
its player, the case where it ignores links. When it wasn't open, it was
still loading after 8 s and a resend interrupted it. Sync also leaves a
just-launched app alone (no play, pause or seek) until it has played for
3 s, or 20 s pass: a seek 5 s after the link, while HBO Max's player was
starting, closed it (logcat on a Samsung phone). On phones, HBO Max is
launched plain (no fresh task) unless its player is open: with a fresh task
its player opened and closed, or it showed its home screen; a plain launch
played it whenever the player wasn't already open (adb). TVs keep the fresh
task. ✓ Confirmed with 0.9.19 on a Samsung phone (2026-11-15): HBO Max
episodes start both when HBO Max was closed and when its player was open.
Discovery+ (`com.discovery.discoveryplus.mobile`, the same Warner Bros.
Discovery player and `play.discoveryplus.com/video/watch/<id>` links) failed
the same way with the fresh task and gets the same treatment. ✓ Episodes
start with 0.9.21 (Samsung phone, 2026-11-15).

**Discovery+ movies:** JustWatch's Discovery+ movie link is the movie's page
(`discoveryplus.com/movie/<id>`), which doesn't play, and the movie id isn't
its video id ("content not available"). But HBO Max and Discovery+ share one
catalog (Discovery+'s pages: `"featureId"` = `"hbomaxId"`), and HBO Max's
video id plays in Discovery+: `play.discoveryplus.com/video/watch/<HBO Max's
video id>` played Ghosts of Flight 401 and Christmas of Yes (adb, Samsung
phone). Discovery+'s own share link for Christmas of Yes is
`www.discoveryplus.com/video/watch/ef8dbdd7-…` — the same id as HBO Max's;
pasted `www.` links are moved to `play.discoveryplus.com`. So when a movie
is on both, Discovery+ gets that play link; a movie only on Discovery+ opens
its page ("Opens the movie's page: press Play"). Netflix needs the `source=30` extra: the same
link without it only opened the app.

**Starz on phones:** the Android TV link JustWatch and Yamtrack give for
Starz is `starz.com/movies/<id>` (episodes: `starz.com/series/<id>/episodes/<id>`).
The TV app takes it; the phone app (`com.bydeluxe.d3.android.program.starz`)
only takes `www.starz.com/us/<lang>/…` (its intent filters, from `adb shell
dumpsys package`). Each device opens the form its app takes: the link as
shared, else `www.starz.com/us/en/play/<id>`, then `/us/en/movies/<id>` (an
episode: `/us/en/play/<episode id>`, then `/us/en/series/<id>/episodes/<id>`,
which only opens the episode's page). ✓ Movies and episodes start playing
on a Samsung phone this way (2026-11-15: the TV link's ids are the web
/play/ ids).

**Apps that don't claim their links:** some apps don't open their website's
links (Starz on phones), so Android can't say which app is the service's.
Watch Party then looks for an installed app named like the service ("STARZ"
for Starz, or the link's site name), and opens that app at its home screen:
find the title and press Play, and sync takes over.

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
- `YamtrackClient.kt` — read-only Yamtrack API client: library, search, title details, cast,
  recommendations, people (unit-tested).
- `Ui.kt` — the design system: colours, Figtree / JetBrains Mono (`assets/fonts`, OFL),
  focus rings, buttons, cards, fields. Design px ÷ 2 = dp.
- `Screens.kt` — Room, Browse (library grid and search), the title page (details, season /
  episode, cast, More like this), the person page and Paste a link.
- `ServiceDialog.kt` — "Start for everyone on": the services in a centered dialog.
- `Posters.kt` — poster thumbnails for the list.
- `Apps.kt` — which installed app opens a link.
- `HboMax.kt` — swaps an HBO Max "(with ASL)" episode link for the regular edition's (unit-tested).
- `SelfSetup.kt`, `AdbClient.kt` — "Set up access": grants notification access through the TV's own adb.
- `SyncService.kt` — foreground service: relay loop, media sessions, launching links.
- `MainActivity.kt` — setup screen, the rail (join/leave, members), screen switching, Back, toasts.
- `SessionAccessService.kt` — empty notification listener (unlocks media sessions).

Tests: `./gradlew test`. To include the relay tests, start a relay
(`python3 relay_standalone.py --port 8765` from the Kodi addon) and run with
`WATCHPARTY_TEST_RELAY=http://127.0.0.1:8765`.
