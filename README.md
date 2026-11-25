# QuotaDog

QuotaDog is a local, cross-platform dashboard for viewing Codex, Claude Code, Grok, Cursor, Antigravity, Devin, Factory Droid, and Amp CLI quota windows.

It reads provider usage data directly from your account and does not proxy model traffic or bypass provider limits.

## Overview

QuotaDog is built with Kotlin Multiplatform and Compose Multiplatform, with app targets for Android, desktop, and iOS.

The app currently focuses on a small workflow:

- Sign in to Codex, Claude Code, or Antigravity through browser-based OAuth (or import Antigravity CLI credentials from the macOS Keychain), sign in to Grok with xAI device-code OAuth (or import Grok CLI credentials on desktop), sign in to Droid with Factory device-code OAuth (or import droid CLI credentials on desktop), sign in to Amp with device-code OAuth (or import Amp CLI credentials on desktop), import Cursor credentials from the local Cursor app on desktop, or import Devin credentials from the Devin CLI credentials file.
- View usage windows, quota progress, and reset timing.
- Refresh accounts, remove local account data, and optionally mask account emails in the UI.
- Share selected quota readings with a paired device without sharing the provider login.

### Share quota accounts between your Mac and phone

1. Sign in or import an account in QuotaDog on the Mac and refresh it once. In **Settings → Share from this computer**, choose **Enable host → Pair a phone**.
2. In QuotaDog on your phone, choose **Add account → Connect my computer** and scan the QR. Compare the six digits on both screens and approve the phone on the Mac. Pairing alone shares no accounts.
3. On the Mac, choose **View only** or **Allow refresh** for each account you want that phone to see. All eight providers can share readings, including Cursor and Devin without a CLI on the phone. Amp workspace credits have their own permission. Change any grant back to **Not shared** to withdraw just that source.
4. On the phone, open **Settings → My computer** to check the connection, forget the Mac, or choose **Share from this phone** to grant access in the other direction. The Mac does not pass that account to other phones. For a new reading the collector must be available (Mac awake or phone app in the foreground). On reconnect the dashboard exchanges the last authorized readings without repeating a provider query.

No Tailscale, QuotaDog account or router setup is needed. The production relay is `sync.quotadog.saien.pro`; the Mac makes an outbound connection and does not expose its ports. This feature shares structured quota readings, not provider credentials. You can pause the host and revoke a phone while offline. A disconnected device can still see readings it already received.

Update both peers for multiple accounts and two-way sharing. A 1.0.17 peer only supports one Mac-owned Devin source. Independent logins stay separate unless QuotaDog can verify the same provider identity **and** quota scope; matching an email or workspace name is not enough. Switching a CLI login does not transfer its grants.

**Upgrading from Dropbox sync:** Version 1.0.18 still offers Dropbox. Builds with Dropbox removed keep the accounts, readings, preferences and pairings already on your device. They do not import, update or delete your old Dropbox file. Before upgrading, check that any login you need is available locally. To stop older clients accessing Dropbox, revoke QuotaDog in your Dropbox connected apps; deleting the backup is a separate choice.

## Privacy, Security, And Limits

QuotaDog reads provider data locally and has no analytics, telemetry, or crash reporting. Optional host sync uses a [dedicated relay](infra/host-sync-relay/README.md).

- OAuth and usage requests go directly to the selected provider.
- During host sync the two devices encrypt traffic end to end. The relay stores admission verifiers, never quota snapshots (even encrypted ones). Cloudflare can see connection metadata such as IP addresses, timing and traffic volume.
- Tokens, account identifiers, cached usage snapshots, and preferences are stored locally using multiplatform settings (`SharedPreferences`, `NSUserDefaults`, `java.util.prefs`), not hardened credential storage.
- Debug logging and Android backup are disabled by default.
- Removing a login deletes its local token and cached reading and stops sharing that source. Independent logins on other devices are unchanged. Platform backups and offline peers may still hold older data.
- Provider behavior can change without notice, and QuotaDog only displays usage available to the signed-in account.

QuotaDog reads usage by calling the same auth flows and HTTP endpoints that the official Codex, Claude Code, and Grok CLIs use to display quota information, by reading Cursor's local auth database plus Cursor's usage-summary API, by reading the Devin CLI credentials file plus the same account-status endpoint the CLI uses, and by calling the same WorkOS device-code and refresh flows plus the Factory billing endpoints that the droid CLI and Factory web app use. These endpoints are not part of any documented public API. Provider terms of service, endpoint shape, authentication, and rate limits may change at any time, which can break QuotaDog without notice. Use of this app is at your own risk and you are responsible for complying with each provider's terms of service.

See [SECURITY.md](SECURITY.md) for how to report security issues. Please remove tokens, callback URLs, and account data from any reproduction details before sharing.

## Development

### Requirements

- Full JDK 21 (including `jpackage` for desktop distributions)
- Android Studio or IntelliJ IDEA for Android/Desktop development
- For iOS, Android and macOS builds: Rust 1.92.0 via rustup; for Android, NDK 27.1.12297006 on macOS or x86_64 Linux. See [native prerequisites](native/host-sync/README.md).
- Xcode for iOS builds

### Commands

Set `JAVA_HOME` to JDK 21 before running Gradle or the release scripts. Desktop
builds target JVM 21; Android continues to target JVM 17 bytecode. Ktor engines
and plugins share one version in `gradle/libs.versions.toml`.

Use the bundled Gradle wrapper:

```bash
./gradlew :shared:allTests              # multiplatform unit tests
./gradlew :composeApp:run               # run the desktop app
./gradlew :androidApp:assembleDebug     # build a debug APK
./gradlew :androidApp:installDebug      # install debug APK on a connected device
./gradlew :androidApp:assembleRelease   # build a release APK (needs signing env vars below)
./gradlew :androidApp:bundleRelease     # build a release AAB for Play
```

Or use `make help` to list the common command aliases.

### Release signing

Release builds are unsigned unless the following environment variables are set
(legacy `SAIEN_*` names are still honored as a fallback):

```bash
export QUOTADOG_KEYSTORE_PATH=/absolute/path/to/keystore.jks
export QUOTADOG_KEYSTORE_PASSWORD=...
export QUOTADOG_KEY_ALIAS=...
export QUOTADOG_KEY_PASSWORD=...
```

App version is resolved in this order:

1. `RELEASE_VERSION` / `RELEASE_VERSION_CODE` env vars (CI / one-off overrides)
2. [`version.properties`](version.properties) (local source of truth)
3. Fallback `1.0.0` / `1`

```bash
export RELEASE_VERSION=1.2.0       # versionName + Compose Desktop packageVersion (MAJOR must be >= 1)
export RELEASE_VERSION_CODE=42     # Android versionCode (monotonically increasing int)
```

[`version.properties`](version.properties) is the single source for the product
version and the shared store build. Android `versionCode` and the iOS build
number are both `VERSION_CODE`, so a release cannot drift across stores.

```bash
make version-current
make bump-version                  # 1.0.7 (19) -> 1.0.8 (20), then commit
make bump-version COMMIT=no        # same bump, without a commit
make set-version VERSION=1.2.0     # product version only; build number unchanged
make bump-build                    # shared store build only, without a commit
make git-build-info
```

`make bump-version` and its alias `make version-bump` run `releasekit bump`
on a clean `main`. Accept the next patch at the prompt, or pass `VERSION=x.y.z`
for noninteractive use. The shared build number increments once. The command
commits only `version.properties`; `COMMIT=no` leaves it uncommitted.
`make set-version` changes only the product version and does not commit. Compose Desktop's
installer formats reject `MAJOR=0`, so `0.x.y` cannot be used.

### Mobile store publishing

After that version commit, `make publish-tracks` prepares and verifies both
signed artifacts, asks once, then uploads Android to Google Play `internal` and
iOS to TestFlight. The terminal shows both platform states. Full logs stay in
`build/release/test-tracks/`. The command does not bump, commit, push, clean,
or target Play production. Every promoted artifact embeds the clean Git commit
that produced it. Preparation fails for a dirty checkout, a mismatched
signature, or inconsistent artifact metadata.

#### Android / Google Play

Keep Android signing and publisher credentials outside the repository in
`~/.config/quotadog/android-release.env` (or export the same variables directly;
override `ANDROID_RELEASE_ENV` to use another file):

```bash
QUOTADOG_KEYSTORE_PATH=/absolute/path/to/upload.jks
QUOTADOG_KEYSTORE_PASSWORD=...
QUOTADOG_KEY_ALIAS=...
QUOTADOG_KEY_PASSWORD=...
ANDROID_PLAY_SERVICE_ACCOUNT_JSON=/absolute/path/to/google-play-service-account.json
```

The service account must have permission to publish the `saien.quotadog`
application in Play Console.

```bash
make android-release-check     # signing preflight
make android-upload-check      # signing + publisher preflight
make android-release-play      # build and verify the signed AAB
make android-play-dry-run      # verify the existing AAB; no API calls
make android-upload-play       # upload the existing AAB to internal
```

Other tracks are explicit. Production additionally requires a second guard:

```bash
make android-upload-play \
  ANDROID_PLAY_TRACK=production \
  ANDROID_PLAY_CONFIRM_PRODUCTION=yes
```

#### iOS / App Store Connect

The release defaults are Team `45V6QJP3A2`, bundle identifier `saien.quotadog`,
automatic signing, and App Store Connect app `6804401157`. Xcode selects the
locally installed profiles for archiving and export. Leave `IOS_RELEASE_PROFILE`
empty for Xcode-managed profiles, including
`iOS Team Store Provisioning Profile: saien.quotadog`. Set `IOS_RELEASE_PROFILE`
only to a manually managed App Store profile to use manual signing.
The API key is used only when uploading, and the TestFlight URL is printed at
the end. Override `IOS_APP_STORE_CONNECT_APP_ID` for a different app.

The iOS app bundles rustls and AWS-LC for host sync. Its [Info.plist](iosApp/iosApp/Info.plist) records the TestFlight export declaration (`ITSAppUsesNonExemptEncryption = NO`); this does not mean the app uses only Apple's HTTPS. If the app's encryption or distribution territories change, [reassess the declaration](https://developer.apple.com/documentation/security/complying-with-encryption-export-regulations) before the next upload.

App Store Connect auth uses `APP_STORE_CONNECT_*`, `ASC_*`, or `EXPO_ASC_*`
variables. These single-platform targets remain available for a retry:

```bash
make ios-release-check         # signing/archive preflight
make ios-upload-check          # signing + App Store Connect preflight
make ios-archive               # create and verify an xcarchive
make ios-release               # archive and export an IPA locally
make ios-upload-testflight     # archive and upload a new build
make ios-upload-archive        # upload the already verified archive
```

#### Combined test-track release

```bash
make publish-tracks
make publish-tracks MOBILE_RELEASE_ARGS="--prepare-only"  # build/verify only
make publish-tracks MOBILE_RELEASE_ARGS="--yes --no-tui"  # explicit non-TTY confirmation
```

If an archive for the current version and build already exists, preparation
stops and leaves it in place. Bump the version, or upload that archive with
`make ios-upload-archive`. Replace the local archive with `make ios-archive
IOS_CLEAN=yes` only after confirming that build did not reach App Store
Connect. If only one store accepts an upload, the command exits unsuccessfully,
keeps both logs and artifacts, and prints that platform's recovery command.
Run `make test-mobile-release` for the public CI checks, including the version
source in `release.toml` and the arguments passed to `releasekit`. With the
private CLI installed, run `scripts/tests/bump-version-test.sh --with-releasekit`
to verify both commit modes in a temporary repository without a remote.

### macOS DMG release (signed + notarized)

For Gatekeeper-clean macOS distribution, use the Saytive-aligned release scripts.
They reuse the same **Developer ID Application** certificate and default to the
shared `saytive-notary` keychain profile.

#### Prerequisites

- Keychain has **Developer ID Application** (same cert as Saytive)
- Notarization credentials configured (one of):

```bash
# Preferred: reuse Saytive's stored profile
# (already created if you ship Saytive from this machine)
xcrun notarytool store-credentials "saytive-notary" \
  --apple-id <Apple ID> \
  --password <App-specific password> \
  --team-id 45V6QJP3A2
```

#### Build

```bash
# Full release: sign app + DMG, notarize, staple
make release-dmg
# or: ./scripts/build_release_dmg.sh

# Signed only (skip notarization)
make release-dmg-local

# Unsigned local smoke test
make release-dmg-unsigned
```

Outputs land in `releases/` (gitignored), e.g.
`releases/QuotaDog-1.0.0-<git>-clean.dmg`.

Optional env vars: `RELEASE_VERSION`, `CODESIGN_IDENTITY`, `NOTARY_PROFILE`,
`NOTARY_KEY_PATH` / `NOTARY_KEY_ID` / `NOTARY_ISSUER`.

#### Publish a signed macOS release

From a clean `main` checkout, run:

```bash
make release-macos
```

The interactive command collects the next version, release notes (file or pasted
Markdown), notes language and whether to publish a GitHub Release, then asks for
one upfront confirmation (default: **yes**, press Enter to proceed). It
automatically checks credentials, commits the shared version/build,
`CHANGELOG.md` and download page, builds and notarizes the Apple
Silicon DMG, pushes the source, uploads and verifies the Cloudflare package/feed,
deploys the website, records the feed and optionally publishes the same DMG to
GitHub. There are no further script prompts after the initial confirmation.
Do not run `bump-version` separately first. The pipeline is driven by
[releasekit](https://github.com/senseFy/releasekit) and [`release.toml`](release.toml).

```bash
make release-macos MACOS_RELEASE_ARGS="--prepare-only"   # stop before pushing/publishing
make release-macos-resume MACOS_RELEASE_RUN=build/release/macos/<run>
```

Resuming an approved publication reuses its saved decision. A run created with
`--prepare-only` asks for publication confirmation at the start of its first
publishing resume, before any credential checks or artifact verification.

See [the macOS publishing guide](docs/macos-updates.md#interactive-release-command)
for preview/automation options, credentials and retry behavior.

### Publishing a GitHub Release

The app domain is [quotadog.saien.pro](https://quotadog.saien.pro). Its
Cloudflare Worker and private R2 update bucket are configured in
[`infra/cloudflare`](infra/cloudflare/README.md). The `/updates/` hosting
paths are reserved for signed update feeds and immutable release packages;
signed macOS builds expose **Settings → Software update**. See
[macOS updates](docs/macos-updates.md) for signing keys, publishing, feed renewal,
and isolated upgrade acceptance. Existing installations without the updater
need one manual installation of a new signed release.

Pushing a `vX.Y.Z` tag triggers
[`.github/workflows/release.yml`](.github/workflows/release.yml), which builds
and attaches:

- Android: signed `*.apk` and `*.aab`
- Desktop: macOS `*.dmg`, Windows `*.msi`, Linux `*.deb`

The CI macOS DMG is an **unsigned** Compose Desktop package for convenience.
For the signed production installer, use `make release-macos`; its separate
`macos-vX.Y.Z-buildN` tag avoids triggering this unsigned packaging workflow.

iOS is intentionally not published this way — distribute via TestFlight / App
Store instead.

The workflow expects four GitHub Actions secrets in the repository
(Settings → Secrets and variables → Actions):

| Secret | Value |
|---|---|
| `QUOTADOG_KEYSTORE_BASE64` | `base64 -i keystore.jks` (single line) |
| `QUOTADOG_KEYSTORE_PASSWORD` | keystore password |
| `QUOTADOG_KEY_ALIAS` | key alias inside the keystore |
| `QUOTADOG_KEY_PASSWORD` | key password |

To cut a release (note: `MAJOR` must be `>= 1` due to Compose Desktop's
installer-format validation):

```bash
git tag v1.0.0
git push origin v1.0.0
```

### iOS development

The repository includes the Xcode project at `iosApp/iosApp.xcodeproj`. Build
and install with Xcode, or use `xcodebuild` and `xcrun devicectl` from the
command line. Store signing and upload commands are documented above.

### Project Layout

- `shared`: provider logic, OAuth flow, token storage, usage parsing, and tests.
- `androidApp`: Android application entry point, manifest, signing, and store artifacts.
- `composeApp`: shared Compose UI plus desktop and iOS Compose entry points.
- `iosApp`: SwiftUI host app and Xcode project.
- `gradle`: version catalog and Gradle wrapper configuration.

## Contributing And License

QuotaDog is not affiliated with OpenAI, Anthropic, Google, or any other provider. Provider names, logos, and marks belong to their respective owners.

Provider icon assets are attributed in `composeApp/src/commonMain/composeResources/files/provider-icons/README.md`.

Issues and pull requests are welcome. Please avoid committing credentials, local build outputs, logs, or account data.

QuotaDog is released under the MIT License. See [LICENSE](LICENSE).
