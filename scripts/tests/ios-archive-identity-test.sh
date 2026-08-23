#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/quotadog-ios-archive-identity-test.XXXXXX")"
trap 'rm -rf "$TEMP_DIR"' EXIT

if ! command -v plutil >/dev/null 2>&1; then
  printf 'iOS archive identity contract tests skipped: plutil is unavailable.\n'
  exit 0
fi

# shellcheck source=../lib/ios-archive-identity.sh
source "$ROOT_DIR/scripts/lib/ios-archive-identity.sh"

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

expect_failure() {
  if "$@" >"$TEMP_DIR/unexpected.stdout" 2>"$TEMP_DIR/expected.stderr"; then
    fail "Command unexpectedly succeeded: $*"
  fi
}

expect_failure_containing() {
  local expected="$1"
  shift

  expect_failure "$@"
  grep -F "$expected" "$TEMP_DIR/expected.stderr" >/dev/null ||
    fail "Failure did not contain '$expected': $*"
}

COMMIT=1234567890abcdef1234567890abcdef12345678
TEAM_ID=TESTTEAM
ARCHIVE="$TEMP_DIR/QuotaDog.xcarchive"
ARCHIVE_INFO_PLIST="$ARCHIVE/Info.plist"
APPLICATIONS="$ARCHIVE/Products/Applications"
APP="$APPLICATIONS/QuotaDog.app"
INFO_PLIST="$APP/Info.plist"
DECOY_APP="$APPLICATIONS/AAA-Decoy.app"
DECOY_INFO_PLIST="$DECOY_APP/Info.plist"
CODESIGN_FIXTURE_DIR="$TEMP_DIR/codesign"
CODESIGN_FIXTURE_SEALS="$TEMP_DIR/codesign-seals"
mkdir -p "$DECOY_APP"
mkdir -p "$APP"
mkdir -p "$CODESIGN_FIXTURE_DIR"
mkdir -p "$CODESIGN_FIXTURE_SEALS"
cat > "$ARCHIVE_INFO_PLIST" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>ApplicationProperties</key>
  <dict>
    <key>ApplicationPath</key><string>Applications/QuotaDog.app</string>
    <key>CFBundleIdentifier</key><string>saien.quotadog</string>
    <key>CFBundleShortVersionString</key><string>9.8.7</string>
    <key>CFBundleVersion</key><string>42</string>
    <key>Team</key><string>$TEAM_ID</string>
  </dict>
  <key>ArchiveVersion</key><integer>2</integer>
</dict>
</plist>
EOF
cat > "$DECOY_INFO_PLIST" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleIdentifier</key><string>saien.quotadog</string>
  <key>CFBundleShortVersionString</key><string>1.2.3</string>
  <key>CFBundleVersion</key><string>7</string>
  <key>QuotaDogSourceCommit</key><string>aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa</string>
  <key>QuotaDogSourceCommitShort</key><string>aaaaaaaaaaaa</string>
  <key>QuotaDogSourceDirty</key><false/>
</dict>
</plist>
EOF
cat > "$INFO_PLIST" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleIdentifier</key><string>saien.quotadog</string>
  <key>CFBundleShortVersionString</key><string>9.8.7</string>
  <key>CFBundleVersion</key><string>42</string>
  <key>QuotaDogSourceCommit</key><string>$COMMIT</string>
  <key>QuotaDogSourceCommitShort</key><string>${COMMIT:0:12}</string>
  <key>QuotaDogSourceDirty</key><false/>
</dict>
</plist>
EOF

cat > "$CODESIGN_FIXTURE_DIR/codesign" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

mode=""
target=""
deep=0
strict=0
for argument in "$@"; do
  case "$argument" in
    --verify) mode="verify" ;;
    --display|-d) mode="display" ;;
    --deep) deep=1 ;;
    --strict) strict=1 ;;
  esac
  target="$argument"
done

case "$mode" in
  verify)
    [[ "$deep" == 1 && "$strict" == 1 ]] || exit 64
    seal_file="$TEST_CODESIGN_SEAL_DIRECTORY/$(basename "$target").seal"
    [[ -f "$seal_file" ]] || exit 1
    actual="$(cksum < "$target/Info.plist")"
    expected="$(< "$seal_file")"
    [[ "$actual" == "$expected" ]] || exit 1
    ;;
  display)
    printf 'Identifier=%s\n' "$TEST_CODESIGN_IDENTIFIER" >&2
    printf 'TeamIdentifier=%s\n' "$TEST_CODESIGN_TEAM_ID" >&2
    ;;
  *)
    exit 64
    ;;
esac
EOF
chmod +x "$CODESIGN_FIXTURE_DIR/codesign"

export TEST_CODESIGN_SEAL_DIRECTORY="$CODESIGN_FIXTURE_SEALS"
export TEST_CODESIGN_IDENTIFIER=saien.quotadog
export TEST_CODESIGN_TEAM_ID="$TEAM_ID"
PATH="$CODESIGN_FIXTURE_DIR:$PATH"
export PATH

seal_application() {
  local application="$1"

  cksum < "$application/Info.plist" > \
    "$CODESIGN_FIXTURE_SEALS/$(basename "$application").seal"
}

seal_archive() {
  seal_application "$DECOY_APP"
  seal_application "$APP"
}

expect_failure_containing "valid sealed code signature" \
  quotadog_ios_archive_identity_resolve "$ARCHIVE" saien.quotadog "$TEAM_ID"

seal_archive
quotadog_ios_archive_identity_resolve "$ARCHIVE" saien.quotadog "$TEAM_ID"
[[ "$IOS_ARCHIVE_MARKETING_VERSION" == "9.8.7" ]] || fail "Wrong archive version."
[[ "$IOS_ARCHIVE_BUILD_NUMBER" == "42" ]] || fail "Wrong archive build."
[[ "$IOS_ARCHIVE_BUNDLE_ID" == "saien.quotadog" ]] || fail "Wrong archive bundle ID."
[[ "$IOS_ARCHIVE_COMMIT" == "$COMMIT" ]] || fail "Wrong archive commit."
[[ "$IOS_ARCHIVE_TEAM_ID" == "$TEAM_ID" ]] || fail "Wrong archive Team ID."
[[ "$IOS_ARCHIVE_APP_PATH" == "$(cd "$APP" && pwd -P)" ]] ||
  fail "Archive root ApplicationPath did not select the release application."
[[ "$IOS_ARCHIVE_APPLICATION_PATH" == "Applications/QuotaDog.app" ]] ||
  fail "Wrong archive root application path."
[[ "$(quotadog_ios_release_directory /repository 9.8.7 42)" == \
  "/repository/build/ios/TestFlight/9.8.7-42" ]] || fail "Wrong release directory."

plutil -replace CFBundleShortVersionString -string 9.8.8 "$INFO_PLIST"
expect_failure_containing "valid sealed code signature" \
  quotadog_ios_archive_identity_resolve "$ARCHIVE" saien.quotadog "$TEAM_ID"
plutil -replace CFBundleShortVersionString -string 9.8.7 "$INFO_PLIST"
seal_archive

quotadog_ios_archive_identity_resolve \
  "$ARCHIVE" saien.quotadog "$TEAM_ID" "$COMMIT" 9.8.7 42
expect_failure_containing "signed identifier" quotadog_ios_archive_identity_resolve \
  "$ARCHIVE" wrong.bundle "$TEAM_ID"
expect_failure_containing "signed Team ID" quotadog_ios_archive_identity_resolve \
  "$ARCHIVE" saien.quotadog WRONGTEAM
expect_failure_containing "Team ID is required" \
  quotadog_ios_archive_identity_resolve "$ARCHIVE" saien.quotadog
expect_failure quotadog_ios_archive_identity_resolve "$ARCHIVE" saien.quotadog "$TEAM_ID" \
  0000000000000000000000000000000000000000
expect_failure quotadog_ios_archive_identity_resolve "$ARCHIVE" saien.quotadog "$TEAM_ID" \
  "$COMMIT" 9.8.8 42
expect_failure quotadog_ios_archive_identity_resolve "$ARCHIVE" saien.quotadog "$TEAM_ID" \
  "$COMMIT" 9.8.7 43

plutil -replace ApplicationProperties.CFBundleIdentifier \
  -string wrong.root.bundle "$ARCHIVE_INFO_PLIST"
expect_failure_containing "root bundle identifier" \
  quotadog_ios_archive_identity_resolve "$ARCHIVE" saien.quotadog "$TEAM_ID"
plutil -replace ApplicationProperties.CFBundleIdentifier \
  -string saien.quotadog "$ARCHIVE_INFO_PLIST"

plutil -replace ApplicationProperties.CFBundleShortVersionString \
  -string 9.8.8 "$ARCHIVE_INFO_PLIST"
expect_failure_containing "root marketing version" \
  quotadog_ios_archive_identity_resolve "$ARCHIVE" saien.quotadog "$TEAM_ID"
plutil -replace ApplicationProperties.CFBundleShortVersionString \
  -string 9.8.7 "$ARCHIVE_INFO_PLIST"

plutil -replace ApplicationProperties.CFBundleVersion \
  -string 43 "$ARCHIVE_INFO_PLIST"
expect_failure_containing "root build number" \
  quotadog_ios_archive_identity_resolve "$ARCHIVE" saien.quotadog "$TEAM_ID"
plutil -replace ApplicationProperties.CFBundleVersion \
  -string 42 "$ARCHIVE_INFO_PLIST"

plutil -replace ApplicationProperties.Team \
  -string WRONGTEAM "$ARCHIVE_INFO_PLIST"
expect_failure_containing "root Team ID" \
  quotadog_ios_archive_identity_resolve "$ARCHIVE" saien.quotadog "$TEAM_ID"
plutil -replace ApplicationProperties.Team \
  -string "$TEAM_ID" "$ARCHIVE_INFO_PLIST"

OUTSIDE_APP="$ARCHIVE/Outside.app"
mkdir -p "$OUTSIDE_APP"
cp "$INFO_PLIST" "$OUTSIDE_APP/Info.plist"
seal_application "$OUTSIDE_APP"
plutil -replace ApplicationProperties.ApplicationPath \
  -string ../Outside.app "$ARCHIVE_INFO_PLIST"
expect_failure_containing "unsafe ApplicationProperties.ApplicationPath" \
  quotadog_ios_archive_identity_resolve "$ARCHIVE" saien.quotadog "$TEAM_ID"

ln -s ../../Outside.app "$APPLICATIONS/Escape.app"
plutil -replace ApplicationProperties.ApplicationPath \
  -string Applications/Escape.app "$ARCHIVE_INFO_PLIST"
expect_failure_containing "resolves outside archive Products" \
  quotadog_ios_archive_identity_resolve "$ARCHIVE" saien.quotadog "$TEAM_ID"
plutil -replace ApplicationProperties.ApplicationPath \
  -string Applications/QuotaDog.app "$ARCHIVE_INFO_PLIST"

if [[ "$(uname -s)" == "Darwin" ]] && command -v xcodebuild >/dev/null 2>&1; then
  RELEASE_OUTPUT="$(
    "$ROOT_DIR/scripts/release-ios.sh" \
      --team "$TEAM_ID" \
      --bundle-id saien.quotadog \
      --profile "" \
      --signing-certificate "" \
      --archive-path "$ARCHIVE" \
      --export-only \
      --check
  )"
  [[ "$RELEASE_OUTPUT" == *"9.8.7 (42)"* ]] ||
    fail "Existing archive mode did not use the artifact version and build."
  [[ "$RELEASE_OUTPUT" == \
    *"$ROOT_DIR/build/ios/TestFlight/9.8.7-42"* ]] ||
    fail "Existing archive mode did not derive its export path from the artifact."
fi

plutil -replace CFBundleShortVersionString -string 9.8 "$INFO_PLIST"
seal_archive
expect_failure quotadog_ios_archive_identity_resolve "$ARCHIVE" saien.quotadog "$TEAM_ID"
plutil -replace CFBundleShortVersionString -string 9.8.7 "$INFO_PLIST"

plutil -replace CFBundleVersion -string 0 "$INFO_PLIST"
seal_archive
expect_failure quotadog_ios_archive_identity_resolve "$ARCHIVE" saien.quotadog "$TEAM_ID"
plutil -replace CFBundleVersion -string 42 "$INFO_PLIST"

plutil -replace QuotaDogSourceDirty -bool true "$INFO_PLIST"
seal_archive
expect_failure quotadog_ios_archive_identity_resolve "$ARCHIVE" saien.quotadog "$TEAM_ID"

printf 'iOS archive identity contract tests passed.\n'
