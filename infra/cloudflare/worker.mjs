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

    // Only immutable packages enter the edge cache. Feed checks always reach R2.
    const cache = artifact ? globalThis.caches?.default : undefined;
    const cacheUrl = new URL(url);
    cacheUrl.search = "";
    const cacheKey = new Request(cacheUrl, { method: "GET" });
    const cached = await cache?.match(cacheKey);
    if (cached) {
      return request.method === "HEAD"
        ? new Response(null, { status: cached.status, headers: cached.headers })
        : cached;
    }

    const object = request.method === "HEAD"
      ? await env.RELEASES.head(key)
      : await env.RELEASES.get(key);
    if (!object) return plain("Not found", 404);

    const headers = new Headers({
      "content-type": feed ? "application/json" : "application/x-apple-diskimage",
      "content-length": String(object.size),
      "cache-control": feed ? NO_CACHE : IMMUTABLE,
      "cloudflare-cdn-cache-control": feed ? "no-store" : "public, max-age=31536000",
      "etag": object.httpEtag,
      "last-modified": object.uploaded.toUTCString(),
      "x-content-type-options": "nosniff",
    });
    if (artifact) headers.set("content-disposition", `attachment; filename="${key.split("/").at(-1)}"`);
    const response = new Response(request.method === "HEAD" ? null : object.body, { headers });
    if (cache && ctx && request.method === "GET") {
      // Cache capacity/size limits must not prevent an otherwise valid download.
      ctx.waitUntil(cache.put(cacheKey, response.clone()).catch(() => {}));
    }
    return response;
  },
};
