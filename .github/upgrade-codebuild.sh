#!/usr/bin/env bash
# The Eval's backup run on AWS CodeBuild (kiff/agents' infra/codebuild), read
# for upgrade.yml's codebuild_build: CodeBuild's own record of the build,
# beside the project's definition, both read through a read-only role. It
# counts as a pass of the shipped definitions only when it ran as the project
# defines it, so a build started with another buildspec, source, environment
# (a variable, image or compute), role or limit counts for nothing, whatever
# its status says.
#
#	upgrade-codebuild.sh PROJECT.json BUILD.json NEWEST_BUILD_ID LATEST_EVAL_CREATED
#
# PROJECT.json and BUILD.json: `aws codebuild batch-get-projects` and
# `batch-get-builds`, each one item. NEWEST_BUILD_ID: the project's newest
# build. LATEST_EVAL_CREATED: when the latest completed eval.yml run on
# kiff/agents' main was created (ISO 8601). Prints the kiff/agents commit the
# build measured, or why it doesn't count, and exits 1.
set -euo pipefail
[ $# -eq 4 ] || { echo "usage: $0 PROJECT.json BUILD.json NEWEST_BUILD_ID LATEST_EVAL_CREATED" >&2; exit 2; }
project=$1 build=$2 newest=$3 latest=$4
no() { echo "the CodeBuild build doesn't count: $*"; exit 1; }

name=$(jq -r .name "$project")
[ "$name" = agents-eval ] || no "the project is $name, not agents-eval"

# The project is kiff/agents' own definition (infra/codebuild/eval-codebuild.sh
# setup), not one edited since: its source, buildspec, role, limit, image and
# variables' names. A project changed once, then a build that matches it,
# counts for nothing either.
pinned() { # pinned LABEL JQ WANT: the project's value under JQ is WANT
  local got
  got=$(jq -cS "$2" "$project")
  [ "$got" = "$3" ] || no "the project differs from kiff/agents' definition in its $1 ($got)"
}
pinned source '.source | {type, location, buildspec}' '{"buildspec":"infra/codebuild/buildspec.yml","location":"https://github.com/kiff/agents.git","type":"GITHUB"}'
pinned role '.serviceRole | test(":role/agents-eval-codebuild$")' 'true'
pinned "time limit" '.timeoutInMinutes' '285'
pinned image '.environment | {type, image, computeType, privilegedMode}' '{"computeType":"BUILD_GENERAL1_MEDIUM","image":"aws/codebuild/standard:7.0","privilegedMode":false,"type":"LINUX_CONTAINER"}'
pinned variables '[.environment.environmentVariables[] | {name, type}] | sort_by(.name)' '[{"name":"AWS_REGION","type":"PLAINTEXT"},{"name":"EVAL_STRIPE_ACCOUNT","type":"PLAINTEXT"},{"name":"REPORTS","type":"PLAINTEXT"},{"name":"TAFS_YOUR_STEP","type":"PLAINTEXT"}]'
pinned "reports bucket" '[.environment.environmentVariables[] | select(.name == "REPORTS" or .name == "AWS_REGION") | .value] | sort' '["eu-west-1","kiff-agents-eval-reports"]'
pinned extras '[(.secondarySources // [] | length), (.fileSystemLocations // [] | length), (.vpcConfig.vpcId // ""), (.cache.type // "NO_CACHE")]' '[0,0,"","NO_CACHE"]'
[ "$(jq -r .projectName "$build")" = "$name" ] || no "it isn't a build of $name"
[ "$(jq -r .id "$build")" = "$newest" ] || no "it isn't the project's newest build ($newest)"
[ "$(jq -r .buildStatus "$build")" = SUCCEEDED ] || no "it ended $(jq -r .buildStatus "$build")"
[ "$(jq -r .buildComplete "$build")" = true ] || no "it hasn't finished"

# Ran as the project defines it. The build's record holds what it ran with,
# overrides included; each part is compared whole, its variables in name order.
same() { # same LABEL JQ: the project's and the build's values under JQ are equal
  local a b
  a=$(jq -cS "$2" "$project")
  b=$(jq -cS "$2" "$build")
  [ "$a" = "$b" ] || no "its $1 isn't the project's"
}
same source '.source'
same environment '.environment | .environmentVariables |= ((. // []) | sort_by(.name))'
same role '.serviceRole'
same "time limit" '.timeoutInMinutes'
same "log group" '.logsConfig.cloudWatchLogs.groupName // .logs.groupName'
[ "$(jq -r '(.secondarySources // []) | length' "$build")" = 0 ] || no "it had a second source"
[ "$(jq -r '(.secondarySourceVersions // []) | length' "$build")" = 0 ] || no "it had a second source version"
[ "$(jq -r '.buildBatchArn // ""' "$build")" = "" ] || no "it was part of a batch"
[ "$(jq -r '.debugSession.sessionEnabled // false' "$build")" = false ] || no "it had a debug session"
[ "$(jq -r '.cache.type // "NO_CACHE"' "$build")" = NO_CACHE ] || no "it had a cache"
[ "$(jq -r '(.fileSystemLocations // []) | length' "$build")" = 0 ] || no "it had a file system"
[ "$(jq -r '.vpcConfig.vpcId // ""' "$build")" = "" ] || no "it ran in a VPC"

sha=$(jq -r '.resolvedSourceVersion // ""' "$build")
[[ "$sha" =~ ^[0-9a-f]{40}$ ]] || no "it names no commit"

# Newer than the latest GitHub Eval: a later red on GitHub isn't passed over.
started=$(date -u -d "$(jq -r .startTime "$build")" +%s) || no "its start can't be read"
eval_created=$(date -u -d "$latest" +%s) || no "the latest Eval's time can't be read"
[ "$started" -gt "$eval_created" ] || no "it started before the latest Eval on GitHub ($latest)"

echo "$sha"
