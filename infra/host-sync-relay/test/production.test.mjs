import assert from "node:assert/strict";
import { createHash, randomBytes } from "node:crypto";
import { once } from "node:events";
import { readFile } from "node:fs/promises";
import { createRequire } from "node:module";
import test from "node:test";
import { fileURLToPath } from "node:url";

const { Miniflare, Log, LogLevel } = createRequire(import.meta.url)("miniflare");
const root = new URL("../", import.meta.url);
const config = async (mode) => JSON.parse(await readFile(new URL(`wrangler.${mode}.jsonc`, root), "utf8"));
const [prod, stage, local] = await Promise.all([config("production"), config("staging"), config("local")]);
const origin = "https://sync.quotadog.saien.pro";
const source = { "CF-Connecting-IP": "192.0.2.1" };
const rateBindings = Object.fromEntries(prod.ratelimits.map(({ name, simple }) => [name, { simple }]));
const digest = (s) => createHash("sha256").update(s, "ascii").digest("hex");

function worker(t, cfg = prod, overrides = {}) {
  const mf = new Miniflare({
    modules: true, modulesRoot: fileURLToPath(root), scriptPath: fileURLToPath(new URL(cfg.main, root)),
    name: cfg.name, compatibilityDate: cfg.compatibility_date, bindings: cfg.vars,
    durableObjects: Object.fromEntries(cfg.durable_objects.bindings.map(({ name, class_name }) =>
      [name, { className: class_name, useSQLite: true }])),
    ratelimits: cfg === prod ? rateBindings : undefined,
    host: "127.0.0.1", port: 0, cf: false, log: new Log(LogLevel.NONE),
    outboundService: () => { throw new Error("unexpected network egress"); }, ...overrides,
  });
  t.after(() => mf.dispose());
  return mf;
}

// dispatchFetch sends the URL to local workerd; it never resolves its hostname
// or opens a connection to the public domain. Only generated test credentials.
function post(mf, url = origin, headers = source, extra = {}) {
  return mf.dispatchFetch(`${url}/v1/routes`, { method: "POST", headers, ...extra });
}
function dial(mf, route, role, token, headers = source, url = origin) {
  return mf.dispatchFetch(`${url}/v1/${route}/${role}`, { headers: {
    ...headers, Upgrade: "websocket", ...(token ? { Authorization: `Bearer ${token}` } : {}),
  } });
}
async function credentials(response) {
  assert.equal(response.status, 201);
  assert.equal(response.headers.get("cache-control"), "no-store");
  const data = await response.json();
  assert.ok(Object.keys(data).sort().join() === "route,token");
  assert.ok(/^[0-9a-f]{32}$/.test(data.route) && /^[0-9a-f]{64}$/.test(data.token));
  return data;
}
async function socket(result) {
  const response = await result;
  assert.equal(response.status, 101);
  response.webSocket.accept();
  return response.webSocket;
}
function next(ws) {
  return once(ws, "message", { signal: AbortSignal.timeout(3000) }).then(([e]) => e.data);
}
async function command(ws, sent, expected) {
  const reply = next(ws);
  ws.send(sent);
  assert.ok(await reply === expected, "unexpected host control"); // Never dump frames/credentials on failure.
}

test("dedicated Worker, class, binding, migration, domain and limiter namespaces", () => {
  assert.ok([local, stage].every((other) => other.name !== prod.name && other.main !== prod.main));
  assert.deepEqual(prod.routes, [{ pattern: new URL(origin).hostname, custom_domain: true }]);
  assert.deepEqual(prod.vars, { RELAY_MODE: "production" });
  assert.deepEqual(prod.durable_objects.bindings, [{ name: "PRODUCTION_ROUTES", class_name: "ProductionRoute" }]);
  assert.deepEqual(prod.migrations, [{ tag: "production-v1", new_sqlite_classes: ["ProductionRoute"] }]);
  assert.ok([local, stage].every((other) => !other.vars?.RELAY_MODE && !other.ratelimits &&
    other.durable_objects.bindings.every((b) => b.class_name !== "ProductionRoute" && b.name !== "PRODUCTION_ROUTES")));
  assert.ok(!prod.workers_dev && !prod.preview_urls && !prod.logpush && !prod.observability.enabled);
  assert.equal(new Set(prod.ratelimits.map((r) => r.namespace_id)).size, 4);
  assert.ok(prod.ratelimits.every((r) => /^[1-9]\d*$/.test(r.namespace_id) && r.simple.period === 60));
  assert.deepEqual(Object.fromEntries(prod.ratelimits.map((r) => [r.name, r.simple.limit])), {
    REGISTER_SOURCE: 3, REGISTER_LOCATION: 30, ADMIT_SOURCE: 60, ADMIT_LOCATION: 600,
  });
});

test("two public registrations yield independent live routes and verifier-only DO contents", async (t) => {
  // Test-only private binding probe; the actual ProductionRoute handles all
  // relay events. No debug route or storage-inspection RPC in the deployable.
  const mf = worker(t, prod, { scriptPath: fileURLToPath(new URL("probe.mjs", root)), script: `
    export { default } from './production.mjs';
    import { ProductionRoute as Base } from './production.mjs';
    export class ProductionRoute extends Base {
      entries() { return Array.from(this.ctx.storage.kv.list()); }
    }
  ` });
  const ns = await mf.getDurableObjectNamespace("PRODUCTION_ROUTES");
  const rows = async (route) => new Map(await ns.get(ns.idFromName(route)).entries());
  const [a, b] = await Promise.all([post(mf).then(credentials), post(mf).then(credentials)]);
  assert.ok(a.route !== b.route && a.token !== b.token, "duplicate route credentials");
  const hostA = await socket(dial(mf, a.route, "host", a.token));
  const hostB = await socket(dial(mf, b.route, "host", b.token));
  assert.equal((await dial(mf, a.route, "host", b.token)).status, 403);
  const dev = randomBytes(32).toString("hex"), hash = digest(dev);
  await command(hostA, `allow ${hash}`, `allowed ${hash}`);
  assert.equal((await dial(mf, b.route, "device", dev)).status, 403);
  await command(hostB, "invite", "inviting");
  assert.equal((await dial(mf, a.route, "enroll")).status, 403);
  const joinB = next(hostB), enrollee = await socket(dial(mf, b.route, "enroll"));
  assert.ok(/^join [a-f0-9]{16} enroll$/.test(await joinB));
  const joinA = next(hostA), phone = await socket(dial(mf, a.route, "device", dev));
  const match = /^join ([0-9a-f]{16}) ([a-f0-9]{64})$/.exec(await joinA);
  assert.ok(match && match[2] === hash, "expected independent authenticated channel");
  const bytes = Buffer.from([0x16, 0x03, 0x03, 0x00, 0xff]);
  const frame = next(hostA);
  phone.send(bytes);
  assert.ok(Buffer.from(await frame).equals(Buffer.concat([Buffer.from(match[1], "ascii"), bytes])));
  const [ar, br] = await Promise.all([rows(a.route), rows(b.route)]);
  assert.ok(ar.size === 2 && ar.get("host") === digest(a.token) && Array.isArray(ar.get("devices")) &&
    ar.get("devices").length === 1 && ar.get("devices")[0] === hash, "unexpected A storage");
  assert.ok(br.size === 1 && br.get("host") === digest(b.token), "unexpected B storage");

  // Same route ID in a physically separate namespace: different synthetic
  // staging token works only in staging, whose public signup is still sealed.
  const stageToken = randomBytes(32).toString("hex"), stageOrigin = `https://${stage.routes[0].pattern}`;
  const staging = worker(t, stage, { bindings: { STAGING_ROUTE: a.route, STAGING_HOST_VERIFIER: digest(stageToken) } });
  assert.equal((await post(staging, stageOrigin)).status, 403);
  assert.equal((await dial(staging, b.route, "host", stageToken, source, stageOrigin)).status, 403);
  assert.equal((await dial(staging, a.route, "host", a.token, source, stageOrigin)).status, 403);
  assert.equal((await dial(mf, a.route, "host", stageToken)).status, 403);
  const testHost = await socket(dial(staging, a.route, "host", stageToken, source, stageOrigin));
  assert.equal((await dial(staging, a.route, "device", dev, source, stageOrigin)).status, 403);

  const loopback = worker(t, local), loopOrigin = "http://127.0.0.1";
  assert.equal((await post(loopback, origin)).status, 403);
  assert.equal((await dial(loopback, a.route, "host", a.token, source, loopOrigin)).status, 403);
  const localHost = await credentials(await post(loopback, loopOrigin));
  assert.equal((await dial(mf, localHost.route, "host", localHost.token)).status, 403);
  for (const ws of [phone, enrollee, hostA, hostB, testHost]) ws.close();
});

test("registration and every WS role fail closed on wrong origin, mixed mode or absent budgets", async (t) => {
  const mf = worker(t), target = await credentials(await post(mf));
  const probe = async (instance, url, status, headers = source) => {
    assert.equal((await post(instance, url, headers)).status, status);
    for (const role of ["host", "device", "enroll"]) {
      assert.equal((await instance.dispatchFetch(`${url}/v1/${target.route}/${role}`, { headers })).status, status);
      assert.equal((await dial(instance, target.route, role, target.token, headers, url)).status, status);
    }
  };
  for (const url of [origin.replace("https:", "http:"), origin + ":444", "https://quotadog-sync-staging.saien.pro",
    "http://127.0.0.1", origin + ".example.invalid", "https://preview.workers.dev"]) {
    await probe(mf, url, 403, { ...source, "X-Forwarded-Proto": "https", "X-Forwarded-Host": new URL(origin).hostname });
  }
  assert.equal((await post(mf, origin, source, { body: "unexpected" })).status, 400);
  for (const bindings of [{}, { RELAY_MODE: "staging" }, { ...prod.vars, LOCAL_PROVISIONING: "loopback" },
    { ...prod.vars, STAGING_ROUTE: target.route }, { ...prod.vars, STAGING_HOST_VERIFIER: digest(target.token) }]) {
    await probe(worker(t, prod, { bindings }), origin, 403);
  }
  const { ADMIT_LOCATION, ...incomplete } = rateBindings;
  await probe(worker(t, prod, { ratelimits: incomplete }), origin, 503);
  const broken = worker(t, prod, { ratelimits: incomplete, bindings: { ...prod.vars, ADMIT_LOCATION: "not-a-limiter" } });
  await probe(broken, origin, 503);
  await probe(worker(t, prod, { durableObjects: {} }), origin, 503);
  const invalidSource = { "CF-Connecting-IP": "not-an-ip", "X-Forwarded-For": "192.0.2.2" };
  assert.equal((await post(mf, origin, invalidSource)).status, 403);
  assert.equal((await dial(mf, target.route, "host", target.token, invalidSource)).status, 403);
});

test("source and location budgets reject before registration/admission; forwarded IPs and route churn do not reset them", async (t) => {
  // Small limits so both thresholds can be crossed within one local workerd
  // rate-limit window; use the real four ratelimit bindings, not mocked calls.
  const ratelimits = Object.fromEntries(prod.ratelimits.map(({ name }) =>
    [name, { simple: { period: 60, limit: name.endsWith("SOURCE") ? 2 : 3 } }]));
  const mf = worker(t, prod, { ratelimits });
  const reg = (ip, n) => post(mf, origin, { "CF-Connecting-IP": ip, "X-Forwarded-For": `198.51.100.${n}` });
  const limited = async (req) => {
    const res = await req;
    assert.equal(res.status, 429); assert.equal(res.headers.get("retry-after"), "60");
    assert.equal(res.headers.get("cache-control"), "no-store"); assert.equal((await res.arrayBuffer()).byteLength, 0);
  };
  // In the local adapter counters reset at a wall-clock minute boundary. A
  // burst starting just before one would test two windows instead of one.
  const remaining = 60_000 - Date.now() % 60_000;
  if (remaining < 3000) await new Promise((done) => setTimeout(done, remaining + 1));
  const [a, b] = await Promise.all([reg("192.0.2.1", 1).then(credentials), reg("192.0.2.1", 2).then(credentials)]);
  await limited(reg("192.0.2.1", 3));
  assert.equal((await reg("192.0.2.2", 4)).status, 201);
  await limited(reg("192.0.2.3", 5));
  // The independent admission budget still has room. Count failed as well as
  // successful attempts, across all roles, before doing DO authentication.
  const host = await socket(dial(mf, a.route, "host", a.token));
  assert.equal((await dial(mf, b.route, "device", "unknown")).status, 403);
  await limited(dial(mf, b.route, "enroll", undefined, { ...source, "X-Forwarded-For": "192.0.2.3" }));
  assert.equal((await dial(mf, b.route, "enroll", undefined, { "CF-Connecting-IP": "192.0.2.2" })).status, 403);
  await limited(dial(mf, b.route, "host", b.token, { "CF-Connecting-IP": "192.0.2.3" }));
  await command(host, "invite", "inviting"); // An existing relay session is undisturbed.
  host.close();
});
