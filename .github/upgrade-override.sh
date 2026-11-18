#!/usr/bin/env bash
# upgrade-override.sh BASE_STEPS BASE_LOG CHECK_LOG CHECK_STEPS SUITES CHANGED
#
# The owner's override for the Upgrade (upgrade.yml, input owner_override): a
# named owner decision on the thread lets a full Eval that failed in one step
# count, once a later run of that step alone passed. It holds only when:
#   - the base run was a full run of every suite (no override, no run of some
#     suites), not mixed (its "deployed: unchanged" line: tafs cloud, planque
#     and agents the same at its start and end), and exactly one of its steps
#     failed;
#   - the check run ran that step at the bar's repeat of 3, on the shipped
#     model, and it passed;
#   - every suite the definitions changed between the two runs touch was run
#     again in the check, and each of its steps passed: CHANGED lists those
#     paths in kiff/tafs, mapped as kiff/agents maps them (SUITES, its
#     eval/suites.json at the check run's commit: a path's first matching
#     prefix, and one that matches none touches every suite);
#   - every other step of the check run passed or was skipped, but for its own
#     "A run of some suites counts only on earlier passes", which a run of
#     some suites ends red when nothing carries.
#
# STEPS files are the jobs' steps from the API, one "number conclusion name"
# per line; the LOGs are the jobs' logs as GitHub serves them. Exits 0 with
# the step's name when the override holds, 1 with the reason.
set -euo pipefail
base=$1 baselog=$2 log=$3 check=$4 suites=$5 changed=$6
carry="A run of some suites counts only on earlier passes"
over="An override doesn't count for the Upgrade gate"

refuse() { echo "::error::The owner's override doesn't hold: $1"; exit 1; }
own() { grep -vE '^[0-9]+ [a-z_]+ (Set up job|Post .*|Complete job)$' "$1" || true; }
named() { cut -d' ' -f3- <<<"$1"; }

grep -E '^[0-9]+ failure (Set up job|Post .*|Complete job)$' "$base" "$check" >/dev/null && refuse "a job failed outside its steps"
for step in "$over" "$carry"; do
  grep -qxF "skipped $step" <(cut -d' ' -f2- "$base") || refuse "the base run isn't a full run of every suite on the shipped model (\"$step\" ran)"
done
sed -E 's/^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9:.]+Z //' "$baselog" | grep -qxF "deployed: unchanged" \
  || refuse "the base run is mixed: a service was deployed during it, or its log doesn't say"
failed=$(own "$base" | grep -vE '^[0-9]+ (success|skipped) ' || true)
[ "$(grep -c . <<<"$failed" || true)" = 1 ] || refuse "the base run must have failed in exactly one step, not: $(named "$failed" | paste -sd ';' -)"
step=$(named "$failed")

grep -qxF "skipped $over" <(cut -d' ' -f2- "$check") || refuse "the check run overrode the model or effort"
# The suites the changed definitions touch, each of whose steps must have passed in the check.
touched=$(jq -r --rawfile ch "$changed" '(.paths["kiff/tafs"] // []) as $rules
  | [($ch | split("\n") | map(select(length > 0)))[] as $p | ([$rules[] | select(. as $r | $p | startswith($r.prefix))][0])
     | if . == null then "*" else .suites[] end] | unique | .[]' "$suites") || refuse "the suites' map can't be read"
grep -qxF '*' <<<"$touched" && touched=$(jq -r '.suites | keys[]' "$suites")
for suite in $touched; do
  while IFS= read -r name; do
    [ -n "$name" ] || continue
    grep -qxF "success $name" <(cut -d' ' -f2- "$check") || refuse "the definitions changed since the base touch suite $suite, and the check didn't pass \"$name\""
  done < <(jq -r --arg s "$suite" '.suites[$s].steps[]?' "$suites")
done
grep -qxF "success $step" <(cut -d' ' -f2- "$check") || refuse "\"$step\" didn't pass in the check run"
others=$(own "$check" | grep -vE '^[0-9]+ (success|skipped) ' | grep -vE "^[0-9]+ [a-z_]+ ${carry}\$" || true)
[ -z "$others" ] || refuse "the check run failed elsewhere: $(named "$others" | paste -sd ';' -)"
repeats=$(sed -E 's/^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9:.]+Z //' "$log" | grep -E '^##\[group\]Run go run \./cmd/(e2e|eval) ' | grep -oE -- '-repeat "[^"]*"' || true)
[ -n "$repeats" ] || refuse "the check run's log shows no suite run"
grep -vxF -- '-repeat "3"' <<<"$repeats" >/dev/null && refuse "the check run ran below the bar's repeat of 3"
echo "$step"
