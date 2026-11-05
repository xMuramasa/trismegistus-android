#!/usr/bin/env bash
# Runs ON the Mac (scripts/qa.sh copies it there): the headless QA emulator, an S21 FE-sized phone (1080 x 2340 at
# 420 dpi = 411 x 891 dp) on the Android 37 arm64 image Android Studio already installed. No window: nothing shows on
# Martin's screen. Martin's own AVDs are not touched.
#
#   emulator.sh start     create the AVD if missing, boot it headless, wait until Android is up
#   emulator.sh stop      shut it down (adb emu kill)
#   emulator.sh status    running or not, and the adb serial
set -euo pipefail
SDK="${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}"
ADB="$SDK/platform-tools/adb"
NAME=Trismegistus_QA
AVD="$HOME/.android/avd"
LOG="$HOME/Library/Logs/trismegistus-qa"
IMAGE=system-images/android-37.0/google_apis_playstore_ps16k/arm64-v8a/

serial() { "$ADB" devices | awk '/^emulator-[0-9]+\tdevice$/ {print $1}' | while read -r s; do
  [ "$("$ADB" -s "$s" emu avd name 2>/dev/null | head -1 | tr -d '\r')" = "$NAME" ] && echo "$s"; done | head -1; }

create() {
  [ -d "$SDK/$IMAGE" ] || { echo "missing $SDK/$IMAGE (install it from Android Studio's SDK Manager)" >&2; exit 1; }
  mkdir -p "$AVD/$NAME.avd"
  printf 'avd.ini.encoding=UTF-8\npath=%s\npath.rel=avd/%s.avd\ntarget=android-37.0\n' "$AVD/$NAME.avd" "$NAME" > "$AVD/$NAME.ini"
  cat > "$AVD/$NAME.avd/config.ini" <<INI
AvdId=$NAME
avd.ini.displayname=Trismegistus QA (S21 FE size)
avd.ini.encoding=UTF-8
PlayStore.enabled=true
abi.type=arm64-v8a
hw.cpu.arch=arm64
hw.cpu.ncore=4
disk.dataPartition.size=6G
hw.device.manufacturer=Generic
hw.device.name=trismegistus_qa
hw.gpu.enabled=yes
hw.gpu.mode=swiftshader_indirect
hw.keyboard=yes
hw.lcd.density=420
hw.lcd.width=1080
hw.lcd.height=2340
hw.ramSize=3072
hw.camera.back=virtualscene
hw.camera.front=emulated
image.sysdir.1=$IMAGE
tag.display=Google APIs PlayStore
tag.id=google_apis_playstore
target=android-37.0
showDeviceFrame=no
INI
}

case "${1:-status}" in
start)
  s=$(serial)
  if [ -z "$s" ]; then
    [ -f "$AVD/$NAME.avd/config.ini" ] || create
    mkdir -p "$LOG"
    # Detached so it outlives this SSH session; -no-snapshot-save keeps every boot from the same clean-ish state.
    nohup "$SDK/emulator/emulator" -avd "$NAME" -no-window -no-audio -no-boot-anim -no-snapshot-save \
      -gpu swiftshader_indirect > "$LOG/emulator.log" 2>&1 < /dev/null &
    for _ in $(seq 1 60); do sleep 2; s=$(serial); [ -n "$s" ] && break; done
    [ -n "$s" ] || { echo "emulator did not appear in adb; see $LOG/emulator.log" >&2; tail -5 "$LOG/emulator.log" >&2; exit 1; }
  fi
  for i in $(seq 1 90); do
    [ "$("$ADB" -s "$s" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && break; sleep 2
  done
  [ "$("$ADB" -s "$s" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] || { echo "boot timed out" >&2; exit 1; }
  # Steady screenshots: no animations, no screen timeout.
  for k in window_animation_scale transition_animation_scale animator_duration_scale; do "$ADB" -s "$s" shell settings put global $k 0; done
  "$ADB" -s "$s" shell settings put system screen_off_timeout 1800000
  "$ADB" -s "$s" shell input keyevent KEYCODE_WAKEUP
  echo "$s"
  ;;
stop)
  s=$(serial); [ -n "$s" ] && "$ADB" -s "$s" emu kill >/dev/null 2>&1 || true
  echo stopped
  ;;
status)
  s=$(serial); [ -n "$s" ] && echo "running $s" || echo "not running"
  ;;
*) echo "usage: $0 start|stop|status" >&2; exit 2 ;;
esac
