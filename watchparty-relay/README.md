# Watch Party relay

The meeting point of a Watch Party: it keeps each room's playback state
(what's playing, where, paused or not) and its members. Everyone — Kodi, the
Android TV / phone app, the browser extension and the remote page — joins a
room on it with short JSON requests about once a second, and follows the
room. Everyone connects *out* to it, so nobody needs port forwarding, a VPN
or router changes.

Pure Python standard library (`relay.py` + `relay_standalone.py`), no pip
installs. The Kodi addon also hosts a copy inside Kodi (see
[The Kodi addon's copy](#the-kodi-addons-copy)).

## Running it

```
python3 relay_standalone.py                  # open mode on :8765
python3 relay_standalone.py --room MOVIE     # invite-only: fixed room(s)
```

Or with Docker — a prebuilt multi-arch image (amd64 / arm64 / armv7, so a
Raspberry Pi works) is published to GHCR by CI
(`.github/workflows/publish-relay.yml`, on every change to `main`):

```
docker run -d -p 8765:8765 -v watchparty-state:/data --restart unless-stopped ghcr.io/breezyslasher/watchparty-relay:latest
```

Or build it yourself from a clone of this repository:

```
cd watchparty-relay && docker compose up -d
# or, from the repository root (the image includes the browser extension's screens):
docker build -f watchparty-relay/Dockerfile -t watchparty-relay . && docker run -d -p 8765:8765 --restart unless-stopped watchparty-relay
```

**Moving from the old image:** it was `ghcr.io/breezyslasher/kodi-watchparty-relay`,
built from Kodi-Addons (`service.watchparty/Dockerfile`). The relay moved
here, and its image is now `ghcr.io/breezyslasher/watchparty-relay`; switch
the image name (or your compose file) and keep the same `/data` volume —
saved rooms carry over.

Put it behind a reverse proxy or a tunnel (cloudflared, ngrok) for TLS, then
everyone joins with the `https://` address.

- **Open mode** (default): any room code creates a room on first join —
  SyncLounge-style. Rooms empty for 5 minutes are pruned. At most 1000
  rooms at a time (`--max-rooms N` or `WATCHPARTY_MAX_ROOMS`); past that
  new rooms get "relay is full".
- **Fixed mode** (`--room CODE`, repeatable, or `WATCHPARTY_ROOMS=A,B`):
  only the listed rooms exist; everything else is rejected.
- **Persistence**: with `--state-file PATH` (or `WATCHPARTY_STATE`), room
  playback state survives relay restarts — after an update or reboot,
  members auto-rejoin and the party continues where it was. The Docker
  image has this on by default (`/data/state.json`; the compose file
  mounts a volume so it also survives container re-creation). Members
  aren't persisted; their devices rejoin automatically within seconds.
- **The last one out**: when the last member leaves a room (or is pruned
  after 15 s without a poll), its title goes with them, so whoever joins
  next starts with nothing playing. A relay restart keeps it (saved rooms
  load with no members, and members rejoin into them).
- **Version handshake**: the Kodi addon warns ("Relay is older than the
  addon") when it joins a relay that doesn't speak its protocol version,
  so a stale container is a visible message instead of silently missing
  features.

## How much it takes

Measured on a local relay (2026-11-17), every member polling once a second
as the apps do:

- **Data:** each poll sends ~340 bytes and gets the room's state back,
  ~500 bytes plus ~230 per member (the member list). About 6 MB an hour
  per member in a room of 3, 8 MB in a room of 5, 10 MB in a room of 7,
  plus 10–15% for TCP/TLS. Every client keeps its connection open, so
  HTTPS adds little after the first handshake. The video itself never
  goes through the relay.
- **1000 members** in rooms of 3 to 7: every poll answered, no errors,
  1–2 ms per reply, about a quarter of one CPU core; roughly 14–22 Mbit/s
  and 6.5–10 GB an hour. The relay is one Python process, so it uses one
  core: the most it answered here was ~4,200 polls a second.
- **One big room doesn't scale:** the member list goes to every member
  every second, so a room's traffic grows with the square of its size.
  It's meant for watching together, not broadcasting.

## Dashboard

Open the relay's address in a browser (the dashboard is the landing page;
`/status` is the same page) for a live monitoring view — now playing with a
sync timeline (every member plotted against the server anchor, drift called
out past the threshold), member list with buffering / locked / no-poll
states, relay health (poll round-trip, commands per minute, corrective seeks)
and other rooms. It keeps the last good snapshot on screen if the relay goes
away, and reads fine on a phone. Cover art is uploaded to the relay by
whoever started the item and served from there, so it shows even when the
artwork lives on a media server the viewer cannot reach — and it keeps its
own shape, square for music covers, tall for posters.

Room codes are masked by default since they double as the access token —
set `WATCHPARTY_SHOW_CODES=1` to show them in full on a private deployment.
Media-server credentials are stripped from every URL the dashboard and
`/status.json` publish, so a token never reaches the page.

## The remote

`/remote` (or the dashboard's *Open the remote* button; `/remote?room=CODE`
fills in the code) runs a room from any browser with nothing to install —
search movies and shows (or paste a link), start one for everyone, play,
pause, seek ±10/30 s, go to a time, stop for everyone. It plays nothing
itself: a web page can't drive another site's video, which is what the
[browser extension](../watchparty-browser) is for. It joins as a member
(under the name you give it) so it can send commands.

Its files are `remote/remote.html`, `remote.js` and `remote.css`, and the
browser extension's own screens (`links.js`, `justwatch.js`, `hbomax.js`,
`relay.js`, `ui.js`, `screens.js`, `room.css`, fonts, icons), served straight
from `../watchparty-browser/extension/` — so Browse and the title pages are
the extension's, unchanged. The Docker image copies both into `/app/remote/`.

JustWatch's API and HBO Max's public pages don't answer other sites' pages,
so the remote fetches those through the relay (`POST /remote/fetch`):
members only, and only `https://apis.justwatch.com/graphql` (POST) and
`https://www.hbomax.com/…` (GET) — it isn't an open proxy. The relay needs
internet access for search to work (checked from GitHub Actions with
`tools/probe/remote_fetch.py`, 2026-11-16: search results and HBO Max's ASL
page came back; a non-member was turned away).

## The Kodi addon's copy

When a Kodi device hosts a party itself, it runs this same `relay.py`
inside Kodi (Kodi-Addons: `service.watchparty/resources/lib/relay.py`), with
the remote page in `resources/remote/`. Both are copies made by

```
tools/vendor-kodi.sh ../Kodi-Addons/service.watchparty
```

— run it after changing `relay.py`, `remote/`, or the extension files the
remote uses, and commit the result in Kodi-Addons. The relay finds the
remote's files in all three layouts (this folder, the Docker image, the
addon).

## Tests

```
python3 -m unittest discover watchparty-relay/tests
```

The protocol, rooms, buffering holds, control lock, persistence, the
standalone server's modes, the dashboard's redaction, the remote's files and
`/remote/fetch`'s rules. They talk to the relay with the Kodi addon's own
client (`tests/kodi_client.py`, a copy of its `resources/lib/client.py`), so
the relay is tested against what Kodi sends. `.github/workflows/relay-tests.yml`
runs them on every change and checks that the Docker image builds and
serves the remote.
