#!/usr/bin/env bash
# Smoke test on an Android TV emulator (API 30, same Android version as Fire OS 8).
# Installs the build, opens every screen, takes screenshots, and fails if Hearth crashes.
set -u
APK="${1:-HearthTV.apk}"
OUT=shots
PKG=com.vanstaveren.hearthtv
ACT=$PKG/.MainActivity
mkdir -p "$OUT"

shot() { sleep "${2:-3}"; adb exec-out screencap -p > "$OUT/$1.png"; echo "shot $1"; }
key() { adb shell input keyevent "$@"; sleep 0.6; }
crashed() { adb logcat -d -b crash 2>/dev/null | grep -q "Process: $PKG"; }

adb logcat -c || true
adb logcat -b crash -c 2>/dev/null || true
adb install -r "$APK" || exit 1
adb shell pm grant $PKG android.permission.WRITE_SECURE_SETTINGS || true
adb shell settings put system screen_brightness 128 || true

adb shell am start -W -n $ACT
shot home-first 30                       # give it time to fetch weather + a background
key KEYCODE_DPAD_UP                      # into the card controls (focus look, clipping)
shot card-focus 2
key KEYCODE_DPAD_RIGHT
shot card-focus-2 2
key KEYCODE_DPAD_DOWN
shot dock-focus 2
key KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT
shot dock-focus-app 2

adb shell am start -n $ACT --es hearth_shot light
shot home-light 5
adb shell am start -n $ACT --es hearth_open guide --es hearth_shot light
shot guide-light 40
adb shell am start -n $ACT --es hearth_shot dark
adb shell am start -n $ACT --es hearth_open guide --es hearth_shot dark
shot guide-dark 8
key KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN
shot guide-dark-2 2
adb shell am start -n $ACT --es hearth_open apps --es hearth_shot dark
shot apps-dark 5
adb shell am start -n $ACT --es hearth_open apps --es hearth_shot light
shot apps-light 5
adb shell am start -n $ACT --es hearth_open home --es hearth_shot clear
shot home-again 4

adb shell run-as $PKG cat files/hearth-log.txt > "$OUT/hearth-log.txt" 2>/dev/null || \
  adb shell cat /sdcard/Android/data/$PKG/files/hearth-log.txt > "$OUT/hearth-log.txt" 2>/dev/null || true
adb logcat -d -s HearthGuide HearthBg HearthHome HearthDisplay AndroidRuntime > "$OUT/logcat.txt" || true

if crashed || ! adb shell pidof $PKG >/dev/null; then
  echo "::error::Hearth crashed on the emulator"
  adb logcat -d -b crash | tee "$OUT/crash.txt" | tail -60
  exit 1
fi
echo "smoke test passed"
