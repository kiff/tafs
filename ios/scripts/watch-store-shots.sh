#!/bin/bash
# The Apple Watch screenshots for the App Store listing (docs/screenshots.md, "Apple Watch"), on a Mac with Xcode:
#   ios/scripts/watch-store-shots.sh [output folder]
# It makes a watch simulator of an App Store size (the largest the newest watchOS runtime has: an Ultra, else a 46 or
# 45 mm), runs WatchChecksUITests/testStoreScreenshots on it against the demo phone (`-watch-demo`, Debug only), and
# writes each screen as watch-N-name.png into a new or empty folder (one holding files is refused, never cleared), then prints each one's size. Nothing is uploaded.
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=${1:-$PWD/build/watch-store-shots}
RESULT=$(mktemp -d)/shots.xcresult
if [ -e "$OUT" ] && [ -n "$(ls -A "$OUT" 2>/dev/null)" ]; then
  echo "::error::$OUT isn't empty: give a new or empty folder" >&2
  exit 2
fi
[ -d Tafs.xcodeproj ] || xcodegen generate

read -r runtime type name < <(python3 -c '
import json, subprocess
def listed(kind):
    return json.loads(subprocess.check_output(["xcrun", "simctl", "list", kind, "-j"]))[kind]
runtimes = [r for r in listed("runtimes") if r.get("platform") == "watchOS" and r.get("isAvailable")]
if not runtimes:
    raise SystemExit("no watchOS runtime")
newest = max(runtimes, key=lambda r: [int(p) for p in r["version"].split(".")])
# The App Store sizes, largest first: Ultra (422x514, 410x502), 46 mm (416x496), 45 mm (396x484), 44 mm (368x448).
order = ["Ultra 3", "Ultra 2", "Ultra", "(46mm)", "(45mm)", "(44mm)"]
types = newest.get("supportedDeviceTypes", [])
for want in order:
    for t in types:
        if want in t["name"]:
            print(newest["identifier"], t["identifier"], t["name"].replace(" ", "_"))
            raise SystemExit
raise SystemExit("no watch of an App Store size in " + newest["name"])
')
echo "watch: ${name//_/ } on $runtime"
WATCH_ID=$(xcrun simctl create tafs-watch-store-shots "$type" "$runtime")
trap 'xcrun simctl delete "$WATCH_ID" >/dev/null 2>&1 || true' EXIT
xcrun simctl boot "$WATCH_ID"
xcrun simctl bootstatus "$WATCH_ID" -b >/dev/null

TEST_RUNNER_TAFS_STORE_SHOTS=1 xcodebuild test -project Tafs.xcodeproj -scheme TafsWatch -destination "id=$WATCH_ID" \
  -only-testing:TafsWatchUITests/WatchChecksUITests/testStoreScreenshots -resultBundlePath "$RESULT" \
  CODE_SIGNING_ALLOWED=NO -quiet

# Never removes anything it's given: a folder that already holds files is refused.
if [ -e "$OUT" ] && [ -n "$(ls -A "$OUT" 2>/dev/null)" ]; then
  echo "::error::$OUT isn't empty: give a new or empty folder" >&2
  exit 2
fi
mkdir -p "$OUT"
EXPORT=$(mktemp -d)
xcrun xcresulttool export attachments --path "$RESULT" --output-path "$EXPORT"
python3 - "$EXPORT" "$OUT" <<'PY'
import json, os, shutil, sys
export, out = sys.argv[1], sys.argv[2]
kept = 0
for test in json.load(open(os.path.join(export, "manifest.json"))):
    for a in test.get("attachments", []):
        name = a.get("suggestedHumanReadableName", "")
        if name.startswith("watch-"):
            shutil.copy(os.path.join(export, a["exportedFileName"]), os.path.join(out, name.split("_")[0] + ".png"))
            kept += 1
if kept != 5:
    raise SystemExit(f"{kept} screenshots kept, 5 expected")
PY
ok="422x514 410x502 416x496 396x484 368x448"
for f in "$OUT"/*.png; do
  size=$(sips -g pixelWidth -g pixelHeight "$f" | awk '/pixelWidth/{w=$2} /pixelHeight/{h=$2} END{print w "x" h}')
  case " $ok " in *" $size "*) echo "$(basename "$f"): $size" ;; *) echo "::warning::$(basename "$f"): $size isn't an App Store watch size" ;; esac
done
echo "in $OUT"
