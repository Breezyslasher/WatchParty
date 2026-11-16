#!/bin/sh
# Copies the browser extension's screens into the relay's remote page.
#
# The relay (Kodi-Addons: service.watchparty) serves /remote, a web page that
# runs the room without playing anything itself. Its Browse, title pages,
# link rules and styles are the extension's own files, copied here so both
# stay the same; remote.html and remote.js there are the remote's own.
#
#   tools/vendor-remote.sh ../Kodi-Addons/service.watchparty
#
# Run it after changing any of these in watchparty-browser/extension/.
set -eu
here=$(cd "$(dirname "$0")/.." && pwd)
src="$here/watchparty-browser/extension"
dest="${1:?usage: tools/vendor-remote.sh <path to service.watchparty>}/resources/remote"
mkdir -p "$dest/fonts" "$dest/icons"
for f in links.js justwatch.js hbomax.js relay.js ui.js screens.js room.css; do
  cp "$src/$f" "$dest/$f"
done
cp "$src"/fonts/*.ttf "$dest/fonts/"
cp "$src/icons/icon32.png" "$dest/icons/"
commit=$(git -C "$here" rev-parse --short HEAD 2>/dev/null || echo unknown)
cat > "$dest/VENDORED.txt" <<EOF
Copied from Breezyslasher/WatchParty watchparty-browser/extension/ at $commit
by tools/vendor-remote.sh: links.js justwatch.js hbomax.js relay.js ui.js
screens.js room.css fonts/ icons/icon32.png. Edit them there, then re-run it.
remote.html, remote.js and remote.css are the remote's own.
EOF
echo "copied the extension's screens ($commit) to $dest"
