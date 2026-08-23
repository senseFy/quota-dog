#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
FIXTURE="$(mktemp -d "${TMPDIR:-/tmp}/quotadog-ios-release-signing-test.XXXXXX")"
trap 'rm -rf "$FIXTURE"' EXIT

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

assert_argument() {
  grep -Fx -- "$1" "$FIXTURE/xcodebuild-arguments" >/dev/null ||
    fail "xcodebuild did not receive $1."
}

mkdir -p \
  "$FIXTURE/scripts/lib" \
  "$FIXTURE/iosApp/Configuration" \
  "$FIXTURE/iosApp/iosApp.xcodeproj" \
  "$FIXTURE/QuotaDog.xcarchive/Products/Applications/QuotaDog.app" \
  "$FIXTURE/bin"
cp "$ROOT_DIR/scripts/release-ios.sh" "$FIXTURE/scripts/"
cp "$ROOT_DIR/scripts/android-version.sh" "$FIXTURE/scripts/"
cp "$ROOT_DIR/scripts/ios-version.sh" "$FIXTURE/scripts/"
cp "$ROOT_DIR/scripts/lib/build-identity.sh" "$FIXTURE/scripts/lib/"
cp "$ROOT_DIR/scripts/lib/ios-archive-identity.sh" "$FIXTURE/scripts/lib/"
cp "$ROOT_DIR/scripts/lib/product-version.sh" "$FIXTURE/scripts/lib/"
printf 'VERSION_NAME=1.2.3\nVERSION_CODE=45\n' >"$FIXTURE/version.properties"
printf 'TEAM_ID=TEAM123\nPRODUCT_BUNDLE_IDENTIFIER=saien.quotadog\n' \
  >"$FIXTURE/iosApp/Configuration/Config.xcconfig"
touch \
  "$FIXTURE/QuotaDog.xcarchive/Info.plist" \
  "$FIXTURE/QuotaDog.xcarchive/Products/Applications/QuotaDog.app/Info.plist" \
  "$FIXTURE/AuthKey_TEST.p8"

cat >"$FIXTURE/bin/uname" <<'EOF'
#!/usr/bin/env bash
printf 'Darwin\n'
EOF

cat >"$FIXTURE/bin/security" <<'EOF'
#!/usr/bin/env bash
printf '  1) TEST "Apple Distribution: QuotaDog"\n'
EOF

cat >"$FIXTURE/bin/codesign" <<'EOF'
#!/usr/bin/env bash
if [[ "${1:-}" == "--display" ]]; then
  printf 'Identifier=saien.quotadog\nTeamIdentifier=TEAM123\n' >&2
fi
exit 0
EOF

cat >"$FIXTURE/bin/plutil" <<'EOF'
#!/usr/bin/env bash
key="${2:-}"
case "$key" in
  ApplicationProperties.ApplicationPath) printf 'Applications/QuotaDog.app\n' ;;
  ApplicationProperties.CFBundleIdentifier|CFBundleIdentifier) printf 'saien.quotadog\n' ;;
  ApplicationProperties.CFBundleShortVersionString|CFBundleShortVersionString) printf '1.2.3\n' ;;
  ApplicationProperties.CFBundleVersion|CFBundleVersion) printf '45\n' ;;
  ApplicationProperties.Team) printf 'TEAM123\n' ;;
  QuotaDogSourceCommit) printf '1234567890abcdef1234567890abcdef12345678\n' ;;
  QuotaDogSourceCommitShort) printf '1234567890ab\n' ;;
  QuotaDogSourceDirty) printf 'false\n' ;;
  *) exit 1 ;;
esac
EOF

cat >"$FIXTURE/bin/xcodebuild" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
: >"$XCODEBUILD_ARGUMENTS"
export_path=""
options_path=""
while [[ $# -gt 0 ]]; do
  printf '%s\n' "$1" >>"$XCODEBUILD_ARGUMENTS"
  case "$1" in
    -exportPath) export_path="${2:-}" ;;
    -exportOptionsPlist) options_path="${2:-}" ;;
  esac
  shift
done
[[ -n "$export_path" && -n "$options_path" ]]
cp "$options_path" "$XCODEBUILD_OPTIONS"
mkdir -p "$export_path"
touch "$export_path/QuotaDog.ipa"
EOF

chmod +x "$FIXTURE/bin/"*

PATH="$FIXTURE/bin:$PATH" \
  XCODEBUILD_ARGUMENTS="$FIXTURE/xcodebuild-arguments" \
  XCODEBUILD_OPTIONS="$FIXTURE/export-options.plist" \
  "$FIXTURE/scripts/release-ios.sh" \
    --team TEAM123 \
    --bundle-id saien.quotadog \
    --archive-path "$FIXTURE/QuotaDog.xcarchive" \
    --export-path "$FIXTURE/export" \
    --export-only \
    --auth-key-path "$FIXTURE/AuthKey_TEST.p8" \
    --auth-key-id TESTKEY \
    --auth-key-issuer-id TESTISSUER \
    >/dev/null

assert_argument -exportArchive
assert_argument -allowProvisioningUpdates
assert_argument -authenticationKeyPath
assert_argument "$FIXTURE/AuthKey_TEST.p8"
assert_argument -authenticationKeyID
assert_argument TESTKEY
assert_argument -authenticationKeyIssuerID
assert_argument TESTISSUER
plutil -extract destination raw -o - "$FIXTURE/export-options.plist" |
  grep -Fx export >/dev/null || fail "Local IPA export did not use destination=export."
[[ -f "$FIXTURE/export/QuotaDog.ipa" ]] || fail "IPA was not exported."

REUSE_OUTPUT="$FIXTURE/reuse-output"
rm -f "$FIXTURE/xcodebuild-arguments"
PATH="$FIXTURE/bin:$PATH" \
  QUOTADOG_SOURCE_COMMIT=1234567890abcdef1234567890abcdef12345678 \
  QUOTADOG_SOURCE_DIRTY=false \
  XCODEBUILD_ARGUMENTS="$FIXTURE/xcodebuild-arguments" \
  XCODEBUILD_OPTIONS="$FIXTURE/export-options.plist" \
  "$FIXTURE/scripts/release-ios.sh" \
    --team TEAM123 \
    --bundle-id saien.quotadog \
    --archive-path "$FIXTURE/QuotaDog.xcarchive" \
    --archive-only \
    --reuse-existing \
    >"$REUSE_OUTPUT"
grep -F "Reused" "$REUSE_OUTPUT" >/dev/null ||
  fail "A matching archive was not reported as reused."
[[ ! -e "$FIXTURE/xcodebuild-arguments" ]] ||
  fail "Reusing an archive unexpectedly invoked xcodebuild."

MISMATCH_OUTPUT="$FIXTURE/reuse-mismatch-output"
if PATH="$FIXTURE/bin:$PATH" \
  QUOTADOG_SOURCE_COMMIT=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
  QUOTADOG_SOURCE_DIRTY=false \
  XCODEBUILD_ARGUMENTS="$FIXTURE/xcodebuild-arguments" \
  XCODEBUILD_OPTIONS="$FIXTURE/export-options.plist" \
  "$FIXTURE/scripts/release-ios.sh" \
    --team TEAM123 \
    --bundle-id saien.quotadog \
    --archive-path "$FIXTURE/QuotaDog.xcarchive" \
    --archive-only \
    --reuse-existing \
    >"$MISMATCH_OUTPUT" 2>&1; then
  fail "A source-mismatched archive was reused."
fi
grep -F "source commit does not match" "$MISMATCH_OUTPUT" >/dev/null ||
  fail "A source-mismatched archive did not explain the rejection."
grep -F "publish-tracks --rebuild" "$MISMATCH_OUTPUT" >/dev/null ||
  fail "A rejected archive did not explain the safe rebuild escape hatch."
[[ ! -e "$FIXTURE/xcodebuild-arguments" ]] ||
  fail "Rejecting an archive unexpectedly invoked xcodebuild."

CONFLICT_OUTPUT="$FIXTURE/reuse-clean-conflict-output"
if "$FIXTURE/scripts/release-ios.sh" \
  --team TEAM123 \
  --bundle-id saien.quotadog \
  --archive-path "$FIXTURE/QuotaDog.xcarchive" \
  --archive-only \
  --clean \
  --reuse-existing \
  >"$CONFLICT_OUTPUT" 2>&1; then
  fail "Conflicting clean and reuse modes were accepted."
fi
grep -F "cannot be used together" "$CONFLICT_OUTPUT" >/dev/null ||
  fail "Conflicting clean and reuse modes did not explain the rejection."

printf 'iOS release signing contract tests passed.\n'
