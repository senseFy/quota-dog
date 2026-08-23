#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUTPUT_FORMAT="human"
if [[ "${1:-}" == "--json" ]]; then
  OUTPUT_FORMAT="json"
  shift
fi
AAB_PATH="${1:-$ROOT_DIR/androidApp/build/outputs/bundle/release/androidApp-release.aab}"
[[ $# -le 1 ]] || {
  printf 'Usage: %s [--json] [aab-path]\n' "$0" >&2
  exit 2
}
EXPECTED_PACKAGE="${ANDROID_PLAY_PACKAGE_NAME:-saien.quotadog}"
EXPECTED_VERSION_NAME="${ANDROID_VERSION_NAME:-}"
EXPECTED_VERSION_CODE="${ANDROID_VERSION_CODE:-}"
BUILD_IDENTITY_ENTRY="base/assets/quotadog-build-identity.properties"

die() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "Required command is unavailable: $1"
}

normalize_fingerprint() {
  tr -d '[:space:]:' | tr '[:lower:]' '[:upper:]'
}

read_build_identity_property() {
  local contents="$1"
  local key="$2"
  local value
  value="$(printf '%s\n' "$contents" | sed -n "s/^${key}=//p")"
  [[ "$(printf '%s\n' "$value" | wc -l | tr -d '[:space:]')" == "1" ]] ||
    die "The AAB build identity contains duplicate $key fields."
  printf '%s\n' "$value"
}

[[ -f "$AAB_PATH" ]] || die "Play AAB not found: $AAB_PATH"
[[ -n "${QUOTADOG_KEYSTORE_PATH:-}" && -f "$QUOTADOG_KEYSTORE_PATH" ]] ||
  die "QUOTADOG_KEYSTORE_PATH is missing or invalid."
[[ -n "${QUOTADOG_KEYSTORE_PASSWORD:-}" ]] || die "QUOTADOG_KEYSTORE_PASSWORD is missing."
[[ -n "${QUOTADOG_KEY_ALIAS:-}" ]] || die "QUOTADOG_KEY_ALIAS is missing."

require_command unzip
require_command jarsigner
require_command keytool
require_command jq

unzip -tqq "$AAB_PATH" || die "The AAB is not a valid ZIP archive."
unzip -Z1 "$AAB_PATH" | grep -Fx 'base/manifest/AndroidManifest.xml' >/dev/null ||
  die "The AAB does not contain the base manifest."
unzip -Z1 "$AAB_PATH" | grep -Fx "$BUILD_IDENTITY_ENTRY" >/dev/null ||
  die "The AAB does not contain embedded build identity."
if unzip -Z1 "$AAB_PATH" |
  grep -E '\.(jks|p12|p8)$|(^|/)(service-account|credentials)[^/]*\.json$' >/dev/null; then
  die "A private credential-like file is embedded in the AAB."
fi

jarsigner -verify -strict \
  -keystore "$QUOTADOG_KEYSTORE_PATH" \
  -storepass:env QUOTADOG_KEYSTORE_PASSWORD \
  "$AAB_PATH" >/dev/null ||
  die "JAR signature verification failed."

expected_fingerprint="$(
  keytool -list -v \
    -keystore "$QUOTADOG_KEYSTORE_PATH" \
    -storepass:env QUOTADOG_KEYSTORE_PASSWORD \
    -alias "$QUOTADOG_KEY_ALIAS" 2>/dev/null |
    awk -F': ' '/SHA256:/{print $2; exit}' |
    normalize_fingerprint
)"
actual_fingerprint="$(
  keytool -printcert -jarfile "$AAB_PATH" 2>/dev/null |
    awk -F': ' '/SHA256:/{print $2; exit}' |
    normalize_fingerprint
)"
[[ -n "$expected_fingerprint" && "$actual_fingerprint" == "$expected_fingerprint" ]] ||
  die "The AAB is not signed by the configured QuotaDog upload key."

build_identity="$(unzip -p "$AAB_PATH" "$BUILD_IDENTITY_ENTRY")"
actual_format_version="$(read_build_identity_property "$build_identity" formatVersion)"
actual_package="$(read_build_identity_property "$build_identity" packageName)"
actual_version_name="$(read_build_identity_property "$build_identity" versionName)"
actual_version_code="$(read_build_identity_property "$build_identity" versionCode)"
actual_build_commit="$(read_build_identity_property "$build_identity" commit)"
actual_build_commit_short="$(read_build_identity_property "$build_identity" shortCommit)"
actual_build_dirty="$(read_build_identity_property "$build_identity" dirty)"
expected_build_commit="${ANDROID_EXPECTED_BUILD_COMMIT:-}"
expected_build_commit_short="${ANDROID_EXPECTED_BUILD_COMMIT_SHORT:-}"
expected_build_dirty="${ANDROID_EXPECTED_BUILD_DIRTY:-}"

[[ "$actual_format_version" == "1" ]] ||
  die "The AAB contains an unsupported build identity format."
[[ "$actual_package" =~ ^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$ ]] ||
  die "The AAB build identity contains an invalid package name."
[[ -n "$actual_version_name" ]] ||
  die "The AAB build identity does not contain a version name."
[[ "$actual_version_code" =~ ^[1-9][0-9]*$ ]] ||
  die "The AAB build identity contains an invalid version code."
[[ "$actual_build_commit" =~ ^[0-9a-f]{40}$ ]] ||
  die "The AAB does not contain a valid full source commit."
[[ "$actual_build_commit_short" == "${actual_build_commit:0:12}" ]] ||
  die "The AAB build identity contains an inconsistent short source commit."
[[ "$actual_build_dirty" == "false" ]] ||
  die "The AAB was built from dirty source."
if [[ -n "$expected_build_commit" ]]; then
  [[ "$expected_build_commit" =~ ^[0-9a-f]{40}$ ]] ||
    die "ANDROID_EXPECTED_BUILD_COMMIT is invalid."
  [[ "$actual_build_commit" == "$expected_build_commit" ]] ||
    die "The AAB source commit does not match the expected release source."
fi
if [[ -n "$expected_build_commit_short" ]]; then
  [[ "$expected_build_commit_short" =~ ^[0-9a-f]{12}$ ]] ||
    die "ANDROID_EXPECTED_BUILD_COMMIT_SHORT is invalid."
  [[ "$actual_build_commit_short" == "$expected_build_commit_short" ]] ||
    die "The AAB short source commit does not match the expected release source."
fi
if [[ -n "$expected_build_dirty" ]]; then
  [[ "$expected_build_dirty" == "false" ]] ||
    die "ANDROID_EXPECTED_BUILD_DIRTY must be false for a release artifact."
fi

[[ "$actual_package" == "$EXPECTED_PACKAGE" ]] ||
  die "Unexpected package name: $actual_package"
if [[ -n "$EXPECTED_VERSION_NAME" ]]; then
  [[ "$actual_version_name" == "$EXPECTED_VERSION_NAME" ]] ||
    die "Unexpected versionName: $actual_version_name"
fi
if [[ -n "$EXPECTED_VERSION_CODE" ]]; then
  [[ "$actual_version_code" == "$EXPECTED_VERSION_CODE" ]] ||
    die "Unexpected versionCode: $actual_version_code"
fi

if [[ "$OUTPUT_FORMAT" == "json" ]]; then
  jq -cn \
    --arg packageName "$actual_package" \
    --arg versionName "$actual_version_name" \
    --argjson versionCode "$actual_version_code" \
    --arg commit "$actual_build_commit" \
    --arg shortCommit "$actual_build_commit_short" \
    --argjson dirty "$actual_build_dirty" \
    '{
      packageName: $packageName,
      versionName: $versionName,
      versionCode: $versionCode,
      commit: $commit,
      shortCommit: $shortCommit,
      dirty: $dirty
    }'
else
  printf 'Google Play AAB verified.\n'
  printf '  Package: %s\n' "$actual_package"
  printf '  Version: %s (%s)\n' "$actual_version_name" "$actual_version_code"
  printf '  Source: %s\n' "$actual_build_commit"
  printf '  Signing: QuotaDog upload key\n'
fi
