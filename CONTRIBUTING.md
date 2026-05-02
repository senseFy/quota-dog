# Contributing to QuotaDog

Thanks for your interest in QuotaDog. This is a small, local-only multiplatform
client; contributions that keep it simple, privacy-respecting, and easy to
build are very welcome.

## Ground rules

- QuotaDog is a local client. Do not add backends, analytics, telemetry,
  crash reporting, or any code that sends data anywhere other than the
  selected provider.
- Never commit credentials, tokens, OAuth callback URLs, real account
  identifiers, signing material, screenshots that reveal account data, or
  local build outputs.
- QuotaDog calls undocumented provider endpoints. Be conservative when
  changing the request shape, headers, or User-Agent strings; small changes
  can cause provider-side failures for everyone.

## Getting set up

Requirements:

- JDK 17
- Android Studio or IntelliJ IDEA for Android/Desktop
- Xcode for iOS

The repo uses the Gradle wrapper, so no global Gradle install is needed.
See [README.md](README.md) for full build commands.

Quick checks:

```bash
./gradlew :shared:allTests
./gradlew :composeApp:assembleDebug
```

## Pull requests

1. Fork the repo and create a topic branch from `main`.
2. Keep PRs focused and reasonably small. Refactors that aren't needed by the
   change you're making belong in a separate PR.
3. Add or update tests in `shared/src/commonTest` when you change provider
   parsing, token storage, or any other testable logic.
4. Run `./gradlew :shared:allTests` and at least one platform build before
   opening the PR.
5. In the PR description, explain *what* changed and *why*, and mention any
   provider behavior you observed (without including real tokens or emails).

## Code style

- Kotlin official style (`kotlin.code.style=official` in `gradle.properties`).
- Prefer existing UI tokens in `composeApp/src/commonMain/kotlin/saien/quotadog/app/theme/`
  over raw `.dp`, color, or typography literals.
- Keep `commonMain` free of platform-specific APIs; use `expect`/`actual`
  declarations under `Platform.kt` and the per-target source sets.

## Security issues

Please **do not** open public issues for security problems. Follow the
process in [SECURITY.md](SECURITY.md) instead.

## License

By contributing, you agree that your contributions will be licensed under
the MIT License, the same license as the rest of the project.
