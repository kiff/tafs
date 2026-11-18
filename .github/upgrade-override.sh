#!/usr/bin/env bash
# upgrade-override.sh RUNS BASE_STEPS BASE_LOG CHECK_LOG CHECK_STEPS TOUCHED_LOG TOUCHED_STEPS SUITES CHANGED
#
# The owner's override for the Upgrade (upgrade.yml, input owner_override): a
# named owner decision on the thread lets a full Eval that failed in one step
# count, once later runs passed that step and what changed since. It holds
# only when:
#   - the later runs (the check, and the touched run if any) are newer than the
#     base and at the agents commit the Upgrade reads its definitions from:
#     RUNS has one "role id created_at agents_sha" line each for base, check,
#     touched (when there's one) and shipped (that commit, id and time "-");
#   - the base run was a full run of every suite (no override, no run of some
#     suites), not mixed (its "deployed: unchanged" line: tafs cloud, planque
#     and agents the same at its start and end), and exactly one of its steps
#     failed;
#   - the check run ran that step's whole suite at the bar's repeat of 3, on
#     the shipped model, and every one of its steps passed;
#   - every other suite the definitions changed between the base and the check
#     touch passed every step, in the check or in the touched run (TOUCHED_*,
#     empty files when there's none): a run on the shipped model at any
#     repeat, as the owner chose (its repeat goes on the record). CHANGED lists
#     those paths in kiff/tafs, mapped as kiff/agents maps them (SUITES, its
#     eval/suites.json at the check run's commit: a path's first matching
#     prefix, and one that matches none touches every suite);
#   - in each later run, every other step passed or was skipped, but for its
#     own "A run of some suites counts only on earlier passes", which a run of
#     some suites ends red when nothing carries or below the bar's repeat, and
#     for a step another later run passed (a case one run couldn't grade, run
#     again alone).
#
# There may be more than one touched run: TOUCHED_LOG and TOUCHED_STEPS then
# list their files, comma-separated, in the order of RUNS' "touched" lines.
#
# STEPS files are the jobs' steps from the API, one "number conclusion name"
# per line; the LOGs are the jobs' logs as GitHub serves them. Exits 0 when
# the override holds, printing the failed step's name, then the record's line
# on the touched suites; 1 with the reason.
set -euo pipefail
runs=$1 base=$2 baselog=$3 log=$4 check=$5 tlogs=$6 tsteps=$7 suites=$8 changed=$9
# The touched runs given: those whose steps file isn't empty, in order.
tlog_a=() tstep_a=()
IFS=, read -ra _l <<<"$tlogs"; IFS=, read -ra _s <<<"$tsteps"
for i in "${!_s[@]}"; do
  [ -s "${_s[$i]}" ] || continue
  tstep_a+=("${_s[$i]}"); tlog_a+=("${_l[$i]:-}")
done
# Each touched run in RUNS has its steps: tfield's i-th "touched" line is tstep_a's i-th file. A touched run with an
# empty steps file would shift every one after it, so it's refused, never skipped.
[ "$(grep -c '^touched ' "$runs" || true)" = "${#tstep_a[@]}" ] \
  || { echo "::error::The owner's override doesn't hold: the touched runs and their steps don't line up"; exit 1; }
carry="A run of some suites counts only on earlier passes"
over="An override doesn't count for the Upgrade gate"

refuse() { echo "::error::The owner's override doesn't hold: $1"; exit 1; }
own() { grep -vE '^[0-9]+ [a-z_]+ (Set up job|Post .*|Complete job)$' "$1" || true; }
named() { cut -d' ' -f3- <<<"$1"; }
passed() { grep -qxF "success $2" <(cut -d' ' -f2- "$1"); }
repeats() { sed -E 's/^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9:.]+Z //' "$1" | grep -E '^##\[group\]Run go run \./cmd/(e2e|eval) ' | grep -oE -- '-repeat "[^"]*"' | sort -u || true; }
# The later runs' steps files: the check, then each touched run.
later_files() { echo "$check"; for f in "${tstep_a[@]}"; do echo "$f"; done; }
# A later run: on the shipped model, no job failure, nothing red but its own carry step, or a step another later run
# passed.
later() {
  local steps=$1 what=$2 others name f ok left=""
  grep -E '^[0-9]+ failure (Set up job|Post .*|Complete job)$' "$steps" >/dev/null && refuse "the $what run failed outside its steps"
  grep -qxF "skipped $over" <(cut -d' ' -f2- "$steps") || refuse "the $what run overrode the model or effort"
  others=$(own "$steps" | grep -vE '^[0-9]+ (success|skipped) ' | grep -vE "^[0-9]+ [a-z_]+ ${carry}\$" || true)
  while IFS= read -r line; do
    [ -n "$line" ] || continue
    name=$(named "$line") ok=""
    while IFS= read -r f; do [ "$f" != "$steps" ] && passed "$f" "$name" && ok=1; done < <(later_files)
    [ -n "$ok" ] || left="$left${left:+;}$name"
  done <<<"$others"
  [ -z "$left" ] || refuse "the $what run failed: $left"
}

# The runs: the later ones newer than the base, at the commit the Upgrade ships from.
field() { awk -v r="$1" -v n="$2" '$1 == r { print $n }' "$runs"; }
# The i-th touched run's field n (RUNS may hold several "touched" lines, in order).
tfield() { awk -v i="$1" -v n="$2" '$1 == "touched" { k++; if (k == i) print $n }' "$runs"; }
newer() {
  local role=$1 id=$2 created=$3 sha=$4
  [ -n "$id" ] || refuse "the $role run isn't named"
  [[ "$(field base 3)" < "$created" ]] || refuse "the $role run isn't newer than the base"
  [ "$sha" = "$(field shipped 4)" ] || refuse "the $role run isn't at the agents commit the Upgrade ships from ($(field shipped 4 | cut -c1-12))"
}
newer check "$(field check 2)" "$(field check 3)" "$(field check 4)"
for i in "${!tstep_a[@]}"; do newer touched "$(tfield $((i + 1)) 2)" "$(tfield $((i + 1)) 3)" "$(tfield $((i + 1)) 4)"; done

# The base.
grep -E '^[0-9]+ failure (Set up job|Post .*|Complete job)$' "$base" >/dev/null && refuse "the base run failed outside its steps"
for step in "$over" "$carry"; do
  grep -qxF "skipped $step" <(cut -d' ' -f2- "$base") || refuse "the base run isn't a full run of every suite on the shipped model (\"$step\" ran)"
done
sed -E 's/^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9:.]+Z //' "$baselog" | grep -qxF "deployed: unchanged" \
  || refuse "the base run is mixed: a service was deployed during it, or its log doesn't say"
failed=$(own "$base" | grep -vE '^[0-9]+ (success|skipped) ' || true)
[ "$(grep -c . <<<"$failed" || true)" = 1 ] || refuse "the base run must have failed in exactly one step, not: $(named "$failed" | paste -sd ';' -)"
step=$(named "$failed")
suite=$(jq -r --arg s "$step" '[.suites | to_entries[] | select(.value.steps | index($s)) | .key][0] // empty' "$suites")
[ -n "$suite" ] || refuse "\"$step\" is in no suite"

# The check: that step's whole suite at repeat 3.
later "$check" check
r=$(repeats "$log")
[ -n "$r" ] || refuse "the check run's log shows no suite run"
[ "$r" = '-repeat "3"' ] || refuse "the check run ran below the bar's repeat of 3"
while IFS= read -r name; do
  passed "$check" "$name" || refuse "\"$name\" (suite $suite) didn't pass in the check run"
done < <(jq -r --arg s "$suite" '.suites[$s].steps[]' "$suites")

# The touched runs, when there are any: the record's repeat is the lowest of them.
trep=3
for i in "${!tstep_a[@]}"; do
  later "${tstep_a[$i]}" touched
  r=$(repeats "${tlog_a[$i]}" | grep -oE '[0-9]+' | sort -n | head -1)
  [ -n "$r" ] || refuse "the touched run's log shows no suite run"
  [ "$r" -lt "$trep" ] && trep=$r
done

# Every suite the changed definitions touch passed every step, in one run or the other.
touched_suites=$(jq -r --rawfile ch "$changed" '(.paths["kiff/tafs"] // []) as $rules
  | [($ch | split("\n") | map(select(length > 0)))[] as $p | ([$rules[] | select(. as $r | $p | startswith($r.prefix))][0])
     | if . == null then "*" else .suites[] end] | unique | .[]' "$suites") || refuse "the suites' map can't be read"
grep -qxF '*' <<<"$touched_suites" && touched_suites=$(jq -r '.suites | keys[]' "$suites")
others=""
for t in $touched_suites; do
  [ "$t" = "$suite" ] && continue
  others="$others $t"
  while IFS= read -r name; do
    [ -n "$name" ] || continue
    ok=""
    while IFS= read -r f; do passed "$f" "$name" && ok=1; done < <(later_files)
    [ -n "$ok" ] \
      || refuse "the definitions changed since the base touch suite $t, and no later run passed \"$name\""
  done < <(jq -r --arg s "$t" '.suites[$s].steps[]?' "$suites")
done
echo "$step"
if [ -n "$others" ]; then
  echo "suite $suite at repeat 3 (Eval $(field check 2)); touched suites${others} at repeat ${trep} (Eval $(awk '$1 == "touched" { print $2 }' "$runs" | paste -sd, -)), the owner's choice: touched suites at repeat 1"
else
  echo "suite $suite at repeat 3 (Eval $(field check 2)); no other suite touched"
fi
