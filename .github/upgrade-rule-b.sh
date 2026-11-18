#!/usr/bin/env bash
# upgrade-rule-b.sh LOG STEPS
#
# The owner's rule B for the Upgrade (upgrade.yml, input rule_b_run): a full
# Eval whose only failed suites failed on the judge's wording check of Coach's
# rules notice ("signs what was agreed"), in 1 run of 3 at most, still allows
# the Upgrade. Every other check of those suites held in all 3 runs, every
# other step passed, and nothing else failed.
#
# LOG is the Eval job's log as GitHub serves it, STEPS its steps from the API,
# one "number conclusion name" per line. Read from the harness's own lines by
# their fixed form, never a loose match: a line that looks like a result but
# doesn't parse refuses. Exits 0 when rule B holds, 1 with the reason.
#
# Each step's part of the log starts at its "##[group]Run" line, in the order
# the steps ran; a step that was skipped writes none. The steps that count are
# the job's own (not "Set up job", "Post …" or "Complete job").
set -euo pipefail
log=$1 steps=$2
check="signs what was agreed"

refuse() { echo "::error::Not allowed under the owner's rule B: $1"; exit 1; }

# Only a full run at the bar's repeat: no override, no run of some suites.
for step in "An override doesn't count for the Upgrade gate" "A run of some suites counts only on earlier passes"; do
  grep -qxF "skipped $step" <(cut -d' ' -f2- "$steps") || refuse "not a full run of every suite on the shipped model (\"$step\" ran)"
done

text=$(mktemp) parts=$(mktemp -d)
sed -E 's/^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9:.]+Z //' "$log" > "$text"
grep -E '^##\[group\]Run go run \./cmd/(e2e|eval) ' "$text" | grep -oE -- '-repeat "[^"]*"' | grep -vxF -- '-repeat "3"' >/dev/null \
  && refuse "a suite ran below the bar's repeat of 3"
awk -v dir="$parts" '/^##\[group\]Run /{n++} n>0 {print > (dir "/" n)}' "$text"

# The job's own steps that ran, in order, against the parts of the log.
own=$(grep -vE '^[0-9]+ [a-z_]+ (Set up job|Post .*|Complete job)$' "$steps" | grep -vE '^[0-9]+ skipped ' || true)
ran=$(grep -c . <<<"$own" || true)
written=$(find "$parts" -type f | wc -l | tr -d ' ')
[ "$ran" = "$written" ] || refuse "the log's $written step(s) don't match the $ran step(s) that ran: it can't be read"
grep -E '^[0-9]+ failure (Set up job|Post .*|Complete job)$' "$steps" >/dev/null && refuse "the job itself failed outside its steps"

# Every step that ran, passed or not: rule B wants every check but the wording one in all 3 runs, in
# every suite, so a run's failure the suite's own bar let through refuses too.
every() {
  local f=$1 name=$2
  # Every line that reads as a failure, a warning or a verdict, in one of its three fixed forms. A
  # warning a panel passed ("1 of 3 judges said not") isn't a failure; any other warning is.
  local odd
  odd=$(grep -E '^ *(FAIL|warn)\b|FAIL:' "$f" \
    | grep -vxE '  warn  [a-z][a-z ,'"'"'-]* \(1 of 3 judges said not\)' \
    | grep -vxF "  FAIL  $check" \
    | grep -vxE "  FAIL  $check: 2 of 3 runs \([a-z ,]+\)" \
    | grep -vxF "FAIL: over 3 runs, below the bar: $check" || true)
  [ -z "$odd" ] || refuse "\"$name\" failed otherwise: $(head -1 <<<"$odd" | sed -E 's/^ *//')"
  [ "$(grep -cxF "  FAIL  $check" "$f")" -le 1 ] || refuse "\"$name\": \"$check\" failed in more than 1 run of 3"
  local short
  short=$(grep -E '^  (pass|FAIL|skip)  .*: [0-9]+ of [0-9]+ runs' "$f" | grep -vE ': 3 of 3 runs( \(|$)' | grep -vE "^  FAIL  $check: 2 of 3 runs " || true)
  [ -z "$short" ] || refuse "\"$name\": another check didn't hold in all 3 runs: $(head -1 <<<"$short" | sed -E 's/^ *//')"
}

# Each failed step is a suite that failed on the wording check alone.
suite() {
  local f=$1 name=$2
  [ "$(grep -E '^run [0-9]+ of [0-9]+$' "$f" | tr '\n' '|')" = "run 1 of 3|run 2 of 3|run 3 of 3|" ] \
    || refuse "\"$name\" doesn't have its 3 runs, each once"
  [ "$(grep -cxF "  FAIL  $check" "$f")" = 1 ] || refuse "\"$name\": no run failed \"$check\""
  [ "$(grep -cxE "  FAIL  $check: 2 of 3 runs \([a-z ,]+\)" "$f")" = 1 ] || refuse "\"$name\": no summary of \"$check\" at 2 of 3 runs"
  [ "$(grep -cxF "FAIL: over 3 runs, below the bar: $check" "$f")" = 1 ] || refuse "\"$name\": its verdict isn't below the bar on \"$check\" alone"
}

failed=0 i=0
while read -r number conclusion name; do
  i=$((i + 1))
  every "$parts/$i" "$name"
  [ "$conclusion" = failure ] || continue
  suite "$parts/$i" "$name"
  failed=$((failed + 1))
done <<<"$own"
[ "$failed" -gt 0 ] || refuse "no step failed, so this isn't a run for rule B: the plain gate reads it"

echo "Rule B holds: $failed suite(s) failed only on \"$check\", each in 1 run of 3; every other check held in all 3 runs, in every step, and every other step passed."
