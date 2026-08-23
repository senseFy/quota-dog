#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_IDENTITY_HELPER="$ROOT_DIR/scripts/lib/build-identity.sh"
RELEASE_ENV="${ANDROID_RELEASE_ENV:-$HOME/.config/quotadog/android-release.env}"
AAB_PATH="${ANDROID_PLAY_AAB_PATH:-$ROOT_DIR/androidApp/build/outputs/bundle/release/androidApp-release.aab}"
PACKAGE_NAME="${ANDROID_PLAY_PACKAGE_NAME:-saien.quotadog}"
MODE="${1:-build}"

die() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

is_truthy() {
  case "${1:-}" in
    yes|YES|y|Y|true|TRUE|1) return 0 ;;
    *) return 1 ;;
  esac
}

require_command() {
  command -v "$1" >/dev/null 2>&1 ||
    die "Required command is unavailable: $1"
}

# shellcheck source=scripts/lib/build-identity.sh
source "$BUILD_IDENTITY_HELPER"

load_release_environment() {
  local credential_exports=""

  if [[ -f "$RELEASE_ENV" ]]; then
    credential_exports="$({
      set -a
      # shellcheck disable=SC1090
      source "$RELEASE_ENV" >/dev/null || exit 1
      set +a
      for name in \
        QUOTADOG_KEYSTORE_PATH \
        QUOTADOG_KEYSTORE_PASSWORD \
        QUOTADOG_KEY_ALIAS \
        QUOTADOG_KEY_PASSWORD \
        SAIEN_KEYSTORE_PATH \
        SAIEN_KEYSTORE_PASSWORD \
        SAIEN_KEY_ALIAS \
        SAIEN_KEY_PASSWORD \
        GOOGLE_PLAY_ACCESS_TOKEN \
        ANDROID_PLAY_SERVICE_ACCOUNT_JSON \
        GOOGLE_PLAY_SERVICE_ACCOUNT_JSON; do
        if [[ "${!name+x}" == x ]]; then
          builtin printf 'export %s=%q\n' "$name" "${!name}"
        fi
      done
    })" || die "Android release environment is invalid: $RELEASE_ENV"
  fi

  # The file is evaluated in an isolated shell. Only signing and publisher
  # credentials cross this boundary; mode, artifact, and rollout policy stay
  # authoritative at the invocation site.
  eval "$credential_exports"

  # Preserve the legacy signing variables already supported by Gradle.
  export QUOTADOG_KEYSTORE_PATH="${QUOTADOG_KEYSTORE_PATH:-${SAIEN_KEYSTORE_PATH:-}}"
  export QUOTADOG_KEYSTORE_PASSWORD="${QUOTADOG_KEYSTORE_PASSWORD:-${SAIEN_KEYSTORE_PASSWORD:-}}"
  export QUOTADOG_KEY_ALIAS="${QUOTADOG_KEY_ALIAS:-${SAIEN_KEY_ALIAS:-}}"
  export QUOTADOG_KEY_PASSWORD="${QUOTADOG_KEY_PASSWORD:-${SAIEN_KEY_PASSWORD:-}}"
}

read_version_code() {
  "$ROOT_DIR/scripts/android-version.sh" show | sed -n 's/.*(\([0-9][0-9]*\))$/\1/p'
}

read_version_name() {
  "$ROOT_DIR/scripts/android-version.sh" show | sed -n 's/^\([^[:space:]]*\).*/\1/p'
}

preflight() {
  local required=(
    QUOTADOG_KEYSTORE_PATH
    QUOTADOG_KEYSTORE_PASSWORD
    QUOTADOG_KEY_ALIAS
    QUOTADOG_KEY_PASSWORD
  )
  local name
  local credentials_path="${ANDROID_PLAY_SERVICE_ACCOUNT_JSON:-${GOOGLE_PLAY_SERVICE_ACCOUNT_JSON:-}}"

  require_command keytool
  require_command jarsigner
  require_command unzip
  require_command jq

  if [[ "$MODE" != "upload-existing" ]]; then
    quotadog_build_identity_assert_release "$ROOT_DIR" ||
      die "Android release builds require immutable source provenance."
  fi

  for name in "${required[@]}"; do
    [[ -n "${!name:-}" ]] ||
      die "$name is missing; export it or add it to $RELEASE_ENV"
  done
  [[ -f "$QUOTADOG_KEYSTORE_PATH" ]] ||
    die "QuotaDog upload keystore not found: $QUOTADOG_KEYSTORE_PATH"
  keytool -list \
    -keystore "$QUOTADOG_KEYSTORE_PATH" \
    -storepass:env QUOTADOG_KEYSTORE_PASSWORD \
    -alias "$QUOTADOG_KEY_ALIAS" \
    >/dev/null 2>&1 ||
    die "QuotaDog upload keystore or credentials are invalid."

  if [[ "$MODE" == "check-upload" || "$MODE" == "upload" ||
    "$MODE" == "upload-existing" ]] &&
    ! is_truthy "${ANDROID_PLAY_DRY_RUN:-}"; then
    require_command jq
    require_command "${CURL_BIN:-curl}"
    require_command openssl
    [[ -n "${GOOGLE_PLAY_ACCESS_TOKEN:-}" ||
      ( -n "$credentials_path" && -f "$credentials_path" ) ]] ||
      die "Google Play publisher credentials are missing."
  fi
}

print_summary() {
  printf 'QuotaDog Android release\n'
  printf '  Mode: %s\n' "$MODE"
  printf '  Package: %s\n' "$PACKAGE_NAME"
  if [[ "$MODE" != "upload-existing" ]]; then
    printf '  Version: %s (%s)\n' "$(read_version_name)" "$(read_version_code)"
    printf '  Source: %s\n' "$QUOTADOG_BUILD_COMMIT"
  fi
  printf '  AAB: %s\n' "$AAB_PATH"
}

case "$MODE" in
  check|check-upload|build|upload|upload-existing) ;;
  *)
    echo "Usage: $0 check | check-upload | build | upload | upload-existing" >&2
    exit 2
    ;;
esac

load_release_environment
preflight
print_summary

if [[ "$MODE" == "check" || "$MODE" == "check-upload" ]]; then
  printf '  Status: ready\n'
  exit 0
fi

if [[ "$MODE" == "build" || "$MODE" == "upload" ]]; then
  (
    cd "$ROOT_DIR"
    ./gradlew :androidApp:bundleRelease
  )
fi

[[ -f "$AAB_PATH" ]] || die "Signed AAB was not produced: $AAB_PATH"
export ANDROID_PLAY_PACKAGE_NAME="$PACKAGE_NAME"
if [[ "$MODE" != "upload-existing" ]]; then
  export ANDROID_VERSION_NAME="$(read_version_name)"
  export ANDROID_VERSION_CODE="$(read_version_code)"
  export ANDROID_EXPECTED_BUILD_COMMIT="$QUOTADOG_BUILD_COMMIT"
  export ANDROID_EXPECTED_BUILD_COMMIT_SHORT="$QUOTADOG_BUILD_COMMIT_SHORT"
  export ANDROID_EXPECTED_BUILD_DIRTY="$QUOTADOG_BUILD_DIRTY"
fi

if [[ "$MODE" == "upload" || "$MODE" == "upload-existing" ]]; then
  upload_args=(
    --aab "$AAB_PATH"
    --package-name "$PACKAGE_NAME"
    --track "${ANDROID_PLAY_TRACK:-internal}"
    --status "${ANDROID_PLAY_RELEASE_STATUS:-completed}"
  )
  [[ -z "${ANDROID_PLAY_RELEASE_NAME:-}" ]] ||
    upload_args+=(--release-name "$ANDROID_PLAY_RELEASE_NAME")
  [[ -z "${ANDROID_PLAY_RELEASE_NOTES:-}" ]] ||
    upload_args+=(--release-notes "$ANDROID_PLAY_RELEASE_NOTES")
  [[ -z "${ANDROID_PLAY_RELEASE_NOTES_LANGUAGE:-}" ]] ||
    upload_args+=(--release-notes-language "$ANDROID_PLAY_RELEASE_NOTES_LANGUAGE")
  [[ -z "${ANDROID_PLAY_USER_FRACTION:-}" ]] ||
    upload_args+=(--user-fraction "$ANDROID_PLAY_USER_FRACTION")
  case "${ANDROID_PLAY_CONFIRM_PRODUCTION:-}" in
    yes|YES|y|Y|true|TRUE|1) upload_args+=(--confirm-production) ;;
  esac
  case "${ANDROID_PLAY_DRY_RUN:-}" in
    yes|YES|y|Y|true|TRUE|1) upload_args+=(--dry-run) ;;
  esac
  "$ROOT_DIR/scripts/upload-google-play.sh" "${upload_args[@]}"
else
  "$ROOT_DIR/scripts/verify-play-aab.sh" "$AAB_PATH"
fi
