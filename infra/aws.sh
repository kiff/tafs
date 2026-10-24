#!/usr/bin/env bash
# Creates tafs cloud on AWS, or brings it back in line. Safe to run again.
#
#   tafs-cloud                 Lambda (Node 22, arm64) with a public function URL;
#                              the code authenticates every route with KIFF.
#   tafs-cloud-stream          the same code, handler index.stream, with a public
#                              function URL in RESPONSE_STREAM mode and 15 minutes:
#                              one route, a chat message whose run is streamed to
#                              the phone as it is written (kiff/tafs#60).
#   tafs-cloud-lambda          its role: logs, the APNs key, this table, calling
#                              kiff/agents' API, and writing the tafs' KIFF keys and
#                              the accounts' alert secrets where kiff/agents reads them.
#   tafs-cloud-names           DynamoDB: a taf's name per account, claimed once; and
#                              chat message slots (rate#tenant#taf#window#n, 10 per
#                              10 minutes), each put once, with a ttl attribute; and
#                              claimed KIFF drafts (draft#tenant#draft: the taf whose
#                              key it is, never the key), kept 30 days.
#                              Also each account's phones and KIFF alert secrets
#                              (push#tenant), and delivered alert ids (ttl).
#                              And the chats each taf's owner archived
#                              (archived#tenant#taf, ttl), kiff/tafs#82.
#   tafs/anthropic-api-key     the model key; a person creates it before this script
#                              runs and replicates it to eu-west-1 (see README). Made
#                              here only when it doesn't exist, holding "unset".
#                              tafs cloud doesn't read it: kiff/agents reads its
#                              replica in eu-west-1, which runs the model.
#   tafs/apns-key              the "tafs push" APNs key (.p8), made in the Apple
#                              Developer portal; a person puts it there.
#   tafs-github-deploy         the role GitHub Actions assumes, only from kiff/tafs
#                              main: update both functions' code, nothing else.
#
# Everything is named tafs-*, in us-east-1, and touches nothing of KIFF's own.
# Usage: infra/aws.sh path/to/lambda.zip
#
# Applied on 2026-10-14 step by step (each part checked before the next), not by
# running this file; it records what exists and can repair it.
set -euo pipefail

REGION=us-east-1
FN=tafs-cloud
SECRET=tafs/anthropic-api-key
APNS_SECRET=tafs/apns-key
# The "tafs push" key (Sandbox & Production, team scoped), and the app it pushes to.
# Its id and the Apple team come from the environment: this repository is public.
APNS_KEY_ID=${APNS_KEY_ID:?set APNS_KEY_ID to the tafs push key id}
APNS_TEAM_ID=${APNS_TEAM_ID:?set APNS_TEAM_ID to the Apple team id}
APNS_TOPIC=bot.tafs.app
LAMBDA_ROLE=tafs-cloud-lambda
TABLE=tafs-cloud-names
DEPLOY_ROLE=tafs-github-deploy
# GitHub sends kiff/tafs's immutable subject (owner and repo by id), so a
# renamed or recreated repo can't take over the role.
# gh api repos/kiff/tafs/actions/oidc/customization/sub -> sub_claim_prefix
REPO_SUB=repo:kiff@2176846/tafs@1418264001
ZIP=${1:?usage: infra/aws.sh lambda.zip}
aws() { command aws --region "$REGION" "$@"; }

ACCOUNT=$(aws sts get-caller-identity --query Account --output text)
OIDC_ARN=arn:aws:iam::$ACCOUNT:oidc-provider/token.actions.githubusercontent.com
echo "account $ACCOUNT"

# GitHub's OIDC provider, so Actions can assume a role without stored keys.
if ! aws iam get-open-id-connect-provider --open-id-connect-provider-arn "$OIDC_ARN" >/dev/null 2>&1; then
  aws iam create-open-id-connect-provider --url https://token.actions.githubusercontent.com \
    --client-id-list sts.amazonaws.com >/dev/null
  echo "created the GitHub OIDC provider"
fi

# The secret, created as a placeholder. A person puts the real key in it.
if ! SECRET_ARN=$(aws secretsmanager describe-secret --secret-id "$SECRET" --query ARN --output text 2>/dev/null); then
  SECRET_ARN=$(aws secretsmanager create-secret --name "$SECRET" --description "tafs cloud: Anthropic API key" \
    --secret-string unset --tags Key=project,Value=tafs --query ARN --output text)
  echo "created secret $SECRET"
fi

# The APNs key, as a placeholder until a person puts the .p8 in it.
if ! APNS_SECRET_ARN=$(aws secretsmanager describe-secret --secret-id "$APNS_SECRET" --query ARN --output text 2>/dev/null); then
  APNS_SECRET_ARN=$(aws secretsmanager create-secret --name "$APNS_SECRET" --description "tafs push: APNs key $APNS_KEY_ID" \
    --secret-string unset --tags Key=project,Value=tafs --query ARN --output text)
  echo "created secret $APNS_SECRET"
fi

# Taf names, claimed with a conditional write so two requests can't both start one.
if ! aws dynamodb describe-table --table-name "$TABLE" >/dev/null 2>&1; then
  aws dynamodb create-table --table-name "$TABLE" --billing-mode PAY_PER_REQUEST \
    --attribute-definitions AttributeName=id,AttributeType=S --key-schema AttributeName=id,KeyType=HASH \
    --tags Key=project,Value=tafs >/dev/null
  aws dynamodb wait table-exists --table-name "$TABLE"
  echo "created table $TABLE"
fi
# Old chat message slots expire on their own.
aws dynamodb update-time-to-live --table-name "$TABLE" \
  --time-to-live-specification Enabled=true,AttributeName=ttl >/dev/null 2>&1 || true
TABLE_ARN=$(aws dynamodb describe-table --table-name "$TABLE" --query Table.TableArn --output text)

# The function's role.
if ! aws iam get-role --role-name "$LAMBDA_ROLE" >/dev/null 2>&1; then
  aws iam create-role --role-name "$LAMBDA_ROLE" --tags Key=project,Value=tafs --assume-role-policy-document '{
    "Version": "2012-10-17",
    "Statement": [{"Effect": "Allow", "Principal": {"Service": "lambda.amazonaws.com"}, "Action": "sts:AssumeRole"}]
  }' >/dev/null
  echo "created role $LAMBDA_ROLE"
fi
aws iam attach-role-policy --role-name "$LAMBDA_ROLE" \
  --policy-arn arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole
# kiff/agents (kiff/agents infra/aws.sh), where every taf runs: its API, called
# with this role, and the region's secrets it reads the tafs' KIFF keys and the
# accounts' alert secrets from. tafs cloud writes those and never reads them.
AGENTS_REGION=eu-west-1
AGENTS_FN_ARN=arn:aws:lambda:$AGENTS_REGION:$ACCOUNT:function:agents
AGENTS_SECRETS=arn:aws:secretsmanager:$AGENTS_REGION:$ACCOUNT:secret
aws iam put-role-policy --role-name "$LAMBDA_ROLE" --policy-name tafs-cloud-access --policy-document "{
  \"Version\": \"2012-10-17\",
  \"Statement\": [
    {\"Effect\": \"Allow\", \"Action\": \"secretsmanager:GetSecretValue\", \"Resource\": [\"$APNS_SECRET_ARN\"]},
    {\"Effect\": \"Allow\", \"Action\": [\"dynamodb:GetItem\", \"dynamodb:PutItem\", \"dynamodb:UpdateItem\", \"dynamodb:DeleteItem\", \"dynamodb:Scan\"], \"Resource\": \"$TABLE_ARN\"},
    {\"Effect\": \"Allow\", \"Action\": \"lambda:InvokeFunctionUrl\", \"Resource\": \"$AGENTS_FN_ARN\",
     \"Condition\": {\"StringEquals\": {\"lambda:FunctionUrlAuthType\": \"AWS_IAM\"}}},
    {\"Effect\": \"Allow\", \"Action\": \"lambda:InvokeFunction\", \"Resource\": \"$AGENTS_FN_ARN\",
     \"Condition\": {\"Bool\": {\"lambda:InvokedViaFunctionUrl\": \"true\"}}},
    {\"Effect\": \"Allow\", \"Action\": [\"secretsmanager:CreateSecret\", \"secretsmanager:PutSecretValue\", \"secretsmanager:DeleteSecret\", \"secretsmanager:DescribeSecret\"],
     \"Resource\": [\"$AGENTS_SECRETS:agents/*\", \"$AGENTS_SECRETS:agents-accounts/*\"]}
  ]
}"
LAMBDA_ROLE_ARN=$(aws iam get-role --role-name "$LAMBDA_ROLE" --query Role.Arn --output text)

# The function.
AGENTS_URL=$(command aws --region "$AGENTS_REGION" lambda get-function-url-config --function-name agents --query FunctionUrl --output text)
AGENTS_ALERTS_URL=$(command aws --region "$AGENTS_REGION" lambda get-function-url-config --function-name agents-kiffalerts --query FunctionUrl --output text)kiff/alerts
ENV="Variables={AGENTS_URL=$AGENTS_URL,AGENTS_ALERTS_URL=$AGENTS_ALERTS_URL,AGENTS_REGION=$AGENTS_REGION,NAMES_TABLE=$TABLE,APNS_SECRET_ID=$APNS_SECRET,APNS_KEY_ID=$APNS_KEY_ID,APNS_TEAM_ID=$APNS_TEAM_ID,APNS_TOPIC=$APNS_TOPIC,NODE_OPTIONS=--enable-source-maps}"
if ! aws lambda get-function --function-name "$FN" >/dev/null 2>&1; then
  for i in 1 2 3 4 5 6; do  # a new role takes a few seconds to be assumable
    if aws lambda create-function --function-name "$FN" --runtime nodejs22.x --architectures arm64 \
      --handler index.handler --role "$LAMBDA_ROLE_ARN" --zip-file "fileb://$ZIP" \
      --memory-size 512 --timeout 60 --environment "$ENV" --tags project=tafs >/dev/null 2>&1; then
      echo "created function $FN"; break
    fi
    [ "$i" = 6 ] && { echo "could not create $FN" >&2; exit 1; }
    sleep 10
  done
  aws lambda wait function-active-v2 --function-name "$FN"
else
  aws lambda update-function-configuration --function-name "$FN" --memory-size 512 --timeout 60 \
    --environment "$ENV" >/dev/null
  aws lambda wait function-updated-v2 --function-name "$FN"
  aws lambda update-function-code --function-name "$FN" --zip-file "fileb://$ZIP" >/dev/null
  aws lambda wait function-updated-v2 --function-name "$FN"
  echo "updated function $FN"
fi
aws logs create-log-group --log-group-name "/aws/lambda/$FN" 2>/dev/null || true
aws logs put-retention-policy --log-group-name "/aws/lambda/$FN" --retention-in-days 30

# Its public URL. Every route but /v1/jobs checks the caller with KIFF.
if ! URL=$(aws lambda get-function-url-config --function-name "$FN" --query FunctionUrl --output text 2>/dev/null); then
  URL=$(aws lambda create-function-url-config --function-name "$FN" --auth-type NONE --query FunctionUrl --output text)
  echo "created the function URL"
fi
aws lambda add-permission --function-name "$FN" --statement-id public-url --action lambda:InvokeFunctionUrl \
  --principal '*' --function-url-auth-type NONE >/dev/null 2>&1 || true
# Since 2025 a public URL also needs lambda:InvokeFunction, limited to calls made
# through the URL. AWS CLI 2.31 has no --invoked-via-function-url; on an older
# CLI, add it with the SDK's AddPermission and InvokedViaFunctionUrl: true.
aws lambda add-permission --function-name "$FN" --statement-id public-url-invoke --action lambda:InvokeFunction \
  --principal '*' --invoked-via-function-url >/dev/null 2>&1 \
  || echo "add the URL-only InvokeFunction permission by hand (see the comment above)" >&2

# The streaming function (kiff/tafs#60): the same code and role, one route
# (POST /v1/tafs/{id}/messages/stream), and a URL that streams. A run can take
# minutes, so it has 15; every other route stays on tafs-cloud's 60 seconds.
SFN=tafs-cloud-stream
if ! aws lambda get-function --function-name "$SFN" >/dev/null 2>&1; then
  aws lambda create-function --function-name "$SFN" --runtime nodejs22.x --architectures arm64 \
    --handler index.stream --role "$LAMBDA_ROLE_ARN" --zip-file "fileb://$ZIP" \
    --memory-size 512 --timeout 900 --environment "$ENV" --tags project=tafs >/dev/null
  aws lambda wait function-active-v2 --function-name "$SFN"
  echo "created function $SFN"
else
  aws lambda update-function-configuration --function-name "$SFN" --handler index.stream --memory-size 512 --timeout 900 \
    --environment "$ENV" >/dev/null
  aws lambda wait function-updated-v2 --function-name "$SFN"
  aws lambda update-function-code --function-name "$SFN" --zip-file "fileb://$ZIP" >/dev/null
  aws lambda wait function-updated-v2 --function-name "$SFN"
  echo "updated function $SFN"
fi
aws logs create-log-group --log-group-name "/aws/lambda/$SFN" 2>/dev/null || true
aws logs put-retention-policy --log-group-name "/aws/lambda/$SFN" --retention-in-days 30
if ! STREAM_URL=$(aws lambda get-function-url-config --function-name "$SFN" --query FunctionUrl --output text 2>/dev/null); then
  STREAM_URL=$(aws lambda create-function-url-config --function-name "$SFN" --auth-type NONE --invoke-mode RESPONSE_STREAM \
    --query FunctionUrl --output text)
  echo "created the streaming function URL"
fi
aws lambda add-permission --function-name "$SFN" --statement-id public-url --action lambda:InvokeFunctionUrl \
  --principal '*' --function-url-auth-type NONE >/dev/null 2>&1 || true
aws lambda add-permission --function-name "$SFN" --statement-id public-url-invoke --action lambda:InvokeFunction \
  --principal '*' --invoked-via-function-url >/dev/null 2>&1 \
  || echo "add the URL-only InvokeFunction permission to $SFN by hand (see above)" >&2

# The deploy role, for kiff/tafs main only: it updates both functions' code,
# and invokes tafs-cloud directly (never its URL) for the definitions upgrade
# (kiff/tafs#69, the upgrade workflow).
FN_ARN=$(aws lambda get-function --function-name "$FN" --query Configuration.FunctionArn --output text)
SFN_ARN=$(aws lambda get-function --function-name "$SFN" --query Configuration.FunctionArn --output text)
TRUST="{
  \"Version\": \"2012-10-17\",
  \"Statement\": [{
    \"Effect\": \"Allow\",
    \"Principal\": {\"Federated\": \"$OIDC_ARN\"},
    \"Action\": \"sts:AssumeRoleWithWebIdentity\",
    \"Condition\": {
      \"StringEquals\": {
        \"token.actions.githubusercontent.com:aud\": \"sts.amazonaws.com\",
        \"token.actions.githubusercontent.com:sub\": \"$REPO_SUB:ref:refs/heads/main\"
      }
    }
  }]
}"
if ! aws iam get-role --role-name "$DEPLOY_ROLE" >/dev/null 2>&1; then
  aws iam create-role --role-name "$DEPLOY_ROLE" --tags Key=project,Value=tafs --assume-role-policy-document "$TRUST" >/dev/null
  echo "created role $DEPLOY_ROLE"
else
  aws iam update-assume-role-policy --role-name "$DEPLOY_ROLE" --policy-document "$TRUST"
fi
aws iam put-role-policy --role-name "$DEPLOY_ROLE" --policy-name deploy-tafs-cloud --policy-document "{
  \"Version\": \"2012-10-17\",
  \"Statement\": [
    {\"Effect\": \"Allow\", \"Action\": [\"lambda:UpdateFunctionCode\", \"lambda:GetFunction\", \"lambda:GetFunctionConfiguration\"], \"Resource\": [\"$FN_ARN\", \"$SFN_ARN\"]},
    {\"Effect\": \"Allow\", \"Action\": \"lambda:InvokeFunction\", \"Resource\": \"$FN_ARN\"}
  ]
}"

echo "deploy role $(aws iam get-role --role-name "$DEPLOY_ROLE" --query Role.Arn --output text)"
echo "url $URL"
echo "stream url $STREAM_URL"
