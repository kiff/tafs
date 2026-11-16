#!/usr/bin/env bash
# The watch finds the phone by the capability the phone app declares (res/values/wear.xml, WatchLink.PHONE_CAPABILITY).
# Google Play services reads it from the installed app's resources and no code does, so a release build's shrinker drops
# it unless res/raw/keep.xml keeps it, and the watch then reads the phone as out of range (111, 16 Nov). This fails a
# release build, an .apk or an .aab, that doesn't carry it.
set -euo pipefail
built=${1:?an .apk or .aab}
case $built in
  *.aab) resources=$(unzip -p "$built" base/resources.pb | LC_ALL=C tr -c '[:print:]' '\n') ;;
  *.apk)
    aapt2=$(ls "$ANDROID_HOME"/build-tools/*/aapt2 | sort -V | tail -1)
    resources=$("$aapt2" dump resources "$built") ;;
  *) echo "check_watch_link: $built is neither an .apk nor an .aab" >&2; exit 2 ;;
esac
for needed in android_wear_capabilities tafs_phone_relay; do
  if ! grep -q "$needed" <<<"$resources"; then
    echo "::error::$built has no $needed: the watch couldn't find this phone (keep it in app/src/main/res/raw/keep.xml)"
    exit 1
  fi
done
echo "$built carries the watch's link (android_wear_capabilities: tafs_phone_relay)"
