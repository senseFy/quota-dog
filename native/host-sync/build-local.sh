#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "$0")" && pwd)
export CARGO_TARGET_DIR="$root/target"
toolchain=1.92.0
ndk_version=27.1.12297006
lib=libquotadog_host_tls
build=(cargo "+$toolchain" build --manifest-path "$root/Cargo.toml" --locked --release --lib)

die() { echo "$*" >&2; exit 1; }

build_desktop() {
    local arch target
    [[ $(uname -s) == Darwin ]] || die 'desktop requires macOS with Xcode command line tools.'
    arch=$(uname -m)
    case "$arch" in
        arm64) target=aarch64-apple-darwin ;;
        x86_64) target=x86_64-apple-darwin ;;
        *) die "Unsupported macOS architecture: $arch" ;;
    esac
    rustup target add --toolchain "$toolchain" "$target"
    MACOSX_DEPLOYMENT_TARGET=11.0 RUSTFLAGS="-C link-arg=-Wl,-install_name,@rpath/$lib.dylib" \
        "${build[@]}" --target "$target"
    lipo -verify_arch "$arch" "$CARGO_TARGET_DIR/$target/release/$lib.dylib"
    mkdir -p "$root/build/desktop"
    install -m 755 "$CARGO_TARGET_DIR/$target/release/$lib.dylib" "$root/build/desktop/$lib.dylib"
    file "$root/build/desktop/$lib.dylib"
}

build_android() {
    local host sdk ndk bin abi target clang cc key cargo_key so
    case "$(uname -sm)" in
        Darwin\ arm64|Darwin\ x86_64) host=darwin-x86_64; sdk="$HOME/Library/Android/sdk" ;;
        Linux\ x86_64) host=linux-x86_64; sdk="$HOME/Android/Sdk" ;;
        *) die 'android requires a macOS or x86_64 Linux NDK host.' ;;
    esac
    sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$sdk}}
    ndk=${ANDROID_NDK_HOME:-$sdk/ndk/$ndk_version}
    grep -Eq "^Pkg.Revision[[:space:]]*=[[:space:]]*${ndk_version//./\\.}[[:space:]]*$" "$ndk/source.properties" 2>/dev/null ||
        die "Install ndk;$ndk_version in the Android SDK, or point ANDROID_NDK_HOME at that version."
    bin="$ndk/toolchains/llvm/prebuilt/$host/bin"
    for abi in "$@"; do
        case "$abi" in
            arm64-v8a) target=aarch64-linux-android; clang=$target ;;
            armeabi-v7a) target=armv7-linux-androideabi; clang=armv7a-linux-androideabi ;;
            x86) target=i686-linux-android; clang=$target ;;
            x86_64) target=x86_64-linux-android; clang=$target ;;
        esac
        cc="$bin/${clang}24-clang"
        [[ -x "$cc" ]] || die "Missing API 24 NDK compiler: $cc"
        rustup target add --toolchain "$toolchain" "$target"
        key=${target//-/_}
        cargo_key=$(printf '%s' "$key" | tr '[:lower:]' '[:upper:]')
        env "CC_$key=$cc" "CXX_$key=$cc++" "AR_$key=$bin/llvm-ar" "RANLIB_$key=$bin/llvm-ranlib" \
            "CARGO_TARGET_${cargo_key}_LINKER=$cc" \
            "CARGO_TARGET_${cargo_key}_RUSTFLAGS=-C link-arg=-Wl,-z,max-page-size=16384,-z,common-page-size=16384" \
            "${build[@]}" --target "$target"
        so="$CARGO_TARGET_DIR/$target/release/$lib.so"
        "$bin/llvm-readelf" -lW "$so" | awk '$1 == "LOAD" { n++; if ($NF != "0x4000") bad=1 } END { exit (!n || bad) }' ||
            die "$abi ELF LOAD segments must be 16 KiB aligned."
        mkdir -p "$root/build/android/$abi"
        install -m 755 "$so" "$root/build/android/$abi/$lib.so"
        file "$root/build/android/$abi/$lib.so"
    done
}

case "$#:${1-}" in
    0:)        build_desktop; build_android arm64-v8a ;; # existing local/staging pilot
    1:desktop) build_desktop ;;
    1:android) build_android arm64-v8a armeabi-v7a x86 x86_64 ;;
    *) die "Usage: $0 [desktop|android]" ;;
esac
