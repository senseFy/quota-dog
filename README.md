# QuotaDog

QuotaDog is a local, cross-platform dashboard for viewing Codex and Claude Code quota windows.

It reads provider usage data directly from your account and does not proxy model traffic or bypass provider limits.

## Overview

QuotaDog is built with Kotlin Multiplatform and Compose Multiplatform, with app targets for Android, desktop, and iOS.

The app currently focuses on a small workflow:

- Sign in to Codex or Claude Code through browser-based OAuth.
- View usage windows, quota progress, and reset timing.
- Refresh accounts, remove local account data, and optionally mask account emails in the UI.

## Privacy, Security, And Limits

QuotaDog is a local client with no backend, analytics, telemetry, or crash reporting.

- OAuth and usage requests go directly to the selected provider.
- Tokens, account identifiers, cached usage snapshots, and preferences are stored locally using multiplatform settings (`SharedPreferences`, `NSUserDefaults`, `java.util.prefs`), not hardened credential storage.
- Debug logging and Android backup are disabled by default.
- Removing an account deletes its local token and cached usage snapshot, but platform backups or system snapshots may keep older copies.
- Provider behavior can change without notice, and QuotaDog only displays usage available to the signed-in account.

QuotaDog reads usage by calling the same OAuth flows and HTTP endpoints that the official Codex CLI and Claude Code CLI use to display quota information. These endpoints are not part of any documented public API. Provider terms of service, endpoint shape, authentication, and rate limits may change at any time, which can break QuotaDog without notice. Use of this app is at your own risk and you are responsible for complying with each provider's terms of service.

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

### Release signing

Release builds are unsigned unless the following environment variables are set
(legacy `SAIEN_*` names are still honored as a fallback):

```bash
export QUOTADOG_KEYSTORE_PATH=/absolute/path/to/keystore.jks
export QUOTADOG_KEYSTORE_PASSWORD=...
export QUOTADOG_KEY_ALIAS=...
export QUOTADOG_KEY_PASSWORD=...
```

### iOS

The repository includes a minimal Xcode project at `iosApp/iosApp.xcodeproj`.

Set signing values in `iosApp/Configuration/Config.xcconfig` before building for a device:

```xcconfig
TEAM_ID=YOUR_APPLE_TEAM_ID
BUNDLE_ID=saien.quotadog
APP_NAME=QuotaDog
```

Then build and install with Xcode, or use `xcodebuild` and `xcrun devicectl` from the command line.

### Project Layout

- `shared`: provider logic, OAuth flow, token storage, usage parsing, and tests.
- `composeApp`: shared Compose UI plus Android, desktop, and iOS Compose entry points.
- `iosApp`: SwiftUI host app and Xcode project.
- `gradle`: version catalog and Gradle wrapper configuration.

## Contributing And License

QuotaDog is not affiliated with OpenAI, Anthropic, or any other provider. Provider names, logos, and marks belong to their respective owners.

Provider icon assets are attributed in `composeApp/src/commonMain/composeResources/files/provider-icons/README.md`.

Issues and pull requests are welcome. Please avoid committing credentials, local build outputs, logs, or account data.

QuotaDog is released under the MIT License. See [LICENSE](LICENSE).
