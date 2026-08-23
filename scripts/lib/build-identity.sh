#!/usr/bin/env bash

# Resolve immutable source identity for build artifacts. This file can be
# sourced by release scripts or executed directly through the CLI below.

quotadog_build_identity_error() {
  printf 'ERROR: %s\n' "$*" >&2
}

quotadog_build_identity_trim() {
  local value="${1:-}"
  value="${value#"${value%%[![:space:]]*}"}"
  value="${value%"${value##*[![:space:]]}"}"
  printf '%s\n' "$value"
}

quotadog_build_identity_normalize_dirty() {
  case "$(quotadog_build_identity_trim "${1:-}")" in
    true|TRUE|1) printf 'true\n' ;;
    false|FALSE|0) printf 'false\n' ;;
    *)
      quotadog_build_identity_error "QUOTADOG_SOURCE_DIRTY must be true or false."
      return 1
      ;;
  esac
}

quotadog_build_identity_is_commit() {
  [[ "${1:-}" =~ ^[0-9a-fA-F]{40}$ ]]
}

quotadog_build_identity_git() {
  env \
    -u GIT_DIR \
    -u GIT_WORK_TREE \
    -u GIT_INDEX_FILE \
    -u GIT_OBJECT_DIRECTORY \
    -u GIT_COMMON_DIR \
    git "$@"
}

quotadog_build_identity_git_root() {
  local root="$1"
  quotadog_build_identity_git -C "$root" rev-parse --show-toplevel 2>/dev/null
}

quotadog_build_identity_canonical_root() {
  local root="$1"
  (
    cd "$root" 2>/dev/null
    pwd -P
  )
}

quotadog_build_identity_resolve() {
  local root="${1:-$(pwd)}"
  local canonical_root=""
  local checkout_root=""
  local checkout_commit=""
  local checkout_status=""
  local checkout_dirty=""
  local commit=""
  local dirty=""
  local commit_override_present="false"
  local dirty_override_present="false"

  canonical_root="$(quotadog_build_identity_canonical_root "$root")" || {
    quotadog_build_identity_error "Source root does not exist or is not accessible: $root"
    return 1
  }

  if [[ "${QUOTADOG_SOURCE_COMMIT+x}" == "x" ]]; then
    commit_override_present="true"
    commit="$(quotadog_build_identity_trim "$QUOTADOG_SOURCE_COMMIT")"
  fi
  if [[ "${QUOTADOG_SOURCE_DIRTY+x}" == "x" ]]; then
    dirty_override_present="true"
    dirty="$(quotadog_build_identity_normalize_dirty "$QUOTADOG_SOURCE_DIRTY")" || return 1
  fi

  if [[ -e "$canonical_root/.git" || -L "$canonical_root/.git" ]]; then
    command -v git >/dev/null 2>&1 || {
      quotadog_build_identity_error "Git is required to inspect the QuotaDog checkout."
      return 1
    }
    checkout_root="$(quotadog_build_identity_git_root "$canonical_root")" || {
      quotadog_build_identity_error "Could not resolve the QuotaDog Git checkout."
      return 1
    }
    checkout_root="$(quotadog_build_identity_canonical_root "$checkout_root")" || {
      quotadog_build_identity_error "Could not canonicalize the QuotaDog Git checkout."
      return 1
    }
    if [[ "$checkout_root" != "$canonical_root" ]]; then
      quotadog_build_identity_error "The QuotaDog source root does not match its Git checkout root."
      return 1
    fi
    checkout_commit="$(
      quotadog_build_identity_git -C "$checkout_root" rev-parse --verify HEAD 2>/dev/null
    )" || {
      quotadog_build_identity_error "Could not resolve the checked-out source commit."
      return 1
    }
    checkout_status="$(
      quotadog_build_identity_git \
        -C "$checkout_root" status --porcelain --untracked-files=normal 2>/dev/null
    )" || {
      quotadog_build_identity_error "Could not determine the checked-out source state."
      return 1
    }
    if [[ -n "$checkout_status" ]]; then
      checkout_dirty="true"
    else
      checkout_dirty="false"
    fi
  fi

  if [[ "$commit_override_present" == "false" ]]; then
    commit="$checkout_commit"
  fi
  if ! quotadog_build_identity_is_commit "$commit"; then
    quotadog_build_identity_error \
      "A full 40-character source commit is required. Set QUOTADOG_SOURCE_COMMIT when building outside a Git checkout."
    return 1
  fi
  commit="$(printf '%s' "$commit" | tr '[:upper:]' '[:lower:]')"

  if [[ -n "$checkout_commit" ]]; then
    checkout_commit="$(printf '%s' "$checkout_commit" | tr '[:upper:]' '[:lower:]')"
    if [[ "$commit" != "$checkout_commit" ]]; then
      quotadog_build_identity_error \
        "QUOTADOG_SOURCE_COMMIT does not match the checked-out HEAD ($checkout_commit)."
      return 1
    fi
  fi

  if [[ "$dirty_override_present" == "false" ]]; then
    dirty="$checkout_dirty"
  fi
  if [[ -z "$dirty" ]]; then
    quotadog_build_identity_error \
      "Source dirty state is unknown. Set QUOTADOG_SOURCE_DIRTY when building outside a Git checkout."
    return 1
  fi
  dirty="$(quotadog_build_identity_normalize_dirty "$dirty")" || return 1
  if [[ -n "$checkout_dirty" && "$dirty" != "$checkout_dirty" ]]; then
    quotadog_build_identity_error \
      "QUOTADOG_SOURCE_DIRTY does not match the checked-out source state ($checkout_dirty)."
    return 1
  fi

  QUOTADOG_BUILD_COMMIT="$commit"
  QUOTADOG_BUILD_COMMIT_SHORT="${commit:0:12}"
  QUOTADOG_BUILD_DIRTY="$dirty"
  export QUOTADOG_BUILD_COMMIT QUOTADOG_BUILD_COMMIT_SHORT QUOTADOG_BUILD_DIRTY
}

quotadog_build_identity_assert_release() {
  local root="${1:-$(pwd)}"
  local canonical_root=""
  local checkout_root=""
  local checkout_status=""

  quotadog_build_identity_resolve "$root" || return 1
  if [[ "$QUOTADOG_BUILD_DIRTY" != "false" ]]; then
    quotadog_build_identity_error "Release artifacts require a clean source tree."
    return 1
  fi

  canonical_root="$(quotadog_build_identity_canonical_root "$root")" || {
    quotadog_build_identity_error "Source root does not exist or is not accessible: $root"
    return 1
  }
  if [[ -e "$canonical_root/.git" || -L "$canonical_root/.git" ]]; then
    checkout_root="$(quotadog_build_identity_git_root "$canonical_root")" || {
      quotadog_build_identity_error "Could not resolve the QuotaDog Git checkout."
      return 1
    }
    checkout_root="$(quotadog_build_identity_canonical_root "$checkout_root")" || {
      quotadog_build_identity_error "Could not canonicalize the QuotaDog Git checkout."
      return 1
    }
    if [[ "$checkout_root" != "$canonical_root" ]]; then
      quotadog_build_identity_error "The QuotaDog source root does not match its Git checkout root."
      return 1
    fi
    checkout_status="$(
      quotadog_build_identity_git \
        -C "$checkout_root" status --porcelain --untracked-files=normal 2>/dev/null
    )" || {
      quotadog_build_identity_error "Could not determine the checked-out source state."
      return 1
    }
    if [[ -n "$checkout_status" ]]; then
      quotadog_build_identity_error "Release artifacts require the actual Git checkout to be clean."
      return 1
    fi
  fi
}

quotadog_build_identity_cli() {
  local command="${1:-}"
  case "$command" in
    resolve)
      quotadog_build_identity_resolve "${2:-$(pwd)}" || return 1
      printf 'commit=%s\nshort=%s\ndirty=%s\n' \
        "$QUOTADOG_BUILD_COMMIT" "$QUOTADOG_BUILD_COMMIT_SHORT" "$QUOTADOG_BUILD_DIRTY"
      ;;
    field)
      local field="${2:-}"
      quotadog_build_identity_resolve "${3:-$(pwd)}" || return 1
      case "$field" in
        commit) printf '%s\n' "$QUOTADOG_BUILD_COMMIT" ;;
        short) printf '%s\n' "$QUOTADOG_BUILD_COMMIT_SHORT" ;;
        dirty) printf '%s\n' "$QUOTADOG_BUILD_DIRTY" ;;
        *)
          quotadog_build_identity_error "Unknown build identity field: $field"
          return 2
          ;;
      esac
      ;;
    assert-release)
      quotadog_build_identity_assert_release "${2:-$(pwd)}" || return 1
      printf 'commit=%s\nshort=%s\ndirty=%s\n' \
        "$QUOTADOG_BUILD_COMMIT" "$QUOTADOG_BUILD_COMMIT_SHORT" "$QUOTADOG_BUILD_DIRTY"
      ;;
    --help|-h|help)
      cat <<'EOF'
Usage:
  build-identity.sh resolve [repository-root]
  build-identity.sh field <commit|short|dirty> [repository-root]
  build-identity.sh assert-release [repository-root]
EOF
      ;;
    *)
      quotadog_build_identity_error "Usage: build-identity.sh resolve|field|assert-release"
      return 2
      ;;
  esac
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  set -euo pipefail
  quotadog_build_identity_cli "$@"
fi
