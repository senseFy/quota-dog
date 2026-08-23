#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

tests=(
  bump-version-test.sh
  product-version-test.sh
  build-identity-test.sh
  build-identity-gradle-parity-test.sh
  verify-play-aab-test.sh
  ios-archive-identity-test.sh
  ios-release-signing-test.sh
  publish-tracks-test.sh
)

for test_script in "${tests[@]}"; do
  printf '\n== %s ==\n' "$test_script"
  "$ROOT_DIR/scripts/tests/$test_script"
done

printf '\nAll mobile release contract tests passed.\n'
