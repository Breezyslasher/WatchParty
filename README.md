# Watch Party

Watch something together, each on your own TV: everyone's TV starts the same
title in the same streaming app (Disney+, Netflix, HBO Max, …) and stays in
sync — pause, play and seek on one TV and the others follow.

## What's here

| Folder | What it is |
|---|---|
| [`watchparty-tv/`](watchparty-tv) | **Watch Party for Android TV** (also runs on phones) — the app. Joins a room, opens titles in their apps, keeps them in sync. Start here. |
| [`tools/session-probe/`](tools/session-probe) | Test app that showed a TV app can read and control another app's playback (Disney+ on a Sony Android TV 9). Also has adb commands to pause/seek from a computer. |
| [`tools/probe/`](tools/probe) | `collect.py`: plays through many streaming apps and reports which ones expose what's playing. |
| [`tools/justwatch/`](tools/justwatch) | `jw.py`: looks up a title's links on JustWatch, per service and platform. Runs from the *JustWatch link lookup* GitHub Actions workflow. |

The **relay** (the small server rooms run on) and the **Kodi addon** live in
[Breezyslasher/kodi-addons](https://github.com/Breezyslasher/kodi-addons),
under `service.watchparty` — Kodi and Android TV members can share a room.

## Quick start

1. Run a relay on a computer or server:
   ```
   python3 relay_standalone.py --port 8765
   ```
   (from `service.watchparty` in the kodi-addons repo; it also has a
   Dockerfile.)
2. Install Watch Party on each TV — a release APK, or build `watchparty-tv`
   with Android Studio.
3. Open it, press **Set up access** once (see
   [watchparty-tv/README.md](watchparty-tv/README.md#grant-access-to-whats-playing-once)),
   then enter the relay address and a room code and press **Join room**.
4. Press **Browse Yamtrack** to pick a movie or episode from your Yamtrack
   library (or search), or paste a title's link in **Start for everyone**.

## Status

Watch Party TV 0.7.1 is tested on a Sony Android TV 9 and a Chromecast HD
(Google TV, Android 14). See [watchparty-tv/README.md](watchparty-tv/README.md)
for which apps' links start playback and what's still being tested.
