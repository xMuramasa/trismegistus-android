#!/usr/bin/env bash
# App QA on Martin's Mac, run from optiplex: the headless S21 FE-sized emulator (qa/mac/emulator.sh) gets the release
# APK, then Maestro runs the flows in qa/flows and the screenshots and JUnit report come back here.
#
#   scripts/qa.sh                  install the APK from app/build, run every flow in qa/flows/*.yaml
#   scripts/qa.sh 01-browse        only that flow
#   scripts/qa.sh setup            once per relay: the "QA" conversation the flows write into
#   scripts/qa.sh stop             shut the emulator down (it otherwise keeps running for the next run)
#
# The emulator is paired with the relay as device "emulator" (manager), once, by hand. Results:
# ~/.local/share/trismegistus-android-qa/runs/<stamp>/ (screenshots *.png, report.xml, maestro.log, output/ with
# Maestro's own debug files).
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
host="${QA_HOST:-mac}"
remote='Library/Caches/trismegistus-android-qa'
apk="$root/app/build/outputs/apk/release/app-release.apk"
stamp="$(date +%Y%m%d-%H%M%S)"
out="$HOME/.local/share/trismegistus-android-qa/runs/$stamp"

ssh_() { ssh -o ConnectTimeout=15 "$host" "$@"; }
ssh_ true 2>/dev/null || { echo "can't reach $host over SSH (asleep?)" >&2; exit 1; }
ssh_ "mkdir -p $remote"
rsync -a --delete "$root/qa/" "$host:$remote/qa/"

if [ "${1:-}" = stop ]; then ssh_ "bash $remote/qa/mac/emulator.sh stop"; exit 0; fi

flows=()
if [ "${1:-}" = setup ]; then
  flows=(qa/flows/setup/qa-conversation.yaml)
elif [ $# -gt 0 ]; then
  for f in "$@"; do flows+=("qa/flows/$f.yaml"); done
else
  for f in "$root"/qa/flows/*.yaml; do flows+=("qa/flows/$(basename "$f")"); done
fi

[ -f "$apk" ] || { echo "no $apk: run scripts/build.sh assembleRelease first" >&2; exit 1; }
scp -q "$apk" "$host:$remote/trismegistus.apk"
serial=$(ssh_ "bash $remote/qa/mac/emulator.sh start" | tail -1)
echo "emulator $serial"

set +e
ssh_ bash -s -- "$remote" "$serial" "$stamp" "${flows[@]}" <<'EOF'
remote=$1 serial=$2 stamp=$3; shift 3
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk" PATH="$HOME/Library/Android/sdk/platform-tools:$HOME/.maestro/bin:$PATH"
cd "$HOME/$remote"
adb -s "$serial" install -r trismegistus.apk < /dev/null | tail -1
run="runs/$stamp"; mkdir -p "$run"; cd "$run"
args=(); for f in "$@"; do args+=("$HOME/$remote/$f"); done
maestro --device "$serial" test -e RUN="$stamp" --format junit --output report.xml --test-output-dir output "${args[@]}" \
  < /dev/null > maestro.log 2>&1
code=$?
# Screenshots land in output/<flow>/takeScreenshot/: copy them up, named after the step.
find output -name '*.png' -path '*takeScreenshot*' -exec cp {} . \;
tail -25 maestro.log
exit $code
EOF
code=$?
set -e
mkdir -p "$out"
rsync -a "$host:$remote/runs/$stamp/" "$out/"
echo "results: $out ($(ls "$out"/*.png 2>/dev/null | wc -l) screenshots)"
exit $code
