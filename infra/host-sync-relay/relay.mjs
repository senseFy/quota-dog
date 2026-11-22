import { DurableObject } from "cloudflare:workers";

const MAX_PHONES = 16;
const MAX_SOCKETS = MAX_PHONES + 2; // One host and a reserved host takeover slot.
const MAX_DEVICES = 32;
const MAX_TLS_BYTES = 64 * 1024;
const INVITATION_MS = 5 * 60 * 1000;
const CHANNEL_BYTES = 16;
const CHANNEL = /^[0-9a-f]{16}$/;
const HOST_TOKEN = /^[0-9a-f]{64}$/;
const encoder = new TextEncoder();

function hex(bytes) {
  return Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join("");
}

function randomHex(bytes) {
  return hex(crypto.getRandomValues(new Uint8Array(bytes)));
}

export async function sha256(text) {
  return hex(new Uint8Array(await crypto.subtle.digest("SHA-256", encoder.encode(text))));
}

export function empty(status, headers = {}) {
  return new Response(null, { status, headers: { "Cache-Control": "no-store", ...headers } });
}

function routeObject(namespace, route) {
  return namespace.get(namespace.idFromName(route));
}

export async function registerRoute(namespace) {
  const route = randomHex(16);
  const token = randomHex(32);
  if (!await routeObject(namespace, route).initialize(await sha256(token))) return empty(503);
  return Response.json({ route, token }, { status: 201, headers: { "Cache-Control": "no-store" } });
}

export function matchSocket(pathname) {
  return /^\/v1\/([0-9a-f]{32})\/(host|enroll|device)$/.exec(pathname);
}

export function checkUpgrade(request) {
  if (request.method !== "GET") return empty(405, { Allow: "GET" });
  if (request.headers.get("Upgrade")?.toLowerCase() !== "websocket") return empty(426, { Upgrade: "websocket" });
}

export async function admitSocket(request, namespace, [, route, role]) {
  // Neither the raw credential nor caller-controlled headers enter the DO.
  const headers = { Upgrade: "websocket" };
  if (role !== "enroll") {
    const bearer = /^Bearer ([!-~]{1,256})$/i.exec(request.headers.get("Authorization") ?? "");
    if (!bearer || (role === "host" && !HOST_TOKEN.test(bearer[1]))) return empty(403);
    headers["X-Relay-Verifier"] = await sha256(bearer[1]);
  }
  return routeObject(namespace, route).fetch(`https://route.internal/${role}`, { headers });
}

// Local/staging ingress. Production has its own entrypoint and cannot select
// this policy by changing a request URL or a binding.
export default {
  async fetch(request, env) {
    const { pathname, search, hostname, protocol } = new URL(request.url);
    const local = env.LOCAL_PROVISIONING === "loopback" && hostname === "127.0.0.1" && protocol === "http:";
    if (search) return empty(404);
    if (pathname === "/v1/routes") {
      if (!local) return empty(403);
      if (request.method !== "POST") return empty(405, { Allow: "POST" });
      return registerRoute(env.ROUTES);
    }

    const match = matchSocket(pathname);
    if (!match) return empty(404);
    // On the public test edge reject all but the pre-provisioned route BEFORE looking up a DO.
    if (!local && (protocol !== "https:" || match[1] !== env.STAGING_ROUTE ||
        !HOST_TOKEN.test(env.STAGING_HOST_VERIFIER))) return empty(403);
    return checkUpgrade(request) ?? admitSocket(request, env.ROUTES, match);
  },
};

// No live socket, routing map, invitation or traffic is kept in instance fields
// or storage. Tags and small attachments survive WebSocket hibernation. After
// hashing at ingress, every admission/command below runs in ONE synchronous DO
// turn, including the verifier check, host selection, join and 101 handoff.
export class StoredRoute extends DurableObject {
  hostVerifier() { return this.ctx.storage.kv.get("host"); }

  initialize(hostVerifier) {
    const kv = this.ctx.storage.kv;
    if (kv.get("host") !== undefined) return false;
    kv.put("host", hostVerifier);
    return true;
  }

  fetch(request) {
    const role = new URL(request.url).pathname;
    const digest = request.headers.get("X-Relay-Verifier");
    const kv = this.ctx.storage.kv;
    if (role === "/host") {
      if (!digest || digest !== this.hostVerifier()) return empty(403);
      if (this.ctx.getWebSockets().length >= MAX_SOCKETS) return empty(429);
      const previous = this.host();
      const owner = this.freshId("session:", 16);
      const pair = this.accept(["host", `session:${owner}`], { role: "host", owner, inviteUntil: 0 });
      if (previous) this.end(previous, 1001);
      return this.upgrade(pair);
    }

    if (role !== "/device" && role !== "/enroll") return empty(404);
    const enrolling = role === "/enroll";
    // Don't let the offline/busy status distinguish an unknown bearer or admit
    // a device on the strength of an authorization check preceding an await.
    if (!enrolling && !(kv.get("devices") ?? []).includes(digest)) return empty(403);
    const host = this.host();
    if (!host) return empty(enrolling ? 403 : 503);
    const { owner, inviteUntil } = host.deserializeAttachment();
    if (enrolling && inviteUntil <= Date.now()) return empty(403);
    if (enrolling && this.findLive("enroll", owner)) return empty(429);
    // Count even CLOSING sockets: a peer need not reply to a close promptly.
    if (this.ctx.getWebSockets("phone").length >= MAX_PHONES ||
        this.ctx.getWebSockets().length >= MAX_SOCKETS - 1) return empty(429);

    const channel = this.freshId("channel:", 8);
    const pair = this.accept([
      "phone", `session:${owner}`, `channel:${channel}`, enrolling ? "enroll" : `device:${digest}`,
    ], { role: "phone", owner, channel });
    if (!this.deliver(host, `join ${channel} ${enrolling ? "enroll" : digest}`)) {
      this.end(pair[1], 1001);
      return empty(503);
    }
    return this.upgrade(pair);
  }

  freshId(tag, bytes) {
    let id;
    do { id = randomHex(bytes); } while (this.ctx.getWebSockets(tag + id).length);
    return id;
  }

  accept(tags, attachment) {
    const pair = new WebSocketPair();
    this.ctx.acceptWebSocket(pair[1], tags);
    pair[1].serializeAttachment(attachment);
    return pair;
  }

  upgrade(pair) {
    return new Response(null, { status: 101, webSocket: pair[0] });
  }

  findLive(tag, owner) {
    return this.ctx.getWebSockets(tag).find((ws) => {
      if (ws.readyState !== WebSocket.OPEN) return false;
      const meta = ws.deserializeAttachment();
      return meta && (owner === undefined || meta.owner === owner);
    });
  }

  host(owner) {
    return this.findLive("host", owner);
  }

  deliver(ws, frame) {
    try {
      ws.send(frame);
      return true;
    } catch {
      this.end(ws, 1011);
      return false;
    }
  }

  end(ws, code) {
    const meta = ws.deserializeAttachment();
    if (meta) {
      // Invalidate before closing: late messages/error/close callbacks cannot
      // route, repeat a left, or operate on a replacement host's session.
      ws.serializeAttachment(null);
      if (meta.role === "host") {
        for (const peer of this.ctx.getWebSockets(`session:${meta.owner}`)) {
          if (peer.deserializeAttachment()?.role === "phone") this.end(peer, 1001);
        }
      } else {
        const host = this.host(meta.owner);
        if (host) this.deliver(host, `left ${meta.channel}`);
      }
    }
    // Never reflect client-controlled close reasons/codes, including 1006.
    ws.close(code);
  }

  webSocketClose(ws) { this.end(ws, 1000); }
  webSocketError(ws) { this.end(ws, 1011); }

  webSocketMessage(ws, message) {
    const meta = ws.deserializeAttachment();
    if (!meta || ws.readyState !== WebSocket.OPEN) return;
    if (meta.role === "phone") {
      if (typeof message === "string") return this.end(ws, 1003);
      if (message.byteLength > MAX_TLS_BYTES) return this.end(ws, 1009);
      const host = this.host(meta.owner);
      if (!host) return this.end(ws, 1001);
      const frame = new Uint8Array(CHANNEL_BYTES + message.byteLength);
      encoder.encodeInto(meta.channel, frame);
      frame.set(new Uint8Array(message), CHANNEL_BYTES);
      this.deliver(host, frame);
      return;
    }

    if (typeof message === "string") return this.command(ws, meta, message);
    if (message.byteLength > CHANNEL_BYTES + MAX_TLS_BYTES) return this.end(ws, 1009);
    if (message.byteLength < CHANNEL_BYTES) return this.end(ws, 1008);
    const channel = String.fromCharCode(...new Uint8Array(message, 0, CHANNEL_BYTES));
    if (!CHANNEL.test(channel)) return this.end(ws, 1008);
    const peer = this.findLive(`channel:${channel}`, meta.owner);
    // A valid addressed frame can race with a phone's departure. Drop it;
    // never queue it for a later connection or send it to a different phone.
    if (peer) this.deliver(peer, new Uint8Array(message, CHANNEL_BYTES));
  }

  command(ws, meta, message) {
    if (message.length > 70) return this.end(ws, 1009);
    if (message === "invite" || message === "seal") {
      const inviting = message === "invite";
      ws.serializeAttachment({ ...meta, inviteUntil: inviting ? Date.now() + INVITATION_MS : 0 });
      if (inviting) this.deliver(ws, "inviting");
      return;
    }
    const close = /^close ([0-9a-f]{16})$/.exec(message);
    if (close) {
      const peer = this.findLive(`channel:${close[1]}`, meta.owner);
      if (peer) this.end(peer, 1000);
      return;
    }
    const change = /^(allow|deny) ([0-9a-f]{64})$/.exec(message);
    if (!change) return this.end(ws, 1008);
    const [, action, digest] = change;
    const kv = this.ctx.storage.kv;
    const devices = kv.get("devices") ?? [];
    if (action === "allow") {
      if (!devices.includes(digest)) {
        if (devices.length >= MAX_DEVICES) return this.end(ws, 1008);
        kv.put("devices", [...devices, digest]);
      }
      this.deliver(ws, `allowed ${digest}`);
    } else {
      if (devices.includes(digest)) {
        const retained = devices.filter((item) => item !== digest);
        if (retained.length) kv.put("devices", retained);
        else kv.delete("devices");
      }
      for (const peer of this.ctx.getWebSockets(`device:${digest}`)) this.end(peer, 1008);
      this.deliver(ws, `denied ${digest}`);
    }
  }
}

// Preserve the existing class/namespace and single host override in staging.
// Production exports StoredRoute directly and never consults a shared secret.
export class Route extends StoredRoute {
  hostVerifier() { return this.env.STAGING_HOST_VERIFIER ?? super.hostVerifier(); }
}
