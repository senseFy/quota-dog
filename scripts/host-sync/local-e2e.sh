#!/usr/bin/env bash
# Local pilot only. Creates a disposable AVD unless a dedicated pilot emulator was supplied.
set -euo pipefail
umask 077
root=$(cd "$(dirname "$0")/../.." && pwd)
cd "$root"
export ANDROID_HOME=${ANDROID_HOME:-$HOME/Library/Android/sdk}
export JAVA_HOME=${JAVA_HOME:-$(/usr/libexec/java_home -F -v 21 2>/dev/null || echo /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home)}
[[ -x "$JAVA_HOME/bin/java" ]] || { echo 'Set JAVA_HOME to JDK 21.' >&2; exit 1; }
export QD_EVIDENCE=${QD_EVIDENCE:-$root/build/reports/host-sync}
export QD_PHONE_DRIVER="$root/scripts/host-sync/phone-ui.py"
export QD_LOCAL_TLS_LIBRARY="$root/native/host-sync/build/desktop/libquotadog_host_tls.dylib"
export NODE_PATH="$(npm root -g)/wrangler/node_modules"
adb="$ANDROID_HOME/platform-tools/adb"
for port in 18787 18788; do
  if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null; then echo "Local test port $port is already in use" >&2; exit 1; fi
done
work=$(mktemp -d "${TMPDIR:-/tmp}/quotadog-local-e2e.XXXXXXXX")
export QD_LOCAL_PROFILE="$work/profile"
mkdir -p "$QD_LOCAL_PROFILE" "$QD_EVIDENCE"
export QD_LOCAL_KEYCHAIN_PASSWORD=$(openssl rand -hex 32)
export DEVIN_CREDENTIALS_TOML="$work/credentials.toml"
# The current CLI login intentionally differs from both explicitly bound desktop records.
printf '%s\n' 'windsurf_api_key = "fixture-current-cli"' 'api_server_url = "https://127.0.0.1:1"' > "$DEVIN_CREDENTIALS_TOML"
stack=; emulator=; reversed=; created_sim=
cleanup() {
  if [[ -n $created_sim ]]; then xcrun simctl shutdown "$created_sim" >/dev/null 2>&1 || :; xcrun simctl delete "$created_sim"; fi
  if [[ -n $reversed ]]; then for port in 18787 18788; do "$adb" -s "$QD_TEST_SERIAL" reverse --remove tcp:$port >/dev/null 2>&1 || :; done; fi
  if [[ -n $emulator ]]; then "$adb" -s "$QD_TEST_SERIAL" emu kill >/dev/null 2>&1 || :; wait "$emulator" || :; fi
  if [[ -n $stack ]]; then kill "$stack" 2>/dev/null || :; wait "$stack" || :; fi
  rm -rf "$work"
}
trap cleanup EXIT
if [[ ${1:-android} == iphone ]]; then
  command -v axe >/dev/null || { echo 'Install AXe to drive the iPhone simulator.' >&2; exit 1; }
  if [[ -z ${QD_IOS_SIM:-} ]]; then
    created_sim=$(xcrun simctl create QuotaDog_Local_Sync com.apple.CoreSimulator.SimDeviceType.iPhone-17-Pro)
    export QD_IOS_SIM=$created_sim
    xcrun simctl boot "$QD_IOS_SIM"
  fi
  xcrun simctl list devices -j | jq -e --arg id "$QD_IOS_SIM" 'any(.devices[][]; .udid == $id and (.name | startswith("QuotaDog")))' >/dev/null
  xcrun simctl bootstatus "$QD_IOS_SIM" -b
  # A fresh Photos library can take longer than the lifetime of a pairing invitation to initialize.
  xcrun simctl addmedia "$QD_IOS_SIM" "$root/iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/AppIcon.png"
  export QD_PHONE_DRIVER="$root/scripts/host-sync/iphone-ui.py"
  export QD_FIND_PICKER_QR="$work/locate-qr"
  xcrun swiftc scripts/host-sync/locate-qr.swift -o "$QD_FIND_PICKER_QR"
  native/host-sync/build-local.sh desktop
  xcodebuild -quiet -project iosApp/iosApp.xcodeproj -scheme iosApp \
    -configuration Debug -destination "id=$QD_IOS_SIM" -derivedDataPath "$root/build/iphone-sync-local" \
    PRODUCT_BUNDLE_IDENTIFIER=saien.quotadog.hostsync.local build
  # Forgetting the pairing does not remove the independent phone login from an earlier run.
  if xcrun simctl get_app_container "$QD_IOS_SIM" saien.quotadog.hostsync.local data >/dev/null 2>&1; then
    xcrun simctl uninstall "$QD_IOS_SIM" saien.quotadog.hostsync.local
  fi
  xcrun simctl install "$QD_IOS_SIM" "$root/build/iphone-sync-local/Build/Products/Debug-iphonesimulator/QuotaDog.app"
elif [[ ${1:-android} != android ]]; then
  echo 'Usage: local-e2e.sh [android|iphone]' >&2; exit 2
elif [[ -z ${QD_TEST_SERIAL:-} ]]; then
  port=5576
  while lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null; do port=$((port + 2)); done
  export QD_TEST_SERIAL=emulator-$port ANDROID_AVD_HOME="$work/avds"
  mkdir "$ANDROID_AVD_HOME"
  "$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" create avd -n QuotaDog_Local_Sync -k 'system-images;android-36;google_apis_playstore;arm64-v8a' -d pixel_6 </dev/null
  "$ANDROID_HOME/emulator/emulator" -avd QuotaDog_Local_Sync -port "$port" -no-window -no-audio -no-snapshot -gpu swiftshader_indirect >"$work/emulator.log" 2>&1 & emulator=$!
  for _ in $(seq 120); do [[ $("$adb" -s "$QD_TEST_SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r') == 1 ]] && break; sleep 1; done
fi
if [[ ${1:-android} == android ]]; then
  unset QD_IOS_SIM
  [[ $("$adb" -s "$QD_TEST_SERIAL" emu avd name | tr -d '\r' | head -1) == QuotaDog_Local_Sync ]] || { echo 'Use the disposable QuotaDog_Local_Sync AVD.' >&2; exit 1; }
  native/host-sync/build-local.sh
  ./gradlew -Pquotadog.hostSyncLocal=true :androidApp:assembleHostSyncLocal
  "$adb" -s "$QD_TEST_SERIAL" install -r androidApp/build/outputs/apk/hostSyncLocal/androidApp-hostSyncLocal.apk
  "$adb" -s "$QD_TEST_SERIAL" reverse tcp:18787 tcp:18787; reversed=1
  "$adb" -s "$QD_TEST_SERIAL" reverse tcp:18788 tcp:18788
  "$adb" -s "$QD_TEST_SERIAL" shell wm dismiss-keyguard
  # Reserve a full-height status bar; the hole overlay clips the clock on this API 36 AVD.
  "$adb" -s "$QD_TEST_SERIAL" shell cmd overlay enable-exclusive --category com.android.internal.display.cutout.emulation.tall
fi
node scripts/host-sync/devin-fixture.mjs --relay & stack=$!
for _ in $(seq 50); do curl -sf http://127.0.0.1:18788/count >/dev/null && break; sleep .2; done
[[ $(curl -sf http://127.0.0.1:18788/count) == 0 ]]
./gradlew -Pquotadog.hostSyncLocal=true :composeApp:desktopTest --tests '*.LocalHostFlowTest' --rerun
cat "$QD_EVIDENCE/host-sync-results.txt"
./gradlew -Pquotadog.hostSyncLocal=true :shared:desktopTest --tests '*.HostAuthorizationTest' --rerun
