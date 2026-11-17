# Watch Party — Privacy Policy

_Effective 17 November 2026_

This policy covers the Watch Party Android app (`io.github.breezyslasher.watchparty`),
the Watch Party browser extension, the Watch Party Kodi addon
(`service.watchparty`) and the Watch Party relay, all developed by
Breezyslasher ("we") and published at <https://github.com/Breezyslasher/WatchParty>.

## In short

- **No account.** There's nothing to sign up for.
- **No analytics, no ads, no tracking, no crash reporting.** None of the
  apps contain analytics or advertising code.
- **We run no server and receive none of your data.** Watch Party talks to a
  *relay* — a small server that keeps a room's playback in step — that **you
  choose**: one you run yourself (on a computer, a Raspberry Pi, Docker, or
  inside Kodi), or one a friend runs. Whoever runs that relay is responsible
  for it.
- **We don't sell or share data.** Apart from the relay you join and the
  services listed below, which you use directly, nothing goes anywhere.

## What stays on your device

The Android app keeps these in its own private storage; the browser
extension in the browser's extension storage; the Kodi addon in its
settings:

- the relay address, room code and the device name you chose;
- if you connect it, your Yamtrack server's address and API token;
- your choices (Watch here / Remote only, library sort order);
- on Android, only if you use automatic setup: a key the app creates to
  grant itself access through Android's debugging connection on this same
  device, like the key a computer keeps for `adb`.

The Android app also, on the device only:

- **reads other apps' media playback** (what's playing, playing or paused,
  position, length), with the *notification access* you grant it. Android
  provides this through its media sessions; Watch Party uses it to keep
  your app in step with the room and to start, pause and seek it. It does
  not read your notifications' content.
- **checks which streaming apps are installed**, to pick the app that opens
  a title. This list isn't sent anywhere.
- writes a diagnostic log to Android's system log (readable with `adb
  logcat`), which never leaves the device unless you send it to someone.

Uninstalling the app (or removing the extension or addon) deletes all of
this.

## What goes to the relay you join

While you're in a room, your device sends the relay, about once a second:

- the **room code** and your **device name** (and a random member id the
  relay gives it);
- **what's playing for the room** when someone starts a title: its name,
  the link that opens it, and, when known, its year, show/season/episode
  and TMDB / IMDb ids; from Kodi, the addon path or file name with any
  media-server credentials removed, and the title's **cover art** as an
  image (fetched from your media server on your device, uploaded as
  picture bytes without the server's address or credentials);
- **your playback state**: position, playing or paused, loading, whether
  you're on the room's title, and how many times Watch Party corrected it;
  which app or file it's playing in (the Android app's package name, e.g.
  `com.netflix.ninja`; Kodi's file with credentials removed);
- your play, pause, seek and stop commands.

The relay's operator can also see your device's IP address, as with any
server you connect to. The relay itself does not log IP addresses.

**Who else sees it:** everyone in the same room sees the room's title and
each member's name and playback state. The relay's dashboard (its web
page) shows the rooms on it, their titles, members' names and playback to
anyone who can open the relay's address; room codes are shown masked, since
they work as the room's password.

**How long it's kept:** the relay holds rooms in memory. A member who stops
polling is removed after 15 seconds; an empty room is removed after 5
minutes, and its title goes when the last member leaves. If the relay's
operator turns on saving (the Docker image does by default), each room's
title and position are saved to disk so a party survives a restart;
members and cover art are not saved.

If you host a party from Kodi, the relay runs on that Kodi device and all
of the above stays on it.

## Services you use through Watch Party

These are contacted directly by your device (or, for the relay's remote
page, by the relay on your behalf); their own privacy policies apply:

- **JustWatch** (`apis.justwatch.com`, `images.justwatch.com`): your
  searches, the titles you open, and your country (from your device's
  language settings), to find where a title streams and its details.
- **Yamtrack**, if you connect it: your own server, with your API token,
  to show your library.
- **Streaming services**: Watch Party opens their links in their apps or
  websites. To find the right link it may load public pages: HBO Max's
  (`www.hbomax.com`) to find an episode's id, and Apple TV's
  (`tv.apple.com`) to complete a short link.
- **The browser extension** works only on the streaming sites it lists
  (Netflix, Disney+, HBO Max, Prime Video, Hulu, Peacock, Paramount+,
  Apple TV, YouTube, Crunchyroll, Tubi, Pluto TV, Starz, Plex,
  Discovery+): there it reads and controls the video player (play, pause,
  position) and the page's address, to keep it in step with the room. It
  doesn't read other sites or your browsing history. It asks for access to
  your relay's address only when you enter one.

## Security

Use a relay address starting with `https://` (for example behind a reverse
proxy or tunnel) so what you send is encrypted on the way. The room code
is the only thing that lets someone into a room, so use one that's hard to
guess.

## Children

Watch Party isn't directed at children under 13 and doesn't knowingly
collect their personal information.

## Your choices

- Leave a room at any time; the relay forgets you within seconds.
- To delete what's stored on the device, including your Yamtrack token,
  clear the app's storage in Android's settings (Apps → Watch Party →
  Storage), remove the extension or addon, or uninstall the app.
- For data on a relay, ask whoever runs it; we hold none.

## Changes

If this policy changes, the new version will be published at this address
with a new effective date.

## Contact

Questions or requests: open an issue at
<https://github.com/Breezyslasher/WatchParty/issues>.
