#!/usr/bin/env bash
# Builds the signed release APK and publishes it for the phone at https://dl.lab.x-muramasa.dev/trismegistus.apk
# (Caddy serves ~/.local/share/homelab-downloads on the LAN/tailnet), plus a copy named by version.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
out=${TRISMEGISTUS_DOWNLOADS:-$HOME/.local/share/homelab-downloads}
"$root/scripts/build.sh" assembleRelease > /dev/null
apk="$root/app/build/outputs/apk/release/app-release.apk"
[ -f "$apk" ] || { echo "no signed release APK (is ~/.local/share/trismegistus-android/signing in place?)" >&2; exit 1; }
version=$(docker run --rm -v "$apk:/app.apk:ro" trismegistus-android-build:1 \
  /opt/android-sdk/build-tools/36.0.0/aapt2 dump badging /app.apk | sed -n "s/.*versionName='\([^']*\)'.*/\1/p")
mkdir -p "$out"
cp "$apk" "$out/trismegistus-$version.apk"
cp "$apk" "$out/trismegistus.apk.tmp" && mv "$out/trismegistus.apk.tmp" "$out/trismegistus.apk"
echo "published $version: https://dl.lab.x-muramasa.dev/trismegistus.apk ($(stat -c %s "$apk") bytes)"
