#!/bin/sh
set -eu

here=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
# Wrangler 4 also has an on-disk debug log, independent of console verbosity.
# Even if its diagnostics change, do not persist request paths or headers.
quiet=$(mktemp -d)
trap 'rm -f "$quiet/wrangler.log"; rmdir "$quiet"' EXIT
ln -s /dev/null "$quiet/wrangler.log"
WRANGLER_LOG=none WRANGLER_LOG_PATH="$quiet/wrangler.log" WRANGLER_SEND_METRICS=false \
  WRANGLER_REGISTRY_PATH="$here/.wrangler/registry" \
  wrangler dev --cwd "$here" --config "$here/wrangler.local.jsonc" --local \
    --ip 127.0.0.1 --inspector-ip 127.0.0.1 --inspector-port 0 --port "${1:-18787}" \
    --log-level none --show-interactive-dev-session=false
