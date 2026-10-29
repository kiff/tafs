#!/bin/zsh
# Makes the app's Play upload key once and keeps it only in AWS Secrets Manager (tafs-android/upload-key):
# the keystore and its random passwords never touch the repository, a clipboard or a chat. It prints
# only the certificate's SHA-256 fingerprint, which is public (Play and Firebase ask for it).
# Run it yourself: ! android/make-upload-key.sh
set -euo pipefail
SECRET=tafs-android/upload-key
REGION=${AWS_REGION:-eu-west-1}
if aws secretsmanager describe-secret --region "$REGION" --secret-id "$SECRET" >/dev/null 2>&1; then
  echo "$SECRET exists already: keep it. A new upload key needs Play's upload key reset." >&2
  exit 1
fi
JAVA_HOME=${JAVA_HOME:-$(ls -d ~/opt/jdk-17*/Contents/Home 2>/dev/null || ls -d ~/opt/jdk-17* | head -1)}
dir=$(mktemp -d)
trap 'rm -rf "$dir"' EXIT
store=$(openssl rand -base64 30 | tr -d '/+=' | cut -c1-32)
"$JAVA_HOME/bin/keytool" -genkeypair -keystore "$dir/upload.jks" -storetype JKS -alias upload -keyalg RSA -keysize 4096 \
  -validity 10000 -storepass "$store" -keypass "$store" -dname "CN=Kiff Agents OU, O=Kiff Agents OU, C=EE" >/dev/null 2>&1
json=$(python3 -c 'import base64,json,sys; print(json.dumps({"keystore": base64.b64encode(open(sys.argv[1],"rb").read()).decode(), "store_password": sys.argv[2], "key_password": sys.argv[2], "alias": "upload"}))' "$dir/upload.jks" "$store")
fingerprint=$("$JAVA_HOME/bin/keytool" -list -v -keystore "$dir/upload.jks" -storepass "$store" 2>/dev/null | grep 'SHA256:')
aws secretsmanager create-secret --region "$REGION" --name "$SECRET" --description "tafs Android: Play upload key (keystore and passwords)" \
  --secret-string "$json" >/dev/null
unset store json
echo "Kept in $SECRET. The upload certificate's fingerprint:"
echo "$fingerprint"
