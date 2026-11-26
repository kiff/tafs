#!/usr/bin/env bash
# The backup Eval's gate (upgrade-codebuild.sh) on a saved project and build:
# a build that ran as its project defines it, newest and after the latest
# GitHub Eval, counts; each way of breaking that is refused. The records are
# synthetic, in the shape `aws codebuild batch-get-projects` and
# `batch-get-builds` return (account id zeroed).
set -euo pipefail
cd "$(dirname "$0")"
script=../upgrade-codebuild.sh
tmp=$(mktemp -d)
fails=0
id=$(jq -r .id build.json)
sha=$(jq -r .resolvedSourceVersion build.json)
latest=2026-11-26T22:10:12Z

counts() {
  local got
  if ! got=$(bash "$script" "${1:-project.json}" "${2:-build.json}" "${3:-$id}" "${4:-$latest}" 2>&1); then
    echo "FAIL: refused: $got"; fails=$((fails + 1)); return
  fi
  [ "$got" = "$sha" ] || { echo "FAIL: printed $got, not the commit"; fails=$((fails + 1)); }
}
refused() { # refused WHY [project] [build] [newest] [latest]
  local why=$1 got; shift
  if got=$(bash "$script" "${1:-project.json}" "${2:-build.json}" "${3:-$id}" "${4:-$latest}" 2>&1); then
    echo "FAIL: counted ($why)"; fails=$((fails + 1)); return
  fi
  grep -qF "$why" <<<"$got" || { echo "FAIL: refused for another reason ($why): $got"; fails=$((fails + 1)); }
}
build() { jq "$1" build.json > "$tmp/$2"; echo "$tmp/$2"; }

counts
# The variables in another order are the same environment.
counts project.json "$(build '.environment.environmentVariables |= reverse' b0)"

refused "isn't a build of agents-eval" project.json "$(build '.projectName = "other"' b1)"
refused "the project is other" "$(jq '.name = "other"' project.json > "$tmp/p1"; echo "$tmp/p1")"
refused "isn't the project's newest build" project.json build.json "agents-eval:another"
refused "it ended FAILED" project.json "$(build '.buildStatus = "FAILED"' b2)"
refused "it ended IN_PROGRESS" project.json "$(build '.buildStatus = "IN_PROGRESS" | .buildComplete = false' b3)"
# Overrides at start: each part of the record compared with the project's.
refused "its source isn't the project's" project.json "$(build '.source.buildspec = "version: 0.2\nphases: {build: {commands: [true]}}"' b4)"
refused "its source isn't the project's" project.json "$(build '.source.location = "https://github.com/someone/agents.git"' b5)"
refused "its environment isn't the project's" project.json "$(build '.environment.environmentVariables += [{"name":"AGENTS_MODEL_SOURCE","value":"anthropic","type":"PLAINTEXT"}]' b6)"
refused "its environment isn't the project's" project.json "$(build '(.environment.environmentVariables[] | select(.name == "EVAL_STRIPE_ACCOUNT") | .value) = "acct_other"' b7)"
refused "its environment isn't the project's" project.json "$(build '.environment.image = "someone/image:latest"' b8)"
refused "its environment isn't the project's" project.json "$(build '.environment.privilegedMode = true' b9)"
refused "its role isn't the project's" project.json "$(build '.serviceRole = "arn:aws:iam::000000000000:role/other"' b10)"
refused "its time limit isn't the project's" project.json "$(build '.timeoutInMinutes = 480' b11)"
refused "its log group isn't the project's" project.json "$(build '.logs.groupName = "/elsewhere"' b12)"
refused "it had a second source" project.json "$(build '.secondarySources = [{"type":"S3"}]' b13)"
refused "it was part of a batch" project.json "$(build '.buildBatchArn = "arn:aws:codebuild:eu-west-1:000000000000:build-batch/x"' b14)"
refused "it names no commit" project.json "$(build '.resolvedSourceVersion = "main"' b15)"
# Older than the latest Eval on GitHub: a later red there isn't passed over.
refused "it started before the latest Eval on GitHub" project.json build.json "$id" 2026-11-27T02:00:00Z

[ "$fails" = 0 ] || { echo "$fails failed"; exit 1; }
echo "upgrade-codebuild: all cases hold"
