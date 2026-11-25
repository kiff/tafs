#!/usr/bin/env bash
# Starts the minified release APK on the connected device or emulator, and fails if the app doesn't stay up.
# A debug build doesn't run R8, so only this catches code R8 removed that is reached by reflection at start
# (Android 144 closed at every launch: WorkManager's database constructor was gone).
#
#   play/check_release_starts.sh app/build/outputs/apk/release/app-release-unsigned.apk
#
# The APK is signed with the debug key (~/.android/debug.keystore, made by any debug build), never the upload key,
# and installed over whatever build of the app is there, keeping its data.
set -euo pipefail

apk=${1:?the unsigned release APK}
keystore=${DEBUG_KEYSTORE:-$HOME/.android/debug.keystore}
wait_s=${START_WAIT_S:-10}
tools=$(ls -d "${ANDROID_HOME:?}"/build-tools/* | sort -V | tail -1)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

"$tools/zipalign" -f -p 4 "$apk" "$work/aligned.apk"
"$tools/apksigner" sign --ks "$keystore" --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias androiddebugkey --out "$work/signed.apk" "$work/aligned.apk"
pkg=$("$tools/aapt2" dump packagename "$work/signed.apk" | tr -d '\r')

adb install -r "$work/signed.apk"
adb shell am force-stop "$pkg"
adb logcat -c
adb logcat -b crash -c || true
# By its component: an implicit start also asks for CATEGORY_DEFAULT, which a launcher entry doesn't have.
activity=$(adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER "$pkg" | tail -1 | tr -d '\r')
case "$activity" in "$pkg"/*) ;; *) echo "::error::no launcher activity for $pkg: $activity"; exit 1 ;; esac
adb shell am start -W -n "$activity"
sleep "$wait_s"

pid=$(adb shell pidof "$pkg" | tr -d '\r' || true)
crash=$(adb logcat -d -b crash | grep -A20 "Process: $pkg" || true)
if [ -n "$crash" ] || [ -z "$pid" ]; then
  echo "::error::the release build of $pkg didn't stay up after start"
  [ -n "$crash" ] && echo "$crash"
  adb logcat -d | grep -E "AndroidRuntime|FATAL" | tail -40 || true
  exit 1
fi
echo "$pkg's release build started and is up (pid $pid) after ${wait_s}s"
