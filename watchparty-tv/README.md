# Watch Party for Android TV

Joins a [Watch Party](https://github.com/Breezyslasher/kodi-addons) room —
the same relay and rooms as the Kodi addon — and keeps the movie playing in
another app (Disney+, Netflix, …) in sync with everyone else.

**Status: 0.14.1** (Remote only and the room's controls on Room, redrawn as a card with round buttons; the Kodi bridge, with Kodi's streaming addons, and search without Yamtrack: not yet tried on a device; links go in Browse's search box, and a seek in a paused room pauses again — for a phone's Netflix). Tested on a Sony Android TV 9 and a Samsung phone
(2026-11-15): joining a room, **Browse** against a live Yamtrack
server, and starting titles from it in each service's app on both devices
(the table below). 0.7.2's **Set up access** was tested on the Sony
(automatic) and a Chromecast HD (Google TV, Android 14; the settings switch
with the remote); the Chromecast hasn't been tried with 0.8+. The sync logic,
link handling, relay client and adb client are unit-tested (the relay tests
against the real relay).

Which services start playing a title, by device (details in
[Which link to share](#which-link-to-share)):

| Service | Phone (Samsung) | TV (Sony) |
|---|---|---|
| Netflix | ✓ movies, episodes | ✓ movies, episodes |
| Disney+ | ✓ movies, episodes | ✓ |
| HBO Max | ✓ movies, episodes (closed or already open) | ✓ movies; episodes (regular edition, not ASL) |
| Starz | ✓ movies, episodes | ✓ movies, episodes |
| Apple TV | ✓ movies, episodes | ✓ movies — episodes untested |
| Paramount+ | ✓ movies, episodes | ✓ movies, episodes |
| YouTube (free) | ✓ movies | ✓ movies |
| Prime Video | ✓ movies, episodes | untested |
| Hulu | ✓ movies, episodes | untested |
| Peacock | ✓ movies, episodes (also already open) | untested |
| Tubi | ✓ movies, episodes | untested |
| Pluto TV | ✓ movies, episode links (some shows only have the show's page) | untested |
| Plex | ✓ movies, episodes | untested |
| Crunchyroll | ✓ movies, episodes | opens the app only (earlier test) |
| Discovery+ | ✓ episodes; movies via HBO Max's video id (adb) | untested |
| The Roku Channel | ✗ opens the app's home screen only | untested |

Still untested: YouTube TV, a Chromecast with 0.8+, the service dialog with a
TV remote, and whether 0.9.31 stops the Netflix start-up loop (below).

## How it works

Android lets an app with notification access read and control other apps'
media sessions — the same thing the system uses for "now playing" and remote
buttons. [Session Probe](../tools/session-probe) showed this works on a real TV:
Disney+ on a Sony Android TV 9 paused, played and seeked exactly on command.

1. **Starting a title.** Someone picks a title in **Browse** or
   pastes a link. Every device in the room opens it in its own app for that
   service (the link form that app takes), with the "fresh start" flags
   (`FLAG_ACTIVITY_NEW_TASK | CLEAR_TASK`) — without them an app that's
   already open ignores the link. (HBO Max and Discovery+ on phones are the
   exception; see below the table.) A just-opened app is left alone until it
   has played 3 s (at most 20 s), and its loading doesn't count as buffering —
   otherwise the slowest device's loading paused the whole room again and
   again.
2. **Following.** Once a second each device reports its position to the relay and
   gets the room's state back. If it's more than 3 s off, it seeks (at most
   every 4 s); if the room paused or resumed, it does the same. While someone
   buffers (after start-up), the relay pauses the room until they're ready.
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
can be a member of the room or pick what to watch with **Browse**:

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
- **Links:** phone apps sometimes take a different form of the link than the
  TV app (Starz, Crunchyroll, Apple TV episodes); each device opens the form
  its app claims. Results per service are in the table at the top.

## Use

The screen has a rail on the left (**Room**, **Browse**, and the room
itself) and the chosen screen on the right. On a phone
the rail folds into a bar at the top.

1. In the rail, enter the relay address (e.g. `192.168.1.5:8765` or
   `https://relay.example.com`), the room code and a name, and press
   **Join room**. It stays in the room while you watch in other apps; the
   rail lists everyone in the room with what they're doing and their drift.
2. To start something, use **Browse** (below). Its search box also takes a
   link (`https://…`): it shows the link as one result with **Start for
   everyone**, for what search can't find (a YouTube video, a title
   JustWatch lacks). Typing on a TV is easiest from the Google TV remote app
   on a phone. **Room** shows what's playing.
3. While something plays, **Room** has the whole room's controls: −30 s,
   −10 s, Play / Pause, +10 s, +30 s, **Go to time** (`1:02:03`) and **Stop
   for everyone** (press it twice). Everyone follows: TVs, phones, browsers
   and Kodi.
4. **This phone / This TV: Watch here or Remote only** (on Room, also before
   joining). *Remote only* keeps the device in the room to start titles and
   run those controls, but it opens and follows nothing itself — a phone as
   the TVs' remote. It reports nothing playing (never on the item, so the
   room doesn't wait for it or count its drift). Back to *Watch here*, it
   opens what the room is playing. The relay's web page `/remote` does the
   same from any browser.
5. **Leave room** stops syncing. **Back** returns to the page before (a title
   page, a person, Browse, then Room), and leaves the app from Room.

Kodi members can be in the same room: see [Kodi in the same room](#kodi-in-the-same-room-the-bridge).

## Kodi in the same room (the bridge)

Kodi, Android and browser members can share a room, and each plays the
same title from its own source:

- **Android → Kodi.** A title started from **Browse** is shared as its
  streaming link plus the title's identity, in the Kodi addon's own fields
  (`type`, `title`, `year`, `ids.tmdb` for a movie; `show`, `season`,
  `episode` for an episode — `RelayClient.appItem`). Kodi can't open the
  link, so it plays its **library copy** of that movie or episode (matched by
  TMDB / IMDb id, title and year, or show, season and episode; the Kodi
  addon's `AppItemTest`). A pasted link has no identity: Kodi skips it.
- **Kodi → Android.** A Kodi member shares a library file (`smb://…`) with
  the title's identity. Android can't open the file, so it looks the title up
  on JustWatch — a movie by TMDB id, else IMDb id, else title and year; an
  episode by its show's title, season and episode — and opens it on the
  first of its services (JustWatch's order) whose app is installed here
  (`Bridge.kt`, `BridgeTest`). It then syncs like any title. "No streaming
  service has …" or "… isn't on an app here" otherwise.
- **Kodi's streaming addons.** A Kodi member playing a title in a streaming
  addon shares the addon's `plugin://` path; Android turns it into the web
  link of the same title and opens it in the service's app
  (`AddonLinks.kt`): Netflix (CastagnaIT's addon), YouTube, SlyGuy's Disney+,
  Hulu and Paramount+, Sandmann79's Prime Video, Paco8's SkyOTT (Peacock),
  and the Apple TV, Tubi and Crunchyroll addons. Formats read from each
  addon's source. Paramount+ and Apple TV need no title name: Paramount+'s
  own links are `/movies/video/<id>/` and `/shows/video/<id>/`, and
  `tv.apple.com/movie|episode/<id>` redirects to the full link, which
  Android follows before opening the app (`tools/probe/nameless_links.py`,
  2026-11-16). HBO Max and Discovery+ paths carry an edit id that no public
  page maps to a link (`tools/probe/hbo_ids.py`); those go through the
  title's identity instead. The
  other way, Kodi plays a shared link in an installed addon when it has no
  library copy (the Kodi addon's `addon_links.py`).
- **The catch:** a library file and a streaming copy can start a few seconds
  apart (studio logos, "previously on"), or more for a different cut. Sync
  keeps everyone at the same time code, so that offset stays.

0.11 (identity) and 0.12 (addons), unit-tested (identity in and out, the
lookup, the relay keeping the fields, every addon path format) and in the
browser extension's end-to-end test; not yet tried with a real Kodi or the
real addons.

## Browse (pick an episode, no link needed)

Press **Browse**. It works **with or without Yamtrack**:

- **Without Yamtrack** (0.10): **Search** finds any movie or TV show on
  JustWatch, and its page comes from JustWatch too — description, genres,
  release date, runtime, age rating, TMDB's rating (IMDb's if TMDB has none)
  and **More like this** (JustWatch's similar titles). No cast row: JustWatch
  has no photos or pages for people. Seasons, episodes and
  services were already JustWatch's when Yamtrack had none. **Connect
  Yamtrack** (a tab) adds your library.
- **With Yamtrack:** enter your server address and API token under
  **Connect Yamtrack** (typing is easiest with the Google TV app's phone
  keyboard). Search uses Yamtrack first and JustWatch when Yamtrack finds
  nothing or fails; a JustWatch result with a TMDB id still gets Yamtrack's
  page.

JustWatch's fields were checked against the live API with
`tools/justwatch/jw.py --detail` (2026-11-15). Then:

1. **Movies** or **TV shows** (with Yamtrack), filtered by **Watching / Planning / Paused /
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
`known_for/`), with `Authorization: Bearer <token>`. Without it, Browse uses
only JustWatch (see above).

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
Episode links start the right episode on the phone for every service in the
table, and on the TV for Netflix, Starz and Paramount+; Apple TV's
`/episode/` links and Amazon are confirmed on the phone only. JustWatch is
unofficial and rate-limited; lookups happen only when you pick a title.

## Which link to share

Share the address from the app's **website while the title is playing** —
that's the link most likely to start playback rather than just open a page.

Rows without "(phone)" were tested on a Sony Android TV 9 (32-bit); "(phone)"
rows on a Samsung phone. The app converts what you paste, so page links and
tracking parameters (`?utm_…`, `?trackId…`) are fine:

| App | Package | Share | The TV launches | Result |
|---|---|---|---|---|
| Disney+ | `com.disney.disneyplus` | `disneyplus.com/play/<id>` or `/browse/entity-<id>` | `https://www.disneyplus.com/play/<id>` | ✓ starts playing (Sony TV, again with 0.9.31 on 2026-11-15) |
| Netflix | `com.netflix.ninja` | `netflix.com/watch/<id>` or `/title/<id>` | `https://www.netflix.com/watch/<id>` + extra `source=30` | ✓ movies and episodes start playing after a few seconds (Sony TV, 2026-11-15). Before 0.9.31 the other device looped its first seconds while the TV loaded (the TV's loading counted as buffering, which pauses the room); 0.9.31's fix isn't confirmed yet |
| HBO Max | `com.wbd.stream` | `hbomax.com/video/watch/<id>` | `https://play.hbomax.com/video/watch/<id>` (the app only claims `play.hbomax.com` / `play.max.com`) | ✓ opens the movie |
| Starz | (found by Android) | `starz.com/us/en/play/<id>` or `/movies/<id>` | `https://www.starz.com/us/en/play/<id>` | ✓ movies and episodes start playing (Sony TV; episodes with 0.9.31, 2026-11-15) |
| Apple TV | `com.apple.atve.androidtv.appletv` | `tv.apple.com/<cc>/movie/<name>/<umc.cmc.id>` | same link + `?action=play` | ✓ starts playing after the profile picker |
| Prime Video (phone) | Prime Video | `watch.amazon.com/watch?asin=…` / `?gti=…` (JustWatch; Amazon Channels like "Starz Amazon Channel" use the same) | same | ✓ movies and episodes start playing (Samsung phone, 2026-11-15) |
| YouTube (free) | YouTube | `www.youtube.com/watch?v=<id>` (JustWatch) | same | ✓ movies start playing (Samsung phone and Sony TV, 2026-11-15) |
| Peacock (phone) | `com.peacocktv.peacockandroid` | JustWatch: web `peacocktv.com/watch/asset/movies/<name>/<id>` (episodes `…/episodes/<name>/<id>`), Android TV `peacocktv.com/deeplink?deeplinkData={"pvid":…,"type":…,"action":"PDP"}` — both open the title's page | the deeplink with `"action":"PLAY"` (pvid = the web link's id; type PROGRAMME for movies, EPISODE for episodes) | ✓ movies and episodes start playing with `PLAY` (Samsung phone, 2026-11-15), also with Peacock already open (once it did nothing then; not seen again) |
| Plex (phone) | `com.plexapp.android` | `watch.plex.tv/movie/<name>?autoplay=1`; an episode `watch.plex.tv/show/<show>/season/<n>/episode/<n>?autoplay=1` (JustWatch) | same | ✓ movies and episodes start playing (Samsung phone, 2026-11-15) |
| The Roku Channel (phone) | `com.roku.web.trc` | JustWatch only has `therokuchannel.roku.com/details/<id>/<name>` (web and Android) | — the app claims only `com.roku.daisy://` and `app.mobile.roku.com/rokuapp…` (dumpsys), not those links | opens the app at its home screen only (the link alone opened the browser; Samsung phone, 2026-11-15). The Roku app (`com.roku.remote`) claims only `roku://` and `app.mobile.roku.com/rokuremote…`, so it can't open titles either |
| Netflix (phone) | `com.netflix.mediaclient` | `netflix.com/watch/<id>` (an episode: its own web `/watch/<id>`) | same | ✓ movies and episodes start playing (Samsung phone, 2026-11-15) |
| Disney+ (phone) | `com.disney.disneyplus` | the same links | same | ✓ movies and episodes start playing (Samsung phone, 2026-11-15) |
| Hulu (phone) | (found by Android) | JustWatch's links | same | ✓ movies and episodes start playing (Samsung phone, 2026-11-15) |
| Pluto TV (phone) | `tv.pluto.android` | `pluto.tv/us/on-demand/movies/<id>`; an episode `pluto.tv/on-demand/series/<show>/season/<n>/episode/<name>`, but for some shows only the show's page `pluto.tv/us/shows/<show>/` (JustWatch, e.g. The King of Queens, The Twilight Zone) | same | ✓ movies and episode links start playing; a show link opens the show's page (Samsung phone, 2026-11-15) |
| Apple TV (phone) | `com.apple.atve.androidtv.appletv` | movies as above; an episode `tv.apple.com/<cc>/episode/<name>/<umc id>?showId=…&playableId=…` | the same + `action=play` | ✓ movies and episodes start playing (episodes only with `action=play`; without it they opened their page) (Samsung phone, 2026-11-15) |
| Tubi | (found by Android) | `tubitv.com/movies/<id>`; an episode `tubitv.com/tv-shows/<episode id>` (JustWatch's links) | same | ✓ starts playing (movies and episodes; on a Samsung phone, 2026-11-15) |
| Paramount+ | (found by Android) | `paramountplus.com/movies/<name>/<id>`; an episode `paramountplus.com/shows/<show>/video/<id>/<name>` (JustWatch's links) | same | ✓ starts playing (movies and episodes; Samsung phone and Sony TV, 2026-11-15) |
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

**Seeking while paused:** a phone's Netflix app starts playing when it's
seeked while paused. With the room paused, each drift correction set it
playing, so sync paused and seeked it every 4 s for most of a minute
(logcat, Samsung phone, 2026-11-15; a lone pause, with no seek, held). So in a
paused room, a seek is followed by a pause 1 s later (`Action.SeekTo`'s
`pauseAfter`); pausing a paused app does nothing. The log line before each
action shows the app's state then (`local PAUSED at 33.0s in …; room paused
at 33.0s`).

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
its page ("Opens the movie's page: press Play").

**Netflix on the TV** needs the `source=30` extra: the same link without it
only opened the app. The phone app (`com.netflix.mediaclient`) gets the plain link, which
starts playing.

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

**Apps that don't claim the shared link:** when no installed app claims a
title's link (the phone's Starz app doesn't take the TV link
`starz.com/movies/<id>`), Watch Party looks for an installed app named like
the service ("STARZ" for Starz, or the link's site name). Then it tries the
other forms of the link that app does claim (for Starz,
`www.starz.com/us/en/play/<id>`, which plays). Only if none of them works
does it open the app at its home screen: find the title and press Play, and
sync takes over. So far that's only The Roku Channel.

Any other app's link is passed through unchanged; it works if the app
registers its website's links. If a link only opens the title's page,
everyone presses Play and sync takes over.

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
- `JustWatch.kt` — search and title pages without Yamtrack; where to watch a title, its seasons and
  episodes, and each one's link (unit-tested).
- `YamtrackClient.kt` — read-only Yamtrack API client: library, search, title details, cast,
  recommendations, people (unit-tested).
- `Ui.kt` — the design system: colours, Figtree / JetBrains Mono (`assets/fonts`, OFL),
  focus rings, buttons, cards, fields. Design px ÷ 2 = dp.
- `Screens.kt` — Room, Browse (library grid and search), the title page (details, season /
  episode, cast, More like this) and the person page.
- `ServiceDialog.kt` — "Start for everyone on": the services in a centered dialog.
- `Posters.kt` — poster thumbnails for the list.
- `Apps.kt` — which installed app opens a link.
- `Bridge.kt` — a Kodi member's title found on JustWatch and opened on an app here (unit-tested).
- `AddonLinks.kt` — Kodi streaming addons' `plugin://` paths → the web link of the same title (unit-tested).
- `HboMax.kt` — swaps an HBO Max "(with ASL)" episode link for the regular edition's (unit-tested).
- `SelfSetup.kt`, `AdbClient.kt` — "Set up access": grants notification access through the TV's own adb.
- `SyncService.kt` — foreground service: relay loop, media sessions, launching links.
- `MainActivity.kt` — setup screen, the rail (join/leave, members), screen switching, Back, toasts.
- `SessionAccessService.kt` — empty notification listener (unlocks media sessions).

Tests: `./gradlew test`. To include the relay tests, start a relay
(`python3 watchparty-relay/relay_standalone.py --port 8765`) and run with
`WATCHPARTY_TEST_RELAY=http://127.0.0.1:8765`.
