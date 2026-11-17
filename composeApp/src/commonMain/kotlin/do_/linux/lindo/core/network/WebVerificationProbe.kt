package do_.linux.lindo.core.network

/**
 * Observe site-owned verification passively from the live Discourse document.
 * Never click widgets, never reload, and never fire competing XHR probes (/session/csrf.json)
 * that can hit rate-limits right after Turnstile finishes.
 */
internal fun webVerificationProbeScript(): String = """
(function() {
    function result(value) { return JSON.stringify(value); }
    if (location.origin !== 'https://linux.do' || document.readyState !== 'complete') {
        return result({waiting:true});
    }
    var title = (document.title || '').trim();
    if (/^(Just a moment|请稍候|Attention Required|Security Check)/i.test(title)) {
        return result({challenge:true});
    }
    if (document.querySelector('#challenge-running,#challenge-stage,#challenge-form,.cf-turnstile,#turnstile-wrapper,iframe[src*="challenges.cloudflare.com"],script[src*="challenge-platform"]')) {
        return result({challenge:true});
    }
    var metaCsrf = document.querySelector('meta[name="csrf-token"]');
    var preloadedEl = document.getElementById('data-preloaded');
    if (!metaCsrf || !metaCsrf.content || !preloadedEl) {
        return result({waiting:true});
    }
    var userObj = null;
    try {
        var rawPreloaded = preloadedEl.getAttribute('data-preloaded') || preloadedEl.textContent || '';
        if (rawPreloaded) {
            var outer = JSON.parse(rawPreloaded);
            if (outer && typeof outer.currentUser === 'string' && outer.currentUser.length > 2) {
                userObj = JSON.parse(outer.currentUser);
            } else if (outer && outer.currentUser && typeof outer.currentUser === 'object') {
                userObj = outer.currentUser;
            }
        }
    } catch (_) {}
    return result({
        ok: true,
        csrf: metaCsrf.content,
        user: userObj
    });
})();
""".trimIndent()
