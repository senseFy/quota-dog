# Run host sync locally

`scripts/host-sync/local-e2e.sh` exercises a desktop-only Devin account from the QuotaDog iPhone or Android dashboard, without a provider login, Cloudflare account, Tailscale, or public listener. The harness uses isolated test builds. Normal builds use the [production relay](../../infra/host-sync-relay/README.md) and actual imported logins instead.

From the repository root on an Apple Silicon Mac:

```sh
scripts/host-sync/local-e2e.sh iphone
scripts/host-sync/local-e2e.sh android
```

Use JDK 21 (`JAVA_HOME`), Xcode, Rust 1.92.0 via rustup, Python 3, Node 22.12+ and global Wrangler 4.78. For iPhone, install an iPhone 17 Pro simulator runtime, `jq` and [AXe](https://github.com/cameroncooke/AXe). For Android, install the project's SDK/build-tools, platform-tools, command-line tools, Emulator, NDK `27.1.12297006` and the arm64 `system-images;android-36;google_apis_playstore;arm64-v8a` image. Each command creates and removes a temporary simulator or emulator; neither installs over the regular QuotaDog app. Run them separately: both need free loopback ports 18787 and 18788.

**What actually runs:** the desktop Compose dashboard in its UI test harness, a separate mobile app, the same [rustls library](../../native/host-sync/README.md) on both ends (JNI on Mac/Android, C interop on iPhone), and the [relay Worker and Durable Object](../../infra/host-sync-relay/README.md) in local workerd. The fixture seeds two desktop records and substitutes only the upstream `GetUserStatus` HTTP service. The phone imports pixels from the rendered desktop invitation through its system picker. Neither the iPhone simulator on loopback nor the Android emulator using `adb reverse` tests a physical camera or carrier path.

**What to look for:** after matching both numbers and approving the phone, the account list starts empty. Sharing one record shows daily/weekly usage and the extra USD balance. Allowing refresh adds the remote action. The test checks the upstream call count, failure without overwriting the last successful reading, interruption after a query was accepted, cold-start cache, reconnect without replay, unsharing, and online/offline revocation. A separate authenticated protocol test sends a delayed request after a downgrade and after regrant, independently of the phone UI.

Screenshots without pairing material and a short observation log go under `build/reports/host-sync/` (override with `QD_EVIDENCE`). Gradle writes XML under `composeApp/build/test-results/desktopTest/` and `shared/build/test-results/desktopTest/`. For repeated runs, `QD_TEST_SERIAL` may name an already-running **disposable** `QuotaDog_Local_Sync` emulator; the script resets only the pilot package on it and removes its port forwarding.

**Limits:** the harness covers one Mac-owned Devin source and requests to refresh that source. It does not cover multi-workspace migration, all-provider grouping, or sharing phone-owned snapshots back to a host. Never publish the local configuration or forward its cleartext listener. Xcode selects loopback only for the `saien.quotadog.hostsync.local` Debug simulator build; Release uses the production origin.

For a physical Android phone on cellular, use the separate [Cloudflare staging build and acceptance procedure](staging.md). It fixes the public TLS origin at build time, pre-provisions one test host and has no cleartext Android exception. The emulator regression also exercises default-network loss/restoration, but its forwarded socket is **not** a carrier path.
