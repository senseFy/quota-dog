#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
FIXTURE="$(mktemp -d "${TMPDIR:-/tmp}/quotadog-bump-version-test.XXXXXX")"
trap 'rm -rf "$FIXTURE"' EXIT

case "$#:${1:-}" in
  0:|1:--with-releasekit) ;;
  *) echo "Usage: $0 [--with-releasekit]" >&2; exit 2 ;;
esac

mkdir -p "$FIXTURE/scripts"
cp "$ROOT_DIR/scripts/bump_version.sh" "$FIXTURE/scripts/"
printf 'VERSION_NAME=1.0.0\nVERSION_CODE=12\n' >"$FIXTURE/version.properties"

read_value() {
  awk -F= -v key="$1" '$1 == key { print $2; exit }' "$FIXTURE/version.properties"
}

assert_versions() {
  local expected_name="$1"
  local expected_code="$2"
  [[ "$(read_value VERSION_NAME)" == "$expected_name" ]] || {
    printf 'Expected VERSION_NAME=%s, got %s\n' "$expected_name" "$(read_value VERSION_NAME)" >&2
    exit 1
  }
  [[ "$(read_value VERSION_CODE)" == "$expected_code" ]] || {
    printf 'Expected VERSION_CODE=%s, got %s\n' "$expected_code" "$(read_value VERSION_CODE)" >&2
    exit 1
  }
}

"$FIXTURE/scripts/bump_version.sh" --no-commit-prompt >/dev/null
assert_versions 1.0.1 13

"$FIXTURE/scripts/bump_version.sh" --no-commit-prompt --bump-code >/dev/null
assert_versions 1.0.1 14

"$FIXTURE/scripts/bump_version.sh" --no-commit-prompt --set-version 1.2.0 >/dev/null
assert_versions 1.2.0 15

"$FIXTURE/scripts/bump_version.sh" --no-commit-prompt --set-code 20 >/dev/null
assert_versions 1.2.0 20

BUMP_SCRIPT="$ROOT_DIR/scripts/bump-version.sh"
fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

init_repo() {
  local fixture="$1"
  git -C "$fixture" init -q -b main
  git -C "$fixture" config user.name "QuotaDog Tests"
  git -C "$fixture" config user.email "tests@quotadog.invalid"
  git -C "$fixture" config commit.gpgsign false
  git -C "$fixture" add -A
  git -C "$fixture" commit -qm "Fixture baseline"
}

new_version_repo() {
  local name="$1"
  local product="${2:-1.0.7}"
  local code="${3:-19}"
  local fixture="$FIXTURE/$name"
  mkdir -p "$fixture"
  printf 'VERSION_NAME=%s\nVERSION_CODE=%s\n' "$product" "$code" >"$fixture/version.properties"
  init_repo "$fixture"
  printf '%s\n' "$fixture"
}

read_case_value() {
  local fixture="$1"
  local key="$2"
  awk -F= -v key="$key" '$1 == key { print $2; exit }' "$fixture/version.properties"
}

commit_case="$(new_version_repo commit-case)"
commit_output="$("$BUMP_SCRIPT" "$commit_case")"
[[ "$(read_case_value "$commit_case" VERSION_NAME)" == "1.0.8" ]] ||
  fail "Patch version was not incremented."
[[ "$(read_case_value "$commit_case" VERSION_CODE)" == "20" ]] ||
  fail "Shared store build was not incremented."
[[ "$(git -C "$commit_case" log -1 --format=%s)" == "Bump version to 1.0.8(20)" ]] ||
  fail "Commit subject did not name the shared build."
[[ -z "$(git -C "$commit_case" status --porcelain)" ]] ||
  fail "Auto-commit left the work tree dirty."
[[ "$(git -C "$commit_case" show --name-only --format= HEAD)" == "version.properties" ]] ||
  fail "Auto-commit did not contain only version.properties."
grep -F "Bump version to 1.0.8(20)" <<<"$commit_output" >/dev/null ||
  fail "Bump summary did not report the created commit."
grep -F "Android versionCode" <<<"$commit_output" >/dev/null ||
  fail "Bump summary omitted the Android build."
grep -F "iOS build number" <<<"$commit_output" >/dev/null ||
  fail "Bump summary omitted the iOS build."

# A releasekit bump must update the same version and build that both mobile stores read.
python3 - "$ROOT_DIR/release.toml" <<'PY'
import sys
import tomllib

with open(sys.argv[1], "rb") as manifest:
    version = tomllib.load(manifest)["version"]
if (version["source"], version["file"], version["key"], version["code_key"]) != (
    "properties", "version.properties", "VERSION_NAME", "VERSION_CODE"
):
    sys.exit("release.toml must point to the shared product version and store build")
PY

# Exercise the Makefile's public CLI contract without access to the private engine.
mkdir -p "$FIXTURE/bin"
cat >"$FIXTURE/bin/releasekit" <<'SH'
#!/bin/sh
printf '[%s]' "$@"
SH
chmod +x "$FIXTURE/bin/releasekit"
expect_releasekit_args() {
  local expected="$1" actual
  shift
  actual=$(env -u RELEASEKIT PATH="$FIXTURE/bin:$PATH" \
    make --no-print-directory -s -C "$FIXTURE" -f "$ROOT_DIR/Makefile" "$@")
  [[ "$actual" == "$expected" ]] || fail "make $*: expected $expected, got $actual"
}
expect_releasekit_args '[bump]' bump-version
expect_releasekit_args '[bump][--version][1.4.0]' version-bump VERSION=1.4.0
expect_releasekit_args '[bump][--no-commit]' bump-version COMMIT=no

if [[ "${1:-}" == --with-releasekit ]]; then
  # Use the same manifest and tracked release files as a real bump, but no remote.
  make_case="$FIXTURE/make-case"
  mkdir -p "$make_case/infra/cloudflare/public"
  cp "$ROOT_DIR/release.toml" "$ROOT_DIR/CHANGELOG.md" "$make_case/"
  cp "$ROOT_DIR/infra/cloudflare/public/index.html" "$make_case/infra/cloudflare/public/"
  new_version_repo make-case 1.2.3 7 >/dev/null
  bump=(make --no-print-directory -s -C "$make_case" -f "$ROOT_DIR/Makefile")
  "${bump[@]}" version-bump VERSION=1.4.0 </dev/null
  [[ "$(read_case_value "$make_case" VERSION_NAME)" == 1.4.0 &&
     "$(read_case_value "$make_case" VERSION_CODE)" == 8 ]] ||
    fail "The bump did not commit the requested product version and next store build."
  [[ "$(git -C "$make_case" diff --name-only HEAD^ HEAD)" == version.properties &&
     -z "$(git -C "$make_case" status --porcelain)" ]] ||
    fail "A standalone bump must commit only version.properties."
  head=$(git -C "$make_case" rev-parse HEAD)
  "${bump[@]}" bump-version VERSION=1.4.2 COMMIT=no </dev/null
  [[ "$(git -C "$make_case" rev-parse HEAD)" == "$head" &&
     "$(git -C "$make_case" status --porcelain)" == ' M version.properties' &&
     "$(read_case_value "$make_case" VERSION_NAME)" == 1.4.2 &&
     "$(read_case_value "$make_case" VERSION_CODE)" == 9 ]] ||
    fail "COMMIT=no must leave only the requested version and next build uncommitted."
  echo 'Real releasekit bump integration passed.'
fi

staged_case="$(new_version_repo staged-case)"
printf 'unrelated staged content\n' >"$staged_case/release-notes.txt"
git -C "$staged_case" add release-notes.txt
"$BUMP_SCRIPT" "$staged_case" >/dev/null
[[ "$(git -C "$staged_case" show --name-only --format= HEAD)" == "version.properties" ]] ||
  fail "The version commit included an unrelated staged file."
[[ "$(git -C "$staged_case" diff --cached --name-only)" == "release-notes.txt" ]] ||
  fail "An unrelated staged file was not left staged."

dirty_case="$(new_version_repo dirty-case)"
printf '\nlocal edit\n' >>"$dirty_case/version.properties"
snapshot="$(mktemp)"
cp "$dirty_case/version.properties" "$snapshot"
if "$BUMP_SCRIPT" "$dirty_case" >"$FIXTURE/dirty-output" 2>&1; then
  fail "A dirty version source was accepted."
fi
grep -F "uncommitted changes" "$FIXTURE/dirty-output" >/dev/null ||
  fail "A dirty version source did not explain the rejection."
cmp "$snapshot" "$dirty_case/version.properties" >/dev/null ||
  fail "A rejected bump modified version.properties."
rm -f "$snapshot"

limit_case="$FIXTURE/play-limit"
mkdir -p "$limit_case"
printf 'VERSION_NAME=1.0.0\nVERSION_CODE=2100000000\n' >"$limit_case/version.properties"
limit_snapshot="$(mktemp)"
cp "$limit_case/version.properties" "$limit_snapshot"
if "$BUMP_SCRIPT" --no-commit "$limit_case" >"$FIXTURE/limit-output" 2>&1; then
  fail "A store build past the Play limit was accepted."
fi
grep -F "Google Play limit" "$FIXTURE/limit-output" >/dev/null ||
  fail "The Play limit rejection did not explain the failure."
cmp "$limit_snapshot" "$limit_case/version.properties" >/dev/null ||
  fail "The Play limit rejection modified version.properties."
rm -f "$limit_snapshot"

major_case="$FIXTURE/major-zero"
mkdir -p "$major_case"
printf 'VERSION_NAME=0.9.9\nVERSION_CODE=4\n' >"$major_case/version.properties"
major_snapshot="$(mktemp)"
cp "$major_case/version.properties" "$major_snapshot"
if "$BUMP_SCRIPT" --no-commit "$major_case" >"$FIXTURE/major-output" 2>&1; then
  fail "A major-zero product version was accepted."
fi
grep -F "major must be >= 1" "$FIXTURE/major-output" >/dev/null ||
  fail "The major-zero rejection did not explain the failure."
cmp "$major_snapshot" "$major_case/version.properties" >/dev/null ||
  fail "The major-zero rejection modified version.properties."
rm -f "$major_snapshot"

set_case="$(new_version_repo set-version-case)"
make --no-print-directory -s -C "$set_case" -f "$ROOT_DIR/Makefile" \
  PRODUCT_VERSION_SCRIPT="$ROOT_DIR/scripts/lib/product-version.sh" \
  set-version VERSION=1.4.0 >/dev/null
[[ "$(read_case_value "$set_case" VERSION_NAME)" == "1.4.0" ]] ||
  fail "set-version did not write the product version."
[[ "$(read_case_value "$set_case" VERSION_CODE)" == "19" ]] ||
  fail "set-version changed the shared store build."
[[ "$(git -C "$set_case" status --porcelain -- version.properties)" == " M version.properties" ]] ||
  fail "set-version committed or failed to leave the product version uncommitted."
if make --no-print-directory -s -C "$set_case" -f "$ROOT_DIR/Makefile" \
  PRODUCT_VERSION_SCRIPT="$ROOT_DIR/scripts/lib/product-version.sh" \
  set-version VERSION=0.4.0 >"$FIXTURE/set-major-output" 2>&1; then
  fail "set-version accepted major 0."
fi
[[ "$(read_case_value "$set_case" VERSION_NAME)" == "1.4.0" ]] ||
  fail "Rejected set-version changed the product version."

printf 'bump-version contract tests passed.\n'
