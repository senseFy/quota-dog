# QuotaDog domain and update hosting

`https://quotadog.saien.pro` uses the `quotadog` Cloudflare Worker. Static
assets serve the app introduction; `/updates/` streams approved object paths
from the private `quotadog-releases` R2 bucket. No R2 development URL or public
bucket domain is needed. The Worker holds no update signing key.

| Public URL | R2 object | Cache policy |
| --- | --- | --- |
| `/` | Static assets | Five minutes |
| `/updates/feeds/stable.json` | `feeds/stable.json` | No storage; every check reads R2 |
| `/updates/releases/24/QuotaDog-1.0.12-macos-aarch64.dmg` | `releases/24/QuotaDog-1.0.12-macos-aarch64.dmg` | Immutable; up to one year |

The release URL above is the first updater-enabled ARM64 release. Only feed JSON
and versioned DMG paths are exposed. Downloads are streamed without changing
their bytes or redirecting. Missing objects are not cached. Package caching
is best-effort and subject to Cloudflare's cache object-size limits.
Temporary `QuotaDog-acceptance-*` DMGs check R2 existence before using an edge
copy and forbid client caching. Deleting their R2 objects immediately makes
their URLs unavailable, without needing zone-wide cache purge permissions.

## Provision and deploy

Use Wrangler (validated with 4.78.0), authenticated to the account in
`wrangler.jsonc`. For automation, inject `CLOUDFLARE_API_TOKEN` through the
environment. Never put it in the configuration or commit it.

From the repository root:

```sh
wrangler whoami
# Once only, if the bucket does not exist:
wrangler r2 bucket create quotadog-releases --location apac
node --test infra/cloudflare/worker.test.mjs
wrangler deploy --dry-run --config infra/cloudflare/wrangler.jsonc
wrangler deploy --config infra/cloudflare/wrangler.jsonc
```

The custom-domain route provisions DNS and TLS for `quotadog.saien.pro` in
the existing `saien.pro` zone. It does not modify the apex domain or its
other routes. R2 remains private and `workers.dev`/preview URLs are disabled.

## Publish signed updates

The app's updater and publication commands are documented in
[`docs/macos-updates.md`](../../docs/macos-updates.md). The signed stable feed
and ARM64 release **1.0.12 (24)** were published and publicly verified on
2026-10-24. Never replace the feed with an unsigned placeholder.

1. Build, sign, notarize and staple the final DMG.
2. Use the kmp-updater publisher to calculate its size/SHA-256 and sign a
   manifest containing the final HTTPS URL above. The private key belongs
   outside this repository and Cloudflare; the app pins its public key.
3. Upload the DMG to a new release-sequence path. Never overwrite a published
   package, because clients and edge caches may retain it.
4. Verify the public package's bytes, then upload the signed feed last.

```sh
wrangler r2 object put quotadog-releases/releases/24/QuotaDog-1.0.12-macos-aarch64.dmg \
  --remote --file /path/to/final-notarized.dmg \
  --content-type application/x-apple-diskimage
wrangler r2 object put quotadog-releases/feeds/stable.json \
  --remote --file /path/to/signed-feed.json --content-type application/json
```

Serialize feed publications to avoid a slower, older publisher replacing a
newer feed. Manifest sequence and expiry are controlled by the offline
publisher, not this Worker. Renew and re-sign before expiry even without a
new app release. Retain packages still referenced by supported feeds.

## Verification

```sh
node --test infra/cloudflare/worker.test.mjs
curl --fail --head https://quotadog.saien.pro/
curl --head https://quotadog.saien.pro/updates/feeds/stable.json
```

Tests cover exact bytes, no redirects, feed freshness, package caching,
HEAD metadata, missing objects and restricted object paths. The stable feed
must now return HTTP 200; acceptance feeds/packages return 404 after cleanup.
