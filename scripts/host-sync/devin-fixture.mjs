// Only upstream substitute in the local acceptance run. No real provider credentials are accepted.
import { createServer } from 'node:http';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
// Run the actual relay Worker, with fresh disposable verifier storage, alongside the fixture.
if (process.argv.includes('--relay')) {
  const { Miniflare, Log, LogLevel } = createRequire(import.meta.url)('miniflare');
  const mf = new Miniflare({ modules: true, scriptPath: fileURLToPath(new URL('../../infra/host-sync-relay/relay.mjs', import.meta.url)),
    bindings: { LOCAL_PROVISIONING: 'loopback' },
    compatibilityDate: '2026-03-17', durableObjects: { ROUTES: { className: 'Route', useSQLite: true, unsafePreventEviction: false } },
    host: '127.0.0.1', port: 18787, cf: false, durableObjectsPersist: false, log: new Log(LogLevel.NONE) });
  await mf.ready;
  process.once('SIGTERM', async () => { await mf.dispose(); process.exit(0); });
}
const count = { personal: 0, unshared: 0 };
let next = 'normal', held;
const server = createServer(async (req, res) => {
  const answer = (value, status = 200) => { res.writeHead(status, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(value)); };
  if (req.url === '/count' && req.method === 'GET') return answer(count.personal);
  if (req.url === '/unshared-count' && req.method === 'GET') return answer(count.unshared);
  if (req.method === 'POST' && ['/fail', '/hold', '/release'].includes(req.url)) {
    if (req.url === '/release') { held?.(); held = undefined; } else next = req.url.slice(1);
    return answer(true);
  }
  if (req.method !== 'POST' || req.url !== '/exa.seat_management_pb.SeatManagementService/GetUserStatus') return answer(null, 404);
  let body = ''; for await (const chunk of req) { body += chunk; if (body.length > 8192) return answer(null, 413); }
  let key; try { key = JSON.parse(body).metadata.apiKey; } catch { return answer(null, 400); }
  const tag = key === 'fixture-personal' ? 'personal' : key === 'fixture-unshared' ? 'unshared' : null;
  if (!tag) return answer(null, 403);
  const n = ++count[tag];
  if (tag === 'personal') {
    const behavior = next; next = 'normal';
    if (behavior === 'hold') await new Promise(resolve => { held = resolve; });
    if (behavior === 'fail') return answer({ error: 'private-upstream-error-marker' }, 503);
  }
  const day = tag === 'unshared' ? 3 : n === 1 ? 75 : n === 2 ? 62 : 47;
  const reset = Math.floor(Date.now() / 1000);
  answer({ userStatus: { email: 'owner@example.invalid', planStatus: { planInfo: { planName: 'Pro' },
    dailyQuotaRemainingPercent: day, weeklyQuotaRemainingPercent: tag === 'unshared' ? 1 : 25,
    dailyQuotaResetAtUnix: reset + 7200, weeklyQuotaResetAtUnix: reset + 259200, overageBalanceMicros: 2500000 } } });
});
server.listen(18788, '127.0.0.1');
