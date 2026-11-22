#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "$0")" && pwd)
work="$root/build/jni"
mkdir -p "$work"
main=saien.quotadog.hostsync.JniRoundTripKt
lib="$root/build/desktop/libquotadog_host_tls.dylib"
kotlinc "$root/tests/JniRoundTrip.kt" -jvm-target 1.8 -include-runtime -d "$work/harness.jar"
host=(java -Xcheck:jni -cp "$work/harness.jar" "$main" "$lib")
"${host[@]}" suite
if [[ $# == 0 ]]; then exit 0; fi
[[ $# == 1 && $1 == emulator-* ]] || { echo 'Usage: test-jni.sh [disposable-emulator-serial]' >&2; exit 1; }

sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}
adb=("$sdk/platform-tools/adb" -s "$1")
[[ $("${adb[@]}" shell getprop ro.product.cpu.abi | tr -d '\r') == arm64-v8a ]]
echo "Testing Android API $("${adb[@]}" shell getprop ro.build.version.sdk | tr -d '\r') / arm64-v8a"
"$sdk/build-tools/37.0.0/d8" --min-api 24 --lib "$sdk/platforms/android-35/android.jar" --output "$work/harness-dex.jar" "$work/harness.jar"
remote=$("${adb[@]}" shell mktemp -d '/data/local/tmp/quotadog-jni.XXXXXX' | tr -d '\r')
[[ $remote =~ ^/data/local/tmp/quotadog-jni\.[a-zA-Z0-9]+$ ]]
trap '"${adb[@]}" shell rm -rf "$remote"' EXIT
"${adb[@]}" push "$work/harness-dex.jar" "$root/build/android/arm64-v8a/libquotadog_host_tls.so" "$remote/" >/dev/null
"${adb[@]}" shell chmod 444 "$remote/harness-dex.jar"
peer=("${adb[@]}" shell -T dalvikvm64 -Xcheck:jni -cp "$remote/harness-dex.jar" "$main" "$remote/libquotadog_host_tls.so")
"${peer[@]}" suite
"${host[@]}" cross client "${peer[@]}" peer server
"${host[@]}" cross server "${peer[@]}" peer client
