#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
VERIFY_SCRIPT="$ROOT_DIR/scripts/verify-play-aab.sh"
TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/quotadog-aab-verifier-test.XXXXXX")"
trap 'rm -rf "$TEMP_DIR"' EXIT

PASSWORD="quotadog-verifier-test"
ALIAS="quotadog-test"
KEYSTORE="$TEMP_DIR/upload.p12"
COMMIT="1234567890abcdef1234567890abcdef12345678"

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "Required command is unavailable: $1"
}

create_fixture() {
  local directory="$1"
  local include_identity="$2"

  mkdir -p "$directory/base/manifest" "$directory/base/assets"
  printf 'fixture\n' > "$directory/base/manifest/AndroidManifest.xml"
  if [[ "$include_identity" == "true" ]]; then
    cat > "$directory/base/assets/quotadog-build-identity.properties" <<EOF
formatVersion=1
packageName=saien.quotadog
versionName=0.2.0
versionCode=2
commit=$COMMIT
shortCommit=${COMMIT:0:12}
dirty=false
EOF
  fi
}

sign_fixture() {
  local directory="$1"
  local output="$2"

  (
    cd "$directory"
    zip -qr "$output" base
  )
  jarsigner \
    -keystore "$KEYSTORE" \
    -storepass:env QUOTADOG_KEYSTORE_PASSWORD \
    -keypass:env QUOTADOG_KEY_PASSWORD \
    "$output" "$ALIAS" >/dev/null
}

verify_fixture() {
  QUOTADOG_KEYSTORE_PATH="$KEYSTORE" \
    QUOTADOG_KEYSTORE_PASSWORD="$PASSWORD" \
    QUOTADOG_KEY_ALIAS="$ALIAS" \
    "$VERIFY_SCRIPT" --json "$1"
}

for command in keytool jarsigner zip; do
  require_command "$command"
done

export QUOTADOG_KEYSTORE_PASSWORD="$PASSWORD"
export QUOTADOG_KEY_PASSWORD="$PASSWORD"
keytool -genkeypair \
  -alias "$ALIAS" \
  -keyalg RSA \
  -keysize 2048 \
  -validity 1 \
  -dname "CN=QuotaDog verifier test" \
  -storetype PKCS12 \
  -keystore "$KEYSTORE" \
  -storepass:env QUOTADOG_KEYSTORE_PASSWORD \
  -keypass:env QUOTADOG_KEY_PASSWORD >/dev/null 2>&1

SIGNED_SOURCE="$TEMP_DIR/signed-source"
SIGNED_AAB="$TEMP_DIR/signed.aab"
create_fixture "$SIGNED_SOURCE" true
sign_fixture "$SIGNED_SOURCE" "$SIGNED_AAB"
verify_fixture "$SIGNED_AAB" >/dev/null

UNSIGNED_SOURCE="$TEMP_DIR/unsigned-source"
TAMPERED_AAB="$TEMP_DIR/tampered.aab"
create_fixture "$UNSIGNED_SOURCE" false
sign_fixture "$UNSIGNED_SOURCE" "$TAMPERED_AAB"
(
  cd "$SIGNED_SOURCE"
  zip -q "$TAMPERED_AAB" base/assets/quotadog-build-identity.properties
)
if verify_fixture "$TAMPERED_AAB" >"$TEMP_DIR/unexpected.stdout" 2>"$TEMP_DIR/expected.stderr"; then
  fail "A provenance entry appended after signing was accepted."
fi

printf 'Play AAB verifier contract tests passed.\n'
