#!/usr/bin/env bash

# Resolve immutable release metadata from an existing iOS archive. The archive,
# rather than the current checkout, is authoritative during artifact promotion.

if ! declare -F quotadog_build_identity_is_commit >/dev/null 2>&1; then
  _quotadog_ios_archive_helper_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  # shellcheck source=build-identity.sh
  source "$_quotadog_ios_archive_helper_dir/build-identity.sh"
  unset _quotadog_ios_archive_helper_dir
fi

quotadog_ios_archive_error() {
  printf 'ERROR: %s\n' "$*" >&2
}

quotadog_ios_archive_application() {
  local archive="$1"
  local application_path="$2"
  local app
  local archive_root
  local products

  case "$application_path" in
    ""|/*)
      quotadog_ios_archive_error \
        "Archive contains an unsafe ApplicationProperties.ApplicationPath: $application_path"
      return 1
      ;;
  esac
  case "/$application_path/" in
    */../*|*/./*)
      quotadog_ios_archive_error \
        "Archive contains an unsafe ApplicationProperties.ApplicationPath: $application_path"
      return 1
      ;;
  esac
  if [[ "$application_path" != *.app ]]; then
    quotadog_ios_archive_error \
      "Archive ApplicationProperties.ApplicationPath is not an application: $application_path"
    return 1
  fi

  archive_root="$(cd "$archive" 2>/dev/null && pwd -P)" || {
    quotadog_ios_archive_error "Archive directory does not exist: $archive"
    return 1
  }
  products="$archive_root/Products"
  if [[ ! -d "$products" ]]; then
    quotadog_ios_archive_error "Archive does not contain a Products directory: $archive"
    return 1
  fi
  app="$(cd "$products/$application_path" 2>/dev/null && pwd -P)" || {
    quotadog_ios_archive_error \
      "Archive application does not exist at ApplicationProperties.ApplicationPath: $application_path"
    return 1
  }
  if [[ "$app" != "$products/"* ]]; then
    quotadog_ios_archive_error \
      "Archive ApplicationProperties.ApplicationPath resolves outside archive Products: $application_path"
    return 1
  fi
  if [[ "$app" != *.app ]]; then
    quotadog_ios_archive_error \
      "Archive ApplicationProperties.ApplicationPath does not resolve to an application: $application_path"
    return 1
  fi
  printf '%s\n' "$app"
}

quotadog_ios_archive_codesign_value() {
  local details="$1"
  local key="$2"
  local value

  value="$(
    printf '%s\n' "$details" |
      sed -n "s/^${key}=//p" |
      head -1
  )"
  [[ -n "$value" ]] || {
    quotadog_ios_archive_error "Archive code signature is missing $key."
    return 1
  }
  printf '%s\n' "$value"
}

quotadog_ios_archive_verify_application() {
  local app="$1"
  local expected_bundle_id="$2"
  local expected_team_id="$3"
  local details
  local signed_bundle_id
  local signed_team_id

  codesign --verify --deep --strict "$app" >/dev/null 2>&1 || {
    quotadog_ios_archive_error "Archive application does not have a valid sealed code signature: $app"
    return 1
  }

  details="$(codesign --display --verbose=4 "$app" 2>&1)" || {
    quotadog_ios_archive_error "Could not inspect the archive application code signature: $app"
    return 1
  }
  signed_bundle_id="$(
    quotadog_ios_archive_codesign_value "$details" Identifier
  )" || return 1
  signed_team_id="$(
    quotadog_ios_archive_codesign_value "$details" TeamIdentifier
  )" || return 1

  if [[ "$signed_bundle_id" != "$expected_bundle_id" ]]; then
    quotadog_ios_archive_error \
      "Archive signed identifier '$signed_bundle_id' does not match '$expected_bundle_id'."
    return 1
  fi
  if [[ "$signed_team_id" != "$expected_team_id" ]]; then
    quotadog_ios_archive_error \
      "Archive signed Team ID '$signed_team_id' does not match '$expected_team_id'."
    return 1
  fi

  IOS_ARCHIVE_APP_PATH="$app"
  IOS_ARCHIVE_SIGNED_IDENTIFIER="$signed_bundle_id"
  IOS_ARCHIVE_TEAM_ID="$signed_team_id"
}

quotadog_ios_archive_plist_value() {
  local info_plist="$1"
  local key="$2"
  local value

  value="$(plutil -extract "$key" raw -o - "$info_plist" 2>/dev/null)" || {
    quotadog_ios_archive_error "Archive Info.plist is missing $key."
    return 1
  }
  [[ -n "$value" ]] || {
    quotadog_ios_archive_error "Archive Info.plist contains an empty $key."
    return 1
  }
  printf '%s\n' "$value"
}

quotadog_ios_archive_identity_resolve() {
  local archive="$1"
  local expected_bundle_id="${2:-}"
  local expected_team_id="${3:-}"
  local expected_commit="${4:-}"
  local expected_version="${5:-}"
  local expected_build="${6:-}"
  local app
  local application_path
  local archive_info_plist
  local info_plist
  local commit
  local short
  local dirty
  local version
  local build
  local bundle_id
  local archive_bundle_id
  local archive_version
  local archive_build
  local archive_team_id

  command -v plutil >/dev/null 2>&1 || {
    quotadog_ios_archive_error "plutil is required to inspect an iOS archive."
    return 1
  }
  command -v codesign >/dev/null 2>&1 || {
    quotadog_ios_archive_error "codesign is required to authenticate an iOS archive."
    return 1
  }
  [[ -n "$expected_bundle_id" ]] || {
    quotadog_ios_archive_error "Expected iOS bundle identifier is required."
    return 1
  }
  [[ -n "$expected_team_id" ]] || {
    quotadog_ios_archive_error "Expected Apple Developer Team ID is required."
    return 1
  }

  archive_info_plist="$archive/Info.plist"
  if [[ ! -f "$archive_info_plist" ]]; then
    quotadog_ios_archive_error "Archive does not contain its root Info.plist: $archive"
    return 1
  fi
  application_path="$(
    quotadog_ios_archive_plist_value \
      "$archive_info_plist" ApplicationProperties.ApplicationPath
  )" || return 1
  app="$(
    quotadog_ios_archive_application "$archive" "$application_path"
  )" || return 1
  quotadog_ios_archive_verify_application \
    "$app" "$expected_bundle_id" "$expected_team_id" || return 1
  info_plist="$app/Info.plist"
  if [[ ! -f "$info_plist" ]]; then
    quotadog_ios_archive_error "Archive does not contain an application Info.plist: $archive"
    return 1
  fi
  commit="$(quotadog_ios_archive_plist_value "$info_plist" QuotaDogSourceCommit)" || return 1
  short="$(quotadog_ios_archive_plist_value "$info_plist" QuotaDogSourceCommitShort)" || return 1
  dirty="$(quotadog_ios_archive_plist_value "$info_plist" QuotaDogSourceDirty)" || return 1
  version="$(quotadog_ios_archive_plist_value "$info_plist" CFBundleShortVersionString)" || return 1
  build="$(quotadog_ios_archive_plist_value "$info_plist" CFBundleVersion)" || return 1
  bundle_id="$(quotadog_ios_archive_plist_value "$info_plist" CFBundleIdentifier)" || return 1
  archive_bundle_id="$(
    quotadog_ios_archive_plist_value \
      "$archive_info_plist" ApplicationProperties.CFBundleIdentifier
  )" || return 1
  archive_version="$(
    quotadog_ios_archive_plist_value \
      "$archive_info_plist" ApplicationProperties.CFBundleShortVersionString
  )" || return 1
  archive_build="$(
    quotadog_ios_archive_plist_value \
      "$archive_info_plist" ApplicationProperties.CFBundleVersion
  )" || return 1
  archive_team_id="$(
    quotadog_ios_archive_plist_value "$archive_info_plist" ApplicationProperties.Team
  )" || return 1

  if ! quotadog_build_identity_is_commit "$commit"; then
    quotadog_ios_archive_error "Archive is missing a valid full source commit."
    return 1
  fi
  if [[ "$short" != "${commit:0:12}" ]]; then
    quotadog_ios_archive_error "Archive source commit metadata is inconsistent."
    return 1
  fi
  if [[ "$dirty" != "false" ]]; then
    quotadog_ios_archive_error "Archive was not built from a clean source tree."
    return 1
  fi
  if [[ ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    quotadog_ios_archive_error "Archive has an invalid marketing version: $version"
    return 1
  fi
  if [[ ! "$build" =~ ^[1-9][0-9]*$ ]]; then
    quotadog_ios_archive_error "Archive has an invalid build number: $build"
    return 1
  fi
  if [[ -z "$expected_bundle_id" || "$bundle_id" != "$expected_bundle_id" ]]; then
    quotadog_ios_archive_error \
      "Archive bundle identifier '$bundle_id' does not match '$expected_bundle_id'."
    return 1
  fi
  if [[ "$archive_bundle_id" != "$bundle_id" || \
        "$archive_bundle_id" != "$IOS_ARCHIVE_SIGNED_IDENTIFIER" ]]; then
    quotadog_ios_archive_error \
      "Archive root bundle identifier '$archive_bundle_id' does not match the signed application."
    return 1
  fi
  if [[ "$archive_version" != "$version" ]]; then
    quotadog_ios_archive_error \
      "Archive root marketing version '$archive_version' does not match the signed application."
    return 1
  fi
  if [[ "$archive_build" != "$build" ]]; then
    quotadog_ios_archive_error \
      "Archive root build number '$archive_build' does not match the signed application."
    return 1
  fi
  if [[ "$archive_team_id" != "$IOS_ARCHIVE_TEAM_ID" ]]; then
    quotadog_ios_archive_error \
      "Archive root Team ID '$archive_team_id' does not match the signed application."
    return 1
  fi
  if [[ -n "$expected_commit" && "$commit" != "$expected_commit" ]]; then
    quotadog_ios_archive_error "Archive source commit does not match the release checkout."
    return 1
  fi
  if [[ -n "$expected_version" && "$version" != "$expected_version" ]]; then
    quotadog_ios_archive_error "Archive marketing version does not match the release build."
    return 1
  fi
  if [[ -n "$expected_build" && "$build" != "$expected_build" ]]; then
    quotadog_ios_archive_error "Archive build number does not match the release build."
    return 1
  fi

  IOS_ARCHIVE_INFO_PLIST="$info_plist"
  IOS_ARCHIVE_ROOT_INFO_PLIST="$archive_info_plist"
  IOS_ARCHIVE_APPLICATION_PATH="$application_path"
  IOS_ARCHIVE_COMMIT="$commit"
  IOS_ARCHIVE_COMMIT_SHORT="$short"
  IOS_ARCHIVE_DIRTY="$dirty"
  IOS_ARCHIVE_MARKETING_VERSION="$version"
  IOS_ARCHIVE_BUILD_NUMBER="$build"
  IOS_ARCHIVE_BUNDLE_ID="$bundle_id"
}

quotadog_ios_release_directory() {
  local root="$1"
  local version="$2"
  local build="$3"

  [[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || return 1
  [[ "$build" =~ ^[1-9][0-9]*$ ]] || return 1
  printf '%s/build/ios/TestFlight/%s-%s\n' "$root" "$version" "$build"
}
