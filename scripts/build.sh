#!/usr/bin/env bash
# Runs Gradle in the build container: scripts/build.sh [gradle tasks...]
# Default: unit tests, lint and both APKs. Gradle's caches and the signing material live outside the repo, in
# ~/.local/share/trismegistus-android/{gradle,android,signing}, so builds stay incremental and the signing key stays the same
# (Android refuses an update signed with another key).
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
state=${TRISMEGISTUS_ANDROID_STATE:-$HOME/.local/share/trismegistus-android}
image=trismegistus-android-build:1
mkdir -p "$state/gradle" "$state/android" "$state/signing"
if ! docker image inspect "$image" > /dev/null 2>&1; then
  docker build -q -t "$image" "$root/docker" > /dev/null
fi
tasks=("$@")
[ ${#tasks[@]} -gt 0 ] || tasks=(test lintDebug assembleDebug assembleRelease)
tty=(); [ -t 1 ] && tty=(-t)
# Hard memory cap, no swap: a build that outgrows it is OOM-killed inside its container instead of pushing the host
# into swap until it freezes (an uncapped build did that on 2026-11-04).
mem=${TRISMEGISTUS_ANDROID_BUILD_MEMORY:-3g}
exec docker run --rm "${tty[@]}" --memory "$mem" --memory-swap "$mem" --user "$(id -u):$(id -g)" \
  -e HOME=/tmp/home -e GRADLE_USER_HOME=/gradle -e ANDROID_USER_HOME=/android \
  -v "$state/gradle:/gradle" -v "$state/android:/android" -v "$state/signing:/signing:ro" \
  -v "$root:/src" -w /src "$image" ./gradlew --no-daemon --console=plain "${tasks[@]}"
