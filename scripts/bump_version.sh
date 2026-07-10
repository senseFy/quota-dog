#!/usr/bin/env bash
set -euo pipefail

# Bump or print QuotaDog version stored in version.properties.
# Env overrides (RELEASE_VERSION / RELEASE_VERSION_CODE) still win in CI builds.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION_FILE="$ROOT_DIR/version.properties"
NO_COMMIT_PROMPT=0
PRINT_CURRENT=0
BUMP_CODE=0
SET_NAME=""
SET_CODE=""

usage() {
  cat <<'EOF'
Usage:
  ./scripts/bump_version.sh
  ./scripts/bump_version.sh --print-current
  ./scripts/bump_version.sh --set-version 1.2.0
  ./scripts/bump_version.sh --set-version 1.2.0 --set-code 42
  ./scripts/bump_version.sh --bump-code
  ./scripts/bump_version.sh --no-commit-prompt --set-version 1.2.0

Description:
  Manage VERSION_NAME (x.y.z) and VERSION_CODE in version.properties.

Defaults:
  - With no args: bump VERSION_CODE by 1
  - --set-version also bumps VERSION_CODE by 1 unless --set-code is given
EOF
}

die() {
  echo "Error: $*" >&2
  exit 1
}

read_versions() {
  [[ -f "$VERSION_FILE" ]] || die "Version file not found: $VERSION_FILE"
  VERSION_NAME="$(awk -F= '/^VERSION_NAME=/{print $2; exit}' "$VERSION_FILE")"
  VERSION_CODE="$(awk -F= '/^VERSION_CODE=/{print $2; exit}' "$VERSION_FILE")"
  [[ -n "${VERSION_NAME:-}" ]] || die "VERSION_NAME missing in $VERSION_FILE"
  [[ -n "${VERSION_CODE:-}" ]] || die "VERSION_CODE missing in $VERSION_FILE"
  [[ "$VERSION_CODE" =~ ^[0-9]+$ ]] || die "VERSION_CODE must be an integer (found: $VERSION_CODE)"
}

validate_semver() {
  local value="$1"
  [[ "$value" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "VERSION_NAME must be x.y.z (found: $value)"
  local major="${value%%.*}"
  [[ "$major" -ge 1 ]] || die "VERSION_NAME major must be >= 1 (Compose Desktop installer constraint)."
}

write_versions() {
  cat >"$VERSION_FILE" <<EOF
VERSION_NAME=$VERSION_NAME
VERSION_CODE=$VERSION_CODE
EOF
}

maybe_commit() {
  local response
  if [[ "$NO_COMMIT_PROMPT" == "1" || "$PRINT_CURRENT" == "1" ]]; then
    return
  fi
  if [[ ! -t 0 || ! -t 1 ]]; then
    return
  fi
  if ! git -C "$ROOT_DIR" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    return
  fi
  if git -C "$ROOT_DIR" diff --quiet -- version.properties; then
    return
  fi

  echo ""
  read -r -p "Commit version changes to $VERSION_NAME ($VERSION_CODE)? [y/N] " response
  case "$response" in
    [yY]|[yY][eE][sS])
      git -C "$ROOT_DIR" add -- version.properties
      git -C "$ROOT_DIR" commit --only \
        -m "Bump version to $VERSION_NAME ($VERSION_CODE)" \
        -- version.properties
      ;;
    *)
      echo "Skipped committing version changes."
      ;;
  esac
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--help)
      usage
      exit 0
      ;;
    --print-current)
      PRINT_CURRENT=1
      ;;
    --no-commit-prompt)
      NO_COMMIT_PROMPT=1
      ;;
    --bump-code)
      BUMP_CODE=1
      ;;
    --set-version)
      shift
      [[ $# -gt 0 ]] || die "--set-version requires a value"
      SET_NAME="$1"
      ;;
    --set-code)
      shift
      [[ $# -gt 0 ]] || die "--set-code requires a value"
      SET_CODE="$1"
      ;;
    *)
      die "Unknown arg: $1"
      ;;
  esac
  shift
done

read_versions

if [[ "$PRINT_CURRENT" == "1" ]]; then
  echo "VERSION_NAME=$VERSION_NAME"
  echo "VERSION_CODE=$VERSION_CODE"
  exit 0
fi

if [[ -n "$SET_NAME" ]]; then
  validate_semver "$SET_NAME"
  VERSION_NAME="$SET_NAME"
  if [[ -n "$SET_CODE" ]]; then
    [[ "$SET_CODE" =~ ^[0-9]+$ ]] || die "VERSION_CODE must be an integer (found: $SET_CODE)"
    VERSION_CODE="$SET_CODE"
  else
    VERSION_CODE="$((VERSION_CODE + 1))"
  fi
elif [[ -n "$SET_CODE" ]]; then
  [[ "$SET_CODE" =~ ^[0-9]+$ ]] || die "VERSION_CODE must be an integer (found: $SET_CODE)"
  VERSION_CODE="$SET_CODE"
elif [[ "$BUMP_CODE" == "1" ]] || [[ -z "$SET_NAME$SET_CODE" && "$BUMP_CODE" == "0" ]]; then
  # Default action with no args: bump code.
  VERSION_CODE="$((VERSION_CODE + 1))"
fi

write_versions
echo "Updated version.properties"
echo "VERSION_NAME=$VERSION_NAME"
echo "VERSION_CODE=$VERSION_CODE"
maybe_commit
