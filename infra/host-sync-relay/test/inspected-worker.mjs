// Miniflare's test entrypoint ONLY. Not imported by the local relay. Node
// reaches this probe via the private DO binding, never an ingress endpoint.
import { Route } from "../relay.mjs";
export { default } from "../relay.mjs";

export class InspectedRoute extends Route {
  instance = crypto.randomUUID();
  closeEvents = 0;

  async fetch(request) {
    const url = new URL(request.url);
    if (url.hostname !== "probe.internal") return super.fetch(request);
    if (url.pathname === "/expire") {
      // Move just the live invitation deadline into the past, not wall time or
      // the verifier store. Tests separately check the actual 5-minute window.
      const host = this.host();
      host.serializeAttachment({ ...host.deserializeAttachment(), inviteUntil: Date.now() - 1 });
      return new Response(null, { status: 204 });
    }
    return Response.json({
      instance: this.instance,
      closeEvents: this.closeEvents,
      entries: [...this.ctx.storage.kv.list()],
      tables: this.ctx.storage.sql.exec("SELECT name FROM sqlite_schema WHERE type = 'table'").toArray(),
      alarm: await this.ctx.storage.getAlarm(),
      sockets: this.ctx.getWebSockets().map((ws) => ({
        tags: this.ctx.getTags(ws), attachment: ws.deserializeAttachment(), state: ws.readyState,
      })),
    });
  }

  webSocketClose(ws, ...args) {
    super.webSocketClose(ws, ...args);
    this.closeEvents++;
  }
}
