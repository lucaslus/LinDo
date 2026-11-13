package do_.linux.lindo.core.network

import kotlinx.serialization.json.JsonPrimitive

/** Synchronous WebView result polling; asynchronous fetch results are kept in the page.
 * Never runs on login/challenge pages. At most two requests per document/session.
 */
internal fun webLoginSessionProbeScript(sessionOwner: String): String = """
(function() {
    if (location.origin !== 'https://linux.do') return JSON.stringify({state:'idle'});
    var path = location.pathname || '';
    if (/^\/(login|user-api-key|session\/otp|session\/sso|auth)(\/|$)/.test(path))
        return JSON.stringify({state:'login'});
    if (/Just a moment|请稍候|Attention Required/i.test(document.title || '') ||
        document.querySelector('#challenge-running, #challenge-stage, .cf-turnstile, iframe[src*="challenges.cloudflare.com"]'))
        return JSON.stringify({state:'challenge'});
    if (document.readyState !== 'complete') return JSON.stringify({state:'idle'});
    var owner = ${JsonPrimitive(sessionOwner)};
    var csrfMeta = document.querySelector('meta[name="csrf-token"]');
    var csrf = csrfMeta ? csrfMeta.content : '';
    function valid(u) { return u && Number(u.id) > 0 && typeof u.username === 'string' && u.username.trim() && u.username !== 'linuxdo_user'; }
    function ready(u) { return JSON.stringify({state:'ready', user:u, csrf:csrf}); }
    // Each source is isolated: an unavailable legacy container must not prevent fallback.
    try {
        var el = document.getElementById('data-preloaded');
        var preload = el && el.dataset && JSON.parse(el.dataset.preloaded || '{}');
        var user = preload && preload.currentUser;
        if (typeof user === 'string') user = JSON.parse(user);
        if (valid(user)) return ready(user);
    } catch (_) {}
    try {
        var container = window.Discourse && window.Discourse.__container__;
        var user = container && container.lookup('service:current-user');
        if (!user && window.Discourse && window.Discourse.User) user = window.Discourse.User.current();
        if (valid(user)) return ready({id:user.id, username:user.username, name:user.name,
            avatar_template:user.avatar_template, animated_avatar:user.animated_avatar, title:user.title, trust_level:user.trust_level});
    } catch (_) {}
    var probe = window.__lindoLoginSessionProbe;
    if (!probe || probe.owner !== owner) {
        probe = {owner:owner, state:'idle', attempts:0, nextAt:0};
        window.__lindoLoginSessionProbe = probe;
    }
    if (valid(probe.user)) return ready(probe.user);
    if (probe.state === 'guest' || probe.state === 'challenge' || probe.state === 'error') return JSON.stringify(probe);
    if (!csrf) return JSON.stringify({state:'idle'});
    if (!probe.inFlight && Date.now() >= probe.nextAt && probe.attempts < 2) {
        probe.inFlight = true;
        probe.state = 'waiting';
        probe.attempts++;
        var controller = new AbortController();
        var timeout = setTimeout(function() { controller.abort(); }, 10000);
        fetch('/session/current.json', {credentials:'include', cache:'no-store', signal:controller.signal,
            headers:{'Accept':'application/json','X-Requested-With':'XMLHttpRequest'}})
        .then(async function(response) {
            if (response.headers.get('cf-mitigated') === 'challenge') { probe.state='challenge'; return; }
            if (response.status === 429) {
                var retry = response.headers.get('Retry-After') || '';
                var wait = retry.trim() && isFinite(Number(retry)) ? Number(retry)*1000 : Date.parse(retry)-Date.now();
                if (!isFinite(wait) || wait <= 0) wait=2000;
                probe.retryAfter=retry || '2'; probe.rateLimitAttempt=probe.attempts;
                probe.nextAt=Date.now()+wait;
                probe.state=probe.attempts < 2 && wait <= 10000 ? 'waiting' : 'error';
                return;
            }
            if (response.status === 401) { probe.state='guest'; return; }
            if (!response.ok) throw new Error('session HTTP failure');
            var data=await response.json();
            if (valid(data.current_user)) { probe.user=data.current_user; probe.state='ready'; }
            else probe.state='guest';
        }).catch(function() {
            probe.nextAt=Date.now()+2000;
            probe.state=probe.attempts < 2 ? 'waiting' : 'error';
        }).finally(function() { clearTimeout(timeout); probe.inFlight=false; });
    }
    return JSON.stringify(probe);
})();
""".trimIndent()
