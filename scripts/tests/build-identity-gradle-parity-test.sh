#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
HELPER="$ROOT_DIR/scripts/lib/build-identity.sh"
TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/quotadog-build-identity-parity.XXXXXX")"
trap 'rm -rf "$TEMP_DIR"' EXIT

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

expect_failure() {
  local label="$1"
  shift
  if "$@" >"$TEMP_DIR/$label.stdout" 2>"$TEMP_DIR/$label.stderr"; then
    fail "$label unexpectedly succeeded."
  fi
}

SHELL_IDENTITY="$("$HELPER" resolve "$ROOT_DIR")"
GRADLE_IDENTITY="$(
  "$ROOT_DIR/gradlew" -q -p "$ROOT_DIR" printBuildIdentity
)"

if [[ "$SHELL_IDENTITY" != "$GRADLE_IDENTITY" ]]; then
  printf 'Shell and Gradle build identity resolvers disagree.\n' >&2
  diff -u <(printf '%s\n' "$SHELL_IDENTITY") <(printf '%s\n' "$GRADLE_IDENTITY") >&2 || true
  exit 1
fi

CURRENT_COMMIT="$("$HELPER" field commit "$ROOT_DIR")"
CURRENT_DIRTY="$("$HELPER" field dirty "$ROOT_DIR")"
SHELL_OVERRIDE_IDENTITY="$(
  QUOTADOG_SOURCE_COMMIT="$CURRENT_COMMIT" QUOTADOG_SOURCE_DIRTY="$CURRENT_DIRTY" \
    "$HELPER" resolve "$ROOT_DIR"
)"
GRADLE_OVERRIDE_IDENTITY="$(
  QUOTADOG_SOURCE_COMMIT="$CURRENT_COMMIT" QUOTADOG_SOURCE_DIRTY="$CURRENT_DIRTY" \
    "$ROOT_DIR/gradlew" -q -p "$ROOT_DIR" printBuildIdentity
)"
[[ "$SHELL_OVERRIDE_IDENTITY" == "$GRADLE_OVERRIDE_IDENTITY" ]] ||
  fail "Shell and Gradle disagree for explicit valid overrides."

expect_failure shell-empty-commit env QUOTADOG_SOURCE_COMMIT= \
  "$HELPER" resolve "$ROOT_DIR"
expect_failure gradle-empty-commit env QUOTADOG_SOURCE_COMMIT= \
  "$ROOT_DIR/gradlew" -q -p "$ROOT_DIR" printBuildIdentity
expect_failure shell-whitespace-commit env QUOTADOG_SOURCE_COMMIT=' ' \
  "$HELPER" resolve "$ROOT_DIR"
expect_failure gradle-whitespace-commit env QUOTADOG_SOURCE_COMMIT=' ' \
  "$ROOT_DIR/gradlew" -q -p "$ROOT_DIR" printBuildIdentity
expect_failure shell-empty-dirty env QUOTADOG_SOURCE_DIRTY= \
  "$HELPER" resolve "$ROOT_DIR"
expect_failure gradle-empty-dirty env QUOTADOG_SOURCE_DIRTY= \
  "$ROOT_DIR/gradlew" -q -p "$ROOT_DIR" printBuildIdentity
expect_failure shell-whitespace-dirty env QUOTADOG_SOURCE_DIRTY=' ' \
  "$HELPER" resolve "$ROOT_DIR"
expect_failure gradle-whitespace-dirty env QUOTADOG_SOURCE_DIRTY=' ' \
  "$ROOT_DIR/gradlew" -q -p "$ROOT_DIR" printBuildIdentity

printf 'Shell and Gradle build identity resolvers agree.\n'
