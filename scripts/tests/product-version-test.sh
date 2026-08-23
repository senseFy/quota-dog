#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
HELPER="$ROOT_DIR/scripts/lib/product-version.sh"
TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/quotadog-product-version-test.XXXXXX")"
trap 'rm -rf "$TEMP_DIR"' EXIT

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

expect_failure() {
  if "$@" >"$TEMP_DIR/unexpected.stdout" 2>"$TEMP_DIR/expected.stderr"; then
    fail "Command unexpectedly succeeded: $*"
  fi
}

FIXTURE="$TEMP_DIR/fixture"
mkdir -p "$FIXTURE"
printf 'VERSION_NAME=0.2.0\n' > "$FIXTURE/version.properties"

[[ "$("$HELPER" show "$FIXTURE")" == "0.2.0" ]] || fail "Could not read the fixture."
"$HELPER" set 1.4.2 "$FIXTURE" >/dev/null
[[ "$("$HELPER" show "$FIXTURE")" == "1.4.2" ]] || fail "Could not update the fixture."
[[ "$(grep -c '^VERSION_NAME=' "$FIXTURE/version.properties")" == "1" ]] ||
  fail "The version source no longer contains exactly one value."

expect_failure "$HELPER" set invalid "$FIXTURE"
expect_failure "$HELPER" set 1 "$FIXTURE"
expect_failure "$HELPER" set 1.2 "$FIXTURE"
expect_failure "$HELPER" show "$TEMP_DIR/missing"

printf 'VERSION_NAME=1.0.0\nVERSION_NAME=2.0.0\n' \
  > "$FIXTURE/version.properties"
expect_failure "$HELPER" show "$FIXTURE"
expect_failure "$HELPER" set 3.0.0 "$FIXTURE"

SHELL_VERSION="$("$HELPER" show "$ROOT_DIR")"
GRADLE_VERSION="$("$ROOT_DIR/gradlew" -q -p "$ROOT_DIR" printProductVersion)"
[[ "$SHELL_VERSION" == "$GRADLE_VERSION" ]] ||
  fail "Shell and Gradle product-version readers disagree."

printf 'Product version contract tests passed.\n'
