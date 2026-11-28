import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';
import assert from 'node:assert/strict';

const ktSource = readFileSync(
  new URL('../composeApp/src/commonMain/kotlin/do_/linux/lindo/core/network/AndroidWebViewBridgeContract.kt', import.meta.url),
  'utf8'
);
const templateJs = ktSource
  .split('internal val ANDROID_HTTP_BRIDGE_TEMPLATE_JS: String = """')[1]
  .split('""".trimIndent()')[0];

function createBridgeEnv({
  origin = 'https://linux.do',
  protocol = 'https:',
  href = 'https://linux.do/about',
  domCsrf = '',
  fetchImpl
} = {}) {
  let now = 1_000_000;
  let nextTimerId = 1;
  const timers = new Map();
  const results = [];
  const csrfUpdates = [];
  let fetchCalls = [];

  const context = vm.createContext({
    location: { origin, protocol, href },
    document: {
      querySelector: (sel) => (sel.includes('csrf-token') && domCsrf ? { content: domCsrf } : null)
    },
    Date: { now: () => now },
    AbortController,
    setTimeout: (fn, ms) => {
      const id = nextTimerId++;
      timers.set(id, { fn, due: now + Math.max(0, Number(ms) || 0) });
      return id;
    },
    clearTimeout: (id) => {
      timers.delete(id);
    },
    LinDoJsBridge: {
      onHttpResultFull: (reqId, status, body, cfMitigated, retryAfter, loggedOut) => {
        results.push({ reqId, status, body, cfMitigated, retryAfter, loggedOut });
      },
      onCsrfTokenUpdated: (token) => {
        csrfUpdates.push(token);
      }
    },
    fetch: async (url, opts) => {
      fetchCalls.push({ url, opts });
      return fetchImpl(url, opts);
    }
  });
  context.window = context;

  async function advanceTime(ms) {
    const target = now + ms;
    while (true) {
      let earliestId = null;
      let earliestDue = Infinity;
      for (const [id, entry] of timers.entries()) {
        if (entry.due <= target && entry.due < earliestDue) {
          earliestDue = entry.due;
          earliestId = id;
        }
      }
      if (earliestId === null) break;
      const entry = timers.get(earliestId);
      timers.delete(earliestId);
      now = entry.due;
      entry.fn();
      await new Promise((r) => setImmediate(r));
    }
    now = target;
    await new Promise((r) => setImmediate(r));
  }

  function buildCfg(overrides = {}) {
    return {
      reqId: 'req_1',
      url: 'https://linux.do/latest.json?no_definitions=true',
      method: 'GET',
      headers: {},
      formBody: '',
      referer: 'https://linux.do/',
      csrfOwner: 'owner_a',
      kotlinCsrf: '',
      csrfInCooldown: false,
      csrfFallbackErr: '会话校验失败',
      csrfHeaderTimeoutMs: 3500,
      csrfBodyTimeoutMs: 2500,
      csrfMaxAttempts: 1,
      headerTimeoutMs: 6500,
      bodyTimeoutMs: 4500,
      jsHardCapMs: 16000,
      ...overrides
    };
  }

  function dispatch(cfgOverrides = {}) {
    const cfg = buildCfg(cfgOverrides);
    const script = templateJs.replace('__LINDO_BRIDGE_CFG__', JSON.stringify(cfg));
    vm.runInContext(script, context);
    return cfg.reqId;
  }

  return {
    context,
    results,
    csrfUpdates,
    get fetchCalls() {
      return fetchCalls;
    },
    dispatch,
    advanceTime
  };
}

test('chrome-error and about:blank documents fail fast with origin_lost and zero fetch calls', async () => {
  for (const bad of [
    { origin: 'null', protocol: 'chrome-error:', href: 'chrome-error://chromewebdata/' },
    { origin: 'null', protocol: 'about:', href: 'about:blank' },
    { origin: 'http://linux.do', protocol: 'http:', href: 'http://linux.do/about' }
  ]) {
    const env = createBridgeEnv({
      ...bad,
      fetchImpl: async () => {
        throw new Error('must never call fetch');
      }
    });
    env.dispatch();
    await env.advanceTime(10);
    assert.equal(env.fetchCalls.length, 0);
    assert.equal(env.results.length, 1);
    assert.equal(env.results[0].status, 0);
    assert.match(env.results[0].body, /^\[BRIDGE_DIAG:origin_lost\]/);
  }
});

test('background write hard-cap fires cleanly before Kotlin 22s timeout and reports at-most-once', async () => {
  const env = createBridgeEnv({
    fetchImpl: (url, opts) =>
      new Promise((_, reject) => {
        opts.signal.addEventListener('abort', () => {
          const e = new Error('The operation was aborted');
          e.name = 'AbortError';
          reject(e);
        });
      })
  });
  env.dispatch({
    reqId: 'bg_mark_read',
    url: 'https://linux.do/notifications/mark-read.json',
    method: 'PUT',
    headers: { 'Discourse-Background': 'true' },
    formBody: 'id=123',
    kotlinCsrf: 'seeded_csrf',
    jsHardCapMs: 16000,
    headerTimeoutMs: 20000
  });
  // Before 16s hard cap: still in flight
  await env.advanceTime(15000);
  assert.equal(env.results.length, 0);

  // At 16s (< Kotlin's 22s timeout): JS reports timeout cleanly and never double-reports
  await env.advanceTime(1500);
  assert.equal(env.results.length, 1);
  assert.equal(env.results[0].reqId, 'bg_mark_read');
  assert.equal(env.results[0].status, 0);
  assert.match(env.results[0].body, /^\[BRIDGE_DIAG:js_timeout\]/);

  await env.advanceTime(10000);
  assert.equal(env.results.length, 1);
});

test('csrfInCooldown short-circuits missing-CSRF write with zero /session/csrf.json network calls', async () => {
  const env = createBridgeEnv({
    fetchImpl: async () => {
      throw new Error('should not fetch csrf during cooldown');
    }
  });
  env.dispatch({
    url: 'https://linux.do/notifications/mark-read.json',
    method: 'PUT',
    kotlinCsrf: '',
    csrfInCooldown: true
  });
  await env.advanceTime(50);
  assert.equal(env.fetchCalls.length, 0);
  assert.equal(env.results.length, 1);
  assert.match(env.results[0].body, /^\[BRIDGE_DIAG:csrf_cooldown\]/);
});

test('BAD CSRF 403 refreshes token once and replays write to 200 OK', async () => {
  let attempt = 0;
  const env = createBridgeEnv({
    fetchImpl: async (url, opts) => {
      attempt++;
      if (url.endsWith('/session/csrf.json')) {
        return {
          ok: true,
          status: 200,
          headers: { get: () => null },
          text: async () => JSON.stringify({ csrf: 'fresh_csrf_999' })
        };
      }
      if (opts.headers['X-CSRF-Token'] === 'stale_csrf') {
        return {
          ok: false,
          status: 403,
          headers: { get: () => null },
          text: async () => '["BAD CSRF"]'
        };
      }
      assert.equal(opts.headers['X-CSRF-Token'], 'fresh_csrf_999');
      return {
        ok: true,
        status: 200,
        headers: { get: () => null },
        text: async () => '{"success":"OK"}'
      };
    }
  });
  env.dispatch({
    url: 'https://linux.do/notifications/mark-read.json',
    method: 'PUT',
    kotlinCsrf: 'stale_csrf'
  });
  await env.advanceTime(100);
  assert.equal(attempt, 3);
  assert.deepEqual(env.csrfUpdates, ['', 'fresh_csrf_999']);
  assert.equal(env.results.length, 1);
  assert.equal(env.results[0].status, 200);
  assert.equal(env.results[0].body, '{"success":"OK"}');
});

test('OTP exchange forces redirect:manual and converts opaqueredirect to HTTP 302', async () => {
  const env = createBridgeEnv({
    fetchImpl: async (url, opts) => {
      if (url.endsWith('/session/csrf.json')) {
        return {
          ok: true,
          status: 200,
          headers: { get: () => null },
          text: async () => JSON.stringify({ csrf: 'otp_csrf' })
        };
      }
      assert.equal(opts.redirect, 'manual');
      return {
        ok: false,
        status: 0,
        type: 'opaqueredirect',
        headers: { get: () => null },
        text: async () => ''
      };
    }
  });
  env.dispatch({
    url: 'https://linux.do/session/otp/secret_token',
    method: 'POST',
    kotlinCsrf: 'ignored_for_otp',
    csrfInCooldown: true // OTP must bypass csrfInCooldown
  });
  await env.advanceTime(100);
  assert.equal(env.results.length, 1);
  assert.equal(env.results[0].status, 302);
});

test('window.__lindoCancelHttp aborts in-flight fetch and suppresses late bridge callback', async () => {
  let aborted = false;
  const env = createBridgeEnv({
    fetchImpl: (_, opts) =>
      new Promise((_, reject) => {
        opts.signal.addEventListener('abort', () => {
          aborted = true;
          reject(new Error('aborted'));
        });
      })
  });
  const reqId = env.dispatch({ reqId: 'cancel_me' });
  await env.advanceTime(50);
  env.context.__lindoCancelHttp(reqId);
  await env.advanceTime(20000);
  assert.equal(aborted, true);
  assert.equal(env.results.length, 0);
});

test('Cloudflare 403 challenge on /session/csrf.json never poisons foreground Like/Reply writes with CsrfCooldownActive', async () => {
  const env = createBridgeEnv({
    fetchImpl: async () => ({
      ok: false,
      status: 403,
      headers: { get: (k) => (k.toLowerCase() === 'cf-mitigated' ? 'challenge' : null) },
      text: async () => '<html><head><title>Just a moment...</title></head><body></body></html>'
    })
  });
  // 1st background write hits CF 403 on /session/csrf.json
  env.dispatch({
    reqId: 'bg_mark_read',
    url: 'https://linux.do/notifications/mark-read.json',
    method: 'PUT',
    headers: { 'Discourse-Background': 'true' },
    kotlinCsrf: ''
  });
  await env.advanceTime(50);
  assert.equal(env.results[0].status, 403);
  assert.equal(env.results[0].cfMitigated, 'challenge');

  // 2s later, foreground Like toggle MUST NOT return status=0 csrf_cooldown — it must surface 403 challenge!
  await env.advanceTime(2000);
  env.dispatch({
    reqId: 'fg_heart_toggle',
    url: 'https://linux.do/discourse-reactions/posts/25358989/custom-reactions/heart/toggle.json',
    method: 'PUT',
    headers: {},
    kotlinCsrf: ''
  });
  await env.advanceTime(50);
  assert.equal(env.results[1].reqId, 'fg_heart_toggle');
  assert.equal(env.results[1].status, 403);
  assert.equal(env.results[1].cfMitigated, 'challenge');
});
