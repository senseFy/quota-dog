#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

usage() {
  echo "Usage: $0 [--no-commit] [repository-root]" >&2
}

commit_enabled=1
root_argument=""
root_seen=0

set_root_argument() {
  if (( root_seen == 1 )); then
    echo "Only one repository root may be specified." >&2
    usage
    exit 2
  fi
  if [[ -z "$1" ]]; then
    echo "The repository root must not be empty." >&2
    usage
    exit 2
  fi
  root_argument="$1"
  root_seen=1
}

while (( $# > 0 )); do
  case "$1" in
    --no-commit)
      if (( commit_enabled == 0 )); then
        echo "--no-commit may only be specified once." >&2
        usage
        exit 2
      fi
      commit_enabled=0
      ;;
    --)
      shift
      if (( $# > 1 )); then
        echo "Only one repository root may be specified." >&2
        usage
        exit 2
      fi
      if (( $# == 1 )); then
        set_root_argument "$1"
      fi
      break
      ;;
    -*)
      echo "Unknown option: $1" >&2
      usage
      exit 2
      ;;
    *)
      set_root_argument "$1"
      ;;
  esac
  shift
done

if (( root_seen == 1 )); then
  ROOT_DIR="$root_argument"
else
  ROOT_DIR="$SCRIPT_DIR/.."
fi
ROOT_DIR="$(cd "$ROOT_DIR" && pwd -P)"
VERSION_FILE="$ROOT_DIR/version.properties"

# shellcheck source=lib/product-version.sh
source "$SCRIPT_DIR/lib/product-version.sh"

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

read_build() {
  local value
  value="$(awk -F= '
    $1 == "VERSION_CODE" {
      count += 1
      value = substr($0, index($0, "=") + 1)
    }
    END {
      if (count != 1) exit 1
      print value
    }
  ' "$VERSION_FILE")" ||
    fail "version.properties must contain exactly one VERSION_CODE value."
  [[ "$value" =~ ^[1-9][0-9]*$ ]] ||
    fail "Invalid store build number: $value"
  printf '%s\n' "$value"
}

write_build() {
  local value="$1"
  local temporary
  temporary="$(mktemp "${VERSION_FILE}.tmp.XXXXXX")"
  if ! awk -v value="$value" '
    BEGIN { found = 0 }
    $0 ~ /^VERSION_CODE=/ {
      print "VERSION_CODE=" value
      found = 1
      next
    }
    { print }
    END { if (!found) exit 1 }
  ' "$VERSION_FILE" > "$temporary"; then
    rm -f "$temporary"
    return 1
  fi
  if ! chmod 0644 "$temporary" || ! mv "$temporary" "$VERSION_FILE"; then
    rm -f "$temporary"
    return 1
  fi
}

require_publishable_version() {
  local version="$1"
  local major="${version%%.*}"
  quotadog_product_version_validate "$version" || return 1
  [[ "$major" =~ ^[1-9][0-9]*$ ]] ||
    fail "VERSION_NAME major must be >= 1 (Compose Desktop installer constraint)."
}

current_product="$(quotadog_product_version_read "$VERSION_FILE")"
current_build="$(read_build)"
next_product="$(quotadog_product_version_next_patch "$current_product")"
next_build="$(quotadog_decimal_increment "$current_build")"
require_publishable_version "$next_product"
quotadog_decimal_is_at_most "$next_build" 2100000000 ||
  fail "Store build number exceeds the Google Play limit: $next_build"

if (( commit_enabled == 1 )); then
  if ! git -C "$ROOT_DIR" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    fail "The version bump commits its change and requires a Git work tree."
  fi
  git_root="$(git -C "$ROOT_DIR" rev-parse --show-toplevel)"
  git_root="$(cd "$git_root" && pwd -P)"
  if [[ "$git_root" != "$ROOT_DIR" ]]; then
    fail "The repository root must be the root of its Git work tree."
  fi
  if [[ -n "$(git -C "$ROOT_DIR" status --porcelain -- version.properties)" ]]; then
    fail "version.properties already has uncommitted changes; commit or restore it first."
  fi
fi

backup="$(mktemp "${TMPDIR:-/tmp}/quotadog-bump-version.XXXXXX")"
rollback_required=0

cleanup() {
  local status=$?
  trap - EXIT HUP INT TERM
  if (( rollback_required == 1 )); then
    if cp -p "$backup" "$VERSION_FILE"; then
      echo "Version bump failed; version.properties was restored." >&2
    else
      echo "ERROR: Version bump failed and rollback was incomplete." >&2
      status=1
    fi
  fi
  rm -f "$backup"
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' HUP INT TERM

cp -p "$VERSION_FILE" "$backup"
rollback_required=1
quotadog_product_version_write "$VERSION_FILE" "$next_product"
write_build "$next_build"

commit_message="Bump version to ${next_product}(${next_build})"
if (( commit_enabled == 1 )); then
  git -C "$ROOT_DIR" commit -q -m "$commit_message" -- version.properties
fi
rollback_required=0

printf 'QuotaDog version bump\n'
printf '  %-22s %s → %s\n' "Product version" "$current_product" "$next_product"
printf '  %-22s %s → %s\n' "Android versionCode" "$current_build" "$next_build"
printf '  %-22s %s → %s\n' "iOS build number" "$current_build" "$next_build"
if (( commit_enabled == 1 )); then
  printf '  %-22s %s\n' "Commit" "$commit_message"
else
  printf '  %-22s %s\n' "Commit" "not created (--no-commit)"
fi
