import assert from "node:assert/strict";
import test from "node:test";
import worker from "./worker.mjs";

function environment(bytes) {
  const object = {
    size: bytes.byteLength,
    httpEtag: '"test-etag"',
    uploaded: new Date("2026-10-24T00:00:00Z"),
  };
  return {
    RELEASES: {
      async get() { return { ...object, body: new Blob([bytes]).stream() }; },
      async head() { return object; },
    },
    ASSETS: { async fetch() { return new Response("QuotaDog"); } },
  };
}

test("signed feeds are served byte-for-byte without cache or redirects", async () => {
  const bytes = new TextEncoder().encode('{"payload":"signed bytes","signatures":[]}\n');
  const response = await worker.fetch(new Request("https://quotadog.saien.pro/updates/feeds/stable.json"), environment(bytes));
  assert.equal(response.status, 200);
  assert.equal(response.headers.get("cache-control"), "no-store, no-transform");
  assert.equal(response.headers.get("cloudflare-cdn-cache-control"), "no-store");
  assert.equal(response.headers.get("location"), null);
  assert.deepEqual(new Uint8Array(await response.arrayBuffer()), bytes);
});

test("DMGs stream unchanged and HEAD returns the same size without a body", async () => {
  const bytes = new Uint8Array([0, 255, 128, 10, 13]);
  const url = "https://quotadog.saien.pro/updates/releases/24/QuotaDog-1.0.12-macos-aarch64.dmg";
  const response = await worker.fetch(new Request(url), environment(bytes));
  assert.match(response.headers.get("cache-control"), /immutable, no-transform/);
  assert.deepEqual(new Uint8Array(await response.arrayBuffer()), bytes);
  const head = await worker.fetch(new Request(url, { method: "HEAD" }), environment(bytes));
  assert.equal(head.headers.get("content-length"), "5");
  assert.equal(await head.text(), "");
});

test("missing feeds are not cached and private keys cannot be exposed", async () => {
  const env = environment(new Uint8Array());
  env.RELEASES.get = async () => null;
  const missing = await worker.fetch(new Request("https://quotadog.saien.pro/updates/feeds/stable.json"), env);
  assert.equal(missing.status, 404);
  assert.match(missing.headers.get("cache-control"), /no-store/);
  env.RELEASES.get = async () => { throw new Error("must not read this key"); };
  for (const path of ["private.key", "feeds/private.key", "releases/24/private.key"]) {
    assert.equal((await worker.fetch(new Request(`https://quotadog.saien.pro/updates/${path}`), env)).status, 404);
  }
  assert.equal((await worker.fetch(new Request("https://quotadog.saien.pro/updates/feeds/stable.json", { method: "PUT", body: "tampered" }), env)).status, 405);
});

test("only immutable artifacts use the edge cache; feed checks always read R2", async () => {
  const original = globalThis.caches;
  const entries = new Map();
  const pending = [];
  let reads = 0;
  const env = environment(new Uint8Array([1, 2, 3]));
  const get = env.RELEASES.get;
  env.RELEASES.get = async (...args) => { reads++; return get(...args); };
  globalThis.caches = { default: {
    async match(request) { return entries.get(request.url)?.clone(); },
    async put(request, response) { entries.set(request.url, response); },
  } };
  try {
    const ctx = { waitUntil(promise) { pending.push(promise); } };
    const artifact = new Request("https://quotadog.saien.pro/updates/releases/24/QuotaDog.dmg");
    await (await worker.fetch(artifact, env, ctx)).arrayBuffer();
    await Promise.all(pending);
    await (await worker.fetch(artifact, env, ctx)).arrayBuffer();
    assert.equal(reads, 1);
    const feed = new Request("https://quotadog.saien.pro/updates/feeds/stable.json");
    await (await worker.fetch(feed, env, ctx)).arrayBuffer();
    await (await worker.fetch(feed, env, ctx)).arrayBuffer();
    assert.equal(reads, 3);
    assert.equal(entries.size, 1);
  } finally {
    if (original === undefined) delete globalThis.caches;
    else globalThis.caches = original;
  }
});
