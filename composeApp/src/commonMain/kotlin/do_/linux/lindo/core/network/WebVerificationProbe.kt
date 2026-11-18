package do_.linux.lindo.core.network

/**
 * Observe site-owned verification passively from the live Discourse document.
 * Never click widgets, never reload, and avoid unnecessary XHR probes once either
 * `#data-preloaded` or booted Discourse chrome (`header.d-header` / `#main-outlet`) is live.
 */
internal fun webVerificationProbeScript(): String = """
(function() {
    function result(value) { return JSON.stringify(value); }
    if (location.origin !== 'https://linux.do' || document.readyState === 'loading') {
        return result({waiting:true});
    }
    var title = (document.title || '').trim();
    var bodyText = (document.body && document.body.innerText) || '';
    var lowerHtml = (((document.documentElement && document.documentElement.innerHTML) || '') + ' ' + title).toLowerCase();
    var isOrigin404 = !!document.querySelector('.page-not-found,.discourse-no-results,#main-outlet .not-found-container') ||
        lowerHtml.indexOf('page-not-found') !== -1 ||
        lowerHtml.indexOf('"errortype":"notfound"') !== -1 ||
        lowerHtml.indexOf('404-body') !== -1 ||
        /exist or is private|page you requested|该页面不存在|页面不存在或为私密/i.test(bodyText);
    if (!isOrigin404) {
        if (/^(Just a moment|请稍候|Attention Required|Security Check)/i.test(title)) {
            return result({challenge:true});
        }
        if (document.querySelector('#challenge-running,#challenge-stage,#challenge-form,.cf-turnstile,#turnstile-wrapper,iframe[src*="challenges.cloudflare.com"]')) {
            return result({challenge:true});
        }
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
    var metaCsrf = document.querySelector('meta[name="csrf-token"]');
    var csrfVal = (metaCsrf && typeof metaCsrf.content === 'string') ? metaCsrf.content : '';
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
    try {
        var node = document.getElementById('data-preloaded');
        var rawPre = node && ((node.textContent || '').trim() || (node.getAttribute && node.getAttribute('data-preloaded')) || (node.dataset && node.dataset.preloaded) || '');
        var preload = rawPre ? JSON.parse(rawPre) : null;
        if (preload && (preload.site || preload.currentUser || preload.topic_list || preload.topic_view) && metaCsrf) {
            return result({ ok: true, csrf: csrfVal, user: parsePreloadedUser(preload) });
        }
    } catch (_) {}
    var pathname = location.pathname || '/';
    if (isOrigin404 || (pathname.indexOf('/challenge') === 0 && document.readyState === 'complete' && metaCsrf)) {
        return result({ ok: true, originReached: true, csrf: csrfVal, user: readBootedDiscourseUser() });
    }
    try {
        if (metaCsrf && document.querySelector('header.d-header,#main-outlet .topic-list,#main-outlet #topic-title,#main-outlet .container')) {
            return result({ ok: true, csrf: csrfVal, user: readBootedDiscourseUser() });
        }
    } catch (_) {}
    try {
        var bodyJson = JSON.parse(bodyText);
        if (bodyJson && ((bodyJson.topic_list && Array.isArray(bodyJson.topic_list.topics)) || (bodyJson.post_stream && Array.isArray(bodyJson.post_stream.posts)))) {
            return result({ ok: true, csrf: csrfVal });
        }
    } catch (_) {}
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
