#!/usr/bin/env bash
# The owner's override (upgrade-override.sh) on saved runs: Eval 44204192951,
# red only in "Plan words, Google Sheets in English", with a run of the
# plan-words suite that passed it, holds; each way of breaking it is refused.
set -euo pipefail
cd "$(dirname "$0")"
script=../upgrade-override.sh
base=eval-44204192951.steps baselog=eval-44204192951.log check=check-plan-words.steps log=check-plan-words.log
tmp=$(mktemp -d)
fails=0

holds() {
  local got
  if ! got=$(bash "$script" "$@" 2>&1); then echo "FAIL: refused: $got"; fails=$((fails + 1)); return; fi
  [ "$got" = "Plan words, Google Sheets in English" ] || { echo "FAIL: held for $got"; fails=$((fails + 1)); }
}
refused() {
  local why=$1; shift
  local got
  if got=$(bash "$script" "$@" 2>&1); then echo "FAIL: held ($why)"; fails=$((fails + 1)); return; fi
  grep -qF "$why" <<<"$got" || { echo "FAIL: refused for another reason ($why): $got"; fails=$((fails + 1)); }
}
edit() { sed -E "$1" "$2" > "$tmp/$3"; echo "$tmp/$3"; }

holds "$base" "$baselog" "$log" "$check"
# The base: a full run, red in exactly one step.
refused "isn't a full run" "$(edit 's/^27 skipped /27 failure /' "$base" b1)" "$baselog" "$log" "$check"
refused "exactly one step" "$(edit 's/^18 success /18 failure /' "$base" b2)" "$baselog" "$log" "$check"
refused "exactly one step" "$(edit 's/^17 failure /17 success /' "$base" b3)" "$baselog" "$log" "$check"
refused "outside its steps" "$(edit 's/^55 success /55 failure /' "$base" b4)" "$baselog" "$log" "$check"
refused "is mixed" "$base" "$(edit 's/deployed: unchanged/deployed: changed (tafs-cloud)/' "$baselog" bl1)" "$log" "$check"
# The check: that step passed, at repeat 3, on the shipped model, nothing else red.
refused "didn't pass" "$base" "$baselog" "$log" "$(edit 's/^17 success /17 failure /' "$check" c1)"
refused "didn't pass" "$base" "$baselog" "$log" "$(edit 's/^17 success /17 skipped /' "$check" c2)"
refused "failed elsewhere" "$base" "$baselog" "$log" "$(edit 's/^18 success /18 failure /' "$check" c3)"
refused "overrode the model" "$base" "$baselog" "$log" "$(edit 's/^25 skipped /25 success /' "$check" c4)"
refused "below the bar's repeat" "$base" "$baselog" "$(edit 's/-repeat "3"/-repeat "1"/' "$log" l1)" "$check"
refused "shows no suite run" "$base" "$baselog" "$(edit '/##\[group\]Run go run/d' "$log" l2)" "$check"
# A check run whose carry held is as good.
holds "$base" "$baselog" "$log" "$(edit 's/^27 failure /27 success /' "$check" c5)"

[ "$fails" = 0 ] && echo "the owner's override: every case as expected" || exit 1
