#!/usr/bin/env bash
set -euo pipefail

# Build a Developer ID–signed QuotaDog.app via Compose Desktop and copy it to releases/.
# Reuses the same Developer ID Application certificate as Saytive.

die() {
  echo "Error: $*" >&2
  exit 1
}

detail() {
  printf '   %-12s %s\n' "$1" "$2"
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
GRADLE_APP_PATH="$ROOT_DIR/composeApp/build/compose/binaries/main/app/$APP_NAME"
OUTPUT_APP_PATH="$RELEASES_DIR/$APP_NAME"
SKIP_CODESIGN="${SKIP_CODESIGN:-0}"

read_version_name() {
  if [[ -n "${RELEASE_VERSION:-}" ]]; then
    printf '%s\n' "$RELEASE_VERSION"
    return 0
  fi
  local from_file
  from_file="$(awk -F= '/^VERSION_NAME=/{print $2; exit}' "$ROOT_DIR/version.properties" 2>/dev/null || true)"
  printf '%s\n' "${from_file:-1.0.0}"
}

GIT_BUILD_INFO="$(bash "$ROOT_DIR/scripts/git_build_info.sh" "$ROOT_DIR" 2>/dev/null || true)"
VERSION_STR="$(read_version_name)"

echo ""
echo "🏗️  Starting Release app build..."
detail "Version:" "$VERSION_STR"
if [[ -n "${GIT_BUILD_INFO:-}" ]]; then
  detail "Git info:" "$GIT_BUILD_INFO"
fi
detail "Output:" "$OUTPUT_APP_PATH"

GRADLE_ARGS=(
  :composeApp:createDistributable
  --stacktrace
)

if [[ "$SKIP_CODESIGN" == "1" ]]; then
  detail "Codesign:" "skip"
  export QUOTADOG_MAC_SIGN=0
else
  CODESIGN_IDENTITY="$(resolve_codesign_identity)"
  [[ -n "${CODESIGN_IDENTITY:-}" ]] || die "CODESIGN_IDENTITY not set and no 'Developer ID Application' identity found in keychain."
  export CODESIGN_IDENTITY
  export QUOTADOG_MAC_SIGN=1
  detail "Codesign:" "$CODESIGN_IDENTITY"
  GRADLE_ARGS+=(
    -Pcompose.desktop.mac.sign=true
    -Pcompose.desktop.mac.signing.identity="$CODESIGN_IDENTITY"
  )
fi

echo ""
echo "📦 Packaging Compose Desktop app..."
(
  cd "$ROOT_DIR"
  export RELEASE_VERSION="$VERSION_STR"
  ./gradlew "${GRADLE_ARGS[@]}"
)

[[ -d "$GRADLE_APP_PATH" ]] || die "Release app not found at: $GRADLE_APP_PATH"

mkdir -p "$RELEASES_DIR"
rm -rf "$OUTPUT_APP_PATH"
cp -R "$GRADLE_APP_PATH" "$OUTPUT_APP_PATH"

if [[ "$SKIP_CODESIGN" != "1" ]]; then
  echo ""
  echo "🔐 Verifying app signature..."
  codesign --verify --deep --strict --verbose=2 "$OUTPUT_APP_PATH"
fi

echo ""
echo "✅ Release app ready: $APP_NAME"
detail "Location:" "$OUTPUT_APP_PATH"
