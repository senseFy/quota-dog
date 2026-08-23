#!/usr/bin/env bash

# Read and update QuotaDog's single product-version source. This file can be
# sourced by platform scripts or executed through the small CLI below.

quotadog_product_version_error() {
  printf 'ERROR: %s\n' "$*" >&2
}

quotadog_product_version_validate() {
  if [[ ! "${1:-}" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    quotadog_product_version_error "Invalid QuotaDog product version: ${1:-<empty>}"
    return 1
  fi
}

quotadog_decimal_increment() {
  local value="$1"
  local result=""
  local carry=1
  local index
  local digit

  [[ "$value" =~ ^[0-9]+$ ]] || {
    quotadog_product_version_error "Invalid decimal value: $value"
    return 1
  }

  for ((index = ${#value} - 1; index >= 0; index--)); do
    digit="${value:index:1}"
    if (( carry == 1 )); then
      case "$digit" in
        0) digit=1; carry=0 ;;
        1) digit=2; carry=0 ;;
        2) digit=3; carry=0 ;;
        3) digit=4; carry=0 ;;
        4) digit=5; carry=0 ;;
        5) digit=6; carry=0 ;;
        6) digit=7; carry=0 ;;
        7) digit=8; carry=0 ;;
        8) digit=9; carry=0 ;;
        9) digit=0 ;;
      esac
    fi
    result="$digit$result"
  done

  if (( carry == 1 )); then
    result="1$result"
  fi
  printf '%s\n' "$result"
}

quotadog_decimal_is_at_most() {
  local value="$1"
  local maximum="$2"

  if (( ${#value} < ${#maximum} )); then
    return 0
  fi
  if (( ${#value} > ${#maximum} )); then
    return 1
  fi
  [[ "$value" == "$maximum" || "$value" < "$maximum" ]]
}

quotadog_product_version_next_patch() {
  local version="$1"
  local major
  local minor
  local patch
  local IFS=.

  quotadog_product_version_validate "$version" || return 1
  read -r major minor patch <<< "$version"
  printf '%s.%s.%s\n' "$major" "$minor" "$(quotadog_decimal_increment "$patch")"
}

quotadog_product_version_read() {
  local file="$1"
  local version

  [[ -f "$file" ]] || {
    quotadog_product_version_error "Product version source not found: $file"
    return 1
  }
  [[ -r "$file" ]] || {
    quotadog_product_version_error "Product version source is not readable: $file"
    return 1
  }

  version="$(awk -F= '
    $1 == "VERSION_NAME" {
      count += 1
      value = substr($0, index($0, "=") + 1)
    }
    END {
      if (count != 1) exit 1
      print value
    }
  ' "$file")" || {
    quotadog_product_version_error \
      "Product version source must contain exactly one VERSION_NAME value."
    return 1
  }

  quotadog_product_version_validate "$version" || return 1
  printf '%s\n' "$version"
}

quotadog_product_version_write() {
  local file="$1"
  local version="$2"
  local temporary

  quotadog_product_version_validate "$version" || return 1
  quotadog_product_version_read "$file" >/dev/null || return 1

  temporary="$(mktemp "${file}.tmp.XXXXXX")" || return 1
  if ! awk -v version="$version" '
    $0 ~ /^VERSION_NAME=/ {
      print "VERSION_NAME=" version
      next
    }
    { print }
  ' "$file" > "$temporary"; then
    rm -f "$temporary"
    return 1
  fi
  if ! chmod 0644 "$temporary" || ! mv "$temporary" "$file"; then
    rm -f "$temporary"
    return 1
  fi
}

quotadog_product_version_cli() {
  local root
  local version_file
  local command="${1:-show}"

  case "$command" in
    show)
      root="${2:-$(pwd)}"
      version_file="$root/version.properties"
      quotadog_product_version_read "$version_file"
      ;;
    set)
      local version="${2:-}"
      root="${3:-$(pwd)}"
      version_file="$root/version.properties"
      quotadog_product_version_write "$version_file" "$version" || return 1
      printf 'QuotaDog product version: %s\n' "$version"
      ;;
    --help|-h|help)
      cat <<'EOF'
Usage:
  product-version.sh show [repository-root]
  product-version.sh set <version> [repository-root]
EOF
      ;;
    *)
      quotadog_product_version_error "Usage: product-version.sh show|set"
      return 2
      ;;
  esac
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  set -euo pipefail
  quotadog_product_version_cli "$@"
fi
