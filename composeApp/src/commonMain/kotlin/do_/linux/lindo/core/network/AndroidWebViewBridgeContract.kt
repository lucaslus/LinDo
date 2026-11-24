package do_.linux.lindo.core.network

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class AndroidBridgeTimeoutBudget(
    val csrfHeaderTimeoutMs: Long,
    val csrfBodyTimeoutMs: Long,
    val csrfMaxAttempts: Int,
    val headerTimeoutMs: Long,
    val bodyTimeoutMs: Long,
    val jsHardCapMs: Long,
    val kotlinTimeoutMs: Long
) {
    init {
        require(kotlinTimeoutMs >= jsHardCapMs + MIN_KOTLIN_GUARD_MARGIN_MS) {
            "kotlinTimeoutMs ($kotlinTimeoutMs) must exceed jsHardCapMs ($jsHardCapMs) by >= ${MIN_KOTLIN_GUARD_MARGIN_MS}ms"
        }
    }

    companion object {
        const val MIN_KOTLIN_GUARD_MARGIN_MS: Long = 5_000L
    }
}

data class AndroidHttpBridgeConfig(
    val reqId: String,
    val url: String,
    val method: String,
    val headers: Map<String, String>,
    val formBodyEncoded: String,
    val preferredReferer: String,
    val csrfOwnerTag: String,
    val initialCachedCsrf: String,
    val csrfInCooldown: Boolean,
    val csrfFallbackErrorMsg: String,
    val timeouts: AndroidBridgeTimeoutBudget
)

object AndroidWebViewBridgeContract {
    private val forbiddenJsHeaders = setOf(
        "origin", "referer", "sec-fetch-dest", "sec-fetch-mode", "sec-fetch-site",
        "cookie", "user-agent"
    )

    fun isValidLinuxDoDocumentUrl(url: String?): Boolean {
        val clean = url?.trim().orEmpty()
        if (clean.isEmpty()) return false
        return clean.equals("https://linux.do", ignoreCase = true) ||
            clean.startsWith("https://linux.do/", ignoreCase = true)
    }

    /**
     * 单一数学公式推导 JS 分阶段超时、JS 总死线硬上限 (`jsHardCapMs`) 与 Kotlin 桥接超时 (`kotlinTimeoutMs`)：
     * 恒定保证 `kotlinTimeoutMs >= jsHardCapMs + 5_000ms`，杜绝慢网 / CSRF 刷新重试比 Kotlin `bridgeTimeoutMs` 晚返回，
     * 从而彻底根除把普通网络抖动误判为 `js_bridge_no_callback` 连环销毁重建 `apiWebView` 的顽疾。
     */
    fun computeTimeouts(isRead: Boolean, isBackground: Boolean): AndroidBridgeTimeoutBudget = when {
        isBackground && isRead -> AndroidBridgeTimeoutBudget(
            csrfHeaderTimeoutMs = 0L,
            csrfBodyTimeoutMs = 0L,
            csrfMaxAttempts = 0,
            headerTimeoutMs = 7_500L,
            bodyTimeoutMs = 5_000L,
            jsHardCapMs = 13_000L,
            kotlinTimeoutMs = 19_000L
        )
        isBackground && !isRead -> AndroidBridgeTimeoutBudget(
            csrfHeaderTimeoutMs = 3_500L,
            csrfBodyTimeoutMs = 2_500L,
            csrfMaxAttempts = 1,
            headerTimeoutMs = 6_500L,
            bodyTimeoutMs = 4_500L,
            jsHardCapMs = 16_000L,
            kotlinTimeoutMs = 22_000L
        )
        !isBackground && isRead -> AndroidBridgeTimeoutBudget(
            csrfHeaderTimeoutMs = 0L,
            csrfBodyTimeoutMs = 0L,
            csrfMaxAttempts = 0,
            headerTimeoutMs = 10_000L,
            bodyTimeoutMs = 12_000L,
            jsHardCapMs = 22_500L,
            kotlinTimeoutMs = 28_500L
        )
        else -> AndroidBridgeTimeoutBudget(
            csrfHeaderTimeoutMs = 4_000L,
            csrfBodyTimeoutMs = 3_000L,
            csrfMaxAttempts = 2,
            headerTimeoutMs = 14_000L,
            bodyTimeoutMs = 10_000L,
            jsHardCapMs = 30_000L,
            kotlinTimeoutMs = 36_000L
        )
    }

    fun sanitizeJsHeaders(
        rawHeaders: Map<String, String>,
        hasBrowserSessionCookie: Boolean
    ): Map<String, String> = buildMap {
        rawHeaders.forEach { (k, v) ->
            val lower = k.lowercase()
            if (lower !in forbiddenJsHeaders && (lower != "discourse-logged-in" || hasBrowserSessionCookie)) {
                put(k, v)
            }
        }
    }

    fun buildConfigJson(config: AndroidHttpBridgeConfig): String = buildJsonObject {
        put("reqId", config.reqId)
        put("url", config.url)
        put("method", config.method.uppercase())
        put("headers", buildJsonObject {
            config.headers.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
        })
        put("formBody", config.formBodyEncoded)
        put("referer", config.preferredReferer)
        put("csrfOwner", config.csrfOwnerTag)
        put("kotlinCsrf", config.initialCachedCsrf)
        put("csrfInCooldown", config.csrfInCooldown)
        put("csrfFallbackErr", config.csrfFallbackErrorMsg)
        put("csrfHeaderTimeoutMs", config.timeouts.csrfHeaderTimeoutMs)
        put("csrfBodyTimeoutMs", config.timeouts.csrfBodyTimeoutMs)
        put("csrfMaxAttempts", config.timeouts.csrfMaxAttempts)
        put("headerTimeoutMs", config.timeouts.headerTimeoutMs)
        put("bodyTimeoutMs", config.timeouts.bodyTimeoutMs)
        put("jsHardCapMs", config.timeouts.jsHardCapMs)
    }.toString()

    fun buildHttpBridgeScript(config: AndroidHttpBridgeConfig): String {
        val cfgJson = buildConfigJson(config)
        return ANDROID_HTTP_BRIDGE_TEMPLATE_JS.replace("__LINDO_BRIDGE_CFG__", cfgJson)
    }
}

internal val ANDROID_HTTP_BRIDGE_TEMPLATE_JS: String = """
(async function() {
    const cfg = __LINDO_BRIDGE_CFG__;
    window.__lindoActiveHttp = window.__lindoActiveHttp || {};
    window.__lindoCancelHttp = window.__lindoCancelHttp || function(id) {
        const cancelFn = window.__lindoActiveHttp && window.__lindoActiveHttp[id];
        if (cancelFn) {
            delete window.__lindoActiveHttp[id];
            try { cancelFn(); } catch (_) {}
        }
    };
    const reqKey = cfg.reqId;
    const method = cfg.method;
    const isRead = method === 'GET' || method === 'HEAD';
    const jsStartMs = Date.now();
    let bridgePhase = 'init';
    let cancelledByKotlin = false;
    let reported = false;
    let currentAttemptCtrl = null;
    let timedOutByJs = false;
    let hardCapFired = false;

    function reportHttp(status, body, cfMitigated, retryAfter, loggedOutHeader) {
        if (cancelledByKotlin || reported) return;
        reported = true;
        if (window.LinDoJsBridge && window.LinDoJsBridge.onHttpResultFull) {
            window.LinDoJsBridge.onHttpResultFull(reqKey, status, body || '', cfMitigated || '', retryAfter || '', loggedOutHeader || '');
        } else if (window.LinDoJsBridge && window.LinDoJsBridge.onHttpResultWithRetryAfter) {
            window.LinDoJsBridge.onHttpResultWithRetryAfter(reqKey, status, body || '', cfMitigated || '', retryAfter || '');
        } else if (window.LinDoJsBridge && window.LinDoJsBridge.onHttpResultWithMeta) {
            window.LinDoJsBridge.onHttpResultWithMeta(reqKey, status, body || '', cfMitigated || '');
        } else if (window.LinDoJsBridge) {
            window.LinDoJsBridge.onHttpResult(reqKey, status, body || '');
        }
    }

    if (!window.location || window.location.origin !== 'https://linux.do' || window.location.protocol !== 'https:') {
        const badHref = (window.location && window.location.href) ? String(window.location.href).slice(0, 80) : 'no_location';
        reportHttp(0, '[BRIDGE_DIAG:origin_lost] phase=origin_check jsMs=0 err=OriginMismatch:' + badHref, '', '', '');
        return;
    }

    window.__lindoActiveHttp[reqKey] = function() {
        cancelledByKotlin = true;
        if (currentAttemptCtrl) {
            try { currentAttemptCtrl.abort(); } catch (_) {}
        }
    };

    const hardCapTimer = setTimeout(function() {
        if (reported || cancelledByKotlin) return;
        hardCapFired = true;
        timedOutByJs = true;
        if (currentAttemptCtrl) {
            try { currentAttemptCtrl.abort(); } catch (_) {}
        }
        const capTag = bridgePhase.indexOf('csrf') === 0 ? 'csrf_timeout' : 'js_timeout';
        reportHttp(0, '[BRIDGE_DIAG:' + capTag + '] phase=' + bridgePhase + ' jsMs=' + (Date.now() - jsStartMs) + ' err=HardCapTimeout:' + cfg.jsHardCapMs + 'ms', '', '', '');
    }, cfg.jsHardCapMs);

    function remainingHardCapMs() {
        return Math.max(250, cfg.jsHardCapMs - (Date.now() - jsStartMs));
    }

    async function fetchTextWithTimeout(inputUrl, baseOpts, headerTimeoutMs, bodyTimeoutMs, phaseTag) {
        if (cancelledByKotlin || hardCapFired) throw new Error('Cancelled');
        bridgePhase = phaseTag || 'http_fetch';
        timedOutByJs = false;
        const attemptCtrl = new AbortController();
        currentAttemptCtrl = attemptCtrl;
        const effHeaderMs = Math.min(headerTimeoutMs, remainingHardCapMs());
        let stageTimer = setTimeout(function() {
            timedOutByJs = true;
            try { attemptCtrl.abort(); } catch (_) {}
        }, effHeaderMs);
        try {
            const r = await fetch(inputUrl, Object.assign({}, baseOpts, { signal: attemptCtrl.signal }));
            clearTimeout(stageTimer);
            bridgePhase = (phaseTag || 'http_fetch') + '_body';
            const effBodyMs = Math.min(bodyTimeoutMs || headerTimeoutMs, remainingHardCapMs());
            stageTimer = setTimeout(function() {
                timedOutByJs = true;
                try { attemptCtrl.abort(); } catch (_) {}
            }, effBodyMs);
            const bodyText = await r.text();
            return { resp: r, text: bodyText };
        } finally {
            clearTimeout(stageTimer);
            if (currentAttemptCtrl === attemptCtrl) {
                currentAttemptCtrl = null;
            }
        }
    }

    try {
        const targetUrl = cfg.url;
        const hdrs = Object.assign({}, cfg.headers || {});
        if (!hdrs['Accept'] && !hdrs['accept']) {
            hdrs['Accept'] = 'application/json, text/javascript, */*; q=0.01';
        }
        if (!hdrs['X-Requested-With'] && !hdrs['x-requested-with']) {
            hdrs['X-Requested-With'] = 'XMLHttpRequest';
        }
        const currentOwner = cfg.csrfOwner || '';
        const hadPriorOwner = typeof window.__lindoCsrfCookieOwner === 'string' && window.__lindoCsrfCookieOwner.length > 0;
        const ownerChanged = hadPriorOwner && window.__lindoCsrfCookieOwner !== currentOwner;
        if (window.__lindoCsrfCookieOwner !== currentOwner) {
            if (ownerChanged) {
                window.__lindoCsrf = null;
                window.__lindoCsrfPromise = null;
            }
            window.__lindoCsrfCookieOwner = currentOwner;
        }
        const kotlinCsrf = cfg.kotlinCsrf || '';
        if (!kotlinCsrf) {
            if (ownerChanged) {
                window.__lindoCsrf = null;
            }
        } else if (!ownerChanged) {
            window.__lindoCsrf = kotlinCsrf;
        }
        const isOtpExchange = targetUrl.indexOf('/session/otp/') !== -1;
        const isApiKeyRevoke = targetUrl.indexOf('/user-api-key/revoke') !== -1 &&
            Boolean(hdrs['User-Api-Key'] || hdrs['user-api-key']);
        let csrfLastStatus = 0;
        let csrfLastBody = '';
        let csrfLastMitigated = '';
        let csrfLastRetryAfter = '';
        let csrfLastErr = '';
        let csrfTimedOut = false;

        async function refreshCsrfToken(force) {
            if (!force && window.__lindoCsrf) {
                return window.__lindoCsrf;
            }
            if (!force && ((cfg.csrfInCooldown && !isOtpExchange) || (window.__lindoCsrfCooldownUntil && Date.now() < window.__lindoCsrfCooldownUntil && !isOtpExchange))) {
                csrfLastErr = 'CsrfCooldownActive';
                return null;
            }
            if (window.__lindoCsrfPromise) {
                try {
                    const shared = await window.__lindoCsrfPromise;
                    if (shared) return shared;
                } catch (_) {}
            }
            const maxAttempts = Math.max(1, cfg.csrfMaxAttempts || 1);
            window.__lindoCsrfPromise = (async function() {
                for (let attempt = 0; attempt < maxAttempts; attempt++) {
                    if (cancelledByKotlin || hardCapFired || remainingHardCapMs() < 600) break;
                    try {
                        const csrfOpts = {
                            headers: {
                                'Accept': 'application/json, text/javascript, */*; q=0.01',
                                'X-Requested-With': 'XMLHttpRequest'
                            },
                            credentials: 'include',
                            cache: 'no-store',
                            referrer: 'https://linux.do/',
                            referrerPolicy: 'strict-origin-when-cross-origin'
                        };
                        const csrfOut = await fetchTextWithTimeout(
                            'https://linux.do/session/csrf.json',
                            csrfOpts,
                            cfg.csrfHeaderTimeoutMs || 3500,
                            cfg.csrfBodyTimeoutMs || 2500,
                            'csrf_fetch'
                        );
                        const csrfResp = csrfOut.resp;
                        csrfLastStatus = csrfResp.status || 0;
                        csrfLastMitigated = (csrfResp.headers && csrfResp.headers.get('cf-mitigated')) || '';
                        csrfLastRetryAfter = (csrfResp.headers && csrfResp.headers.get('Retry-After')) || '';
                        if (csrfResp.ok && !csrfLastMitigated) {
                            const csrfData = JSON.parse(csrfOut.text || '{}');
                            if (csrfData && csrfData.csrf) {
                                window.__lindoCsrf = csrfData.csrf;
                                window.__lindoCsrfCooldownUntil = 0;
                                if (window.LinDoJsBridge && window.LinDoJsBridge.onCsrfTokenUpdated) {
                                    window.LinDoJsBridge.onCsrfTokenUpdated(csrfData.csrf);
                                }
                                return csrfData.csrf;
                            }
                        } else {
                            csrfLastBody = csrfOut.text || '';
                            if (csrfResp.status === 429 || csrfResp.status === 403 || csrfResp.status === 503) {
                                window.__lindoCsrfCooldownUntil = Date.now() + 30000;
                            }
                            if (csrfLastMitigated || csrfResp.status === 429 || csrfResp.status === 401 || csrfResp.status === 403 || csrfResp.status === 503) {
                                break;
                            }
                        }
                    } catch (csrfErr) {
                        csrfTimedOut = timedOutByJs;
                        csrfLastErr = ((csrfErr && csrfErr.name) ? csrfErr.name : 'Error') + ': ' + ((csrfErr && csrfErr.message) ? csrfErr.message : String(csrfErr || ''));
                    }
                    if (attempt + 1 < maxAttempts && !cancelledByKotlin && !hardCapFired) {
                        await new Promise(function(r) { setTimeout(r, 200); });
                    }
                }
                return null;
            })();
            try {
                return await window.__lindoCsrfPromise;
            } finally {
                window.__lindoCsrfPromise = null;
            }
        }

        if (!isRead && !isApiKeyRevoke) {
            const metaCsrf = document.querySelector && document.querySelector('meta[name="csrf-token"]');
            const cachedCsrf = isOtpExchange
                ? null
                : (window.__lindoCsrf || (!window.__lindoIgnoreDomCsrf && metaCsrf && metaCsrf.content));
            const token = cachedCsrf || (await refreshCsrfToken(isOtpExchange));
            if (!token) {
                if (csrfLastStatus > 0) {
                    const body = csrfLastBody || JSON.stringify({ errors: [cfg.csrfFallbackErr || 'CSRF token unavailable'] });
                    reportHttp(csrfLastStatus, body, csrfLastMitigated, csrfLastRetryAfter, '');
                } else {
                    const csrfTag = csrfLastErr === 'CsrfCooldownActive'
                        ? 'csrf_cooldown'
                        : (csrfTimedOut ? 'csrf_timeout' : (csrfLastErr ? 'csrf_net_err' : 'csrf_failed'));
                    reportHttp(0, '[BRIDGE_DIAG:' + csrfTag + '] phase=csrf_fetch jsMs=' + (Date.now() - jsStartMs) + (csrfLastErr ? (' err=' + csrfLastErr) : ''), csrfLastMitigated, csrfLastRetryAfter, '');
                }
                return;
            }
            hdrs['X-CSRF-Token'] = token;
        }
        const bodyStr = cfg.formBody || '';
        const hasBody = !isRead && (bodyStr.length > 0 || method === 'POST');
        if (hasBody) {
            hdrs['Content-Type'] = 'application/x-www-form-urlencoded; charset=UTF-8';
        }
        const mustNoStore = isRead && /\/session\/(csrf|current)\.json|\/notifications|\/drafts/i.test(targetUrl);
        const targetReferer = cfg.referer || 'https://linux.do/';
        function buildBaseOpts() {
            const o = {
                method: method,
                headers: Object.assign({}, hdrs),
                credentials: 'include',
                cache: mustNoStore ? 'no-store' : 'default',
                referrer: targetReferer,
                referrerPolicy: 'strict-origin-when-cross-origin'
            };
            if (hasBody) {
                o.body = bodyStr;
            }
            if (isOtpExchange) {
                o.redirect = 'manual';
            }
            return o;
        }
        const out = await fetchTextWithTimeout(targetUrl, buildBaseOpts(), cfg.headerTimeoutMs, cfg.bodyTimeoutMs, 'http_fetch');
        let resp = out.resp;
        let text = out.text;
        let cfMitigated = (resp && resp.headers && resp.headers.get('cf-mitigated')) || '';
        if (!isRead && !isOtpExchange && !cfMitigated && (resp.status === 403 || resp.status === 422) && /csrf|invalid authenticity token|bad csrf/i.test(text) && remainingHardCapMs() >= 2500) {
            window.__lindoCsrf = null;
            window.__lindoIgnoreDomCsrf = true;
            if (window.LinDoJsBridge && window.LinDoJsBridge.onCsrfTokenUpdated) {
                window.LinDoJsBridge.onCsrfTokenUpdated('');
            }
            const freshToken = await refreshCsrfToken(true);
            if (freshToken && remainingHardCapMs() >= 1500) {
                hdrs['X-CSRF-Token'] = freshToken;
                const retryOut = await fetchTextWithTimeout(targetUrl, buildBaseOpts(), cfg.headerTimeoutMs, cfg.bodyTimeoutMs, 'csrf_retry_fetch');
                resp = retryOut.resp;
                text = retryOut.text;
                cfMitigated = (resp && resp.headers && resp.headers.get('cf-mitigated')) || '';
            } else if (csrfLastMitigated || csrfLastStatus === 403 || csrfLastStatus === 503 || csrfLastStatus === 429) {
                reportHttp(csrfLastStatus || resp.status, csrfLastBody || text, csrfLastMitigated, csrfLastRetryAfter, '');
                return;
            }
        }
        const loggedOutHeader = (resp && resp.headers && resp.headers.get('discourse-logged-out')) || '';
        if (isOtpExchange && resp && resp.type === 'opaqueredirect') {
            reportHttp(302, '', '', '', '');
            return;
        }
        reportHttp(resp.status, text, cfMitigated, resp.headers ? resp.headers.get('Retry-After') : '', loggedOutHeader);
    } catch (err) {
        const errName = (err && err.name) ? String(err.name) : 'Error';
        const errMsg = (err && err.message) ? String(err.message) : String(err || '');
        const diagTag = cancelledByKotlin
            ? 'kotlin_cancelled'
            : ((timedOutByJs || hardCapFired)
                ? (bridgePhase.indexOf('csrf') === 0 ? 'csrf_timeout' : 'js_timeout')
                : (bridgePhase.indexOf('csrf') === 0 ? 'csrf_net_err' : 'js_fetch_err'));
        reportHttp(0, '[BRIDGE_DIAG:' + diagTag + '] phase=' + bridgePhase + ' jsMs=' + (Date.now() - jsStartMs) + ' err=' + errName + ': ' + errMsg, '', '', '');
    } finally {
        clearTimeout(hardCapTimer);
        if (window.__lindoActiveHttp) {
            delete window.__lindoActiveHttp[reqKey];
        }
    }
})();
""".trimIndent()
