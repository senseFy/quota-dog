import { admitSocket, checkUpgrade, empty, matchSocket, registerRoute, sha256 } from "./relay.mjs";
export { StoredRoute as ProductionRoute } from "./relay.mjs";

const ORIGIN = "https://sync.quotadog.saien.pro";

// Cloudflare supplies CF-Connecting-IP on direct public ingress. Don't trust
// forwarded headers or use a caller-supplied route/token as the source key.
// Reject before object lookup; no IP, limiter state or request body enters a DO.
async function checkBudget(request, sourceLimiter, locationLimiter) {
  const ip = request.headers.get("CF-Connecting-IP");
  if (!ip || ip.length > 45 || !/^[\da-f:.]+$/i.test(ip)) return empty(403);
  try {
    // Check the source first: a single throttled source shouldn't burn the
    // shared budget. A fixed second key also bounds traffic across new IPs.
    for (const [limiter, key] of [[sourceLimiter, await sha256(ip)], [locationLimiter, "all"]]) {
      const { success } = await limiter.limit({ key });
      if (success === false) return empty(429, { "Retry-After": "60" });
      if (success !== true) return empty(503);
    }
  } catch {
    return empty(503);
  }
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (env.RELAY_MODE !== "production" || url.origin !== ORIGIN ||
        env.LOCAL_PROVISIONING !== undefined || env.STAGING_ROUTE !== undefined ||
        env.STAGING_HOST_VERIFIER !== undefined || env.ROUTES !== undefined) return empty(403);
    if (typeof env.PRODUCTION_ROUTES?.get !== "function" || typeof env.PRODUCTION_ROUTES?.idFromName !== "function" ||
        [env.REGISTER_SOURCE, env.REGISTER_LOCATION, env.ADMIT_SOURCE, env.ADMIT_LOCATION]
          .some((limiter) => typeof limiter?.limit !== "function")) return empty(503);
    if (url.search) return empty(404);

    if (url.pathname === "/v1/routes") {
      if (request.method !== "POST") return empty(405, { Allow: "POST" });
      // workerd may expose a stream even on an empty Content-Length: 0 POST.
      if (request.body !== null && request.headers.get("Content-Length") !== "0") return empty(400);
      const refused = await checkBudget(request, env.REGISTER_SOURCE, env.REGISTER_LOCATION);
      return refused ?? registerRoute(env.PRODUCTION_ROUTES);
    }

    const match = matchSocket(url.pathname);
    if (!match) return empty(404);
    const invalid = checkUpgrade(request);
    if (invalid) return invalid;
    const refused = await checkBudget(request, env.ADMIT_SOURCE, env.ADMIT_LOCATION);
    return refused ?? admitSocket(request, env.PRODUCTION_ROUTES, match);
  },
};
