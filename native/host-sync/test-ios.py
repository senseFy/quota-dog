"""Link each shipping iOS archive; run the simulator C client against the desktop JNI server."""
import subprocess as sp
import sys
import tempfile
from pathlib import Path

root = Path(__file__).resolve().parent
if len(sys.argv) not in (2, 3):
    sys.exit("Usage: python3 test-ios.py <local-arm64-iOS-simulator-UDID> [reference-server.dylib]")

for mode in ("iosArm64", "iosSimulatorArm64"):
    sp.run([root / "build-local.sh", mode], check=True)
if len(sys.argv) == 3:
    server_lib = Path(sys.argv[2]).resolve(strict=True)
else:
    sp.run([root / "build-local.sh", "desktop"], check=True)
    server_lib = root / "build/desktop/libquotadog_host_tls.dylib"

with tempfile.TemporaryDirectory(prefix="c-jni-", dir=root / "build") as tmp:
    work = Path(tmp)
    for mode, sdk, suffix, platform in [
        ("iosArm64", "iphoneos", "", "IOS"),
        ("iosSimulatorArm64", "iphonesimulator", "-simulator", "IOSSIMULATOR"),
    ]:
        sdkroot = sp.check_output(["xcrun", "--sdk", sdk, "--show-sdk-path"], text=True).strip()
        sp.run(["xcrun", "--sdk", sdk, "clang", "-target", f"arm64-apple-ios15.0{suffix}",
                "-isysroot", sdkroot, "-std=c11", "-Wall", "-Wextra", "-Werror", "-Wl,-fatal_warnings",
                "-I", root / "include", root / "tests/ios_client.c", root / "build" / mode / "libquotadog_host_tls.a",
                "-framework", "CoreFoundation", "-framework", "Security", "-o", work / mode], check=True)
        info = sp.check_output(["xcrun", "vtool", "-show-build", work / mode], text=True)
        fields = [line.split() for line in info.splitlines()]
        if ["platform", platform] not in fields or ["minos", "15.0"] not in fields:
            sys.exit(f"Wrong linked platform or deployment target: {info}")
        print(f"PASS linked C fixture: {platform} arm64, minos 15.0", flush=True)

    jar = work / "harness.jar"
    sp.run(["kotlinc", root / "tests/JniRoundTrip.kt", "-jvm-target", "1.8", "-include-runtime", "-d", jar], check=True)
    jvm = ["java", "-Xcheck:jni", "-cp", jar, "saien.quotadog.hostsync.JniRoundTripKt",
           server_lib, "peer", "server"]
    # Standalone spawn: no installation, boot, app data or physical device access.
    client = ["xcrun", "simctl", "spawn", "--standalone", sys.argv[1], work / "iosSimulatorArm64"]
    for mode in ("pinned", "wrong-pin", "wrong-key", "sign-error", "sign-overflow"):
        with sp.Popen(jvm, stdin=sp.PIPE, stdout=sp.PIPE) as server:
            try:
                sp.run([*client, mode], stdin=server.stdout, stdout=server.stdin, timeout=30, check=True)
                if mode == "pinned" and server.wait(timeout=30) != 0:
                    sys.exit("JNI server did not authenticate the client or validate its request")
            finally:
                # Rejected clients deliberately never send Finished; don't count server EOF as proof.
                if server.poll() is None:
                    server.kill()
