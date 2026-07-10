#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Build a Release .app, package it into a .dmg, codesign, notarize, and staple.

Mirrors Saytive's release DMG flow and reuses the same Developer ID certificate
plus the `saytive-notary` keychain profile by default.

Usage:
  ./scripts/build_release_dmg.sh [--skip-build] [--skip-codesign] [--skip-notarize]

Env vars:
  DMG_NAME            Output dmg base name (default: QuotaDog)
  DMG_VOLNAME         Finder volume name (default: QuotaDog)
  RELEASE_VERSION     App/package version (default: 1.0.0)
  CODESIGN_IDENTITY   Developer ID Application identity
                      (if unset, auto-detect from keychain)

  # Notarization (choose one):
  NOTARY_KEY_PATH     Path to AuthKey_*.p8
  NOTARY_KEY_ID       Key ID
  NOTARY_ISSUER       Issuer ID (UUID)

  NOTARY_PROFILE      Keychain profile name created via:
                      xcrun notarytool store-credentials <name> ...
                      (default: saytive-notary — shared with Saytive)

Examples:
  ./scripts/build_release_dmg.sh
  NOTARY_PROFILE=saytive-notary ./scripts/build_release_dmg.sh
  ./scripts/build_release_dmg.sh --skip-notarize
  ./scripts/build_release_dmg.sh --skip-codesign --skip-notarize
EOF
}

SKIP_BUILD=0
SKIP_CODESIGN=0
SKIP_NOTARIZE=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--help)
      usage
      exit 0
      ;;
    --skip-build)
      SKIP_BUILD=1
      ;;
    --skip-codesign)
      SKIP_CODESIGN=1
      ;;
    --skip-notarize)
      SKIP_NOTARIZE=1
      ;;
    *)
      echo "Unknown arg: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
  shift
done

die() {
  echo "Error: $*" >&2
  exit 1
}

detail() {
  printf '   %-12s %s\n' "$1" "$2"
}

section() {
  echo ""
  echo "$1"
}

resolve_codesign_identity() {
  if [[ -n "${CODESIGN_IDENTITY:-}" ]]; then
    printf '%s\n' "$CODESIGN_IDENTITY"
    return 0
  fi
  local identities
  identities="$(security find-identity -p codesigning -v 2>/dev/null || true)"
  awk -F\\\" '/Developer ID Application/{print $2; exit}' <<<"$identities"
}

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RELEASES_DIR="$ROOT_DIR/releases"
APP_NAME="QuotaDog.app"
APP_PATH="$RELEASES_DIR/$APP_NAME"

DMG_NAME="${DMG_NAME:-QuotaDog}"
DMG_VOLNAME="${DMG_VOLNAME:-QuotaDog}"
NOTARY_PROFILE="${NOTARY_PROFILE:-saytive-notary}"

read_version_name() {
  if [[ -n "${RELEASE_VERSION:-}" ]]; then
    printf '%s\n' "$RELEASE_VERSION"
    return 0
  fi
  local from_file
  from_file="$(awk -F= '/^VERSION_NAME=/{print $2; exit}' "$ROOT_DIR/version.properties" 2>/dev/null || true)"
  printf '%s\n' "${from_file:-1.0.0}"
}

VERSION_STR="$(read_version_name)"
GIT_BUILD_INFO="$(bash "$ROOT_DIR/scripts/git_build_info.sh" "$ROOT_DIR" 2>/dev/null || true)"
DMG_NAME_WITH_METADATA="$DMG_NAME"
if [[ -n "${VERSION_STR:-}" && -n "${GIT_BUILD_INFO:-}" ]]; then
  DMG_NAME_WITH_METADATA="$DMG_NAME-${VERSION_STR}-${GIT_BUILD_INFO}"
elif [[ -n "${VERSION_STR:-}" ]]; then
  DMG_NAME_WITH_METADATA="$DMG_NAME-${VERSION_STR}"
fi

BUILD_MODE="run"
[[ "$SKIP_BUILD" == "1" ]] && BUILD_MODE="skip"

CODESIGN_MODE="run"
[[ "$SKIP_CODESIGN" == "1" ]] && CODESIGN_MODE="skip"

NOTARIZE_MODE="run"
[[ "$SKIP_NOTARIZE" == "1" ]] && NOTARIZE_MODE="skip"

section "💿 Starting release DMG build..."
detail "DMG:" "$DMG_NAME_WITH_METADATA.dmg"
detail "Build:" "$BUILD_MODE"
detail "Codesign:" "$CODESIGN_MODE"
detail "Notarize:" "$NOTARIZE_MODE"

if [[ "$SKIP_BUILD" != "1" ]]; then
  SKIP_CODESIGN="$SKIP_CODESIGN" \
    CODESIGN_IDENTITY="${CODESIGN_IDENTITY:-}" \
    RELEASE_VERSION="$VERSION_STR" \
    "$ROOT_DIR/scripts/build_release.sh"
else
  section "⏭️  Skipping app build..."
fi

if [[ ! -d "$APP_PATH" ]]; then
  die "Release app not found at: $APP_PATH (did build_release.sh run?)"
fi

section "🔐 Verifying app signature..."
if [[ "$SKIP_CODESIGN" == "1" ]]; then
  echo "   (unsigned build; deep verify skipped)"
else
  codesign --verify --deep --strict --verbose=2 "$APP_PATH"
fi

if [[ "$SKIP_NOTARIZE" != "1" ]]; then
  # Notarization requires Developer ID signing.
  CODESIGN_INFO="$(codesign -dv --verbose=4 "$APP_PATH" 2>&1)" || die "Failed to read app signing information."
  AUTHORITY="$(awk -F= '/^Authority=/{print $2; exit}' <<<"$CODESIGN_INFO")"
  if [[ "${AUTHORITY:-}" != Developer\ ID\ Application* ]]; then
    die "App is not signed with a Developer ID Application certificate (found: ${AUTHORITY:-unknown})."
  fi
fi

TMP_DMG_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/quotadog-dmgroot.XXXXXX")"
MOUNT_POINT=""
cleanup() {
  if [[ -n "${MOUNT_POINT:-}" && -d "$MOUNT_POINT" ]]; then
    hdiutil detach "$MOUNT_POINT" >/dev/null 2>&1 || true
  fi
  rm -rf "$TMP_DMG_ROOT"
}
trap cleanup EXIT

section "📂 Preparing DMG staging folder..."
cp -R "$APP_PATH" "$TMP_DMG_ROOT/"
ln -s /Applications "$TMP_DMG_ROOT/Applications"

mkdir -p "$RELEASES_DIR"
DMG_PATH="$RELEASES_DIR/$DMG_NAME_WITH_METADATA.dmg"
rm -f "$DMG_PATH"

section "💿 Creating DMG..."
hdiutil create \
  -volname "$DMG_VOLNAME" \
  -srcfolder "$TMP_DMG_ROOT" \
  -ov -format UDZO \
  "$DMG_PATH" \
  >/dev/null

if [[ "$SKIP_CODESIGN" != "1" ]]; then
  CODESIGN_IDENTITY="$(resolve_codesign_identity)"
  if [[ -z "${CODESIGN_IDENTITY:-}" ]]; then
    die "CODESIGN_IDENTITY not set and no 'Developer ID Application' identity found in keychain."
  fi

  section "✍️  Signing DMG..."
  codesign --force --timestamp --sign "$CODESIGN_IDENTITY" "$DMG_PATH"
else
  section "⏭️  Skipping DMG signing..."
fi

if [[ "$SKIP_NOTARIZE" != "1" ]]; then
  NOTARY_ARGS=()
  if [[ -n "${NOTARY_KEY_PATH:-}" || -n "${NOTARY_KEY_ID:-}" || -n "${NOTARY_ISSUER:-}" ]]; then
    [[ -n "${NOTARY_KEY_PATH:-}" && -n "${NOTARY_KEY_ID:-}" && -n "${NOTARY_ISSUER:-}" ]] || die "To use API key auth, set NOTARY_KEY_PATH, NOTARY_KEY_ID, and NOTARY_ISSUER."
    [[ -f "$NOTARY_KEY_PATH" ]] || die "NOTARY_KEY_PATH does not exist: $NOTARY_KEY_PATH"
    NOTARY_ARGS+=(--key "$NOTARY_KEY_PATH" --key-id "$NOTARY_KEY_ID" --issuer "$NOTARY_ISSUER")
  else
    NOTARY_ARGS+=(--keychain-profile "$NOTARY_PROFILE")
  fi

  section "🧾 Submitting for notarization..."
  NOTARY_SUBMIT_OUTPUT="$(xcrun notarytool submit "$DMG_PATH" "${NOTARY_ARGS[@]}" --wait --output-format json)" || die "Notarization submission failed."
  NOTARY_ID="$(plutil -extract id raw -o - - <<<"$NOTARY_SUBMIT_OUTPUT" 2>/dev/null || true)"
  NOTARY_STATUS="$(plutil -extract status raw -o - - <<<"$NOTARY_SUBMIT_OUTPUT" 2>/dev/null || true)"
  NOTARY_STATUS_SUMMARY="$(plutil -extract statusSummary raw -o - - <<<"$NOTARY_SUBMIT_OUTPUT" 2>/dev/null || true)"
  [[ -n "${NOTARY_ID:-}" ]] && detail "Request ID:" "$NOTARY_ID"
  [[ -n "${NOTARY_STATUS:-}" ]] && detail "Status:" "$NOTARY_STATUS"
  [[ -n "${NOTARY_STATUS_SUMMARY:-}" ]] && detail "Summary:" "$NOTARY_STATUS_SUMMARY"
  if [[ "${NOTARY_STATUS:-}" != "Accepted" ]]; then
    if [[ -n "${NOTARY_ID:-}" ]]; then
      echo "📄 Fetching notarization log..."
      xcrun notarytool log "$NOTARY_ID" "${NOTARY_ARGS[@]}" || true
    fi
    die "Notarization failed with status: ${NOTARY_STATUS:-unknown}${NOTARY_STATUS_SUMMARY:+ (${NOTARY_STATUS_SUMMARY})}."
  fi

  section "📌 Stapling DMG..."
  xcrun stapler staple "$DMG_PATH"

  section "🧪 Validating stapled DMG..."
  xcrun stapler validate "$DMG_PATH"

  section "🛡️  Assessing mounted app..."
  ATTACH_PLIST="$(hdiutil attach -plist -readonly -nobrowse "$DMG_PATH")" || die "Failed to mount DMG for validation."
  MOUNT_POINT="$(awk -F'[<>]' '/<key>mount-point<\/key>/{getline; print $3; exit}' <<<"$ATTACH_PLIST")"
  [[ -n "${MOUNT_POINT:-}" ]] || die "Failed to determine mounted DMG path."
  MOUNTED_APP_PATH="$MOUNT_POINT/$APP_NAME"
  [[ -d "$MOUNTED_APP_PATH" ]] || die "App not found inside mounted DMG at: $MOUNTED_APP_PATH"
  spctl -a -vv --type exec "$MOUNTED_APP_PATH"
  hdiutil detach "$MOUNT_POINT" >/dev/null
  MOUNT_POINT=""
else
  section "⏭️  Skipping notarization and stapling..."
fi

DMG_SIZE="$(du -h "$DMG_PATH" | cut -f1 | xargs)"

section "✅ Release DMG ready: $(basename "$DMG_PATH")"
detail "Size:" "$DMG_SIZE"
detail "Path:" "$DMG_PATH"

RELEASE_NOTES_PATH="${DMG_PATH%.dmg}.md"

if [[ ! -f "$RELEASE_NOTES_PATH" ]]; then
  cat >"$RELEASE_NOTES_PATH" <<EOF
# Release Notes

- TBD
EOF
fi

echo ""
echo "💡 Release notes markdown file:"
echo "$RELEASE_NOTES_PATH"
