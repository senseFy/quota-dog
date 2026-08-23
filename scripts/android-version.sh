#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="${QUOTADOG_ROOT_DIR:-$(cd "$SCRIPT_DIR/.." && pwd)}"
VERSION_FILE="$ROOT_DIR/version.properties"

# shellcheck source=lib/product-version.sh
source "$SCRIPT_DIR/lib/product-version.sh"

read_build() {
  local value
  value="$(awk -F= '
    $1 == "VERSION_CODE" { count += 1; value = substr($0, index($0, "=") + 1) }
    END { if (count != 1) exit 1; print value }
  ' "$VERSION_FILE")" || {
    printf 'ERROR: version.properties must contain exactly one VERSION_CODE value.\n' >&2
    return 1
  }
  [[ "$value" =~ ^[1-9][0-9]*$ ]] || {
    printf 'ERROR: Invalid Android versionCode: %s\n' "$value" >&2
    return 1
  }
  quotadog_decimal_is_at_most "$value" 2100000000 || {
    printf 'ERROR: Android versionCode exceeds the Google Play limit: %s\n' "$value" >&2
    return 1
  }
  printf '%s\n' "$value"
}

case "${1:-show}" in
  show)
    printf '%s (%s)\n' \
      "$(quotadog_product_version_read "$VERSION_FILE")" \
      "$(read_build)"
    ;;
  show-build)
    read_build
    ;;
  bump-build)
    "$SCRIPT_DIR/bump_version.sh" --bump-code
    ;;
  *)
    printf 'Usage: %s show | show-build | bump-build\n' "$0" >&2
    exit 2
    ;;
esac
