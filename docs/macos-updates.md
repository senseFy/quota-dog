# macOS application updates

Signed QuotaDog DMGs built with `make release-dmg` include
`io.github.sensefy:updater-jvm:0.1.0-alpha.1` and the matching native helper.
The SDK is an early preview. The host currently provides manual checks,
explicit downloads, cancellation/discard and **Install & restart** in Settings.
Windows, Linux and mobile installations do not use this installer.

## Runtime and trust

- The app pins the Ed25519 public key, key ID, Developer ID team and update
  origin from `updater.properties`. These are packaged resources, not runtime
  environment overrides. Private signing keys are never bundled or uploaded.
- `VERSION_CODE` is the desktop release sequence as well as the shared store
  build. It is embedded as a string in `Info.plist` (`KMPUpdaterRelease`) and
  in the desktop build metadata. Display versions do not determine update order.
- Production checks use `https://quotadog.saien.pro/updates/feeds/stable.json`.
  Only that HTTPS host is allowed; redirects, invalid signatures, stale feeds,
  rollback metadata and wrong artifact lengths/digests fail closed.
- Update files and replay checkpoints live in
  `~/Library/Application Support/QuotaDog/updater`. An accepted transaction is
  persisted before normal application exit. After the new host UI starts, the
  helper's status is checked and installation is confirmed before deleting the
  retained old bundle. A retained original installation can be cleaned up with
  **Retry recovery**. Failed/unrecognized recovery never silently clears state.
- Before handing off, the app probes receipt storage with an actual write and
  fsync. If saving still fails after helper acceptance, ordinary Quit, Cmd+Q and
  Dock Quit are blocked. **Save recovery info & quit** retries the same receipt
  without accepting another installation. If the helper has already timed out,
  opening the app again recovers the retained original transaction.
- The tray's Quit action (or closing the window when no tray is available)
  cancels transfers and releases staged updates. Closing the main window into
  the tray keeps the updater alive. There are no background checks,
  automatic downloads, forced exits or automatic privilege elevation.

Updates require macOS 13+, a signed installed `.app`, and write access to its
parent directory. Run from Applications, not a mounted read-only DMG. Development
runs, unsigned CI installers and builds without the helper show an unavailable
status. ARM64 has the local acceptance harness; Intel artifacts can be published
separately but require their own actual upgrade acceptance.

## Signing material

The publisher key created for this app is stored locally at:

```text
~/.config/quotadog/updater/quotadog-2026.private-key
```

The file is mode `0600`. This is the update manifest signing key, separate from
the Apple Developer ID certificate and Cloudflare upload credentials. The
workflow also uses an encrypted GitHub environment secret, whose original
value cannot be downloaded from GitHub later. Keep an independent encrypted
backup of the local file, for example a password-manager attachment or a copy
on an encrypted external drive. A second copy on the same Mac does not protect
against losing that Mac. No backup has been made to an external destination yet.
Do not regenerate it
for a routine release: previously installed apps trust the pinned public key.
The SDK supports overlapping keys for planned rotation, but loss of the only
trusted key requires a separately distributed replacement application.

The publisher and universal helper are downloaded from the pinned upstream
release by `scripts/updater_tools.py`. Archive SHA-256 values are verified before
extraction. The helper's Apache-2.0 license is included in the app and in
`third_party/kmp-updater/LICENSE`. Never embed the upstream fixture helper.

## Build and publish

Requirements: full JDK 21 (`JAVA_HOME`), Python 3.11+, OpenSSL 3, Xcode tools,
Wrangler authenticated to the Cloudflare account, a Developer ID Application
identity, and the `saytive-notary` Keychain profile (or existing `NOTARY_*`
environment overrides). The signing and feed-upload credentials are independent.

1. Commit the implementation and use the normal version bump workflow to create
   a new version/build. All desktop architectures of one release use that same
   `VERSION_CODE`; the next release must increase it.
2. Build the signed, notarized and stapled DMG:

   ```sh
   make release-dmg
   ```

   The build embeds the helper, signs native libraries inside JARs (including
   their loader checksums), signs the runtime and app, then notarizes/staples the
   app before creating/notarizing/stapling the final DMG.

3. Prepare a signed feed without uploading anything:

   ```sh
   make update-prepare DMG=/absolute/path/to/QuotaDog.dmg NOTES=/path/to/notes.txt
   ```

   Review the payload and signed envelope under `build/update-publication/`.
   The publisher verifies Apple signatures/tickets, bundle identity, embedded
   release sequence, version and architecture. It also verifies the generated
   signature against the app's pinned public key. A wrong private key fails
   before any upload.

4. Publish explicitly:

   ```sh
   make update-publish DMG=/absolute/path/to/QuotaDog.dmg NOTES=/path/to/notes.txt
   ```

   The publisher uploads the immutable package first, downloads it to verify
   the public bytes, and replaces the signed feed last. An existing package is
   reused only if its size and digest match. Existing signed releases are
   retained, including other architectures. Same-release/same-architecture
   publication is rejected; use feed renewal if only the expiry needs changing.

Use one publishing machine or a single serialized CI job. A local file lock
and a last-minute feed comparison catch overlapping runs, but Wrangler's object
upload is not a distributed compare-and-swap operation. Never run separate
publishers concurrently. Packages above Wrangler's upload limit require an S3
multipart upload workflow before feed publication.

CI's generic unsigned macOS package is not eligible for this feed. There is no
automatic production upload on commit or tag. Existing apps without an updater
must install the first updater-enabled signed DMG manually.

## Feed renewal

The default feed validity is 30 days. Renew before expiry even when no app
release is planned:

```sh
make update-refresh
```

For a daily scheduler, use `make update-renew`. It publishes only when the signed
feed expires within 10 days, renews it for 30 days, and skips an unpublished
channel. It verifies the previous signature even when renewal is not yet due.
The signing machine needs JDK 21, OpenSSL 3, Wrangler credentials and the private
key. The command alone does not install a local scheduled job.

`.github/workflows/renew-update-feed.yml` runs this policy daily at 03:17 UTC
(11:17 Asia/Shanghai), and supports a manual `force_refresh` run for verification
or immediate renewal. It uses JDK 21, pinned action commits and Wrangler 4.78.0.
It runs only on this repository's `main` branch. GitHub's `updater-production`
environment is also restricted to `main`, with these environment secrets:

- `QUOTADOG_UPDATER_PRIVATE_KEY`: the contents of the existing signing-key file.
- `QUOTADOG_CLOUDFLARE_API_TOKEN`: the authenticated R2 upload credential.

Secrets are supplied only to the publication step. The signing key is written
to a mode-0600 temporary file and removed when the step exits. No private key
is committed, printed or included in workflow artifacts. The job verifies the
publisher against temporary test keys before receiving production secrets.
Every workflow that publishes `stable.json` must share the concurrency group
`quotadog-stable-feed`; do not publish locally while this job is running.

GitHub can delay scheduled runs, so renewal starts with a 10-day margin. After
renewal, the workflow commits the verified public signed feed to
`infra/cloudflare/published/stable.json` as an audit record. An unchanged feed
does not create a commit. Production still reads R2, never this audit file.
These renewal commits also keep repository activity current: GitHub disables
scheduled workflows in public repositories after 60 days of inactivity. This
is why the job has `contents: write`; the audit step receives only GitHub's
short-lived repository token, not the update or Cloudflare secrets. Keep Actions
failure/disable notifications enabled in case the workflow stops succeeding. See
[GitHub's schedule documentation](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#schedule).

Renewal verifies the previous signature and retains its releases, increments
the manifest sequence, and signs new timestamps. Manifest sequence and app
release sequence are independent. No signing key or renewal job runs in
Cloudflare. An unpublished channel returns 404; conditional renewal never
initializes one automatically.

## Verification and recovery

```sh
make test-updater
make test-updater-macos
```

The first command tests explicit UI installation, receipt persistence/corruption,
and real publisher signatures. The second builds two isolated QuotaDog updater
hosts with bundle ID `saien.quotadog.acceptance`, signs/notarizes them, and uses
temporary Cloudflare feeds/packages. It runs the same controller, UI, SDK and
production helper as QuotaDog without initializing provider clients or reading
real account settings. It verifies a new process, release replacement, startup
confirmation, old-bundle removal, data preservation, bad signatures and altered
artifact digests. A receipt-write fault also checks that Quit is blocked until
storage recovers and the same transaction can finish. It never replaces `/Applications/QuotaDog.app` or publishes
`stable.json`.

The harness deletes its temporary R2 objects. Packages named
`QuotaDog-acceptance-*` check R2 existence before using an edge copy, and forbid
client caching, so deleting them makes their URLs unavailable immediately.
Any cleanup failures are recorded in the report.

Logs, notarization receipts, fixtures and `report.json` remain under
`build/updater-acceptance/<run>/`. Resume a run only without source changes:

```sh
make test-updater-macos UPDATER_ACCEPTANCE_ARGS="--resume build/updater-acceptance/<run>"
```

Add `--case success`, `--case receipt-failure`, `--case bad-signature` or `--case bad-artifact` to rerun
one scenario. Selected-case reports are separate from the complete run report.

Keep failed native transaction directories for diagnosis. Startup confirmation
is a basic host-readiness check, not an automatic rollback policy for later
application crashes or data migrations.

## Integration validation (2026-10-24)

- Desktop UI/receipt/runtime tests, shared desktop and iOS simulator tests,
  Android debug packaging, iOS compilation, publisher signature tests and
  Worker tests passed. Account-dependent live provider tests were excluded.
- The follow-up run passed 16 desktop tests and both publisher tests, including
  actual Ed25519 signatures, conditional renewal and tampering rejection.
- Version **1.0.12 (24)** passed Developer ID signing, Apple notarization,
  stapling and Gatekeeper assessment. Its ARM64 DMG is 85,621,305 bytes with
  SHA-256 `194e01737316befbd887a41509339a2e697a5b8d4215a8b0a21328a6b79dbada`.
- All four fresh, signed/notarized acceptance cases passed through the public
  Cloudflare HTTPS domain: normal upgrade, receipt-write failure/retry, invalid
  manifest signature and incorrect artifact SHA-256. Both upgrade cases
  observed normal host exit, a new process, confirmation, retained-old-bundle
  cleanup and preservation of sentinel data. The fault case also observed a
  blocked Quit before storage recovered.
- The report is `build/updater-acceptance/20261024-153219-ca515ef4/report.json`.
  Temporary R2 objects were deleted without errors; the cached package URL
  returned 404 after deletion. Earlier download timeouts were resolved by
  changing this validation machine's local route for the update domain.
- The ARM64 package was uploaded, downloaded again through its public URL and
  verified byte-for-byte by size/SHA-256 before publishing the signed stable
  feed. The publisher then fetched and verified the published feed.

Existing users need to install the first updater-enabled DMG manually.
The encrypted offline key backup still requires an operator-selected destination.
