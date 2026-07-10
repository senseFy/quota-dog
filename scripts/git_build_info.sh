#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"

if ! git -C "$ROOT_DIR" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  exit 0
fi

GIT_HASH="$(git -C "$ROOT_DIR" rev-parse --short=7 HEAD 2>/dev/null || true)"
if [[ -z "${GIT_HASH:-}" ]]; then
  exit 0
fi

GIT_STATE="clean"
if [[ -n "$(git -C "$ROOT_DIR" status --porcelain --untracked-files=normal 2>/dev/null)" ]]; then
  GIT_STATE="dirty"
fi

printf '%s-%s\n' "$GIT_HASH" "$GIT_STATE"
