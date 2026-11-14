# Which apps expose what they're playing?

`collect.py` checks many streaming apps in one go: you play titles, it
captures everything Android exposes about each one and reports whether the
title's id shows up anywhere. That decides how Watch Party can share it:

1. **the app's own id** (e.g. Disney+ logs it) — read it directly;
2. **JustWatch** — look the title up (fallback);
3. **title only** — everyone picks it themselves.

## Use

On a computer with Python 3 and adb connected to the TV (Android 11+:
Wireless debugging → `adb pair`, then `adb connect`):

```
python3 collect.py watch
```

Then on the TV play a title in each app you want to check — Disney+,
Netflix, HBO Max, … — leaving each playing ~15 s. Every new title is
captured; press Ctrl+C for a summary like:

```
app                                  title                    id exposed in
com.disney.disneyplus                Thunderbolts*            app_log
com.netflix.ninja                    …                        nowhere
```

`python3 collect.py once` captures just what's playing now.

## What it collects

For the playing app only: its media session, **its own** log lines (by
process id), the screen text, its launch intents, its notifications, and
"Continue watching" rows. It asks JustWatch for the title's ids in every
service (only the title is sent) and searches the capture for them.

Reports go to `probe-reports/<time>_<package>/` — `report.json` plus the raw
captures. Tokens, signatures, keys and email addresses are redacted, so a
report folder is safe to share.

If the summary says **no title**, the app publishes none (e.g. Apple TV) and
there's nothing to look up. If it can't see what's playing on your TV,
install Session Probe and grant it access (see `../../session-probe`) — the
collector then asks it instead.
