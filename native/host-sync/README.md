# QuotaDog host-sync TLS

`quotadog_host_tls` is a socket-free JNI library for one TLS byte stream per handle. It uses **TLS 1.3 with mutual RFC 7250 raw public keys**, not X.509 certificates. Each session performs a full handshake. There is no public CA trust, hostname fallback, resumption, early data, or key logging.

## Build and test

Use rustup with `rustup toolchain install 1.92.0 --profile minimal`. The script uses that exact toolchain and `cargo build --locked --release`, adds the requested Rust targets if missing, and statically links AWS-LC. It can be invoked from any directory. Build/target output stays in this crate; no Gradle plugin, `cargo-ndk`, or system OpenSSL is required.

**Gradle packaging contract** (paths relative to `native/host-sync/`):

| Invoke | Host / prerequisites | Output after success |
| --- | --- | --- |
| `./build-local.sh desktop` | macOS with Xcode command line tools; **no NDK needed** | `build/desktop/libquotadog_host_tls.dylib`, a single slice matching the running shell (`arm64` or `x86_64`), minimum macOS 11.0 |
| `./build-local.sh android` | x86_64 Linux with a host C toolchain and `file`, or macOS with Xcode command line tools; Android NDK **27.1.12297006** (r27b) | `build/android/<abi>/libquotadog_host_tls.so` for **all four**: `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` |
| `./build-local.sh` | macOS with both sets of prerequisites | Legacy pilot: builds the desktop dylib and the `arm64-v8a` Android library only. Does not remove other ABIs from previous builds. |

For Android, install the NDK with `sdkmanager --install 'ndk;27.1.12297006'`. The script checks `ANDROID_NDK_HOME` if set; otherwise it uses `ndk/27.1.12297006` under `ANDROID_HOME`, `ANDROID_SDK_ROOT`, or the platform's normal SDK location. Each ABI uses NDK clang for **API 24**. The build rejects any ELF without 16 KiB-aligned `LOAD` segments. This checks the libraries, not the APK's ZIP layout; verify the packaged APK separately with `zipalign -c -P 16 4 app.apk`.

Make Android packaging depend on a successful **`android`** invocation and use `build/android` as a JNI library root without restricting ABIs. For macOS, invoke **`desktop`** from a shell with the same architecture as the packaged JVM and embed the dylib before signing. Do not use these outputs as a universal Mac binary or for Windows/Linux desktop. If using Gradle's up-to-date/build cache, key the task on the mode, architecture, `build-local.sh`, `Cargo.toml`, `Cargo.lock`, and Rust sources; `target/` is an internal Cargo cache, not a packaging input.

Runtime test commands (separate from the package builds):

```sh
./build-local.sh                   # builds the two libraries used by the JNI tests
cargo +1.92.0 test --locked
./test-jni.sh                      # desktop, requires kotlinc and java
./test-jni.sh emulator-5554        # use the serial of YOUR disposable arm64 emulator
```

The emulator check needs SDK platform-tools, build-tools **37.0.0** (D8), and `platforms;android-35` as the D8 library. It runs a DEX fixture with `dalvikvm64` on the explicitly selected emulator; it does not install an app, enroll an account, or read a stored key. It removes its temporary device directory on exit. The test uses a real Kotlin `object`, in-memory JCA keypairs, and `-Xcheck:jni`. In addition to the per-platform negative tests, the two VMs exchange ciphertext over a local adb pipe with each platform taking each TLS role. The Rust tests independently exercise invalid peer signatures without relying on the Java signer's local checks.

Verified with Kotlin 2.2.21, arm64 JDK 17.0.14, and the SDK image `system-images;android-24;default;arm64-v8a` (Android 7.0, revision 9).

## JNI contract

The instance method declarations in [the fixture](tests/JniRoundTrip.kt) are the ABI of `saien.quotadog.hostsync.NativeHostTls`. Load `quotadog_host_tls` before calling them. Keep the object's class/method names and the signer's public `sign(byte[]): byte[]` unmangled when using a JVM shrinker.

- Pass a canonical DER SubjectPublicKeyInfo for named `secp256r1` with an uncompressed point (91 bytes, as returned by `PublicKey.getEncoded()`). `sign` receives the **unhashed** TLS CertificateVerify input. Use `SHA256withECDSA`, returning DER `(r,s)`, not P1363. The callback runs synchronously on the calling thread. No private key bytes cross JNI. The signer reference is global; local JNI references and environments are not retained.
- `open(false, ..., peerPin)` requires the server's **32 raw bytes** of SHA-256 over the entire SPKI DER. A normal server likewise requires the pinned client. **Only** the separate enrollment handler should call `open(true, ..., null)`: this accepts an unknown client key for that handle but still requires its CertificateVerify and Finished. A newly observed pin identifies a key, not an authorized user. Kotlin must authorize enrollment separately, persist the accepted pin, close the enrollment handle, and open a pinned session for normal service. The native library never learns or persists trust automatically.
- After `open`, drain `outbound()` to send the initial client flight. Feed ordered TLS bytes to `receive()` and forward `outbound()` after each call, including the final client flight. The transport may split or coalesce records arbitrarily. `peerPin()` is null until **local** handshake completion; on the client, the final flight can still await delivery. `plaintext()` drains authenticated bytes (empty before completion). `send()` requires a completed handshake; drain `outbound()` after sending. Neither drain preserves application-message boundaries.
- Kotlin owns serialization, outer framing, deadlines, connection cancellation, and application authorization. An empty input is not EOF. A fatal TLS error, `close_notify`, size violation or failed signer terminates the operation and discards the session state. Close the handle in `finally`; do not reuse it. `close()` releases resources, not a network close handshake. Unknown/closed handles throw; concurrent/reentrant calls on a busy handle are rejected rather than blocking inside a signer callback.

Native ceilings (apply tighter framing limits in the application): 32 registered handles including failed handles awaiting `close`, 64 KiB cumulative input during the handshake, 1 MiB per plaintext send or accumulated undrained plaintext, and 1 MiB + 64 KiB per ciphertext input or queued output. JNI checks input array lengths before copying. There is no overflow spill, partial-success return on error, or automatic eviction of another session.

See [the rustls source notes](RUSTLS.md) for the pinned implementation and upgrade checks. This library does not implement key storage, QR trust, relay access, user approval, or a release signing/packaging policy.
