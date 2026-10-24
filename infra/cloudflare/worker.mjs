const NO_CACHE = "no-store, no-transform";
const IMMUTABLE = "public, max-age=31536000, immutable, no-transform";

function plain(message, status, extraHeaders = {}) {
  return new Response(message, {
    status,
    headers: {
      "content-type": "text/plain; charset=utf-8",
      "cache-control": NO_CACHE,
      "x-content-type-options": "nosniff",
      ...extraHeaders,
    },
  });
}

export default {
  async fetch(request, env, ctx) {
    if (request.method !== "GET" && request.method !== "HEAD") {
      return plain("Method not allowed", 405, { allow: "GET, HEAD" });
    }
    const url = new URL(request.url);
    if (!url.pathname.startsWith("/updates/")) {
      return env.ASSETS.fetch(request);
    }

    const key = url.pathname.slice("/updates/".length);
    const feed = /^feeds\/[A-Za-z0-9][A-Za-z0-9._-]*\.json$/.test(key);
    const artifact = /^releases\/[1-9][0-9]*\/[A-Za-z0-9][A-Za-z0-9._-]*\.dmg$/.test(key);
    if (!feed && !artifact) return plain("Not found", 404);
    const temporary = artifact && /^releases\/[1-9][0-9]*\/QuotaDog-acceptance-/.test(key);
    const immutable = artifact && !temporary;

    // Acceptance packages may use edge storage, but every request checks R2 first
    // so deletion makes their URLs unavailable even when an edge copy remains.
    const temporaryObject = temporary ? await env.RELEASES.head(key) : undefined;
    if (temporary && !temporaryObject) return plain("Not found", 404);
    const cache = artifact ? globalThis.caches?.default : undefined;
    const cacheUrl = new URL(url);
    cacheUrl.search = "";
    const cacheKey = new Request(cacheUrl, { method: "GET" });
    const cached = await cache?.match(cacheKey);
    if (cached && (!temporary || cached.headers.get("etag") === temporaryObject.httpEtag)) {
      const headers = new Headers(cached.headers);
      if (temporary) {
        headers.set("cache-control", NO_CACHE);
        headers.set("cloudflare-cdn-cache-control", "no-store");
      }
      return new Response(request.method === "HEAD" ? null : cached.body, { status: cached.status, headers });
    }

    const object = request.method === "HEAD"
      ? await env.RELEASES.head(key)
      : await env.RELEASES.get(key);
    if (!object) return plain("Not found", 404);

    const headers = new Headers({
      "content-type": feed ? "application/json" : "application/x-apple-diskimage",
      "content-length": String(object.size),
      "cache-control": immutable ? IMMUTABLE : NO_CACHE,
      "cloudflare-cdn-cache-control": immutable ? "public, max-age=31536000" : "no-store",
      "etag": object.httpEtag,
      "last-modified": object.uploaded.toUTCString(),
      "x-content-type-options": "nosniff",
    });
    if (artifact) headers.set("content-disposition", `attachment; filename="${key.split("/").at(-1)}"`);
    const response = new Response(request.method === "HEAD" ? null : object.body, { headers });
    if (cache && ctx && request.method === "GET") {
      // Cache capacity/size limits must not prevent an otherwise valid download.
      const entry = response.clone();
      if (temporary) {
        entry.headers.set("cache-control", "public, max-age=3600, no-transform");
        entry.headers.set("cloudflare-cdn-cache-control", "public, max-age=3600");
      }
      ctx.waitUntil(cache.put(cacheKey, entry).catch(() => {}));
    }
    return response;
  },
};
