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
const providers = new Map();
let next = 'normal', held;
const server = createServer(async (req, res) => {
  const answer = (value, status = 200) => { res.writeHead(status, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(value)); };
  if (req.url === '/count' && req.method === 'GET') return answer(count.personal);
  if (req.url === '/unshared-count' && req.method === 'GET') return answer(count.unshared);
  if (req.url === '/provider-counts' && req.method === 'GET') return answer(Object.fromEntries(providers));
  if (req.url.startsWith('/provider-counts/') && req.method === 'GET') return answer(providers.get(req.url.slice('/provider-counts/'.length)) ?? 0);
  if (req.method === 'POST' && ['/fail', '/hold', '/release'].includes(req.url)) {
    if (req.url === '/release') { held?.(); held = undefined; } else next = req.url.slice(1);
    return answer(true);
  }
  // Same paths and response shapes the production collectors use; the local build redirects only
  // its disposable provider client to this process. No fixture data or routing hook in the app.
  const match = /^Bearer fixture-(codex|claude_code|droid|antigravity|amp|grok|cursor)-(mac|phone|other)$/.exec(req.headers.authorization ?? '');
  if (match) {
    const [, provider, source] = match;
    const key = `${provider}-${source}`;
    const counted = value => { providers.set(key, (providers.get(key) ?? 0) + 1); return answer(value); };
    const n = providers.get(key) ?? 0;
    const email = 'owner@example.invalid';
    if (provider === 'codex' && req.url === '/backend-api/wham/usage') return counted({user_id:'private-user-id', account_id:source === 'other' ? 'private-company' : 'private-personal',
      plan_type:'plus', rate_limit:{primary_window:{used_percent:source === 'other' ? 99 : source === 'phone' ? 62 + n : 12 + n, limit_window_seconds:18000},secondary_window:{used_percent:47,limit_window_seconds:604800}}, rate_limit_reset_credits:{available_count:2}});
    if (provider === 'codex' && req.url === '/backend-api/wham/rate-limit-reset-credits') return answer({available_count:2,applicable_available_count:1,credits:[{id:'PRIVATE-REDEEM-ID',status:'available',expires_at:'2027-01-02T00:00:00Z'}]});
    if (provider === 'claude_code' && req.url === '/api/oauth/usage') return counted({five_hour:{utilization:23}, seven_day_opus:{utilization:81}});
    if (provider === 'droid' && req.url === '/api/billing/limits') return counted({usesTokenRateLimitsBilling:true,extraUsageBalanceCents:12345,limits:{standard:{fiveHour:{usedPercent:34}},core:{weekly:{usedPercent:79}}}});
    if (provider === 'droid' && req.url === '/api/app/auth/me') return answer({user:{email},organization:{planName:'Pro'}});
    if (provider === 'grok' && req.url === '/v1/billing?format=credits') return counted({config:{creditUsagePercent:45,subscriptionTier:'SuperGrok',currentPeriod:{type:'WEEKLY',start:'2026-11-20T00:00:00Z',end:'2026-11-27T00:00:00Z'}}});
    if (provider === 'antigravity' && req.url.endsWith(':loadCodeAssist')) return answer({cloudaicompanionProject:'private-actual-project',currentTier:{id:'free-tier'}});
    if (provider === 'antigravity' && req.url.endsWith(':retrieveUserQuotaSummary')) return counted({groups:[{displayName:'Gemini Models',buckets:[{bucketId:'gemini-weekly',window:'weekly',displayName:'Weekly Limit',remainingFraction:0.44,resetTime:'2026-11-28T00:00:00Z'}]}]});
    if (provider === 'antigravity' && req.url.endsWith('userinfo')) return answer({sub:'private-google-member',id:'private-google-member',email});
    if (provider === 'cursor' && req.url === '/auth/usage-summary') return counted({membershipType:'ultra',billingCycleEnd:'2026-12-01T00:00:00Z',individualUsage:{plan:{used:39273,limit:40000,totalPercentUsed:n === 0 ? 15.7092 : 16.25,autoPercentUsed:0,apiPercentUsed:77.862},onDemand:{enabled:true,used:347}}});
    if (provider === 'amp' && req.url === '/api/internal?userDisplayBalanceInfo') return counted({ok:true,result:{displayText:`Signed in as ${email} (fixture)\nAmp Free: 33% remaining today (resets daily)\nIndividual credits: $14.41 remaining - https://ampcode.com/settings\nPrivate Workspace credits: $91.72 remaining`}});
    return answer(null, 404);
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
