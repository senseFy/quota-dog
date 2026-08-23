# QuotaDog

QuotaDog is a local, cross-platform dashboard for viewing Codex, Claude Code, Grok, Cursor, and Antigravity CLI quota windows.

It reads provider usage data directly from your account and does not proxy model traffic or bypass provider limits.

## Overview

QuotaDog is built with Kotlin Multiplatform and Compose Multiplatform, with app targets for Android, desktop, and iOS.

The app currently focuses on a small workflow:

- Sign in to Codex or Claude Code through browser-based OAuth, sign in to Grok with xAI device-code OAuth (or import Grok CLI credentials on desktop), import Cursor credentials from the local Cursor app on desktop, or import Antigravity credentials from the Antigravity CLI keyring.
- View usage windows, quota progress, and reset timing.
- Refresh accounts, remove local account data, and optionally mask account emails in the UI.
- Optionally sync encrypted account tokens, cached usage, and preferences through a user-provided Dropbox app folder.

## Privacy, Security, And Limits

QuotaDog is a local client with no backend, analytics, telemetry, or crash reporting.

- OAuth and usage requests go directly to the selected provider.
- Tokens, account identifiers, cached usage snapshots, and preferences are stored locally using multiplatform settings (`SharedPreferences`, `NSUserDefaults`, `java.util.prefs`), not hardened credential storage.
- Dropbox cloud sync is opt-in. When enabled, QuotaDog encrypts its sync document with your sync passphrase before uploading it to your Dropbox app folder.
- The Dropbox integration uses QuotaDog's Dropbox app with App Folder access and the minimum file scopes needed to read and write the QuotaDog sync file.
- The Dropbox refresh token used to access that app folder is stored locally with the same platform settings backend as other app data.
- Debug logging and Android backup are disabled by default.
- Removing an account deletes its local token and cached usage snapshot, but platform backups or system snapshots may keep older copies.
- Removing an account while Dropbox sync is unlocked writes a tombstone so other synced devices remove the same account instead of restoring stale data.
- Provider behavior can change without notice, and QuotaDog only displays usage available to the signed-in account.

QuotaDog reads usage by calling the same auth flows and HTTP endpoints that the official Codex, Claude Code, and Grok CLIs use to display quota information, and by reading Cursor's local auth database plus Cursor's usage-summary API. These endpoints are not part of any documented public API. Provider terms of service, endpoint shape, authentication, and rate limits may change at any time, which can break QuotaDog without notice. Use of this app is at your own risk and you are responsible for complying with each provider's terms of service.

See [SECURITY.md](SECURITY.md) for how to report security issues. Please remove tokens, callback URLs, and account data from any reproduction details before sharing.

## Development

### Requirements

- JDK 17
- Android Studio or IntelliJ IDEA for Android/Desktop development
- Xcode for iOS builds

### Commands

Use the bundled Gradle wrapper:

```bash
./gradlew :shared:allTests              # multiplatform unit tests
./gradlew :composeApp:run               # run the desktop app
./gradlew :composeApp:assembleDebug     # build a debug APK
./gradlew :composeApp:installDebug      # install debug APK on a connected device
./gradlew :composeApp:assembleRelease   # build a release APK (needs signing env vars below)
./gradlew :composeApp:bundleRelease     # build a release AAB for Play
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

Manage the checked-in version file:

```bash
make version-current
make version-bump                              # 1.0.0 (12) -> 1.0.1 (13)
make version-bump ARGS='--set-version 1.2.0'   # also bumps VERSION_CODE unless --set-code is set
make version-bump ARGS='--bump-code'           # bump VERSION_CODE only
make git-build-info
```

Compose Desktop's installer formats reject `MAJOR=0`, so `0.x.y` cannot be used.

### Mobile store publishing

The store release commands mirror the guarded workflow used by Enjoy. Android
and iOS both use [`version.properties`](version.properties); every promoted
artifact also embeds the clean Git commit that produced it. Release preparation
fails for a dirty checkout, a mismatched signature, or inconsistent artifact
metadata.

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

The checked-in Xcode configuration uses Team `45V6QJP3A2`, bundle identifier
`saien.quotadog`, and the shared product version/build number. Override the
Make variables when a different signing setup is needed. TestFlight upload uses
an App Store Connect API key:

```bash
export APP_STORE_CONNECT_API_KEY_PATH=/absolute/path/to/AuthKey_ABC123.p8
export APP_STORE_CONNECT_API_KEY_ID=ABC123
export APP_STORE_CONNECT_API_ISSUER_ID=00000000-0000-0000-0000-000000000000

make ios-release-check         # signing/archive preflight
make ios-upload-check          # signing + App Store Connect preflight
make ios-archive               # create and verify an xcarchive
make ios-release               # archive and export an IPA locally
make ios-upload-testflight     # archive and upload a new build
make ios-upload-archive        # upload the already verified archive
```

For manual signing, also set `IOS_RELEASE_PROFILE` to the installed App Store
provisioning profile name. `IOS_APP_STORE_CONNECT_APP_ID` is optional and only
used to print the direct TestFlight URL.

#### Combined test-track release

`publish-tracks` prepares and verifies both artifacts before asking once for
confirmation, then uploads Android to Play `internal` and iOS to TestFlight in
parallel. It never targets Play production.

```bash
make publish-tracks MOBILE_RELEASE_ARGS="--prepare-only"  # build/verify only
make publish-tracks                                        # interactive upload
make publish-tracks MOBILE_RELEASE_ARGS="--yes --no-tui"  # explicit non-TTY confirmation
```

Logs are kept under `build/release/test-tracks/`. If only one store accepts an
upload, the command exits unsuccessfully and prints the safe single-platform
recovery command. Run `make test-mobile-release` to verify the release contracts.

### Dropbox Sync Setup

QuotaDog does not run a sync server. To use cloud sync, connect Dropbox from Settings with a sync passphrase:

1. Open QuotaDog Settings → Cloud sync.
2. Enter a sync passphrase with at least 8 characters.
3. Click Connect Dropbox and approve the Dropbox authorization in your browser.

Use the same Dropbox account and sync passphrase on each device you want to sync. Developers building their own fork should create a scoped Dropbox app with App Folder access, enable `files.metadata.read`, `files.content.read`, and `files.content.write`, add `http://localhost:17553/dropbox/callback` as an OAuth redirect URI, and replace the Dropbox app key constant in code.

If you forget the sync passphrase, QuotaDog cannot decrypt the existing Dropbox sync file. You can reset the sync file with a new passphrase from a device that still has the local data you want to keep, but that overwrites the Dropbox copy and may lose data that only exists in Dropbox or on another unsynced device.

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

### Publishing a GitHub Release

Pushing a `vX.Y.Z` tag triggers
[`.github/workflows/release.yml`](.github/workflows/release.yml), which builds
and attaches:

- Android: signed `*.apk` and `*.aab`
- Desktop: macOS `*.dmg`, Windows `*.msi`, Linux `*.deb`

The CI macOS DMG is an **unsigned** Compose Desktop package for convenience.
For a Gatekeeper-clean installer, build locally with `make release-dmg` and
attach that artifact (or replace the CI upload later).

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
- `composeApp`: shared Compose UI plus Android, desktop, and iOS Compose entry points.
- `iosApp`: SwiftUI host app and Xcode project.
- `gradle`: version catalog and Gradle wrapper configuration.

## Contributing And License

QuotaDog is not affiliated with OpenAI, Anthropic, Google, or any other provider. Provider names, logos, and marks belong to their respective owners.

Provider icon assets are attributed in `composeApp/src/commonMain/composeResources/files/provider-icons/README.md`.

Issues and pull requests are welcome. Please avoid committing credentials, local build outputs, logs, or account data.

QuotaDog is released under the MIT License. See [LICENSE](LICENSE).
