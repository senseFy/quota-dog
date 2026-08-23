#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
AAB_PATH="${ANDROID_PLAY_AAB_PATH:-$ROOT_DIR/androidApp/build/outputs/bundle/release/androidApp-release.aab}"
PACKAGE_NAME="${ANDROID_PLAY_PACKAGE_NAME:-saien.quotadog}"
TRACK="${ANDROID_PLAY_TRACK:-internal}"
RELEASE_STATUS="${ANDROID_PLAY_RELEASE_STATUS:-completed}"
RELEASE_NAME="${ANDROID_PLAY_RELEASE_NAME:-}"
RELEASE_NOTES="${ANDROID_PLAY_RELEASE_NOTES:-}"
RELEASE_NOTES_LANGUAGE="${ANDROID_PLAY_RELEASE_NOTES_LANGUAGE:-en-US}"
USER_FRACTION="${ANDROID_PLAY_USER_FRACTION:-}"
CREDENTIALS_PATH="${ANDROID_PLAY_SERVICE_ACCOUNT_JSON:-${GOOGLE_PLAY_SERVICE_ACCOUNT_JSON:-}}"
ACCESS_TOKEN="${GOOGLE_PLAY_ACCESS_TOKEN:-}"
VERIFY_SCRIPT="$ROOT_DIR/scripts/verify-play-aab.sh"
CURL_BIN="${CURL_BIN:-curl}"
UPLOAD_MAX_ATTEMPTS="${ANDROID_PLAY_UPLOAD_MAX_ATTEMPTS:-3}"

API_BASE="https://androidpublisher.googleapis.com/androidpublisher/v3"
UPLOAD_BASE="https://androidpublisher.googleapis.com/upload/androidpublisher/v3"
TOKEN_URL="https://oauth2.googleapis.com/token"
ANDROID_PUBLISHER_SCOPE="https://www.googleapis.com/auth/androidpublisher"

DRY_RUN=0
CONFIRM_PRODUCTION=0
TMP_DIR=""
EDIT_ID=""
EDIT_COMMITTED=0
CURL_HTTP_ARGS=(--http1.1)

usage() {
  cat <<'EOF'
Upload a verified QuotaDog Android App Bundle to Google Play.

Usage:
  ./scripts/upload-google-play.sh [options]

Options:
  --aab <path>                 Signed AAB path
  --package-name <id>         Package name (default: saien.quotadog)
  --track <track>             Release track (default: internal)
  --status <status>           completed, draft, or inProgress
  --release-name <name>       Play Console name (default: version/build/commit)
  --release-notes <text>      Optional localized release notes
  --release-notes-language <locale>
  --user-fraction <fraction>  Required for inProgress staged rollout
  --credentials <path>        Google service account JSON
  --confirm-production        Required for production
  --dry-run                   Verify locally without API calls
  --help                      Show this help
EOF
}

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

is_production_track() {
  [[ "$TRACK" == "production" || "$TRACK" == *":production" ]]
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "Required command is unavailable: $1"
}

base64url() {
  openssl base64 -A | tr '+/' '-_' | tr -d '='
}

print_error_response() {
  local response_file="$1"
  local message=""
  message="$(jq -r '.error.message // .error_description // empty' "$response_file" 2>/dev/null || true)"
  if [[ -n "$message" ]]; then
    printf '%s\n' "$message" >&2
  elif [[ -s "$response_file" ]]; then
    head -c 4000 "$response_file" >&2
    printf '\n' >&2
  fi
}

cleanup() {
  local exit_code=$?
  trap - EXIT
  if [[ -n "$EDIT_ID" && "$EDIT_COMMITTED" -eq 0 && -n "$ACCESS_TOKEN" ]]; then
    "$CURL_BIN" \
      "${CURL_HTTP_ARGS[@]}" \
      --silent \
      --request DELETE \
      --header "Authorization: Bearer $ACCESS_TOKEN" \
      "$API_BASE/applications/$PACKAGE_NAME/edits/$EDIT_ID" \
      >/dev/null 2>&1 || true
  fi
  if [[ -n "$TMP_DIR" ]]; then
    rm -rf "$TMP_DIR"
  fi
  exit "$exit_code"
}

mint_service_account_access_token() {
  local credentials_path="$1"
  local private_key_path="$TMP_DIR/service-account-private-key.pem"
  local response_file="$TMP_DIR/oauth-token-response.json"
  local client_email
  local issued_at
  local expires_at
  local header
  local claims
  local unsigned_token
  local signature
  local assertion
  local http_code

  client_email="$(
    jq -er 'select(.type == "service_account") | .client_email | select(length > 0)' \
      "$credentials_path"
  )" || die "Credentials must be a Google service account JSON file."
  jq -er '.private_key | select(length > 0)' "$credentials_path" > "$private_key_path" ||
    die "Service account JSON does not contain a private key."
  chmod 600 "$private_key_path"

  issued_at="$(date +%s)"
  expires_at="$((issued_at + 3600))"
  header="$(printf '%s' '{"alg":"RS256","typ":"JWT"}' | base64url)"
  claims="$(
    jq -cn \
      --arg iss "$client_email" \
      --arg scope "$ANDROID_PUBLISHER_SCOPE" \
      --arg aud "$TOKEN_URL" \
      --argjson iat "$issued_at" \
      --argjson exp "$expires_at" \
      '{iss: $iss, scope: $scope, aud: $aud, iat: $iat, exp: $exp}'
  )"
  unsigned_token="$header.$(printf '%s' "$claims" | base64url)"
  signature="$(
    printf '%s' "$unsigned_token" |
      openssl dgst -sha256 -sign "$private_key_path" -binary |
      base64url
  )" || die "Unable to sign the service account OAuth assertion."
  assertion="$unsigned_token.$signature"

  printf 'Authenticating Google Play publisher.\n' >&2
  if ! http_code="$(
    "$CURL_BIN" \
      "${CURL_HTTP_ARGS[@]}" \
      --silent \
      --show-error \
      --connect-timeout 20 \
      --max-time 60 \
      --output "$response_file" \
      --write-out '%{http_code}' \
      --request POST \
      --header 'Content-Type: application/x-www-form-urlencoded' \
      --data-urlencode 'grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer' \
      --data-urlencode "assertion=$assertion" \
      "$TOKEN_URL"
  )"; then
    die "Unable to request a Google OAuth access token."
  fi
  if [[ "$http_code" != 2* ]]; then
    printf 'Google OAuth token request failed (HTTP %s): ' "$http_code" >&2
    print_error_response "$response_file"
    exit 1
  fi
  jq -er '.access_token | select(length > 0)' "$response_file" ||
    die "Google OAuth response did not contain an access token."
}

api_request() {
  local method="$1"
  local url="$2"
  local response_file="$3"
  local label="$4"
  local body_file="${5:-}"
  local http_code
  local args=(
    "${CURL_HTTP_ARGS[@]}"
    --silent
    --show-error
    --connect-timeout 20
    --max-time 180
    --output "$response_file"
    --write-out '%{http_code}'
    --request "$method"
    --header "Authorization: Bearer $ACCESS_TOKEN"
  )

  if [[ -n "$body_file" ]]; then
    args+=(--header 'Content-Type: application/json; charset=UTF-8' --data-binary "@$body_file")
  elif [[ "$method" == "POST" || "$method" == "PUT" || "$method" == "PATCH" ]]; then
    args+=(--header 'Content-Type: application/json; charset=UTF-8' --data-binary '')
  fi

  if ! http_code="$("$CURL_BIN" "${args[@]}" "$url")"; then
    die "$label failed before receiving an HTTP response."
  fi
  if [[ "$http_code" != 2* ]]; then
    printf '%s failed (HTTP %s): ' "$label" "$http_code" >&2
    print_error_response "$response_file"
    exit 1
  fi
}

upload_bundle() {
  local response_file="$1"
  local upload_url="$UPLOAD_BASE/applications/$PACKAGE_NAME/edits/$EDIT_ID/bundles?uploadType=media"
  local attempt=1
  local http_code

  [[ "$UPLOAD_MAX_ATTEMPTS" =~ ^[1-9][0-9]*$ ]] ||
    die "ANDROID_PLAY_UPLOAD_MAX_ATTEMPTS must be a positive integer."

  while (( attempt <= UPLOAD_MAX_ATTEMPTS )); do
    : > "$response_file"
    if http_code="$(
      "$CURL_BIN" \
        "${CURL_HTTP_ARGS[@]}" \
        --silent \
        --show-error \
        --connect-timeout 30 \
        --max-time 900 \
        --output "$response_file" \
        --write-out '%{http_code}' \
        --request POST \
        --header "Authorization: Bearer $ACCESS_TOKEN" \
        --header 'Content-Type: application/octet-stream' \
        --upload-file "$AAB_PATH" \
        "$upload_url"
    )"; then
      if [[ "$http_code" == 2* ]]; then
        return 0
      fi
      if [[ "$http_code" == 408 || "$http_code" == 429 || "$http_code" == 5* ]] &&
        (( attempt < UPLOAD_MAX_ATTEMPTS )); then
        printf 'AAB upload returned HTTP %s (%s/%s); retrying.\n' \
          "$http_code" "$attempt" "$UPLOAD_MAX_ATTEMPTS" >&2
      else
        printf 'AAB upload failed (HTTP %s): ' "$http_code" >&2
        print_error_response "$response_file"
        exit 1
      fi
    elif (( attempt >= UPLOAD_MAX_ATTEMPTS )); then
      die "AAB upload failed before receiving an HTTP response."
    else
      printf 'AAB upload transport error (%s/%s); retrying.\n' \
        "$attempt" "$UPLOAD_MAX_ATTEMPTS" >&2
    fi
    sleep "$((attempt * 2))"
    attempt=$((attempt + 1))
  done
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --aab) AAB_PATH="${2:-}"; shift 2 ;;
    --package-name) PACKAGE_NAME="${2:-}"; shift 2 ;;
    --track) TRACK="${2:-}"; shift 2 ;;
    --status) RELEASE_STATUS="${2:-}"; shift 2 ;;
    --release-name) RELEASE_NAME="${2:-}"; shift 2 ;;
    --release-notes) RELEASE_NOTES="${2:-}"; shift 2 ;;
    --release-notes-language) RELEASE_NOTES_LANGUAGE="${2:-}"; shift 2 ;;
    --user-fraction) USER_FRACTION="${2:-}"; shift 2 ;;
    --credentials) CREDENTIALS_PATH="${2:-}"; shift 2 ;;
    --confirm-production) CONFIRM_PRODUCTION=1; shift ;;
    --dry-run) DRY_RUN=1; shift ;;
    --help|-h) usage; exit 0 ;;
    *)
      printf 'Unknown option: %s\n' "$1" >&2
      usage
      exit 2
      ;;
  esac
done

is_truthy "${ANDROID_PLAY_DRY_RUN:-}" && DRY_RUN=1
is_truthy "${ANDROID_PLAY_CONFIRM_PRODUCTION:-}" && CONFIRM_PRODUCTION=1

[[ -f "$AAB_PATH" ]] || die "Play AAB not found: $AAB_PATH"
[[ "$PACKAGE_NAME" =~ ^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$ ]] ||
  die "Invalid Android package name: $PACKAGE_NAME"
[[ "$TRACK" =~ ^[A-Za-z0-9._:-]+$ ]] || die "Invalid Google Play track: $TRACK"
case "$RELEASE_STATUS" in
  completed|draft) [[ -z "$USER_FRACTION" ]] || die "userFraction requires inProgress status." ;;
  inProgress)
    [[ -n "$USER_FRACTION" ]] || die "inProgress status requires --user-fraction."
    awk -v value="$USER_FRACTION" 'BEGIN { exit !(value > 0 && value < 1) }' ||
      die "userFraction must be greater than 0 and less than 1."
    ;;
  *) die "Release status must be completed, draft, or inProgress." ;;
esac
if is_production_track && [[ "$DRY_RUN" -eq 0 && "$CONFIRM_PRODUCTION" -ne 1 ]]; then
  die "Production upload requires --confirm-production."
fi

printf 'Verifying Google Play App Bundle.\n'
require_command jq
verification_json="$("$VERIFY_SCRIPT" --json "$AAB_PATH")"
verified_version_name="$(jq -er '.versionName' <<<"$verification_json")"
verified_version_code="$(jq -er '.versionCode | tostring' <<<"$verification_json")"
verified_commit="$(jq -er '.commit' <<<"$verification_json")"
verified_short_commit="$(jq -er '.shortCommit' <<<"$verification_json")"
if [[ -z "$RELEASE_NAME" ]]; then
  RELEASE_NAME="$verified_version_name ($verified_version_code) · $verified_short_commit"
fi
printf 'Google Play App Bundle verified.\n'
printf '  Version: %s (%s)\n' "$verified_version_name" "$verified_version_code"
printf '  Source: %s\n' "$verified_commit"
printf 'Google Play release plan:\n'
printf '  AAB: %s\n' "$AAB_PATH"
printf '  Package: %s\n' "$PACKAGE_NAME"
printf '  Track: %s\n' "$TRACK"
printf '  Status: %s\n' "$RELEASE_STATUS"
[[ -z "$USER_FRACTION" ]] || printf '  User fraction: %s\n' "$USER_FRACTION"
[[ -z "$RELEASE_NAME" ]] || printf '  Release name: %s\n' "$RELEASE_NAME"

if [[ "$DRY_RUN" -eq 1 ]]; then
  printf 'Dry run completed; no Google Play API calls were made.\n'
  exit 0
fi

require_command "$CURL_BIN"
require_command openssl
[[ -n "$ACCESS_TOKEN" || -f "$CREDENTIALS_PATH" ]] ||
  die "Google Play publisher credentials are missing."

TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/quotadog-google-play-upload.XXXXXX")"
trap cleanup EXIT
if [[ -z "$ACCESS_TOKEN" ]]; then
  ACCESS_TOKEN="$(mint_service_account_access_token "$CREDENTIALS_PATH")"
fi

CREATE_BODY="$TMP_DIR/create-edit.json"
CREATE_RESPONSE="$TMP_DIR/create-edit-response.json"
printf '{}\n' > "$CREATE_BODY"
printf 'Creating Google Play edit.\n'
api_request \
  POST \
  "$API_BASE/applications/$PACKAGE_NAME/edits" \
  "$CREATE_RESPONSE" \
  "Create edit" \
  "$CREATE_BODY"
EDIT_ID="$(jq -er '.id | select(length > 0)' "$CREATE_RESPONSE")" ||
  die "Google Play did not return an edit ID."

UPLOAD_RESPONSE="$TMP_DIR/upload-bundle-response.json"
printf 'Uploading Android App Bundle.\n'
upload_bundle "$UPLOAD_RESPONSE"
VERSION_CODE="$(jq -er '.versionCode | tostring | select(length > 0)' "$UPLOAD_RESPONSE")" ||
  die "Google Play did not return the uploaded versionCode."

TRACK_BODY="$TMP_DIR/update-track.json"
TRACK_RESPONSE="$TMP_DIR/update-track-response.json"
jq -n \
  --arg track "$TRACK" \
  --arg versionCode "$VERSION_CODE" \
  --arg status "$RELEASE_STATUS" \
  --arg name "$RELEASE_NAME" \
  --arg notes "$RELEASE_NOTES" \
  --arg language "$RELEASE_NOTES_LANGUAGE" \
  --arg fraction "$USER_FRACTION" \
  '{
    track: $track,
    releases: [
      (
        {versionCodes: [$versionCode], status: $status}
        + (if $name == "" then {} else {name: $name} end)
        + (if $notes == "" then {} else {releaseNotes: [{language: $language, text: $notes}]} end)
        + (if $fraction == "" then {} else {userFraction: ($fraction | tonumber)} end)
      )
    ]
  }' > "$TRACK_BODY"
printf 'Updating Google Play track %s.\n' "$TRACK"
api_request \
  PUT \
  "$API_BASE/applications/$PACKAGE_NAME/edits/$EDIT_ID/tracks/$TRACK" \
  "$TRACK_RESPONSE" \
  "Update track" \
  "$TRACK_BODY"

VALIDATE_RESPONSE="$TMP_DIR/validate-edit-response.json"
printf 'Validating Google Play edit.\n'
api_request \
  POST \
  "$API_BASE/applications/$PACKAGE_NAME/edits/$EDIT_ID:validate" \
  "$VALIDATE_RESPONSE" \
  "Validate edit"

COMMIT_RESPONSE="$TMP_DIR/commit-edit-response.json"
printf 'Committing Google Play edit.\n'
api_request \
  POST \
  "$API_BASE/applications/$PACKAGE_NAME/edits/$EDIT_ID:commit?changesInReviewBehavior=ERROR_IF_IN_REVIEW" \
  "$COMMIT_RESPONSE" \
  "Commit edit"
EDIT_COMMITTED=1

printf 'Google Play upload committed.\n'
printf '  Package: %s\n' "$PACKAGE_NAME"
printf '  Track: %s\n' "$TRACK"
printf '  Version code: %s\n' "$VERSION_CODE"
