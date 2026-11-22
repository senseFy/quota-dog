#!/usr/bin/env bash
# One disposable staging identity per run. Never start a loopback relay or connect adb.
set -euo pipefail
umask 077
root=$(cd "$(dirname "$0")/../.." && pwd)
cd "$root"
export ANDROID_HOME=${ANDROID_HOME:-$HOME/Library/Android/sdk}
export JAVA_HOME=${JAVA_HOME:-$(/usr/libexec/java_home -F -v 21 2>/dev/null || echo /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home)}
[[ -x "$JAVA_HOME/bin/java" ]] || { echo 'Set JAVA_HOME to JDK 21.' >&2; exit 1; }
if lsof -nP -iTCP:18788 -sTCP:LISTEN >/dev/null; then echo 'The provider fixture port 18788 is busy.' >&2; exit 1; fi
native/host-sync/build-local.sh
./gradlew -Pquotadog.hostSyncStaging=true :androidApp:assembleHostSyncStaging
work=$(mktemp -d "${TMPDIR:-/tmp}/quotadog-staging.XXXXXXXX")
export QD_LOCAL_PROFILE="$work" QD_LOCAL_TLS_LIBRARY="$root/native/host-sync/build/desktop/libquotadog_host_tls.dylib"
export QD_LOCAL_KEYCHAIN_PASSWORD=$(openssl rand -hex 32)
export DEVIN_CREDENTIALS_TOML="$work/unused-cli.toml"
node scripts/host-sync/devin-fixture.mjs & fixture=$!
cleanup() {
  kill "$fixture" 2>/dev/null || :; wait "$fixture" || :
  if [[ -f "$work/host-sync.keychain-db" ]]; then security delete-keychain "$work/host-sync.keychain-db" >/dev/null || :; fi
  rm -rf "$work"
}
trap cleanup EXIT
for _ in $(seq 50); do curl -fsS http://127.0.0.1:18788/count >/dev/null 2>&1 && break; sleep .2; done
kill -0 "$fixture" && [[ $(curl -fsS http://127.0.0.1:18788/count) == 0 ]] || exit 1
printf '\nStaging deployment bindings (available after the window opens): %s\n' "$work/relay-bindings.json"
echo 'Keep this window open through the test. Closing it discards this test host.'
./gradlew -Pquotadog.hostSyncStaging=true :composeApp:run
