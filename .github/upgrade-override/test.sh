#!/usr/bin/env bash
# The owner's override (upgrade-override.sh) on saved runs: Eval 44204192951,
# red only in "Plan words, Google Sheets in English"; a run of the plan-words
# suite at repeat 3 that passed it; and a run of the suites the core's change
# touches (connect, route, setup) at repeat 1, the owner's choice. It holds;
# each way of breaking it is refused.
set -euo pipefail
cd "$(dirname "$0")"
script=../upgrade-override.sh
tmp=$(mktemp -d)
fails=0
# The defaults; a case changes one.
runs=runs base=eval-44204192951.steps baselog=eval-44204192951.log
check=check-plan-words.steps log=check-plan-words.log
touched=touched-connect-route-setup.steps tlog=touched-connect-route-setup.log suites=suites.json
printf 'runtime/src/cloud/setupdef.ts\n' > "$tmp/core"; chg=$tmp/core
: > "$tmp/empty"

run() { bash "$script" "$runs" "$base" "$baselog" "$log" "$check" "$tlog" "$touched" "$suites" "$chg" 2>&1; }
holds() {
  local got
  if ! got=$(run); then echo "FAIL: refused ($1): $got"; fails=$((fails + 1)); return; fi
  [ "$(head -1 <<<"$got")" = "Plan words, Google Sheets in English" ] || { echo "FAIL: held for $got"; fails=$((fails + 1)); }
  [ -z "${2:-}" ] || grep -qF "$2" <<<"$got" || { echo "FAIL: the record ($1) lacks \"$2\": $got"; fails=$((fails + 1)); }
}
refused() {
  local got
  if got=$(run); then echo "FAIL: held ($1)"; fails=$((fails + 1)); return; fi
  grep -qF "$1" <<<"$got" || { echo "FAIL: refused for another reason ($1): $got"; fails=$((fails + 1)); }
}
edit() { sed -E "$1" "$2" > "$tmp/$3"; echo "$tmp/$3"; }
# Each case sets one thing, then the defaults come back.
case_() { local saved_runs=$runs saved_base=$base saved_baselog=$baselog saved_check=$check saved_log=$log saved_touched=$touched saved_tlog=$tlog saved_chg=$chg
  eval "$1"; shift; "$@"
  runs=$saved_runs base=$saved_base baselog=$saved_baselog check=$saved_check log=$saved_log touched=$saved_touched tlog=$saved_tlog chg=$saved_chg; }

# Plan-words at repeat 3, connect, route and setup at repeat 1: it holds, and the record says so.
holds "the owner's choice" "touched suites connect route setup at repeat 1 (Eval 101), the owner's choice: touched suites at repeat 1"
# Nothing else touched: the check alone holds.
case_ "chg=$tmp/empty runs=\$(edit '/^touched /d' runs r00) touched=$tmp/empty tlog=$tmp/empty" holds "no other suite" "no other suite touched"

# The runs: both newer than the base, both at the commit shipped.
case_ "runs=\$(edit 's/^check 100 [^ ]+/check 100 2026-11-17T23:00:00Z/' runs r1)" refused "the check run isn't newer than the base"
case_ "runs=\$(edit 's/^touched 101 ([^ ]+) eb05ab7/touched 101 \1 1234567/' runs r2)" refused "the touched run isn't at the agents commit"
case_ "runs=\$(edit 's/^check 100 ([^ ]+) eb05ab7/check 100 \1 1234567/' runs r3)" refused "the check run isn't at the agents commit"
# The base: a full run, not mixed, red in exactly one step.
case_ "base=\$(edit 's/^27 skipped /27 failure /' eval-44204192951.steps b1)" refused "isn't a full run"
case_ "base=\$(edit 's/^18 success /18 failure /' eval-44204192951.steps b2)" refused "exactly one step"
case_ "base=\$(edit 's/^17 failure /17 success /' eval-44204192951.steps b3)" refused "exactly one step"
case_ "base=\$(edit 's/^55 success /55 failure /' eval-44204192951.steps b4)" refused "outside its steps"
case_ "baselog=\$(edit 's/deployed: unchanged/deployed: changed (tafs-cloud)/' eval-44204192951.log bl)" refused "is mixed"
# The check: the failed step's whole suite, at repeat 3, on the shipped model.
case_ "log=\$(edit 's/-repeat \"3\"/-repeat \"1\"/' check-plan-words.log l1)" refused "below the bar's repeat of 3"
case_ "check=\$(edit 's/^17 success /17 failure /' check-plan-words.steps c1)" refused "the check run failed"
case_ "check=\$(edit 's/^18 success /18 skipped /' check-plan-words.steps c2)" refused "(suite plan-words) didn't pass"
case_ "check=\$(edit 's/^25 skipped /25 success /' check-plan-words.steps c3)" refused "the check run overrode the model"
case_ "log=\$(edit '/##\[group\]Run go run/d' check-plan-words.log l2)" refused "shows no suite run"
# The touched suites: every step passed in one run or the other.
case_ "touched=\$(edit 's/^20 success /20 skipped /' touched-connect-route-setup.steps t1)" refused "touch suite route"
case_ "touched=\$(edit 's/^14 success /14 failure /' touched-connect-route-setup.steps t2)" refused "the touched run failed"
case_ "runs=\$(edit '/^touched /d' runs r0) touched=$tmp/empty tlog=$tmp/empty" refused "touch suite connect"
# A touched run named with no steps: the touched runs and their files must line up, one each, in order.
case_ "touched=$tmp/empty tlog=$tmp/empty" refused "the touched runs and their steps don't line up"
case_ "touched=\$(edit 's/^25 skipped /25 success /' touched-connect-route-setup.steps t3)" refused "the touched run overrode the model"
# A path the map doesn't name touches every suite, cards included.
case_ "chg=\$(printf 'runtime/src/cloud/tafdef.ts\n' > $tmp/all; echo $tmp/all)" refused "touch suite cards"
# A carry that held is as good.
case_ "check=\$(edit 's/^27 failure /27 success /' check-plan-words.steps c4)" holds "carry held"

# Two touched runs (the second a case the first couldn't grade, run again alone): each touched suite passed every step
# in one of them, and a step red in the first counts only because the second passed it.
two="runs=runs-two-touched touched=touched-setup-unsigned.steps,touched-setup-alone.steps tlog=touched-setup-unsigned.log,touched-setup-alone.log"
case_ "$two" holds "two touched runs" "touched suites connect route setup at repeat 1 (Eval 101,102), the owner's choice"
# The first alone: its red step passed nowhere else.
case_ "runs=\$(edit '/^touched 102 /d' runs-two-touched r1) touched=touched-setup-unsigned.steps tlog=touched-setup-unsigned.log" refused "the touched run failed: Setup with the taf, in English, graded"
# The second red in the same step: nobody passed it.
case_ "$two touched=touched-setup-unsigned.steps,\$(edit 's/^13 success /13 failure /' touched-setup-alone.steps t4) tlog=touched-setup-unsigned.log,touched-setup-alone.log" refused "the touched run failed: Setup with the taf, in English, graded"
# The second run newer than the base and at the commit shipped, like the first.
case_ "$two runs=\$(edit 's/^touched 102 ([^ ]+) eb05ab7/touched 102 \1 1234567/' runs-two-touched r4)" refused "the touched run isn't at the agents commit"
case_ "$two runs=\$(edit 's/^touched 102 [^ ]+/touched 102 2026-11-17T23:00:00Z/' runs-two-touched r5)" refused "the touched run isn't newer than the base"
# The first of two touched runs with no steps: refused, not read as the second.
case_ "$two touched=$tmp/empty,touched-setup-alone.steps tlog=$tmp/empty,touched-setup-alone.log" refused "the touched runs and their steps don't line up"
# Two touched runs and three files, the first empty: the files given must be the touched runs', one each.
case_ "$two touched=$tmp/empty,touched-setup-unsigned.steps,touched-setup-alone.steps tlog=$tmp/empty,touched-setup-unsigned.log,touched-setup-alone.log" refused "the touched runs and their steps don't line up"
# A failed job in the second is never excused.
case_ "$two touched=touched-setup-unsigned.steps,\$(edit 's/^55 success /55 failure /' touched-setup-alone.steps t5) tlog=touched-setup-unsigned.log,touched-setup-alone.log" refused "failed outside its steps"
# The second on another model is refused.
case_ "$two touched=touched-setup-unsigned.steps,\$(edit 's/^25 skipped /25 success /' touched-setup-alone.steps t6) tlog=touched-setup-unsigned.log,touched-setup-alone.log" refused "overrode the model"

[ "$fails" = 0 ] && echo "the owner's override: every case as expected" || exit 1
