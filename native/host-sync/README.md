# QuotaDog host-sync TLS

`quotadog_host_tls` exposes one socket-free TLS byte stream per handle: JNI on macOS/Android and a C client ABI on iOS. Both use the same **TLS 1.3 with mutual RFC 7250 raw public keys**, not X.509 certificates. Each session performs a full handshake. There is no public CA trust, hostname fallback, resumption, early data, or key logging.

## Build and test

Use rustup with `rustup toolchain install 1.92.0 --profile minimal`. The script uses that exact toolchain with `--locked --release`, adds the requested Rust targets if missing, and statically links AWS-LC. It can be invoked from any directory. Build/target output stays in this crate; no Gradle plugin, `cargo-ndk`, or system OpenSSL is required.

**Gradle packaging contract** (paths relative to `native/host-sync/`):

| Invoke | Host / prerequisites | Output after success |
| --- | --- | --- |
| `./build-local.sh desktop` | macOS with Xcode command line tools; **no NDK needed** | `build/desktop/libquotadog_host_tls.dylib`, a single slice matching the running shell (`arm64` or `x86_64`), minimum macOS 11.0 |
| `./build-local.sh android` | x86_64 Linux with a host C toolchain and `file`, or macOS with Xcode command line tools; Android NDK **27.1.12297006** (r27b) | `build/android/<abi>/libquotadog_host_tls.so` for **all four**: `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` |
| `./build-local.sh iosArm64` | macOS with Xcode's `iphoneos` SDK | `build/iosArm64/libquotadog_host_tls.a`, arm64 **device**, deployment target iOS 15.0 |
| `./build-local.sh iosSimulatorArm64` | macOS with Xcode's `iphonesimulator` SDK | `build/iosSimulatorArm64/libquotadog_host_tls.a`, arm64 **simulator**, deployment target iOS 15.0 |
| `./build-local.sh` | macOS with both sets of prerequisites | Legacy pilot: builds the desktop dylib and the `arm64-v8a` Android library only. Does not remove other ABIs from previous builds. |

For Android, install the NDK with `sdkmanager --install 'ndk;27.1.12297006'`. The script checks `ANDROID_NDK_HOME` if set; otherwise it uses `ndk/27.1.12297006` under `ANDROID_HOME`, `ANDROID_SDK_ROOT`, or the platform's normal SDK location. Each ABI uses NDK clang for **API 24**. The build rejects any ELF without 16 KiB-aligned `LOAD` segments. This checks the libraries, not the APK's ZIP layout; verify the packaged APK separately with `zipalign -c -P 16 4 app.apk`.

Make Android packaging depend on a successful **`android`** invocation and use `build/android` as a JNI library root without restricting ABIs. For macOS, invoke **`desktop`** from a shell with the same architecture as the packaged JVM and embed the dylib before signing. Do not use these outputs as a universal Mac binary or for Windows/Linux desktop. If using Gradle's up-to-date/build cache, key the task on the mode, architecture, `build-local.sh`, `Cargo.toml`, `Cargo.lock`, and Rust sources; `target/` is an internal Cargo cache, not a packaging input.

Runtime test commands (separate from the package builds):

```sh
./build-local.sh                   # builds the two libraries used by the JNI tests
cargo +1.92.0 test --locked
./test-jni.sh                      # desktop, requires kotlinc and java
./test-jni.sh emulator-5554        # use the serial of YOUR disposable arm64 emulator
python3 test-ios.py "$SIMULATOR_UDID" # set to an ID from xcrun simctl list devices available
```

The iOS test rebuilds both archives, links [a C client](tests/ios_client.c) against each archive at iOS 15.0 with fatal linker warnings, and checks the platform load commands. It runs the simulator executable directly, without booting a simulator or installing an app. An ephemeral Apple Security signer and the desktop JNI server exchange 38,417 bytes each way over pipes, with short drains and a fragmented Finished. Separate handshakes test a wrong server pin, a mismatched signing key, a callback error, and an out-of-bounds callback result. By default it builds the desktop JNI server; pass an existing server dylib as the second argument to check a previous release instead. Requires `python3`, `kotlinc`, `java` and an installed arm64 iOS Simulator runtime on Apple Silicon.

The emulator check needs SDK platform-tools, build-tools **37.0.0** (D8), and `platforms;android-35` as the D8 library. It runs a DEX fixture with `dalvikvm64` on the explicitly selected emulator; it does not install an app, enroll an account, or read a stored key. It removes its temporary device directory on exit. The test uses a real Kotlin `object`, in-memory JCA keypairs, and `-Xcheck:jni`. In addition to the per-platform negative tests, the two VMs exchange ciphertext over a local adb pipe with each platform taking each TLS role. The Rust tests independently exercise invalid peer signatures without relying on the Java signer's local checks.

Verified with Kotlin 2.2.21, arm64 JDK 17.0.14, and the SDK image `system-images;android-24;default;arm64-v8a` (Android 7.0, revision 9).

## iOS C client contract

Use [`include/qd_host_tls.h`](include/qd_host_tls.h) as the interop header. Link `-L<crate>/build/<matching-target> -lquotadog_host_tls` into the final executable or framework. Do **not** bundle/dlopen the archive, combine the two arm64 platforms with `lipo`, or load a JVM. Rust's reported native libraries are `-lSystem -lc -lm`; the Apple Security signer in the test also links Security and CoreFoundation. No special entitlement is needed by the TLS library itself.

- `qd_tls_open_client` copies the client's canonical SPKI DER and the **mandatory** 32-byte server pin. There is no iOS server, unpinned enrollment, or trust-on-first-use entry point. Return `0` means open failed.
- The synchronous callback receives the **unhashed** CertificateVerify message. `SecKeyCreateSignature` with `kSecKeyAlgorithmECDSASignatureMessageX962SHA256` returns DER; do not pre-hash or return raw `r || s`. Copy at most `capacity` bytes (currently 72) and return their count, or `-1`. Rust verifies the returned signature against the supplied client SPKI. Keep `ctx`/the signer alive through `qd_tls_close`; serialize access and never call back into this handle from the signer or let an exception cross the ABI.
- Drain `qd_tls_outbound` after open, receive and send. Drain `qd_tls_plaintext` for authenticated application bytes. Positive results are stream fragments: use only the returned length and repeat until `0`. For `qd_tls_peer_pin` use a 32-byte buffer; `0` means not ready, `32` means authenticated **locally**. Always forward the final client flight. On `-1`, close the handle and discard any buffered application data; do not retry it. Close releases the state, not a network `close_notify`.
- A receive chunk is at most 65,536 bytes. A send is at most 16,388 bytes (16,384-byte message plus its four-byte length prefix). Each drain copies at most 65,536 bytes and never more than its supplied capacity. The caller validates message boundaries, delivers ordered bytes and handles deadlines and transport shutdown.

## JNI contract

The instance method declarations in [the fixture](tests/JniRoundTrip.kt) are the ABI of `saien.quotadog.hostsync.NativeHostTls`. Load `quotadog_host_tls` before calling them. Keep the object's class/method names and the signer's public `sign(byte[]): byte[]` unmangled when using a JVM shrinker.

- Pass a canonical DER SubjectPublicKeyInfo for named `secp256r1` with an uncompressed point (91 bytes, as returned by `PublicKey.getEncoded()`). `sign` receives the **unhashed** TLS CertificateVerify input. Use `SHA256withECDSA`, returning DER `(r,s)`, not P1363. The callback runs synchronously on the calling thread. No private key bytes cross JNI. The signer reference is global; local JNI references and environments are not retained.
- `open(false, ..., peerPin)` requires the server's **32 raw bytes** of SHA-256 over the entire SPKI DER. A normal server likewise requires the pinned client. **Only** the separate enrollment handler should call `open(true, ..., null)`: this accepts an unknown client key for that handle but still requires its CertificateVerify and Finished. A newly observed pin identifies a key, not an authorized user. Kotlin must authorize enrollment separately, persist the accepted pin, close the enrollment handle, and open a pinned session for normal service. The native library never learns or persists trust automatically.
- After `open`, drain `outbound()` to send the initial client flight. Feed ordered TLS bytes to `receive()` and forward `outbound()` after each call, including the final client flight. The transport may split or coalesce records arbitrarily. `peerPin()` is null until **local** handshake completion; on the client, the final flight can still await delivery. `plaintext()` drains authenticated bytes (empty before completion). `send()` requires a completed handshake; drain `outbound()` after sending. Neither drain preserves application-message boundaries.
- Kotlin owns serialization, outer framing, deadlines, connection cancellation, and application authorization. An empty input is not EOF. A fatal TLS error, `close_notify`, size violation or failed signer terminates the operation and discards the session state. Close the handle in `finally`; do not reuse it. `close()` releases resources, not a network close handshake. Unknown/closed handles throw; concurrent/reentrant calls on a busy handle are rejected rather than blocking inside a signer callback.

Native ceilings (apply tighter framing limits in the application): 32 registered handles including failed handles awaiting `close`, 64 KiB cumulative input during the handshake, 1 MiB per plaintext send or accumulated undrained plaintext, and 1 MiB + 64 KiB per ciphertext input or queued output. JNI checks input array lengths before copying. There is no overflow spill, partial-success return on error, or automatic eviction of another session.

See [the rustls source notes](RUSTLS.md) for the pinned implementation and upgrade checks. This library does not implement key storage, QR trust, relay access, user approval, or a release signing/packaging policy.
