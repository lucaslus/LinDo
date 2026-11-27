import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import test from "node:test";
import vm from "node:vm";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const BRIDGE_RS_PATH = path.resolve(
  __dirname,
  "../desktop-tauri/src-tauri/src/bridge.rs"
);

function loadDesktopHttpBridgeTemplate() {
  const src = fs.readFileSync(BRIDGE_RS_PATH, "utf8");
  const marker = 'pub(crate) const DESKTOP_HTTP_BRIDGE_TEMPLATE_JS: &str = r#"';
  const startIdx = src.indexOf(marker);
  assert.ok(startIdx !== -1, "Could not locate DESKTOP_HTTP_BRIDGE_TEMPLATE_JS in bridge.rs");
  const bodyStart = startIdx + marker.length;
  const endIdx = src.indexOf('"#;', bodyStart);
  assert.ok(endIdx !== -1, "Could not locate end of DESKTOP_HTTP_BRIDGE_TEMPLATE_JS");
  return src.slice(bodyStart, endIdx);
}

const TEMPLATE_JS = loadDesktopHttpBridgeTemplate();

function makeHeaders(map = {}) {
  const normalized = new Map(
    Object.entries(map).map(([k, v]) => [k.toLowerCase(), String(v)])
  );
  return {
    get(name) {
      return normalized.get(String(name).toLowerCase()) ?? null;
    },
  };
}

function makeResponse(status, body, headers = {}) {
  return {
    status,
    ok: status >= 200 && status < 300,
    headers: makeHeaders(headers),
    async text() {
      return typeof body === "string" ? body : JSON.stringify(body);
    },
    async json() {
      return typeof body === "string" ? JSON.parse(body) : body;
    },
  };
}

async function runBridgeInVm(cfgOverrides = {}, envOverrides = {}) {
  const cfg = {
    url: "https://linux.do/latest.json",
    method: "GET",
    headers: { Accept: "application/json" },
    form: null,
    referrer: "https://linux.do/",
    forceNoStore: false,
    csrfOwner: "lucas",
    reqTimeoutMs: 12000,
    csrfTimeoutMs: 8000,
    retryTimeoutMs: 10000,
    hardCapMs: 22000,
    ...cfgOverrides,
  };

  const windowObj = {
    ...(envOverrides.windowProps || {}),
  };
  const sandbox = {
    window: windowObj,
    location: {
      origin: envOverrides.origin ?? "https://linux.do",
    },
    document: {
      querySelector(sel) {
        if (sel === 'meta[name="csrf-token"]' && envOverrides.domCsrf) {
          return { content: envOverrides.domCsrf };
        }
        return null;
      },
    },
    AbortController,
    URLSearchParams,
    setTimeout,
    clearTimeout,
    Date,
    Math,
    Object,
    Set,
    Array,
    Promise,
    Number,
    String,
    fetch: envOverrides.fetchImpl ?? (async () => makeResponse(200, { ok: true })),
  };
  sandbox.window.document = sandbox.document;
  sandbox.window.location = sandbox.location;

  const script = TEMPLATE_JS.replace("__LINDO_CFG_JSON__", JSON.stringify(cfg));
  const res = await vm.runInNewContext(script, sandbox, { timeout: 5000 });
  return { res, windowObj };
}

test("1. returns originLost=true immediately when WebView origin is not https://linux.do", async () => {
  let fetchCalled = false;
  const { res } = await runBridgeInVm(
    {},
    {
      origin: "about:blank",
      fetchImpl: async () => {
        fetchCalled = true;
        return makeResponse(200, "{}");
      },
    }
  );
  assert.equal(res.originLost, true);
  assert.equal(fetchCalled, false);
});

test("recovery reads the requested topic once and returns the actual response without navigating", async () => {
  const url = "https://linux.do/t/482293/8.json?track_visit=false";
  for (const [status, body, headers] of [
    [200, { id: 482293, post_stream: { posts: [] } }, { "content-type": "application/json" }],
    [403, "challenge page", { "content-type": "text/html", "cf-mitigated": "challenge" }],
    [403, { errors: ["You need to be logged in"], error_type: "not_logged_in" }, { "content-type": "application/json" }],
    [429, { errors: ["wait"] }, { "content-type": "application/json", "retry-after": "45" }],
  ]) {
    const calls = [];
    const { res } = await runBridgeInVm({
      url, singleRead: true, forceNoStore: true,
      headers: { Accept: "application/json", "Discourse-Logged-In": "true" },
    }, {
      fetchImpl: async (target, init) => {
        calls.push([target, init.method, init.credentials, init.cache]);
        return makeResponse(status, body, headers);
      },
    });
    assert.deepEqual(calls, [[url, "GET", "include", "no-store"]]);
    assert.equal(res.status, status);
    assert.equal(res.body, typeof body === "string" ? body : JSON.stringify(body));
    assert.equal(res.challenge, headers["cf-mitigated"] ?? null);
    assert.equal(res.retryAfter, headers["retry-after"] ?? null);
  }
});

test("2. preserves pre-seeded DOM/window CSRF on fresh boot without false owner mismatch", async () => {
  const calls = [];
  const { res, windowObj } = await runBridgeInVm(
    {
      url: "https://linux.do/topics/timings",
      method: "POST",
      form: { topic_id: "100" },
      csrfOwner: "lucas",
    },
    {
      domCsrf: "dom_csrf_fresh_boot",
      fetchImpl: async (url, init) => {
        calls.push({ url, csrf: init?.headers?.["X-CSRF-Token"] });
        return makeResponse(200, { ok: true });
      },
    }
  );
  assert.equal(res.status, 200);
  assert.equal(calls.length, 1);
  assert.equal(calls[0].url, "https://linux.do/topics/timings");
  assert.equal(calls[0].csrf, "dom_csrf_fresh_boot");
  assert.equal(windowObj.__lindoCsrfOwner, "lucas");
});

test("3. invalidates cached CSRF across account switches and fetches fresh token for new owner", async () => {
  const calls = [];
  const { res, windowObj } = await runBridgeInVm(
    {
      url: "https://linux.do/notifications/mark-read.json",
      method: "PUT",
      form: { id: "42" },
      csrfOwner: "bob",
    },
    {
      domCsrf: "stale_alice_dom_csrf",
      windowProps: {
        __lindoCsrfOwner: "alice",
        __lindoCsrf: "stale_alice_mem_csrf",
        __lindoIgnoreDomCsrf: false,
      },
      fetchImpl: async (url, init) => {
        calls.push({ url, csrf: init?.headers?.["X-CSRF-Token"] });
        if (url === "/session/csrf.json") {
          return makeResponse(200, { csrf: "fresh_bob_csrf" });
        }
        return makeResponse(200, { success: "OK" });
      },
    }
  );
  assert.equal(res.status, 200);
  assert.deepEqual(
    calls.map((c) => c.url),
    ["/session/csrf.json", "https://linux.do/notifications/mark-read.json"]
  );
  assert.equal(calls[1].csrf, "fresh_bob_csrf");
  assert.equal(windowObj.__lindoCsrfOwner, "bob");
  assert.equal(windowObj.__lindoCsrf, "fresh_bob_csrf");
});

test("4. enforces 30s CSRF failure cooldown to prevent /session/csrf.json storming", async () => {
  let csrfCalls = 0;
  const sharedWindow = {};
  const fetch403 = async (url) => {
    if (url === "/session/csrf.json") {
      csrfCalls += 1;
      return makeResponse(403, "<html>Just a moment...</html>", {
        "cf-mitigated": "challenge",
        "content-type": "text/html",
      });
    }
    return makeResponse(200, "{}");
  };

  const r1 = await runBridgeInVm(
    {
      url: "https://linux.do/topics/timings",
      method: "POST",
      csrfOwner: "lucas",
    },
    { windowProps: sharedWindow, fetchImpl: fetch403 }
  );
  assert.equal(r1.res.status, 403);
  assert.equal(csrfCalls, 1);
  assert.ok(r1.windowObj.__lindoCsrfCooldownUntil > Date.now());

  const r2 = await runBridgeInVm(
    {
      url: "https://linux.do/topics/timings",
      method: "POST",
      csrfOwner: "lucas",
    },
    { windowProps: r1.windowObj, fetchImpl: fetch403 }
  );
  assert.equal(r2.res.status, 429);
  assert.equal(r2.res.localCooldown, true);
  assert.equal(csrfCalls, 1);
});

test("5. settles hung fetch via JS hardCapMs with originLost=false before Rust IPC timeout", async () => {
  let signalAborted = false;
  const t0 = Date.now();
  const { res } = await runBridgeInVm(
    {
      url: "https://linux.do/latest.json",
      method: "GET",
      reqTimeoutMs: 50000,
      hardCapMs: 3000, // min clamped to 3000ms in template
    },
    {
      fetchImpl: (_url, init) =>
        new Promise((_resolve, reject) => {
          init?.signal?.addEventListener("abort", () => {
            signalAborted = true;
            reject(new Error("The operation was aborted"));
          });
        }),
    }
  );
  const elapsed = Date.now() - t0;
  assert.ok(elapsed < 4200, `Expected JS settlement < 4200ms, took ${elapsed}ms`);
  assert.equal(signalAborted, true);
  assert.equal(res.originLost, false);
  assert.ok(typeof res.netErr === "string" && res.netErr.length > 0);
});

test("6. transparently refreshes expired CSRF on 422 BAD CSRF and replays mutation once", async () => {
  const seenTokens = [];
  const { res } = await runBridgeInVm(
    {
      url: "https://linux.do/posts.json",
      method: "POST",
      form: { raw: "hello" },
      csrfOwner: "lucas",
    },
    {
      windowProps: {
        __lindoCsrfOwner: "lucas",
        __lindoCsrf: "expired_csrf_tok",
      },
      fetchImpl: async (url, init) => {
        if (url === "/session/csrf.json") {
          return makeResponse(200, { csrf: "rotated_csrf_tok" });
        }
        const tok = init?.headers?.["X-CSRF-Token"];
        seenTokens.push(tok);
        if (tok === "expired_csrf_tok") {
          return makeResponse(422, '{"errors":["BAD CSRF"]}');
        }
        return makeResponse(200, { id: 999 });
      },
    }
  );
  assert.equal(res.status, 200);
  assert.equal(res.csrfRefreshed, true);
  assert.deepEqual(seenTokens, ["expired_csrf_tok", "rotated_csrf_tok"]);
});

test("7. falls back to guest GET when Discourse-Logged-In header receives non-CF 403 not_logged_in", async () => {
  const seenLoggedHeaders = [];
  const { res } = await runBridgeInVm(
    {
      url: "https://linux.do/latest.json",
      method: "GET",
      headers: {
        Accept: "application/json",
        "Discourse-Logged-In": "true",
      },
    },
    {
      fetchImpl: async (_url, init) => {
        const hasLoggedHdr = Boolean(init?.headers?.["Discourse-Logged-In"]);
        seenLoggedHeaders.push(hasLoggedHdr);
        if (hasLoggedHdr) {
          return makeResponse(403, '{"errors":["You need to be logged in"],"error_type":"not_logged_in"}', {
            "content-type": "application/json",
          });
        }
        return makeResponse(200, { topic_list: { topics: [] } }, {
          "content-type": "application/json",
        });
      },
    }
  );
  assert.equal(res.status, 200);
  assert.equal(res.guestFallback, true);
  assert.equal(res.hadUnauthLoggedHeader, true);
  assert.deepEqual(seenLoggedHeaders, [true, false]);
});
