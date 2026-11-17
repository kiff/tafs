#!/usr/bin/env bash
# The owner's rule B read from saved Eval logs (.github/upgrade-rule-b.sh).
# Two real full Evals, both refused: eval-44036967781's Setup in English and
# Replay each failed "signs what was agreed" in 1 run of 3, but its Chargebacks
# run 2 also failed "its words don't contradict the record", which that
# suite's bar let through and rule B doesn't; eval-44055541451's Setup in
# English failed on the taf's words against its record. The first with that
# one Chargebacks run passing is the run rule B allows, and each case below
# breaks it one way. (The private report bucket's name is a placeholder.)
set -uo pipefail
cd "$(dirname "$0")"
gate=../upgrade-rule-b.sh steps=eval-44036967781.steps
tmp=$(mktemp -d) failures=0
log=$tmp/holds.log
sed -e "s/^\(.*Z \)  FAIL  its words don't contradict the record$/\1  pass  its words don't contradict the record/" \
    -e 's/^\(.*Z \)chargebacks  8\/9 /\1chargebacks  9\/9 /' eval-44036967781.log > "$log"

holds() { if out=$(bash "$gate" "$1" "$2" 2>&1); then echo "ok    $3"; else echo "FAIL  $3: refused: $out"; failures=$((failures + 1)); fi; }
refused() {
  if out=$(bash "$gate" "$1" "$2" 2>&1); then echo "FAIL  $3: let through"; failures=$((failures + 1))
  elif grep -qF "$4" <<<"$out"; then echo "ok    $3"
  else echo "FAIL  $3: refused for another reason: $out"; failures=$((failures + 1)); fi
}

holds "$log" "$steps" "the saved Eval, its one Chargebacks run passing, meets rule B"
refused eval-44036967781.log "$steps" "a record check failing in a suite that passed (Eval 44036967781)" "failed otherwise: FAIL  its words don't contradict the record"
sed 's/  pass  no loop: 3 of 3 runs (experience)/  FAIL  no loop: 2 of 3 runs (experience)/' "$log" > "$tmp/extra.log"
refused "$tmp/extra.log" "$steps" "another check failing in a failed suite" "failed otherwise: FAIL  no loop"
awk '!(/ run 3 of 3$/ && !done++)' "$log" > "$tmp/short.log"
refused "$tmp/short.log" "$steps" "a run missing from a failed suite" "doesn't have its 3 runs"
sed 's/  FAIL  signs what was agreed: 2 of 3 runs/  FAIL  signs what was agreed: 1 of 3 runs/' "$log" > "$tmp/twice.log"
refused "$tmp/twice.log" "$steps" "the wording check failing in 2 runs of 3 (its summary)" "failed otherwise: FAIL  signs what was agreed: 1 of 3 runs"
awk '{ print } / run 3 of 3$/ && !done++ { print "2026-11-17T08:30:00.0000000Z   FAIL  signs what was agreed" }' "$log" > "$tmp/twice-runs.log"
refused "$tmp/twice-runs.log" "$steps" "the wording check failing in 2 runs of 3 (its runs)" "in more than 1 run of 3"
sed 's/^20 success The sweep/20 failure The sweep/' "$steps" > "$tmp/sweep.steps"
refused "$log" "$tmp/sweep.steps" "a step outside the suites failing" "\"The sweep, the test account's leftover tafs\""
sed "s/^23 skipped An override/23 failure An override/" "$steps" > "$tmp/override.steps"
refused "$log" "$tmp/override.steps" "a run on another model" "not a full run"
sed "s/^24 skipped A run of some suites/24 success A run of some suites/" "$steps" > "$tmp/some.steps"
refused "$log" "$tmp/some.steps" "a run of some suites" "not a full run"
sed 's/-repeat "3"/-repeat "2"/' "$log" > "$tmp/repeat.log"
refused "$tmp/repeat.log" "$steps" "a run below the bar's repeat" "below the bar's repeat"
grep -v '^20 ' "$steps" > "$tmp/unread.steps"
refused "$log" "$tmp/unread.steps" "steps that don't match the log" "can't be read"
sed 's/^12 failure/12 success/; s/^15 failure/15 success/' "$steps" > "$tmp/green.steps"
refused "$log" "$tmp/green.steps" "a green run" "the plain gate reads it"
sed 's/Z FAIL: over 3 runs, below the bar: signs what was agreed$/Z FAIL: over 3 runs, below the bar: signs what was agreed, no loop/' "$log" > "$tmp/wider.log"
refused "$tmp/wider.log" "$steps" "a verdict below the bar on more than the wording" "failed otherwise"

# A later full Eval whose Setup in English failed on the taf's own words against its record (run 1,
# answer 8), with "signs what was agreed" passing everywhere: not the wording check, so refused.
refused eval-44055541451.log eval-44055541451.steps "a suite failing on another gate (Eval 44055541451)" "failed otherwise: FAIL: 1 of 3 runs broke"
# A warning a panel passed (1 of 3 judges) isn't a failure.
sed 's/^\(.*Z \)  pass  no loop$/\1  warn  no loop (1 of 3 judges said not)/' "$log" > "$tmp/warned.log"
holds "$tmp/warned.log" "$steps" "a warning a panel passed"
sed 's/^\(.*Z \)  pass  no loop$/\1  warn  no loop (2 of 3 judges said not)/' "$log" > "$tmp/warned2.log"
refused "$tmp/warned2.log" "$steps" "a warning most judges gave" "failed otherwise: warn  no loop"

[ "$failures" = 0 ] || { echo "$failures case(s) wrong"; exit 1; }
