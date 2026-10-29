#!/bin/zsh
# Builds the signed release bundle for Play (app/build/outputs/bundle/release/app-release.aab) with the upload
# key from Secrets Manager (tafs-android/upload-key). The key is written to a private temporary directory for
# the build and removed after it; its passwords live only in this script's environment. Run it yourself:
# ! android/release.sh
set -euo pipefail
cd "${0:A:h}"
SECRET=tafs-android/upload-key
REGION=${AWS_REGION:-eu-west-1}
export JAVA_HOME=${JAVA_HOME:-$(/usr/libexec/java_home -v 17 2>/dev/null || ls -d ~/opt/jdk-17*/Contents/Home 2>/dev/null || ls -d ~/opt/jdk-17* | head -1)}
export ANDROID_HOME=${ANDROID_HOME:-~/Library/Android/sdk}
dir=$(mktemp -d)
trap 'rm -rf "$dir"' EXIT
secret=$(aws secretsmanager get-secret-value --region "$REGION" --secret-id "$SECRET" --query SecretString --output text)
python3 -c 'import base64,json,sys; d=json.loads(sys.stdin.read()); open(sys.argv[1],"wb").write(base64.b64decode(d["keystore"]))' "$dir/upload.jks" <<<"$secret"
export TAFS_UPLOAD_KEYSTORE="$dir/upload.jks"
export TAFS_UPLOAD_STORE_PASSWORD=$(python3 -c 'import json,sys; print(json.loads(sys.stdin.read())["store_password"])' <<<"$secret")
export TAFS_UPLOAD_KEY_PASSWORD=$(python3 -c 'import json,sys; print(json.loads(sys.stdin.read())["key_password"])' <<<"$secret")
export TAFS_UPLOAD_KEY_ALIAS=$(python3 -c 'import json,sys; print(json.loads(sys.stdin.read()).get("alias","upload"))' <<<"$secret")
unset secret
[ -f app/google-services.json ] || echo "Note: no app/google-services.json, so this build has alerts off." >&2
./gradlew -q :app:bundleRelease
echo "Built: $PWD/app/build/outputs/bundle/release/app-release.aab"
