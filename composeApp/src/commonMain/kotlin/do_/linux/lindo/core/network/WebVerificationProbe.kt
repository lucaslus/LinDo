package do_.linux.lindo.core.network

/** Observe site-owned verification. Never click widgets or navigate to JSON challenge endpoints. */
internal fun webVerificationProbeScript(): String = """
(function() {
    function result(value) { return JSON.stringify(value); }
    if (location.origin !== 'https://linux.do' || document.readyState === 'loading') return result({waiting:true});
    if (document.querySelector('#challenge-running,#challenge-stage,#challenge-form')) return result({challenge:true});
    try {
        var node = document.getElementById('data-preloaded');
        var preload = node && JSON.parse((node.textContent || '').trim() || (node.dataset && node.dataset.preloaded) || '{}');
        if (preload && (preload.site || preload.currentUser || preload.topic_list) && document.querySelector('meta[name="csrf-token"]')) {
            return result({ok:true});
        }
    } catch (_) {}
    try {
        var data = JSON.parse(document.body.innerText);
        if (data.topic_list && Array.isArray(data.topic_list.topics)) return result({ok:true});
    } catch (_) {}
    if (window.__lindoRecoveryResult) return result(window.__lindoRecoveryResult);
    if (!document.querySelector('meta[name="csrf-token"]') || document.readyState !== 'complete' || window.__lindoRecoveryProbeSent) return result({waiting:true});
    window.__lindoRecoveryProbeSent = true;
    (async function() {
        var controller = new AbortController();
        var timer = setTimeout(function() { controller.abort(); }, 12000);
        try {
            var r = await fetch('/latest.json', {credentials:'include',cache:'no-store',signal:controller.signal});
            if (r.headers.get('cf-mitigated') === 'challenge') { window.__lindoRecoveryResult={blocked:true}; return; }
            if (r.status === 429) { window.__lindoRecoveryResult={status:429,retryAfter:r.headers.get('Retry-After')||''}; return; }
            if (!r.ok) { window.__lindoRecoveryResult={error:true}; return; }
            var data = await r.json();
            window.__lindoRecoveryResult=data.topic_list && Array.isArray(data.topic_list.topics) ? {ok:true} : {error:true};
        } catch (_) { window.__lindoRecoveryResult={error:true}; }
        finally { clearTimeout(timer); }
    })();
    return result({waiting:true});
})();
""".trimIndent()
