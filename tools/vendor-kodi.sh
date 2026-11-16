#!/bin/sh
# Copies the relay into the Kodi addon, for the relay it hosts inside Kodi.
#
# The relay lives here (watchparty-relay/). The Kodi addon (Kodi-Addons:
# service.watchparty) runs the same relay.py as resources/lib/relay.py when a
# Kodi device hosts a party, and serves the same remote page (/remote) from
# resources/remote/: the remote's own files plus the browser extension's
# screens it uses.
#
#   tools/vendor-kodi.sh ../Kodi-Addons/service.watchparty
#
# Run it after changing watchparty-relay/relay.py, watchparty-relay/remote/,
# or the extension's links.js, justwatch.js, hbomax.js, relay.js, ui.js,
# screens.js or room.css.
set -eu
here=$(cd "$(dirname "$0")/.." && pwd)
addon="${1:?usage: tools/vendor-kodi.sh <path to service.watchparty>}"
ext="$here/watchparty-browser/extension"
remote="$addon/resources/remote"
[ -f "$addon/addon.xml" ] || { echo "$addon isn't the Kodi addon (no addon.xml)" >&2; exit 1; }
cp "$here/watchparty-relay/relay.py" "$addon/resources/lib/relay.py"
rm -rf "$remote"
mkdir -p "$remote/fonts" "$remote/icons"
cp "$here"/watchparty-relay/remote/remote.* "$remote/"
for f in links.js justwatch.js hbomax.js relay.js ui.js screens.js room.css; do
  cp "$ext/$f" "$remote/$f"
done
cp "$ext"/fonts/*.ttf "$remote/fonts/"
cp "$ext/icons/icon32.png" "$ext/icons/icon128.png" "$remote/icons/"
commit=$(git -C "$here" rev-parse --short HEAD 2>/dev/null || echo unknown)
cat > "$remote/VENDORED.txt" <<EOF
Copied from Breezyslasher/WatchParty at $commit by tools/vendor-kodi.sh,
with resources/lib/relay.py (watchparty-relay/relay.py): remote.html,
remote.js and remote.css (watchparty-relay/remote/), and the browser
extension's links.js justwatch.js hbomax.js relay.js ui.js screens.js
room.css fonts/ icons/ (watchparty-browser/extension/). Edit them there,
then re-run it.
EOF
echo "copied the relay and its remote page ($commit) to $addon"
