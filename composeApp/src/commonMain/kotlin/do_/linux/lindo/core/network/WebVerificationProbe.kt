package do_.linux.lindo.core.network

/**
 * Observe site-owned verification passively from the live Discourse document.
 * Never click widgets, never reload, and avoid unnecessary XHR probes once either
 * `#data-preloaded` or booted Discourse chrome (`header.d-header` / `#main-outlet`) is live.
 */
internal fun webVerificationProbeScript(): String = """
(function() {
    function result(value) { return JSON.stringify(value); }
    if (location.origin !== 'https://linux.do' || typeof document === 'undefined' || !document) {
        return result({waiting:true});
    }
    var pathname = location.pathname || '/';
    var title = (document.title || '').trim();
    var bodyText = (document.body && document.body.innerText) || '';
    var lowerHtml = (((document.documentElement && document.documentElement.innerHTML) || '') + ' ' + title).toLowerCase();
    var isOrigin404Body = lowerHtml.indexOf('page-not-found') !== -1 ||
        lowerHtml.indexOf('"errortype":"notfound"') !== -1 ||
        lowerHtml.indexOf('404-body') !== -1 ||
        (pathname.indexOf('/challenge') === 0 && /^not found\.?$/i.test(bodyText.trim())) ||
        /exist or is private|page you requested|该页面不存在|页面不存在或为私密/i.test(bodyText);
    var hasWidget = !!document.querySelector('#challenge-stage,.cf-turnstile,#turnstile-wrapper,iframe[src*="challenges.cloudflare.com"]');
    var hasInteractiveSignal = window.__lindoTurnstileInteractiveBegin === true;
    var hasChallenge = hasWidget ||
        /^(Just a moment|请稍候|Attention Required|Security Check)/i.test(title) ||
        !!document.querySelector('#challenge-running,#challenge-form');
    var hasStageDom = hasWidget ||
        !!document.querySelector('.main-wrapper,.main-content,#challenge-body-text,.zone-name-title,#challenge-error-text') ||
        (document.readyState !== 'loading' && !!document.body && document.body.children.length > 0 && document.body.scrollHeight >= 120);
    if (hasWidget && !isOrigin404Body) {
        return result({challenge:true, widget:true, stageDom:true, interactive:hasInteractiveSignal});
    }
    if (hasChallenge && !isOrigin404Body) {
        return result({challenge:true, widget:false, stageDom:hasStageDom, interactive:hasInteractiveSignal});
    }
    var isCfErrorPage = /^(Something went wrong|Access denied)/i.test(title) ||
        /error code:\s*(1020|1034)\b/i.test(bodyText) ||
        /sorry,\s*you have been blocked/i.test(bodyText);
    if (isCfErrorPage && !isOrigin404Body) {
        return result({ cfErrorPage: true });
    }
    var isOrigin404 = isOrigin404Body || !!document.querySelector('.page-not-found,.discourse-no-results,#main-outlet .not-found-container');
    var metaCsrf = document.querySelector('meta[name="csrf-token"]');
    var csrfVal = (metaCsrf && typeof metaCsrf.content === 'string') ? metaCsrf.content.trim() : '';
    function parsePreloadedUser(preload) {
        if (!preload || !preload.currentUser) return null;
        try {
            return typeof preload.currentUser === 'string'
                ? JSON.parse(preload.currentUser)
                : preload.currentUser;
        } catch (_) { return null; }
    }
    function readBootedDiscourseUser() {
        try {
            var u = window.Discourse && window.Discourse.User && typeof window.Discourse.User.current === 'function'
                ? window.Discourse.User.current()
                : null;
            if (!u || !u.username) return null;
            return {
                id: u.id || 0,
                username: u.username,
                name: u.name || '',
                avatar_template: u.avatar_template || '',
                animated_avatar: u.animated_avatar || '',
                title: u.title || '',
                trust_level: typeof u.trust_level === 'number' ? u.trust_level : 1
            };
        } catch (_) { return null; }
    }
    var preloadObj = null;
    try {
        var node = document.getElementById('data-preloaded');
        var rawPre = node && ((node.textContent || '').trim() || (node.getAttribute && node.getAttribute('data-preloaded')) || (node.dataset && node.dataset.preloaded) || '');
        preloadObj = rawPre ? JSON.parse(rawPre) : null;
        if (preloadObj && (preloadObj.site || preloadObj.currentUser || preloadObj.topic_list || preloadObj.topic_view) && metaCsrf) {
            return result({ ok: true, csrf: csrfVal, user: parsePreloadedUser(preloadObj) });
        }
    } catch (_) {}
    var hasDiscourseShell = !!document.querySelector('header.d-header,#main-outlet .topic-list,#main-outlet #topic-title,#main-outlet .container,#d-splash');
    // Fast-pass as soon as Discourse origin HTML streams meta[name="csrf-token"] + #d-splash / shell
    // (parity with PC bridge.rs: never stall 60s+ downloading 15MB Ember JS bundles over cellular VPN!).
    if (hasDiscourseShell && (csrfVal.length > 0 || (metaCsrf && document.readyState !== 'loading'))) {
        return result({ ok: true, originReached: true, csrf: csrfVal, user: parsePreloadedUser(preloadObj) || readBootedDiscourseUser() });
    }
    if (document.readyState === 'loading') {
        return result({waiting:true});
    }
    var isDiscourseOops = /discussion forum encountered an unexpected problem/i.test(bodyText);
    if (isDiscourseOops && document.readyState === 'complete' && !window.__lindoOopsRetried) {
        window.__lindoOopsRetried = true;
        try {
            var oopsKey = '__lindoOops_' + (location.pathname || '/');
            if (typeof sessionStorage !== 'undefined' && !sessionStorage.getItem(oopsKey) && typeof location.replace === 'function') {
                sessionStorage.setItem(oopsKey, '1');
                location.replace(location.pathname || '/');
                return result({waiting:true});
            }
        } catch (_) {}
    }
    if (isOrigin404 || (pathname.indexOf('/challenge') === 0 && document.readyState === 'complete' && metaCsrf)) {
        return result({ ok: true, originReached: true, csrf: csrfVal, user: readBootedDiscourseUser() });
    }
    var docCt = ((document.contentType || '') + '').toLowerCase();
    var trimmedBody = (bodyText || '').trim();
    if (docCt.indexOf('json') !== -1 || (trimmedBody.length >= 2 && (trimmedBody[0] === '{' || trimmedBody[0] === '['))) {
        try {
            var bodyJson = JSON.parse(trimmedBody);
            if (bodyJson !== null && typeof bodyJson === 'object') {
                return result({ ok: true, originReached: true, csrf: csrfVal });
            }
        } catch (_) {}
    }
    if (window.__lindoRecoveryResult) return result(window.__lindoRecoveryResult);
    if ((!metaCsrf && !isDiscourseOops) || document.readyState !== 'complete' || window.__lindoRecoveryProbeSent) {
        return result({waiting:true});
    }
    window.__lindoRecoveryProbeSent = true;
    var pathMatch = (location.pathname || '').match(/^\/t\/(?:[^/]+\/)?(\d+)/);
    var probeUrl = pathMatch ? ('/t/' + pathMatch[1] + '.json?track_visit=false') : '/latest.json?no_definitions=true';
    (async function() {
        var controller = new AbortController();
        var timer = setTimeout(function() { controller.abort(); }, 12000);
        try {
            var r = await fetch(probeUrl, {credentials:'include',cache:'no-store',signal:controller.signal});
            if (r.headers.get('cf-mitigated') === 'challenge') { window.__lindoRecoveryResult = {blocked:true}; return; }
            if (r.status === 429) { window.__lindoRecoveryResult = {status:429,retryAfter:r.headers.get('Retry-After')||''}; return; }
            if (!r.ok) { window.__lindoRecoveryResult = {error:true}; return; }
            var data = await r.json();
            var validJson = data && ((data.topic_list && Array.isArray(data.topic_list.topics)) || (data.post_stream && Array.isArray(data.post_stream.posts)));
            window.__lindoRecoveryResult = validJson
                ? { ok: true, csrf: csrfVal, user: readBootedDiscourseUser() }
                : { error: true };
        } catch (_) {
            window.__lindoRecoveryResult = {error:true};
        } finally {
            clearTimeout(timer);
        }
    })();
    return result({waiting:true});
})();
""".trimIndent()

/**
 * Single-shot clean-IP `/session/csrf.json` XHR probe for manual `forceRefresh` when `/challenge`
 * reaches Discourse origin 404 without issuing a `cf_clearance` cookie.
 * MUST include `Accept: application/json` and `X-Requested-With: XMLHttpRequest` so Discourse Rails'
 * `check_xhr` filter returns 200 JSON instead of rejecting with 404 HTML.
 */
internal fun forceRefreshCsrfProbeScript(): String = """
(function() {
    if (location.origin !== 'https://linux.do' || document.readyState === 'loading') return JSON.stringify({pending:true});
    if (window.__lindoForceRefreshCsrfResult) return JSON.stringify(window.__lindoForceRefreshCsrfResult);
    if (window.__lindoForceRefreshCsrfRunning) return JSON.stringify({pending:true});
    window.__lindoForceRefreshCsrfRunning = true;
    (async function() {
        var controller = new AbortController();
        var timer = setTimeout(function() { controller.abort(); }, 8000);
        try {
            var r = await fetch('/session/csrf.json', {
                credentials: 'include',
                cache: 'no-store',
                headers: {
                    'Accept': 'application/json',
                    'X-Requested-With': 'XMLHttpRequest'
                },
                signal: controller.signal
            });
            var mitigated = (r.headers.get('cf-mitigated') || '').toLowerCase();
            if (mitigated.indexOf('challenge') !== -1) {
                window.__lindoForceRefreshCsrfResult = { status: r.status, challenged: true, ok: false };
                return;
            }
            if (r.status >= 200 && r.status < 300) {
                var data = await r.json().catch(function() { return null; });
                var csrf = (data && typeof data.csrf === 'string') ? data.csrf : '';
                window.__lindoForceRefreshCsrfResult = { status: r.status, challenged: false, ok: true, csrf: csrf };
                return;
            }
            window.__lindoForceRefreshCsrfResult = { status: r.status, challenged: false, ok: false };
        } catch (_) {
            window.__lindoForceRefreshCsrfResult = { status: 0, challenged: false, ok: false };
        } finally {
            window.__lindoForceRefreshCsrfRunning = false;
            clearTimeout(timer);
        }
    })();
    return JSON.stringify({pending:true});
})();
""".trimIndent()

private fun escapeJsStringLiteral(value: String): String = buildString(value.length + 4) {
    append('"')
    for (ch in value) {
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(ch)
        }
    }
    append('"')
}

/**
 * PC `bridge.rs::confirm_fresh_cf_clearance_via_xhr` parity for Android WebView:
 * Once `CookieManager` observes a genuinely rotated `cf_clearance` (keyed by opaque [candidateKey]),
 * confirms via same-origin XHR `GET /session/csrf.json` that Cloudflare WAF + Discourse Rails accept it,
 * even when `/challenge` stays on the Turnstile DOM or `POST /challenge` returned non-404 status.
 */
internal fun rotatedClearanceXhrProbeScript(candidateKey: String): String {
    val quotedKey = escapeJsStringLiteral(candidateKey)
    return ROTATED_CLEARANCE_XHR_PROBE_TEMPLATE.replace("__LINDO_CANDIDATE_KEY__", quotedKey)
}

private val ROTATED_CLEARANCE_XHR_PROBE_TEMPLATE: String = """
(function() {
    var key = __LINDO_CANDIDATE_KEY__;
    if (typeof location === 'undefined' || !location || location.origin !== 'https://linux.do' || document.readyState === 'loading') {
        return JSON.stringify({ key: key, pending: true });
    }
    var nowMs = (typeof Date !== 'undefined' && Date.now) ? Date.now() : 0;
    var st = window.__lindoRotatedCfProbe;
    if (st && st.key === key) {
        if (st.pending) {
            return JSON.stringify({ key: key, pending: true });
        }
        if (st.status === 0 && (st.zeroRetries || 0) < 2 && (nowMs - (st.finishedAt || 0)) >= 600) {
            // Transient abort during post-Turnstile navigation: allow up to 2 retries on settled document.
        } else {
            return JSON.stringify(st);
        }
    }
    var prevZeroRetries = (st && st.key === key) ? (st.zeroRetries || 0) : 0;
    window.__lindoRotatedCfProbe = { key: key, pending: true, zeroRetries: prevZeroRetries };
    (async function() {
        var controller = new AbortController();
        var timer = setTimeout(function() { controller.abort(); }, 5000);
        try {
            var r = await fetch('/session/csrf.json', {
                credentials: 'include',
                cache: 'no-store',
                headers: {
                    'Accept': 'application/json',
                    'X-Requested-With': 'XMLHttpRequest'
                },
                signal: controller.signal
            });
            var cf = (r.headers.get('cf-mitigated') || '').toLowerCase();
            var challenged = cf.indexOf('challenge') !== -1;
            var ok = r.status >= 200 && r.status < 300 && !challenged;
            var csrf = '';
            if (ok) {
                var data = await r.json().catch(function() { return null; });
                csrf = (data && typeof data.csrf === 'string') ? data.csrf : '';
            }
            window.__lindoRotatedCfProbe = {
                key: key,
                pending: false,
                status: r.status,
                challenged: challenged,
                ok: ok,
                csrf: csrf,
                zeroRetries: prevZeroRetries,
                finishedAt: (typeof Date !== 'undefined' && Date.now) ? Date.now() : 0
            };
        } catch (_) {
            window.__lindoRotatedCfProbe = {
                key: key,
                pending: false,
                status: 0,
                challenged: false,
                ok: false,
                csrf: '',
                zeroRetries: prevZeroRetries + 1,
                finishedAt: (typeof Date !== 'undefined' && Date.now) ? Date.now() : 0
            };
        } finally {
            clearTimeout(timer);
        }
    })();
    return JSON.stringify({ key: key, pending: true });
})();
""".trimIndent()
