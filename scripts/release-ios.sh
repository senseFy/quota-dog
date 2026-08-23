#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT="$ROOT_DIR/iosApp/iosApp.xcodeproj"
SCHEME="iosApp"
CONFIGURATION="Release"
CONFIG_PATH="$ROOT_DIR/iosApp/Configuration/Config.xcconfig"
VERSION_FILE="$ROOT_DIR/version.properties"
BUILD_IDENTITY_HELPER="$ROOT_DIR/scripts/lib/build-identity.sh"
PRODUCT_VERSION_HELPER="$ROOT_DIR/scripts/lib/product-version.sh"
ARCHIVE_IDENTITY_HELPER="$ROOT_DIR/scripts/lib/ios-archive-identity.sh"

# shellcheck source=lib/build-identity.sh
source "$BUILD_IDENTITY_HELPER"
# shellcheck source=lib/product-version.sh
source "$PRODUCT_VERSION_HELPER"
# shellcheck source=lib/ios-archive-identity.sh
source "$ARCHIVE_IDENTITY_HELPER"

TEAM_ID="${IOS_TEAM_ID:-}"
BUNDLE_ID="${IOS_BUNDLE_ID:-}"
PROFILE="${IOS_RELEASE_PROFILE:-}"
SIGNING_CERTIFICATE="${IOS_SIGNING_CERTIFICATE:-Apple Distribution}"
APP_STORE_CONNECT_APP_ID="${IOS_APP_STORE_CONNECT_APP_ID:-}"
ARCHIVE_PATH="${IOS_ARCHIVE_PATH:-}"
EXPORT_PATH="${IOS_EXPORT_PATH:-}"
EXPORT_PATH_PROVIDED=0
if [[ -n "$EXPORT_PATH" ]]; then
  EXPORT_PATH_PROVIDED=1
fi
BUILD_NUMBER="${IOS_BUILD_NUMBER:-}"
AUTH_KEY_PATH="${APP_STORE_CONNECT_API_KEY_PATH:-${ASC_KEY_PATH:-${EXPO_ASC_API_KEY_PATH:-}}}"
AUTH_KEY_ID="${APP_STORE_CONNECT_API_KEY_ID:-${ASC_KEY_ID:-${EXPO_ASC_KEY_ID:-}}}"
AUTH_KEY_ISSUER_ID="${APP_STORE_CONNECT_API_ISSUER_ID:-${ASC_ISSUER_ID:-${EXPO_ASC_ISSUER_ID:-}}}"

DO_ARCHIVE=1
DO_EXPORT=1
DESTINATION="export"
CHECK_ONLY=0
CLEAN=0
REUSE_EXISTING=0
VERBOSE=0
ARCHIVE_REUSED=0

usage() {
  cat <<'EOF'
Archive, export, or upload the QuotaDog iOS app.

Usage:
  ./scripts/release-ios.sh --team <team-id>
  ./scripts/release-ios.sh --team <team-id> --archive-only
  ./scripts/release-ios.sh --team <team-id> --export-only
  ./scripts/release-ios.sh --team <team-id> --upload

Options:
  --team <id>                     Apple Developer Team ID
  --bundle-id <id>                App bundle identifier
  --profile <name>                App Store provisioning profile name
  --signing-certificate <name>    Signing identity selector
  --archive-path <path>           Archive path
  --export-path <path>            IPA export directory
  --build-number <number>         Build number override
  --app-store-connect-app-id <id> App Store Connect application ID
  --auth-key-path <path>          App Store Connect API private key
  --auth-key-id <id>              App Store Connect API key ID
  --auth-key-issuer-id <id>       App Store Connect API issuer ID
  --archive-only                  Stop after archiving
  --export-only                   Use an existing archive
  --upload                        Upload to TestFlight
  --check                         Verify prerequisites without building
  --clean                         Replace outputs for the same version/build
  --reuse-existing                Verify and reuse a matching existing archive
  --verbose                       Print full xcodebuild output
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --team) TEAM_ID="${2:-}"; shift 2 ;;
    --bundle-id) BUNDLE_ID="${2:-}"; shift 2 ;;
    --profile) PROFILE="${2:-}"; shift 2 ;;
    --signing-certificate) SIGNING_CERTIFICATE="${2:-}"; shift 2 ;;
    --archive-path) ARCHIVE_PATH="${2:-}"; shift 2 ;;
    --export-path) EXPORT_PATH="${2:-}"; EXPORT_PATH_PROVIDED=1; shift 2 ;;
    --build-number) BUILD_NUMBER="${2:-}"; shift 2 ;;
    --app-store-connect-app-id) APP_STORE_CONNECT_APP_ID="${2:-}"; shift 2 ;;
    --auth-key-path) AUTH_KEY_PATH="${2:-}"; shift 2 ;;
    --auth-key-id) AUTH_KEY_ID="${2:-}"; shift 2 ;;
    --auth-key-issuer-id) AUTH_KEY_ISSUER_ID="${2:-}"; shift 2 ;;
    --archive-only) DO_ARCHIVE=1; DO_EXPORT=0; shift ;;
    --export-only) DO_ARCHIVE=0; DO_EXPORT=1; shift ;;
    --upload) DESTINATION="upload"; DO_EXPORT=1; shift ;;
    --check) CHECK_ONLY=1; shift ;;
    --clean) CLEAN=1; shift ;;
    --reuse-existing) REUSE_EXISTING=1; shift ;;
    --verbose) VERBOSE=1; shift ;;
    --help|-h) usage; exit 0 ;;
    *)
      echo "Unknown option: $1" >&2
      usage
      exit 2
      ;;
  esac
done

read_config() {
  sed -n "s/^$1=//p" "$CONFIG_PATH" | tail -1
}

TEAM_ID="${TEAM_ID:-$(read_config TEAM_ID)}"
BUNDLE_ID="${BUNDLE_ID:-$(read_config PRODUCT_BUNDLE_IDENTIFIER)}"
MARKETING_VERSION=""
RELEASE_DIR=""

if [[ "$DO_ARCHIVE" == 1 ]]; then
  MARKETING_VERSION="$(quotadog_product_version_read "$VERSION_FILE")"
  BUILD_NUMBER="${BUILD_NUMBER:-$("$ROOT_DIR/scripts/ios-version.sh" show-build)}"
  RELEASE_DIR="$ROOT_DIR/build/ios/TestFlight/${MARKETING_VERSION}-${BUILD_NUMBER}"
  ARCHIVE_PATH="${ARCHIVE_PATH:-$RELEASE_DIR/QuotaDog.xcarchive}"
elif [[ -z "$ARCHIVE_PATH" ]]; then
  lookup_version="$(quotadog_product_version_read "$VERSION_FILE")"
  lookup_build="${BUILD_NUMBER:-$("$ROOT_DIR/scripts/ios-version.sh" show-build)}"
  ARCHIVE_PATH="$ROOT_DIR/build/ios/TestFlight/${lookup_version}-${lookup_build}/QuotaDog.xcarchive"
fi

absolute_path() {
  case "$1" in
    /*) printf '%s\n' "$1" ;;
    *) printf '%s\n' "$(pwd)/$1" ;;
  esac
}

ARCHIVE_PATH="$(absolute_path "$ARCHIVE_PATH")"
if [[ "$EXPORT_PATH_PROVIDED" == 1 ]]; then
  EXPORT_PATH="$(absolute_path "$EXPORT_PATH")"
fi
if [[ -n "$AUTH_KEY_PATH" ]]; then
  AUTH_KEY_PATH="$(absolute_path "$AUTH_KEY_PATH")"
fi

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

if [[ "$CLEAN" == 1 && "$REUSE_EXISTING" == 1 ]]; then
  fail "--clean and --reuse-existing cannot be used together."
fi
if [[ "$DO_ARCHIVE" == 0 && "$REUSE_EXISTING" == 1 ]]; then
  fail "--reuse-existing requires an archive operation."
fi

detail() {
  printf '  %-12s %s\n' "$1" "$2"
}

find_profile() {
  local directory
  local candidate
  local name
  local application_identifier

  for directory in \
    "$HOME/Library/MobileDevice/Provisioning Profiles" \
    "$HOME/Library/Developer/Xcode/UserData/Provisioning Profiles"; do
    [[ -d "$directory" ]] || continue
    while IFS= read -r -d '' candidate; do
      name="$(
        openssl smime -inform der -verify -noverify -in "$candidate" 2>/dev/null |
          plutil -extract Name raw -o - - 2>/dev/null || true
      )"
      [[ "$name" == "$PROFILE" ]] || continue
      application_identifier="$(
        openssl smime -inform der -verify -noverify -in "$candidate" 2>/dev/null |
          plutil -extract Entitlements.application-identifier raw -o - - 2>/dev/null || true
      )"
      [[ "$application_identifier" == "$TEAM_ID.$BUNDLE_ID" ]] || continue
      printf '%s\n' "$candidate"
      return 0
    done < <(find "$directory" -maxdepth 1 -type f \( -name '*.mobileprovision' -o -name '*.provisionprofile' \) -print0)
  done
  return 1
}

preflight() {
  [[ "$(uname -s)" == "Darwin" ]] || fail "iOS releases require macOS."
  command -v xcodebuild >/dev/null || fail "xcodebuild is not installed."
  command -v openssl >/dev/null || fail "openssl is not installed."
  command -v security >/dev/null || fail "security is not installed."
  command -v codesign >/dev/null || fail "codesign is not installed."
  command -v plutil >/dev/null || fail "plutil is not installed."
  [[ -d "$PROJECT" ]] || fail "Xcode project not found: $PROJECT"
  [[ -n "$TEAM_ID" ]] || fail "Apple Developer Team ID is missing."
  [[ -n "$BUNDLE_ID" ]] || fail "Bundle ID is missing."
  [[ "$ARCHIVE_PATH" == *.xcarchive && "$ARCHIVE_PATH" != "/" ]] ||
    fail "Archive path must name a specific .xcarchive directory."
  [[ "$MARKETING_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] ||
    fail "Invalid marketing version: $MARKETING_VERSION"
  [[ "$BUILD_NUMBER" =~ ^[1-9][0-9]*$ ]] || fail "Invalid build number: $BUILD_NUMBER"

  if [[ -n "$PROFILE" ]]; then
    PROFILE_PATH="$(find_profile)" || fail "Provisioning profile '$PROFILE' for $BUNDLE_ID is not installed."
  else
    PROFILE_PATH=""
  fi

  if [[ -n "$SIGNING_CERTIFICATE" ]]; then
    security find-identity -v -p codesigning |
      grep -F "$SIGNING_CERTIFICATE" >/dev/null ||
      fail "No valid '$SIGNING_CERTIFICATE' signing identity was found."
  fi

  if [[ -n "$AUTH_KEY_PATH$AUTH_KEY_ID$AUTH_KEY_ISSUER_ID" ]]; then
    [[ -n "$AUTH_KEY_PATH" && -n "$AUTH_KEY_ID" && -n "$AUTH_KEY_ISSUER_ID" ]] ||
      fail "App Store Connect API auth requires key path, key ID, and issuer ID."
    [[ -f "$AUTH_KEY_PATH" ]] || fail "App Store Connect API key not found: $AUTH_KEY_PATH"
  fi

  if [[ "$DESTINATION" == "upload" ]]; then
    [[ -n "$AUTH_KEY_PATH" && -n "$AUTH_KEY_ID" && -n "$AUTH_KEY_ISSUER_ID" ]] ||
      fail "Uploading requires App Store Connect API key path, key ID, and issuer ID."
  fi
}

print_summary() {
  printf '\nQuotaDog iOS release\n'
  detail "Version" "$MARKETING_VERSION ($BUILD_NUMBER)"
  detail "Revision" "$QUOTADOG_BUILD_COMMIT_SHORT"
  detail "Bundle" "$BUNDLE_ID"
  detail "Team" "$TEAM_ID"
  detail "Profile" "${PROFILE:-automatic}"
  detail "Archive" "$ARCHIVE_PATH"
  if [[ "$DO_EXPORT" == 1 ]]; then
    detail "Destination" "$DESTINATION"
    detail "Export" "$EXPORT_PATH"
  fi
}

prepare_archive() {
  if [[ -e "$ARCHIVE_PATH" ]]; then
    [[ "$CLEAN" == 1 ]] ||
      fail "Archive already exists. Increment the build, pass a different path, or set IOS_CLEAN=yes."
    rm -rf "$ARCHIVE_PATH"
  fi
  mkdir -p "$(dirname "$ARCHIVE_PATH")"
}

prepare_export() {
  local ipa="$EXPORT_PATH/QuotaDog.ipa"
  if [[ "$DESTINATION" == "export" && -e "$ipa" ]]; then
    [[ "$CLEAN" == 1 ]] ||
      fail "IPA already exists. Increment the build, pass a different path, or set IOS_CLEAN=yes."
    rm -f "$ipa"
  fi
  mkdir -p "$EXPORT_PATH"
}

write_export_options() {
  local path="$1"
  local destination="$2"
  local signing_style="automatic"

  if [[ -n "$PROFILE" ]]; then
    signing_style="manual"
  fi

  cat > "$path" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>method</key>
  <string>app-store-connect</string>
  <key>destination</key>
  <string>$destination</string>
  <key>teamID</key>
  <string>$TEAM_ID</string>
  <key>signingStyle</key>
  <string>$signing_style</string>
  <key>manageAppVersionAndBuildNumber</key>
  <false/>
  <key>stripSwiftSymbols</key>
  <true/>
  <key>uploadSymbols</key>
  <true/>
EOF

  if [[ -n "$PROFILE" ]]; then
    cat >> "$path" <<EOF
  <key>signingCertificate</key>
  <string>$SIGNING_CERTIFICATE</string>
  <key>provisioningProfiles</key>
  <dict>
    <key>$BUNDLE_ID</key>
    <string>$PROFILE</string>
  </dict>
EOF
  fi

  cat >> "$path" <<'EOF'
</dict>
</plist>
EOF
}

run_xcodebuild() {
  if [[ "$VERBOSE" == 1 ]]; then
    xcodebuild "$@"
  else
    xcodebuild -quiet "$@"
  fi
}

if [[ "$DO_ARCHIVE" == 1 ]]; then
  quotadog_build_identity_assert_release "$ROOT_DIR" || exit 1
  if [[ "$REUSE_EXISTING" == 1 && -e "$ARCHIVE_PATH" ]]; then
    if ! quotadog_ios_archive_identity_resolve \
      "$ARCHIVE_PATH" \
      "$BUNDLE_ID" \
      "$TEAM_ID" \
      "$QUOTADOG_BUILD_COMMIT" \
      "$MARKETING_VERSION" \
      "$BUILD_NUMBER"; then
      printf '%s\n' \
        "ERROR: Existing archive could not be safely reused." \
        "Use --clean, or publish-tracks --rebuild, only after confirming this build did not reach App Store Connect." >&2
      exit 1
    fi
    ARCHIVE_REUSED=1
  fi
else
  [[ -d "$ARCHIVE_PATH" ]] || fail "Archive not found: $ARCHIVE_PATH"
  quotadog_ios_archive_identity_resolve \
    "$ARCHIVE_PATH" "$BUNDLE_ID" "$TEAM_ID" || exit 1
  MARKETING_VERSION="$IOS_ARCHIVE_MARKETING_VERSION"
  BUILD_NUMBER="$IOS_ARCHIVE_BUILD_NUMBER"
  QUOTADOG_BUILD_COMMIT="$IOS_ARCHIVE_COMMIT"
  QUOTADOG_BUILD_COMMIT_SHORT="$IOS_ARCHIVE_COMMIT_SHORT"
  QUOTADOG_BUILD_DIRTY="$IOS_ARCHIVE_DIRTY"
  RELEASE_DIR="$(
    quotadog_ios_release_directory "$ROOT_DIR" "$MARKETING_VERSION" "$BUILD_NUMBER"
  )" || exit 1
fi

if [[ "$EXPORT_PATH_PROVIDED" == 0 ]]; then
  if [[ "$DESTINATION" == "upload" ]]; then
    EXPORT_PATH="$RELEASE_DIR/upload"
  else
    EXPORT_PATH="$RELEASE_DIR"
  fi
fi

preflight
print_summary

if [[ "$CHECK_ONLY" == 1 ]]; then
  detail "Status" "ready"
  exit 0
fi

xcode_auth_args=()
if [[ -n "$AUTH_KEY_PATH" ]]; then
  xcode_auth_args=(
    -authenticationKeyPath "$AUTH_KEY_PATH"
    -authenticationKeyID "$AUTH_KEY_ID"
    -authenticationKeyIssuerID "$AUTH_KEY_ISSUER_ID"
  )
fi

# Automatic signing must be allowed to fetch or refresh distribution profiles.
# Supplying the API key here also makes local IPA export independent of the
# developer account currently signed in to Xcode.
automatic_provisioning_args=()
if [[ -z "$PROFILE" ]]; then
  automatic_provisioning_args=(-allowProvisioningUpdates)
fi

signing_overrides=(
  DEVELOPMENT_TEAM="$TEAM_ID"
  PRODUCT_BUNDLE_IDENTIFIER="$BUNDLE_ID"
  CURRENT_PROJECT_VERSION="$BUILD_NUMBER"
  MARKETING_VERSION="$MARKETING_VERSION"
  QUOTADOG_BUILD_COMMIT="$QUOTADOG_BUILD_COMMIT"
  QUOTADOG_BUILD_COMMIT_SHORT="$QUOTADOG_BUILD_COMMIT_SHORT"
  QUOTADOG_BUILD_DIRTY="$QUOTADOG_BUILD_DIRTY"
)
if [[ -n "$PROFILE" ]]; then
  signing_overrides+=(
    CODE_SIGN_STYLE=Manual
    CODE_SIGN_IDENTITY="$SIGNING_CERTIFICATE"
    PROVISIONING_PROFILE_SPECIFIER="$PROFILE"
  )
fi

if [[ "$DO_ARCHIVE" == 1 ]]; then
  if [[ "$ARCHIVE_REUSED" == 1 ]]; then
    detail "Reused" "$ARCHIVE_PATH"
  else
    prepare_archive
    run_xcodebuild \
      -project "$PROJECT" \
      -scheme "$SCHEME" \
      -configuration "$CONFIGURATION" \
      -destination "generic/platform=iOS" \
      -archivePath "$ARCHIVE_PATH" \
      archive \
      "${automatic_provisioning_args[@]}" \
      "${xcode_auth_args[@]}" \
      "${signing_overrides[@]}"
    quotadog_ios_archive_identity_resolve \
      "$ARCHIVE_PATH" \
      "$BUNDLE_ID" \
      "$TEAM_ID" \
      "$QUOTADOG_BUILD_COMMIT" \
      "$MARKETING_VERSION" \
      "$BUILD_NUMBER" || exit 1
    detail "Archived" "$ARCHIVE_PATH"
  fi
elif [[ ! -d "$ARCHIVE_PATH" ]]; then
  fail "Archive not found: $ARCHIVE_PATH"
fi

if [[ "$DO_EXPORT" == 1 ]]; then
  prepare_export
  EXPORT_OPTIONS="$(mktemp "${TMPDIR:-/tmp}/quotadog-export-options.XXXXXX")"
  trap 'rm -f "$EXPORT_OPTIONS"' EXIT
  write_export_options "$EXPORT_OPTIONS" "$DESTINATION"

  run_xcodebuild \
    -exportArchive \
    -archivePath "$ARCHIVE_PATH" \
    -exportPath "$EXPORT_PATH" \
    -exportOptionsPlist "$EXPORT_OPTIONS" \
    "${automatic_provisioning_args[@]}" \
    "${xcode_auth_args[@]}"

  if [[ "$DESTINATION" == "upload" ]]; then
    detail "Uploaded" "TestFlight processing started"
    if [[ -n "$APP_STORE_CONNECT_APP_ID" ]]; then
      detail "TestFlight" "https://appstoreconnect.apple.com/apps/$APP_STORE_CONNECT_APP_ID/testflight/ios"
    fi
  else
    IPA_PATH="$(find "$EXPORT_PATH" -maxdepth 1 -type f -name '*.ipa' -print -quit)"
    [[ -n "$IPA_PATH" ]] || fail "xcodebuild completed without producing an IPA."
    detail "Exported" "$IPA_PATH"
  fi
fi
