#!/usr/bin/env bash
#
# Fetch the lxserver Web front-end (public/) and bundle it into the Android
# app assets so the "built-in frontend" mode works offline-bundled.
#
# Usage:  bash scripts/fetch-frontend.sh [lxserver_ref]
#   lxserver_ref   branch / tag / sha of zues0077/lxserver (default: main)
#
# The downloaded tree is placed in app/src/main/assets/webplayer/ (gitignored)
# and trimmed of optional spatial-audio filter assets to keep the APK smaller.
set -euo pipefail

REPO="${LXSERVER_REPO:-zues0077/lxserver}"
REF="${1:-main}"
DEST="app/src/main/assets/webplayer"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "==> Fetching public/ from $REPO@$REF"
curl -sL "https://github.com/$REPO/archive/refs/heads/$REF.tar.gz" -o "$TMP/repo.tar.gz"

echo "==> Extracting"
mkdir -p "$TMP/ext"
tar -xzf "$TMP/repo.tar.gz" -C "$TMP/ext"

SRC="$(find "$TMP/ext" -maxdepth 2 -type d -name public | head -n1)"
if [ -z "$SRC" ]; then
  echo "!! Could not find public/ in the archive (ref=$REF)" >&2
  exit 1
fi

echo "==> Copying front-end into $DEST"
rm -rf "$DEST"
mkdir -p "$DEST"
cp -r "$SRC/." "$DEST/"

# Trim optional spatial-audio filter assets (not required for playback).
find "$DEST" -path '*/assets/medias/filters/*.wav' -delete 2>/dev/null || true
# Drop the browser-extension helper (not used inside the WebView).
rm -rf "$DEST/ext" 2>/dev/null || true

echo "==> Done. Bundled front-end size:"
du -sh "$DEST"
