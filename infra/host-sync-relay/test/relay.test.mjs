import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { createHash, randomBytes } from "node:crypto";
import { on } from "node:events";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import { createRequire } from "node:module";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { after, afterEach, before, test } from "node:test";
import { setTimeout as delay } from "node:timers/promises";
import { fileURLToPath } from "node:url";

const require = createRequire(import.meta.url);
const { Miniflare, Log, LogLevel } = require("miniflare");
const WebSocket = require("ws");
const digest = (s) => createHash("sha256").update(s, "ascii").digest("hex");
const tls = Buffer.from([0x16, 0x03, 0x03, 0x00, 0x03, 0x00, 0xff, 0x80]);
const addressed = (channel, bytes) => Buffer.concat([Buffer.from(channel, "ascii"), bytes]);
const peers = new Set();
let mf, base, objects;

before(async () => {
  mf = new Miniflare({
    modules: true,
    modulesRoot: fileURLToPath(new URL("../", import.meta.url)),
    scriptPath: fileURLToPath(new URL("inspected-worker.mjs", import.meta.url)),
    bindings: { LOCAL_PROVISIONING: "loopback" },
    compatibilityDate: "2026-03-17",
    durableObjects: { ROUTES: { className: "InspectedRoute", useSQLite: true, unsafePreventEviction: false } },
    host: "127.0.0.1", port: 0, log: new Log(LogLevel.NONE),
  });
  base = await mf.ready;
  objects = await mf.getDurableObjectNamespace("ROUTES");
});

afterEach(() => {
  for (const ws of peers) { ws.resume(); ws.terminate(); }
  peers.clear();
});
after(async () => { await mf?.dispose(); });

async function createRoute() {
  const response = await fetch(new URL("/v1/routes", base), { method: "POST" });
  assert.equal(response.status, 201);
  assert.equal(response.headers.get("cache-control"), "no-store");
  const data = await response.json();
  // Use boolean assertions for credentials, controls and data throughout: a
  // failing test must not print either side of a secret or payload comparison.
  assert.ok(Object.keys(data).sort().join() === "route,token", "unexpected route response fields");
  assert.ok(/^[0-9a-f]{32}$/.test(data.route) && /^[0-9a-f]{64}$/.test(data.token), "credential shape");
  return data;
}

function probe(route, path = "/") {
  return objects.get(objects.idFromName(route)).fetch(`https://probe.internal${path}`);
}
async function inspect(route) { return (await probe(route)).json(); }

// Receiving a close on a client is not evidence that workerd has processed
// the corresponding ack/callback. Use the real callback count as a barrier.
async function afterCloses(route, count) {
  const until = Date.now() + 5000;
  let state = await inspect(route);
  while (state.closeEvents < count && Date.now() < until) {
    await delay(10);
    state = await inspect(route);
  }
  assert.equal(state.closeEvents, count);
  return state;
}

function checkStorage(state, token, devices = []) {
  const entries = new Map(state.entries);
  assert.equal(entries.size, devices.length ? 2 : 1, "unexpected durable keys");
  assert.ok(entries.get("host") === digest(token), "wrong durable host verifier");
  if (devices.length) {
    const saved = entries.get("devices");
    assert.ok(Array.isArray(saved) && saved.length === devices.length &&
      devices.every((d) => saved.includes(d)), "durable device verifiers differ");
  }
  assert.equal(state.alarm, null);
  assert.ok(state.tables.every(({ name }) => ["_cf_KV", "_cf_METADATA"].includes(name)),
    "unexpected application SQL table");
}

// Real TCP/WebSocket upgrades to workerd (not a JS mock or direct message
// handler call). Install the inbox in the open event, before any frames arrive.
function connect(route, role, token, extraHeaders = {}, origin = base, ca) {
  const url = new URL(`/v1/${route}/${role}`, origin);
  url.protocol = url.protocol === "https:" ? "wss:" : "ws:";
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(url, {
      ca, perMessageDeflate: false, handshakeTimeout: 5000,
      headers: { ...(token === undefined ? {} : { Authorization: `Bearer ${token}` }), ...extraHeaders },
    });
    ws.on("error", () => reject(new Error("WebSocket transport failure")));
    ws.once("unexpected-response", (request, response) => {
      resolve({ status: response.statusCode });
      response.resume();
      request.destroy();
    });
    ws.once("open", () => {
      peers.add(ws);
      const frames = on(ws, "message", { close: ["close"] });
      // ws 8's receiver exposes the actual peer close frame. Its public close
      // event waits for TCP FIN, which can lag the acknowledged WS close on
      // local workerd. A timeout, TCP reset or client terminate cannot pass.
      const closed = new Promise((done) => ws._receiver.once("conclude", (code, reason) => {
        done({ code, reasonBytes: reason.length });
      }));
      ws.once("close", () => peers.delete(ws));
      resolve({ status: 101, ws, frames, closed });
    });
  });
}

async function open(route, role, token) {
  const peer = await connect(route, role, token);
  assert.equal(peer.status, 101, `expected ${role} upgrade`);
  return peer;
}
async function refused(route, role, token, status, headers) {
  assert.equal((await connect(route, role, token, headers)).status, status, `expected ${role} refusal`);
}

async function within(promise, label) {
  let timer;
  const timeout = new Error(`timeout: ${label}`);
  try {
    return await Promise.race([promise, new Promise((_, reject) => {
      timer = setTimeout(() => reject(timeout), 5000);
    })]);
  } finally { clearTimeout(timer); }
}
async function frame(peer) {
  const { value, done } = await within(peer.frames.next(), "frame");
  assert.ok(!done, "unexpected peer close while waiting for frame");
  return value; // [Buffer, isBinary]
}
async function text(peer) {
  const [data, binary] = await frame(peer);
  assert.ok(!binary, "expected control, not binary");
  return data.toString("utf8");
}
async function control(peer, expected) {
  assert.ok((await text(peer)) === expected, "unexpected control");
}
async function binary(peer, expected) {
  const [data, isBinary] = await frame(peer);
  assert.ok(isBinary && data.equals(expected), "binary frame type, bytes or addressing differ");
}
async function joined(host, label) {
  const match = /^join ([0-9a-f]{16}) (enroll|[0-9a-f]{64})$/.exec(await text(host));
  assert.ok(match && match[2] === label, "expected scoped join");
  return match[1];
}
async function allow(host, d) {
  host.ws.send(`allow ${d}`);
  await control(host, `allowed ${d}`);
}
async function invite(host) {
  host.ws.send("invite");
  await control(host, "inviting");
}
async function closed(peer, code) {
  const result = await within(peer.closed, "close");
  assert.equal(result.code, code);
  assert.equal(result.reasonBytes, 0, "relay must not reflect input in close reasons");
}

test("provisioning, strict ingress, independent route/role credentials and verifier-only storage", async () => {
  const [a, b] = await Promise.all([createRoute(), createRoute()]);
  assert.ok(a.route !== b.route && a.token !== b.token, "reused credentials");
  checkStorage(await inspect(a.route), a.token);
  checkStorage(await inspect(b.route), b.token);
  for (const [path, method, status] of [
    ["/v1/routes", "GET", 405], [`/v1/${a.route}/host`, "GET", 426],
    [`/v1/${a.route}/device`, "POST", 405], [`/v1/${a.route}/inspect`, "GET", 404],
    ["/v1/invalid/enroll", "GET", 404], ["/v1/routes?token=ignored", "POST", 404],
  ]) assert.equal((await fetch(new URL(path, base), { method })).status, status);
  await refused(a.route, "host", b.token, 403);
  await refused(a.route, "host", undefined, 403, { "X-Relay-Verifier": digest(a.token) });
  await refused(a.route, "host", digest(a.token), 403);
  await refused(a.route, "enroll", undefined, 403);
  await refused(a.route, "device", a.token, 403);
  const host = await open(a.route, "host", a.token);
  const device = "MixedCASE-ascii.device_credential~";
  await allow(host, digest(device));
  await refused(b.route, "device", device, 403);
  await refused(a.route, "device", digest(device), 403);
  await refused(a.route, "device", "x".repeat(257), 403);
  const phone = await open(a.route, "device", device);
  await joined(host, digest(device));
  host.ws.close();
  await closed(phone, 1001);
  await refused(a.route, "device", device, 503);
  await refused(a.route, "device", "unknown", 403);
  checkStorage(await inspect(a.route), a.token, [digest(device)]);
});

test("staging WSS: only the provisioned host, no public signup, phone reopens a fresh channel", async () => {
  const route = randomBytes(16).toString("hex"), token = randomBytes(32).toString("hex");
  const other = randomBytes(16).toString("hex");
  const dir = await mkdtemp(join(tmpdir(), "qd-staging-test-"));
  const cert = join(dir, "cert.pem"), key = join(dir, "key.pem");
  let stage;
  try {
    execFileSync("openssl", ["req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "2",
      "-subj", "/CN=localhost", "-addext", "subjectAltName=IP:127.0.0.1", "-keyout", key, "-out", cert], { stdio: "ignore" });
    stage = new Miniflare({ modules: true, scriptPath: fileURLToPath(new URL("../relay.mjs", import.meta.url)),
      compatibilityDate: "2026-03-17", bindings: { STAGING_ROUTE: route, STAGING_HOST_VERIFIER: digest(token) },
      durableObjects: { ROUTES: { className: "Route", useSQLite: true } }, host: "127.0.0.1", port: 0,
      httpsCertPath: cert, httpsKeyPath: key, cf: false, log: new Log(LogLevel.NONE) });
    const origin = await stage.ready, ca = await readFile(cert);
    assert.equal(origin.protocol, "https:");
    const dial = (r, role, bearer) => connect(r, role, bearer, {}, origin, ca);
    const http = "http://127.0.0.1", https = "https://staging.test";
    for (const [worker, prefix, path, method] of [
      [stage, https, "/v1/routes", "POST"], [stage, http, "/v1/routes", "POST"],
      [stage, http, `/v1/${route}/host`, "GET"], [mf, https, "/v1/routes", "POST"],
      [mf, https, `/v1/${route}/enroll`, "GET"],
    ]) assert.equal((await worker.dispatchFetch(prefix + path, { method })).status, 403);
    assert.equal((await dial(other, "host", token)).status, 403);
    assert.equal((await dial(route, "host", digest(token))).status, 403);
    const host = await dial(route, "host", token);
    assert.equal(host.status, 101);
    assert.equal((await dial(route, "enroll")).status, 403);
    await invite(host);
    const enrollee = await dial(route, "enroll");
    const pairing = await joined(host, "enroll");
    host.ws.send("seal"); host.ws.send(`close ${pairing}`);
    await closed(enrollee, 1000); await control(host, `left ${pairing}`);
    const device = randomBytes(32).toString("hex");
    await allow(host, digest(device));
    const phone = await dial(route, "device", device);
    const first = await joined(host, digest(device));
    phone.ws.terminate(); await control(host, `left ${first}`);
    const again = await dial(route, "device", device);
    const second = await joined(host, digest(device));
    assert.ok(second !== first);
    host.ws.send(addressed(first, Buffer.from([1])));
    host.ws.send(addressed(second, tls)); await binary(again, tls);
    host.ws.send(`deny ${digest(device)}`); await closed(again, 1008);
    assert.equal((await dial(route, "device", device)).status, 403);
    host.ws.terminate();
  } finally { if (stage) await stage.dispose(); await rm(dir, { recursive: true, force: true }); }
});

test("5-minute invitation, one enrollment, seal and expiry affect admission only", async () => {
  const { route, token } = await createRoute();
  const host = await open(route, "host", token);
  await refused(route, "enroll", undefined, 403);
  const beforeInvite = Date.now();
  await invite(host);
  const deadline = (await inspect(route)).sockets[0].attachment.inviteUntil;
  assert.ok(deadline >= beforeInvite + 300_000 && deadline <= Date.now() + 300_000, "invitation duration");
  const attempts = await Promise.all(Array.from({ length: 4 }, () => connect(route, "enroll")));
  assert.equal(attempts.filter((p) => p.status === 429).length, 3);
  const enroll = attempts.find((p) => p.status === 101);
  assert.ok(enroll, "no enrollment admitted");
  const channel = await joined(host, "enroll");
  host.ws.send("seal");
  await allow(host, digest("barrier")); // Ordered command/ack witnesses seal completion.
  await refused(route, "enroll", undefined, 403);
  enroll.ws.send(tls);
  await binary(host, addressed(channel, tls));
  host.ws.send(addressed(channel, tls));
  await binary(enroll, tls);
  await invite(host);
  assert.equal((await probe(route, "/expire")).status, 204);
  await refused(route, "enroll", undefined, 403);
  enroll.ws.send(tls);
  await binary(host, addressed(channel, tls));
  host.ws.send(`close ${channel}`);
  await closed(enroll, 1000);
  await control(host, `left ${channel}`);
  checkStorage(await inspect(route), token, [digest("barrier")]);
});

test("join precedes first TLS bytes; independent channels, scoped close and multi-socket revoke", async () => {
  const { route, token } = await createRoute();
  const host = await open(route, "host", token);
  const a = digest("phone-A"), b = digest("phone-B");
  await allow(host, a);
  await allow(host, b);
  const first = await open(route, "device", "phone-A");
  first.ws.send(tls); // Don't wait for the host to consume the join.
  const one = await joined(host, a);
  await binary(host, addressed(one, tls));
  const second = await open(route, "device", "phone-B");
  const two = await joined(host, b);
  const bytes = Buffer.from([0, 0xff, 4]);
  host.ws.send(addressed(two, bytes));
  host.ws.send(addressed(one, tls));
  await binary(first, tls);
  await binary(second, bytes);
  const third = await open(route, "device", "phone-A");
  const three = await joined(host, a);
  assert.equal(new Set([one, two, three]).size, 3);
  host.ws.send(`close ${one}`);
  await control(host, `left ${one}`);
  await closed(first, 1000);
  host.ws.send(addressed(one, tls)); // A stale address mustn't hit the other A socket.
  host.ws.send(addressed(three, bytes));
  await binary(third, bytes);
  const fourth = await open(route, "device", "phone-A");
  const four = await joined(host, a);
  assert.ok(four !== one && four !== three && four !== two, "reused channel");
  host.ws.send(`deny ${a}`);
  const left = new Set([await text(host), await text(host)]);
  assert.ok(left.has(`left ${three}`) && left.has(`left ${four}`), "revoke did not close precisely A");
  await control(host, `denied ${a}`);
  await Promise.all([closed(third, 1008), closed(fourth, 1008)]);
  await refused(route, "device", "phone-A", 403);
  second.ws.send(bytes);
  await binary(host, addressed(two, bytes));
  host.ws.send(addressed(two, tls));
  await binary(second, tls);
  checkStorage(await inspect(route), token, [b]);
  second.ws.close(3001, "do-not-reflect");
  await closed(second, 1000);
  await control(host, `left ${two}`);
  host.ws.send(`deny ${b}`);
  await control(host, `denied ${b}`);
  host.ws.send(`deny ${b}`); // Host can retry if the original acknowledgement was lost.
  await control(host, `denied ${b}`);
  checkStorage(await inspect(route), token);
});

test("replacement fences old commands, data and delayed close callbacks from the new session", async () => {
  const { route, token } = await createRoute();
  const old = await open(route, "host", token);
  const d = digest("phone");
  await allow(old, d);
  await invite(old);
  const stale = await open(route, "device", "phone");
  const oldChannel = await joined(old, d);
  // Pause TCP reads (not writes) before takeover. Neither old peer can answer
  // its close handshake until after the replacement has live phone channels.
  old.ws.pause();
  stale.ws.pause();
  const current = await open(route, "host", token);
  await refused(route, "enroll", undefined, 403); // No inherited invite.
  const phone = await open(route, "device", "phone");
  const channel = await joined(current, d);
  old.ws.send(`deny ${d}`);
  old.ws.send(`close ${channel}`);
  old.ws.send(addressed(channel, Buffer.from([42])));
  stale.ws.send(Buffer.from([42]));
  current.ws.send(addressed(oldChannel, Buffer.from([42])));
  const overlap = await inspect(route);
  assert.equal(overlap.closeEvents, 0, "late callbacks were not actually held");
  assert.equal(overlap.sockets.filter((s) => s.attachment === null).length, 2, "old sockets not fenced");
  old.ws.resume();
  stale.ws.resume();
  await Promise.all([closed(old, 1001), closed(stale, 1001)]);
  const state = await afterCloses(route, 2);
  assert.equal(state.sockets.length, 2);
  checkStorage(state, token, [d]);
  phone.ws.send(tls);
  await binary(current, addressed(channel, tls));
  current.ws.send(addressed(channel, tls));
  await binary(phone, tls);
  current.ws.terminate(); // Abrupt current-host loss, not just an orderly close.
  await closed(phone, 1001);
  await refused(route, "device", "phone", 503);
});

test("concurrent upgrades hit the phone cap; a takeover slot remains, closing sockets still count", async () => {
  const { route, token } = await createRoute();
  const host = await open(route, "host", token);
  const d = digest("bounded");
  await allow(host, d);
  const attempts = await Promise.all(Array.from({ length: 24 }, () => connect(route, "device", "bounded")));
  const phones = attempts.filter((p) => p.status === 101);
  assert.equal(phones.length, 16);
  assert.equal(attempts.filter((p) => p.status === 429).length, 8);
  const channels = new Set();
  for (const _ of phones) channels.add(await joined(host, d));
  assert.equal(channels.size, 16);
  await refused(route, "device", "unknown", 403);
  host.ws.pause();
  for (const p of phones) p.ws.pause();
  const replacement = await open(route, "host", token);
  assert.equal((await inspect(route)).sockets.length, 18);
  await refused(route, "host", token, 429);
  await refused(route, "device", "bounded", 429);
  host.ws.resume();
  for (const p of phones) p.ws.resume();
  await Promise.all([host, ...phones].map((p) => closed(p, 1001)));
  assert.equal((await afterCloses(route, 17)).sockets.length, 1);
  await invite(replacement);
  const phone = await open(route, "device", "bounded");
  const fresh = await joined(replacement, d);
  phone.ws.send(tls);
  await binary(replacement, addressed(fresh, tls));
  checkStorage(await inspect(route), token, [d]);
});

test("32-verifier ceiling is idempotent at capacity; overflow cannot evict or silently authorize", async () => {
  const { route, token } = await createRoute();
  const host = await open(route, "host", token);
  const devices = Array.from({ length: 32 }, (_, i) => digest(`cap-${i}`));
  for (const d of devices) host.ws.send(`allow ${d}`);
  for (const d of devices) await control(host, `allowed ${d}`);
  await allow(host, devices[0]);
  host.ws.send(`allow ${digest("overflow")}`);
  await closed(host, 1008);
  await refused(route, "device", "overflow", 403);
  await refused(route, "device", "cap-31", 503);
  checkStorage(await inspect(route), token, devices);
  const next = await open(route, "host", token);
  next.ws.send(`deny ${devices[0]}`);
  await control(next, `denied ${devices[0]}`);
  await allow(next, digest("overflow"));
  await refused(route, "device", "cap-0", 403);
  checkStorage(await inspect(route), token, [...devices.slice(1), digest("overflow")]);
});

test("frame bounds in both directions, binary-only phone, strict host control and ASCII prefix", async () => {
  const { route, token } = await createRoute();
  const host = await open(route, "host", token);
  const d = digest("framed");
  await allow(host, d);
  const max = Buffer.alloc(65_536, 0xa7), over = Buffer.alloc(65_537);
  const phone = await open(route, "device", "framed");
  const channel = await joined(host, d);
  for (const bytes of [Buffer.alloc(0), max]) {
    phone.ws.send(bytes);
    await binary(host, addressed(channel, bytes));
    host.ws.send(addressed(channel, bytes));
    await binary(phone, bytes);
  }
  phone.ws.send(over);
  await closed(phone, 1009);
  await control(host, `left ${channel}`);
  const bad = await open(route, "device", "framed");
  const other = await joined(host, d);
  bad.ws.send("invite"); // Never relay or interpret phone text.
  await closed(bad, 1003);
  await control(host, `left ${other}`);
  await refused(route, "enroll", undefined, 403);
  const good = await open(route, "device", "framed");
  const last = await joined(host, d);
  host.ws.send(addressed(last, over));
  await Promise.all([closed(host, 1009), closed(good, 1001)]);
  for (const [badFrame, code] of [
    [Buffer.alloc(15), 1008], [Buffer.alloc(16, 0xe1), 1008],
    ["seal extra", 1008], [`allow ${"A".repeat(64)}`, 1008], ["x".repeat(71), 1009],
  ]) {
    const invalid = await open(route, "host", token);
    invalid.ws.send(badFrame);
    await closed(invalid, code);
  }
  checkStorage(await inspect(route), token, [d]);
});

test("actual hibernation: a new DO instance routes existing sockets, retains the invite, and revokes", async () => {
  const { route, token } = await createRoute();
  const host = await open(route, "host", token);
  const d = digest("waking");
  await allow(host, d);
  await invite(host);
  const phone = await open(route, "device", "waking");
  const channel = await joined(host, d);
  phone.ws.send(tls);
  await binary(host, addressed(channel, tls));
  const asleep = await inspect(route);
  checkStorage(asleep, token, [d]);
  const h = asleep.sockets.find((s) => s.attachment?.role === "host");
  const p = asleep.sockets.find((s) => s.attachment?.role === "phone");
  assert.equal(asleep.sockets.length, 2);
  assert.ok(h && p && Object.keys(h.attachment).sort().join() === "inviteUntil,owner,role" &&
    Object.keys(p.attachment).sort().join() === "channel,owner,role", "unexpected hibernation attachment fields");
  const { owner, inviteUntil } = h.attachment;
  assert.ok(/^[0-9a-f]{32}$/.test(owner) && Number.isSafeInteger(inviteUntil) && inviteUntil > Date.now());
  assert.ok(p.attachment.channel === channel && p.attachment.owner === owner);
  assert.ok(h.tags.sort().join() === ["host", `session:${owner}`].sort().join());
  assert.ok(p.tags.sort().join() === ["phone", `session:${owner}`, `channel:${channel}`, `device:${d}`].sort().join());

  // workerd evicts after 10 seconds idle. Don't poll and wake the object. A
  // successful exchange alone is not evidence: require a new constructor ID.
  await delay(12_000);
  phone.ws.send(tls);
  await binary(host, addressed(channel, tls));
  const awake = await inspect(route);
  assert.ok(awake.instance !== asleep.instance, "workerd did not reinstantiate the hibernating DO");
  host.ws.send(addressed(channel, tls));
  await binary(phone, tls);
  const enroll = await open(route, "enroll");
  const pending = await joined(host, "enroll");
  host.ws.send(`deny ${d}`);
  await closed(phone, 1008);
  await control(host, `left ${channel}`);
  await control(host, `denied ${d}`);
  await refused(route, "device", "waking", 403);
  enroll.ws.send(tls);
  await binary(host, addressed(pending, tls));
  checkStorage(await inspect(route), token);
});
