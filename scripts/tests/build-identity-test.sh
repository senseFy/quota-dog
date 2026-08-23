#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
HELPER="$ROOT_DIR/scripts/lib/build-identity.sh"
TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/quotadog-build-identity-test.XXXXXX")"
trap 'rm -rf "$TEMP_DIR"' EXIT

unset QUOTADOG_SOURCE_COMMIT QUOTADOG_SOURCE_DIRTY

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

assert_equal() {
  [[ "$1" == "$2" ]] || fail "Expected '$2', got '$1'."
}

expect_failure() {
  if "$@" >"$TEMP_DIR/unexpected.stdout" 2>"$TEMP_DIR/expected.stderr"; then
    fail "Command unexpectedly succeeded: $*"
  fi
}

REPOSITORY="$TEMP_DIR/repository"
mkdir -p "$REPOSITORY"
git -C "$REPOSITORY" init -q
git -C "$REPOSITORY" config user.name "QuotaDog Build Identity Test"
git -C "$REPOSITORY" config user.email "build-identity@example.invalid"
printf 'clean\n' > "$REPOSITORY/source.txt"
git -C "$REPOSITORY" add source.txt
git -C "$REPOSITORY" commit -q -m "Fixture"

COMMIT="$(git -C "$REPOSITORY" rev-parse HEAD)"
assert_equal "$("$HELPER" field commit "$REPOSITORY")" "$COMMIT"
assert_equal "$("$HELPER" field short "$REPOSITORY")" "${COMMIT:0:12}"
assert_equal "$("$HELPER" field dirty "$REPOSITORY")" "false"
"$HELPER" assert-release "$REPOSITORY" >/dev/null
assert_equal "$(
  GIT_DIR=/definitely/not/a/git/directory \
    GIT_WORK_TREE=/definitely/not/a/work/tree \
    "$HELPER" field commit "$REPOSITORY"
)" "$COMMIT"

printf 'dirty\n' >> "$REPOSITORY/source.txt"
assert_equal "$("$HELPER" field dirty "$REPOSITORY")" "true"
expect_failure "$HELPER" assert-release "$REPOSITORY"
expect_failure env QUOTADOG_SOURCE_DIRTY=false "$HELPER" resolve "$REPOSITORY"
git -C "$REPOSITORY" restore source.txt

expect_failure env QUOTADOG_SOURCE_COMMIT=invalid "$HELPER" resolve "$REPOSITORY"
expect_failure env QUOTADOG_SOURCE_COMMIT=0000000000000000000000000000000000000000 \
  "$HELPER" resolve "$REPOSITORY"
expect_failure env QUOTADOG_SOURCE_COMMIT= "$HELPER" resolve "$REPOSITORY"
expect_failure env QUOTADOG_SOURCE_COMMIT=' ' "$HELPER" resolve "$REPOSITORY"
expect_failure env QUOTADOG_SOURCE_DIRTY= "$HELPER" resolve "$REPOSITORY"
expect_failure env QUOTADOG_SOURCE_DIRTY=' ' "$HELPER" resolve "$REPOSITORY"
assert_equal "$(
  QUOTADOG_SOURCE_COMMIT="  $COMMIT  " QUOTADOG_SOURCE_DIRTY=" FALSE " \
    "$HELPER" field commit "$REPOSITORY"
)" "$COMMIT"

REAL_GIT="$(command -v git)"
GIT_SHIM_DIRECTORY="$TEMP_DIR/git-shim"
mkdir -p "$GIT_SHIM_DIRECTORY"
cat > "$GIT_SHIM_DIRECTORY/git" <<'EOF'
#!/usr/bin/env bash
set -eu
arguments=" $* "
case "${QUOTADOG_TEST_GIT_FAILURE:-}" in
  root)
    [[ "$arguments" != *" rev-parse --show-toplevel "* ]] || exit 75
    ;;
  head)
    [[ "$arguments" != *" rev-parse --verify HEAD "* ]] || exit 75
    ;;
  status)
    [[ "$arguments" != *" status --porcelain --untracked-files=normal "* ]] || exit 75
    ;;
esac
exec "$QUOTADOG_TEST_REAL_GIT" "$@"
EOF
chmod +x "$GIT_SHIM_DIRECTORY/git"
expect_failure env \
  PATH="$GIT_SHIM_DIRECTORY:$PATH" \
  QUOTADOG_TEST_REAL_GIT="$REAL_GIT" \
  QUOTADOG_TEST_GIT_FAILURE=root \
  QUOTADOG_SOURCE_COMMIT="$COMMIT" \
  QUOTADOG_SOURCE_DIRTY=false \
  "$HELPER" assert-release "$REPOSITORY"
expect_failure env \
  PATH="$GIT_SHIM_DIRECTORY:$PATH" \
  QUOTADOG_TEST_REAL_GIT="$REAL_GIT" \
  QUOTADOG_TEST_GIT_FAILURE=head \
  "$HELPER" resolve "$REPOSITORY"
expect_failure env \
  PATH="$GIT_SHIM_DIRECTORY:$PATH" \
  QUOTADOG_TEST_REAL_GIT="$REAL_GIT" \
  QUOTADOG_TEST_GIT_FAILURE=status \
  "$HELPER" resolve "$REPOSITORY"

SOURCE_ARCHIVE="$TEMP_DIR/source-archive"
mkdir -p "$SOURCE_ARCHIVE"
ARCHIVE_COMMIT=1234567890abcdef1234567890abcdef12345678
assert_equal "$({
  QUOTADOG_SOURCE_COMMIT="$ARCHIVE_COMMIT" QUOTADOG_SOURCE_DIRTY=false \
    "$HELPER" field commit "$SOURCE_ARCHIVE"
})" "$ARCHIVE_COMMIT"
QUOTADOG_SOURCE_COMMIT="$ARCHIVE_COMMIT" QUOTADOG_SOURCE_DIRTY=false \
  "$HELPER" assert-release "$SOURCE_ARCHIVE" >/dev/null
expect_failure env QUOTADOG_SOURCE_COMMIT="$ARCHIVE_COMMIT" \
  "$HELPER" resolve "$SOURCE_ARCHIVE"

PARENT_REPOSITORY="$TEMP_DIR/parent-repository"
NESTED_SOURCE_ARCHIVE="$PARENT_REPOSITORY/source-archive"
mkdir -p "$NESTED_SOURCE_ARCHIVE"
git -C "$PARENT_REPOSITORY" init -q
git -C "$PARENT_REPOSITORY" config user.name "QuotaDog Build Identity Test"
git -C "$PARENT_REPOSITORY" config user.email "build-identity@example.invalid"
printf 'parent\n' > "$PARENT_REPOSITORY/source.txt"
git -C "$PARENT_REPOSITORY" add source.txt
git -C "$PARENT_REPOSITORY" commit -q -m "Parent fixture"
assert_equal "$(
  QUOTADOG_SOURCE_COMMIT="$ARCHIVE_COMMIT" QUOTADOG_SOURCE_DIRTY=false \
    "$HELPER" field commit "$NESTED_SOURCE_ARCHIVE"
)" "$ARCHIVE_COMMIT"

printf 'Build identity contract tests passed.\n'
