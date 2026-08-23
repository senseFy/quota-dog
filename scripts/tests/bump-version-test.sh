#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
FIXTURE="$(mktemp -d "${TMPDIR:-/tmp}/quotadog-bump-version-test.XXXXXX")"
trap 'rm -rf "$FIXTURE"' EXIT

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

printf 'bump-version contract tests passed.\n'
