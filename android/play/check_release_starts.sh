#!/usr/bin/env bash
# Starts the minified release APK on the connected device or emulator, and fails if the app doesn't stay up.
# A debug build doesn't run R8, so only this catches code R8 removed that is reached by reflection at start
# (Android 144 closed at every launch: WorkManager's database constructor was gone).
#
#   play/check_release_starts.sh app/build/outputs/apk/release/app-release-unsigned.apk
#
# The APK is signed with the debug key (~/.android/debug.keystore) or, where there is none (a CI runner), a throwaway
# key made here, never the upload key; with the debug key it is installed over the build there, keeping its data.
set -euo pipefail

apk=${1:?the unsigned release APK}
keystore=${DEBUG_KEYSTORE:-$HOME/.android/debug.keystore}
wait_s=${START_WAIT_S:-10}
tools=$(ls -d "${ANDROID_HOME:?}"/build-tools/* | sort -V | tail -1)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

"$tools/zipalign" -f -p 4 "$apk" "$work/aligned.apk"
pkg=$("$tools/aapt2" dump packagename "$work/aligned.apk" | tr -d '\r')
# No debug key here (a CI runner keeps none): a throwaway one, and the app installed afresh, since its signature differs.
fresh=""
if [ ! -f "$keystore" ]; then
  keystore="$work/throwaway.keystore"
  keytool -genkeypair -keystore "$keystore" -storepass android -keypass android -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 1 -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
  fresh=1
fi
"$tools/apksigner" sign --ks "$keystore" --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias androiddebugkey --out "$work/signed.apk" "$work/aligned.apk"

# Uninstalling deletes the app's data, so it's done only on an emulator or in CI, never on a phone someone attached.
if [ -n "$fresh" ]; then
  emulator=$(adb shell 'getprop ro.kernel.qemu; getprop ro.boot.qemu' | tr -d '\r' | grep -c '^1$' || true)
  if [ "$emulator" = "0" ] && [ "${CI:-}" != "true" ]; then
    echo "::error::no debug key, and the device isn't an emulator: the app would have to be uninstalled, deleting its data. Run a debug build once, or use an emulator."
    exit 1
  fi
  adb uninstall "$pkg" >/dev/null 2>&1 || true
fi
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
