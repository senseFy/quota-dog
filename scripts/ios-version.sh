#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="${QUOTADOG_ROOT_DIR:-$(cd "$SCRIPT_DIR/.." && pwd)}"
VERSION_FILE="$ROOT_DIR/version.properties"

# QuotaDog intentionally shares one monotonically increasing build number
# between Android and iOS so a release cannot drift across stores.
exec "$SCRIPT_DIR/android-version.sh" "${1:-show}"
