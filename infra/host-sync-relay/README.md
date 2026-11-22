# Host-sync relay

The relay switches opaque inner TLS bytes between a host and its phones. It is not an endpoint of that TLS session, a sync API, or part of the updater Worker. On the public endpoints, Cloudflare terminates outer HTTPS/WSS. The three configurations are independent:

| Configuration | Origin | Registration |
| --- | --- | --- |
| `wrangler.local.jsonc` | `http://127.0.0.1:18787` | Loopback only. Never deploy this config. |
| `wrangler.staging.jsonc` | `https://quotadog-sync-staging.saien.pro` | No signup (`403`); one pre-provisioned route and host verifier for the [physical Android test](../../scripts/host-sync/staging.md). |
| `wrangler.production.jsonc` | `https://sync.quotadog.saien.pro` | Each host registers its own route. No accounts, shared bearer, staging credentials or per-host operator step. |

## Run locally

From the repository root, with Wrangler 4.78 on `PATH`:

```sh
sh infra/host-sync-relay/local.sh 18787
```

Use `http://127.0.0.1:18787` for route creation and `ws://127.0.0.1:18787` for upgrades. The optional port defaults to 18787. The launcher pins the listener and inspector to IPv4 loopback, disables remote bindings and request logging, and discards Wrangler's debug log. Local verifier storage lives under this directory's ignored `.wrangler/state/`. Stop with Ctrl-C. Do not expose this HTTP listener or its inspector through a tunnel or public proxy.

## Production deployment (operator only)

Clients send an empty `POST https://sync.quotadog.saien.pro/v1/routes`, persist the returned route and host token, then use `wss://sync.quotadog.saien.pro/v1/<route>/<role>`. `production.mjs` accepts only that exact HTTPS origin with `RELAY_MODE=production`. The Worker `quota-dog-host-sync-production` owns its own SQLite-backed namespace, `PRODUCTION_ROUTES` → `ProductionRoute` (migration `production-v1`). Its host verification reads only that route's stored verifier. Mixing in loopback/staging bindings denies requests rather than falling back. No `workers.dev` or preview endpoint, Worker observability, Logpush, or relay logs are enabled.

From the repository root, check the config and bundle **without publishing** (Wrangler 4.78):

```sh
wrangler deploy --strict --dry-run --config infra/host-sync-relay/wrangler.production.jsonc
```

Before a live deployment, verify the target account/zone, that the Worker name and exact hostname are free or owned by this service, and that rate-limit namespace IDs `3178701`–`3178704` are not used by another binding in that account. A [Workers Custom Domain](https://developers.cloudflare.com/workers/configuration/routing/custom-domains/#certificates) provisions DNS and a managed certificate for the exact hostname, including `sync.quotadog.saien.pro`. The zone wildcard `*.saien.pro` alone would not cover it. Verify public TLS after issuance; do not change the website or updater at `quotadog.saien.pro`.

The account and deployer need access to Workers, SQLite Durable Objects, Workers rate limiting, and Custom Domains in the authoritative zone. Check plan/storage quotas and billing before enabling anonymous registration. Dry-run does not check ownership, DNS, certificates or account entitlements.

**Only after approval**, in an interactive terminal, the production command is:

```sh
wrangler deploy --strict --config infra/host-sync-relay/wrangler.production.jsonc
```

Use the config as-is: no `--env`, `--name`, `--domain`, `--keep-vars` or `--secrets-file`. A first live deploy would apply the production-only DO migration and bind the custom domain. Stop on any DNS conflict, overwrite or plan-change prompt. No staging device, host bearer or test bindings file is needed.

### Public admission budgets

| Operation | Per source, per minute | All sources, per minute |
| --- | ---: | ---: |
| Empty `POST /v1/routes` | 3 | 30 |
| WS upgrade attempts (host, enroll and device combined) | 60 | 600 |

Both columns are **per Cloudflare location**. The Worker checks the source budget first, then a fixed location key, before initializing or looking up a route DO. It keys the source on a SHA-256 digest of Cloudflare's `CF-Connecting-IP`, never `X-Forwarded-For`, a bearer or a caller-supplied route. Rate counters live in Cloudflare's limiter, not in route storage. An absent or failed limiter does not admit the request. Preserve Cloudflare's source header on this direct custom-domain ingress.

These [rate limits](https://developers.cloudflare.com/workers/runtime-apis/bindings/rate-limit/) are approximate, not worldwide or billing caps. Shared NATs share a source budget; distributed sources or locations can exceed it. The budgets do not meter traffic on already-open sockets or impose an absolute limit on stored routes. Each successful registration persists a verifier (no automatic expiry or signup deduplication); clients should keep and reuse their route and bearer. Configure account-level abuse/billing controls separately. Cloudflare still sees outer request metadata, IPs, timing and volume; disabling application logging is not a promise about platform logs.

## HTTP

| Request | Result |
| --- | --- |
| `POST /v1/routes` | `201`, no-store JSON `{ "route": "…", "token": "…" }`. No request fields. Route = 16 random bytes, host token = another 32 random bytes, both lower-case hex. Save the token at the host; there is no recovery endpoint. |
| `GET /v1/<route>/host` + WebSocket upgrade | `101` for `Authorization: Bearer <host token>`. Replaces any previous host and closes its phone channels. New host starts sealed. |
| `GET /v1/<route>/enroll` + upgrade | Anonymous `101` **only** while the authenticated online host's invitation is open. |
| `GET /v1/<route>/device` + upgrade | `101` for `Authorization: Bearer <device token>` if its SHA-256 verifier is allowed and the host is live. Hash the **ASCII token text**, not hex-decoded token bytes. |

All ordinary failures have empty, non-cacheable responses:

- `400`: production registration supplied a body. Send an empty POST.
- `403`: missing, invalid, wrong-role, unknown or revoked credential; or no open invitation (including offline). Also wrong production mode/origin or unusable source metadata. After the edge budget check, device authorization is checked **before** host availability and socket capacity.
- `503`: valid device, no live host; a failed live-host join handoff; or unavailable production bindings/limiter. An RNG route collision fails creation rather than overwriting the existing host verifier.
- `429`: public edge budget (`Retry-After: 60`), or concurrent enrollment/phone/total socket limit (retry after channels close).
- `426`: a known socket endpoint without `Upgrade: websocket`. `405`: wrong HTTP method on a known endpoint. `404`: unrecognized path/route syntax or nonempty query string. Tokens never go in URLs.

## WebSocket framing

The host's connection is multiplexed; the phone's connection is **binary only**, without an application envelope. One TLS byte-stream chunk is one binary WS message. On the host socket, prepend exactly **16 ASCII bytes** of lower-case hex channel ID (no separator, no length field) in both directions. The relay adds/removes this prefix without parsing the remaining bytes. A new phone socket gets a new random channel, even when it uses the same device token.

| Host text | Relay behavior |
| --- | --- |
| `invite` | Open/refresh a five-minute window; reply `inviting`. |
| `seal` | Close the window immediately. An already admitted enrollment can finish. No ack. |
| `allow <digest>` | Store the 64-character lower-case SHA-256 hex verifier; reply `allowed <digest>`, also on repeat. This does not reclassify an anonymous enrollment socket. |
| `deny <digest>` | Remove the verifier and close **all and only** device sockets admitted with it; reply `denied <digest>`, also on repeat. A reconnecting host can safely finish an offline revocation. |
| `close <channel>` | Close only that channel, if still open. |

The relay sends `join <channel> enroll` or `join <channel> <device-digest>` to the live owner **before** handing the phone its upgrade. It sends `left <channel>` once when a channel ends while its owner is live. Host loss, replacement or a host protocol error closes that session's phones; a delayed old close cannot tear down a new session. A well-formed host frame for a departed/unknown channel is dropped, never replayed.

**Per-route bounds (all environments):** 32 stored device verifiers, 16 attached phones, one live enrollment at a time, 18 total attached sockets per route including closing ones (one host takeover slot). Each binary message carries at most 65,536 TLS bytes, plus the 16 host-side prefix bytes. Split larger writes into multiple messages. Device bearer text is 1–256 visible ASCII characters with no spaces. No additional wire commands or phone metadata.

Oversize messages close the sender with `1009`; phone text closes that phone with `1003`. Malformed host framing/commands or adding a 33rd distinct verifier closes the host with `1008` (no false `allowed` ack or implicit eviction). A scoped revoke uses `1008`, host/session loss `1001`, and an explicit channel close `1000`. Close reasons never contain client input.

## Storage and validation

One SQLite-backed Durable Object per route stores only `host` (the SHA-256 verifier) and, while nonempty, `devices` (an array of verifiers). Only in staging does the host verifier come from a Worker secret instead of a DO row; staging rejects signup, cleartext or a different route before selecting a DO. The Worker rebuilds the internal upgrade request; raw authorization headers never enter the object. Synchronous verifier reads/writes, host selection and socket operations do not yield between authorization and routing. Hibernation tags and attachments describe roles, the owning session, channel, device verifier tag or invitation deadline. No durable session registry, timers, snapshots, frame storage, logging or application queues.

Run the integration tests from the repository root (Node 22.12+; uses the Miniflare and `ws` shipped with global Wrangler 4.78, no install/login):

```sh
NODE_PATH="$(npm root -g)/wrangler/node_modules" node --test infra/host-sync-relay/test/*.test.mjs
```

Tests use real loopback TCP upgrades and SQLite in temporary Miniflare/workerd instances. A test-only DO subclass enumerates the actual KV contents, SQL table names, alarms, tags and attachments; it is **not** in the local relay entrypoint. Tests hold old TCP close handshakes across host takeover, check overlapping quota admissions, and require a *different DO constructor instance* after idle hibernation with both original sockets still working. They check the real five-minute deadline and separately move it into the past rather than waiting five minutes.

The staging integration test uses loopback workerd with an ephemeral OpenSSL certificate trusted only by its test client. Production tests dispatch requests carrying the production origin to **local** workerd; they do not resolve public DNS or contact Cloudflare. They exercise independent concurrent registrations, verifier-only storage, cross-host and same-route cross-environment rejection, the origin/mode checks, and both rate-limit tiers. They do not validate live DNS, TLS, physical handover, or Cloudflare's distributed enforcement.
