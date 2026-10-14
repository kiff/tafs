#!/usr/bin/env bash
# Creates tafs cloud on AWS, or brings it back in line. Safe to run again.
#
#   tafs-cloud                 Lambda (Node 22, arm64) with a public function URL;
#                              the code authenticates every route with KIFF.
#   tafs-cloud-lambda          its role: logs, and reading the one secret below.
#   tafs/anthropic-api-key     the Anthropic key; a person puts it there (see README).
#   tafs-github-deploy         the role GitHub Actions assumes, only from kiff/tafs
#                              main: update this function's code, nothing else.
#
# Everything is named tafs-*, in us-east-1, and touches nothing of kiff-cloud's.
# Usage: infra/aws.sh path/to/lambda.zip
set -euo pipefail

REGION=us-east-1
FN=tafs-cloud
SECRET=tafs/anthropic-api-key
LAMBDA_ROLE=tafs-cloud-lambda
DEPLOY_ROLE=tafs-github-deploy
REPO=kiff/tafs
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
aws iam put-role-policy --role-name "$LAMBDA_ROLE" --policy-name read-anthropic-key --policy-document "{
  \"Version\": \"2012-10-17\",
  \"Statement\": [{\"Effect\": \"Allow\", \"Action\": \"secretsmanager:GetSecretValue\", \"Resource\": \"$SECRET_ARN\"}]
}"
LAMBDA_ROLE_ARN=$(aws iam get-role --role-name "$LAMBDA_ROLE" --query Role.Arn --output text)

# The function.
ENV="Variables={ANTHROPIC_SECRET_ID=$SECRET,NODE_OPTIONS=--enable-source-maps}"
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
aws lambda add-permission --function-name "$FN" --statement-id public-url-invoke --action lambda:InvokeFunction \
  --principal '*' --invoked-via-function-url >/dev/null 2>&1 || true

# The deploy role, for kiff/tafs main only.
FN_ARN=$(aws lambda get-function --function-name "$FN" --query Configuration.FunctionArn --output text)
TRUST="{
  \"Version\": \"2012-10-17\",
  \"Statement\": [{
    \"Effect\": \"Allow\",
    \"Principal\": {\"Federated\": \"$OIDC_ARN\"},
    \"Action\": \"sts:AssumeRoleWithWebIdentity\",
    \"Condition\": {
      \"StringEquals\": {
        \"token.actions.githubusercontent.com:aud\": \"sts.amazonaws.com\",
        \"token.actions.githubusercontent.com:sub\": \"repo:$REPO:ref:refs/heads/main\"
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
    {\"Effect\": \"Allow\", \"Action\": [\"lambda:UpdateFunctionCode\", \"lambda:GetFunction\", \"lambda:GetFunctionConfiguration\"], \"Resource\": \"$FN_ARN\"}
  ]
}"

echo "deploy role $(aws iam get-role --role-name "$DEPLOY_ROLE" --query Role.Arn --output text)"
echo "url $URL"
