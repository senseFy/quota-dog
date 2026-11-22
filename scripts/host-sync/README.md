# Run host sync locally

`scripts/host-sync/local-e2e.sh` exercises a desktop-only Devin account from the QuotaDog Android dashboard, without a provider login, Cloudflare account, Tailscale, or public listener. This harness uses isolated test builds. Normal Mac and Android builds use the [production relay](../../infra/host-sync-relay/README.md) and actual imported logins instead.

From the repository root on an Apple Silicon Mac:

```sh
scripts/host-sync/local-e2e.sh
```

Use JDK 21 (`JAVA_HOME`), Xcode command line tools, Rust 1.92.0 via rustup, Python 3, Node 22.12+ and global Wrangler 4.78. The Android SDK needs the project's SDK/build-tools, platform-tools, command-line tools, Emulator, NDK `27.1.12297006` and the arm64 `system-images;android-36;google_apis_playstore;arm64-v8a` image. Allow the emulator to boot. The script creates and removes a temporary AVD; it never installs over the regular QuotaDog package. Both loopback ports, 18787 and 18788, must be free. The first native build may take several minutes.

**What actually runs:** the desktop Compose dashboard in its UI test harness, a separately installed Android APK, the [JNI TLS library](../../native/host-sync/README.md) on both ends, and the actual [relay Worker and Durable Object](../../infra/host-sync-relay/README.md) in local workerd. The fixture seeds two desktop records and substitutes only the upstream `GetUserStatus` HTTP service. Desktop credentials/trust and the test app's data are isolated from normal QuotaDog. The phone runs the same QR decoder on pixels from the rendered desktop invitation via the system file picker; this does not test a physical camera. `adb reverse` connects Android to loopback; it does not test a network change.

**What to look for:** after matching both numbers and approving the phone, the account list starts empty. Sharing one record shows daily/weekly usage and the extra USD balance. Allowing refresh adds the remote action. The test checks the upstream call count, failure without overwriting the last successful reading, interruption after a query was accepted, cold-start cache, reconnect without replay, unsharing, and online/offline revocation. A separate authenticated protocol test sends a delayed request after a downgrade and after regrant, independently of the phone UI.

Screenshots without pairing material and a short observation log go under `build/reports/host-sync/` (override with `QD_EVIDENCE`). Gradle writes XML under `composeApp/build/test-results/desktopTest/` and `shared/build/test-results/desktopTest/`. For repeated runs, `QD_TEST_SERIAL` may name an already-running **disposable** `QuotaDog_Local_Sync` emulator; the script resets only the pilot package on it and removes its port forwarding.

**Limits:** the opt-in `-Pquotadog.hostSyncLocal=true` build implements one desktop-to-Android Devin source, including refresh requests in the opposite direction. It does not ship public WSS provisioning, iOS, multi-workspace migration, all-provider grouping, or sharing phone-owned snapshots back to a host. The local relay is not hardened for public registration or abuse. Do not publish the local configuration or forward its cleartext loopback listener.

For a physical Android phone on cellular, use the separate [Cloudflare staging build and acceptance procedure](staging.md). It fixes the public TLS origin at build time, pre-provisions one test host and has no cleartext Android exception. The emulator regression also exercises default-network loss/restoration, but its forwarded socket is **not** a carrier path.
