package do_.linux.lindo.core.network

/** Browser cookies win. Only missing account cookies may be restored from encrypted storage. */
internal fun sessionCookiesForRestore(browserCookies: String, savedCookies: String): String {
    fun pairs(raw: String) = raw.split(';').mapNotNull {
        val pair = it.trim(); val split = pair.indexOf('=')
        if (split <= 0) null else pair.substring(0, split) to pair.substring(split + 1)
    }.toMap()
    val live = pairs(browserCookies).toMutableMap()
    if (live["_t"].isNullOrBlank() || live["_t"] == "demo_session_token") {
        val saved = pairs(savedCookies)
        val token = saved["_t"]?.takeIf { it.isNotBlank() && it != "demo_session_token" }
        if (token != null) {
            live["_t"] = token
            saved["_forum_session"]?.takeIf { it.isNotBlank() }?.let { live["_forum_session"] = it }
        }
    }
    return live.entries.joinToString("; ") { "${it.key}=${it.value}" }
}
