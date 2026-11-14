package do_.linux.lindo.core.network

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import do_.linux.lindo.core.compose.LocalComposerDrafts
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Concurrent requests stay concurrent. Credential replacement waits for in-flight work to finish. */
internal object AccountSessionGate {
    private class Lease : AbstractCoroutineContextElement(Key) { companion object Key : CoroutineContext.Key<Lease> }
    private val state = Mutex()
    private val writers = Mutex()
    private var barrier: CompletableDeferred<Unit>? = null
    private var drained: CompletableDeferred<Unit>? = null
    private var readers = 0
    suspend fun <T> read(block: suspend () -> T): T {
        if (currentCoroutineContext()[Lease] != null) return block()
        if (SavedAccounts.blocked) throw CancellationException("Account transport needs recovery")
        val generation = UserApiKeyAuthManager.sessionGeneration
        while (true) {
            val wait = state.withLock { barrier.also { if (it == null) readers++ } }
            if (wait == null) break
            wait.await()
            if (SavedAccounts.blocked) throw CancellationException("Account transport needs recovery")
            if (generation != UserApiKeyAuthManager.sessionGeneration) throw CancellationException("Account changed")
        }
        try {
            return withContext(Lease()) {
                val result = block()
                if (generation != UserApiKeyAuthManager.sessionGeneration) throw CancellationException("Account changed")
                result
            }
        } finally {
            withContext(NonCancellable) { state.withLock { readers--; if (readers == 0) drained?.complete(Unit) } }
        }
    }
    suspend fun <T> replace(block: suspend () -> T): T = writers.withLock {
        val wait = state.withLock {
            barrier = CompletableDeferred()
            drained = CompletableDeferred<Unit>().also { if (readers == 0) it.complete(Unit) }
            drained!!
        }
        try { withTimeout(30_000L) { wait.await() }; withContext(NonCancellable + Lease()) { block() } }
        finally { withContext(NonCancellable) { state.withLock { barrier?.complete(Unit); barrier = null; drained = null } } }
    }
}

internal data class SavedAccountSummary(val profile: AuthenticatedUserProfile, val needsLogin: Boolean)

/** Credential payloads never enter the UI model or logs. Platform storage protects this blob. */
internal object SavedAccounts {
    @Serializable private data class Session(
        val profile: AuthenticatedUserProfile,
        val cookies: String = "",
        val apiKey: String? = null,
        val readTopics: String = "",
        val drafts: String? = null,
        val needsLogin: Boolean = false
    )
    @Serializable private data class Store(val sessions: List<Session>, val pendingSwitch: Boolean = false, val pendingUsername: String? = null)
    private var pendingSwitch = false
    private var pendingUsername: String? = null
    private val json = Json { ignoreUnknownKeys = true }
    private val sessions = linkedMapOf<String, Session>()
    private var persist: ((String) -> Unit)? = null
    private var enabled = false
    private var applying = false
    private var returnTo: String? = null
    private val operation = Mutex()
    @Volatile var blocked = false; private set
    var accounts by mutableStateOf<List<SavedAccountSummary>>(emptyList()); private set
    var busy by mutableStateOf(false); private set
    var adding by mutableStateOf(false); private set
    var refreshBrowserCookies: (suspend () -> Unit)? = null
    var replaceBrowserCookies: (suspend (String) -> Unit)? = null

    private fun key(profile: AuthenticatedUserProfile) = profile.username.lowercase()
    fun configure(encoded: String?, save: (String) -> Unit) {
        synchronized(this) {
            sessions.clear()
            val stored = runCatching { json.decodeFromString<Store>(encoded.orEmpty()) }.getOrDefault(Store(emptyList()))
            pendingSwitch = stored.pendingSwitch
            pendingUsername = stored.pendingUsername
            stored.sessions.forEach {
                if (it.profile.username.isNotBlank() && it.profile.username != "linuxdo_user") sessions[key(it.profile)] = it
            }
            enabled = true; persist = save; publish()
        }
        if (pendingSwitch) busy = true else captureCurrent()
    }
    suspend fun recoverPendingSwitch() {
        if (!pendingSwitch) return
        change {
            val target = synchronized(this) { pendingUsername?.let(sessions::get) }
            try { activate(target) }
            catch (_: Exception) { /* activate restores the prior session, or blocks transport if recovery fails. */ }
        }
    }
    @Synchronized private fun publish() {
        accounts = sessions.values.map { SavedAccountSummary(it.profile, it.needsLogin) }
        persist?.invoke(json.encodeToString(Store(sessions.values.toList(), pendingSwitch, pendingUsername)))
    }
    @Synchronized fun captureCurrent() {
        if (!enabled || applying || blocked) return
        val user = UserApiKeyAuthManager.currentUser ?: return
        if (user.username.isBlank() || user.username == "linuxdo_user") return
        val cookies = CookieSentinel.resolveWinningCookies(targetHost = "linux.do")
            .filterKeys { it in setOf("_t", "_forum_session") }
            .entries.joinToString("; ") { "${it.key}=${it.value.value}" }
        val apiKey = UserApiKeyAuthManager.getApiKey()
        if (!UserApiKeyAuthManager.hasValidWebSessionCookie() && apiKey.isNullOrBlank()) return
        val value = Session(user, cookies, apiKey, LinuxDoRepository.exportReadTopics(), LocalComposerDrafts.exportForAccount(user.username))
        if (sessions[key(user)] != value) { sessions[key(user)] = value; publish() }
        if (adding) { adding = false; returnTo = null }
    }
    @Synchronized fun forget(username: String) {
        if (!enabled) return
        sessions.remove(username.lowercase()); publish()
    }
    @Synchronized fun markExpired(user: AuthenticatedUserProfile?) {
        if (!enabled || user == null || applying) return
        val id = key(user)
        sessions[id]?.let { sessions[id] = it.copy(cookies = "", apiKey = null, needsLogin = true); publish() }
    }
    suspend fun switchTo(username: String): Boolean = change {
        val target = synchronized(this) { sessions[username.lowercase()] } ?: return@change false
        if (target.needsLogin) return@change false
        if (UserApiKeyAuthManager.currentUser?.username.equals(username, true) && !adding) return@change true
        refreshBrowserCookies?.invoke()
        captureCurrent()
        activate(target)
        adding = false; returnTo = null
        true
    }
    suspend fun beginAdd() = change {
        refreshBrowserCookies?.invoke()
        captureCurrent()
        returnTo = UserApiKeyAuthManager.currentUser?.username
        activate(null)
        adding = true
        true
    }
    suspend fun cancelAdd() = change {
        if (!adding) return@change true
        val previous = synchronized(this) { returnTo?.lowercase()?.let(sessions::get) }
        activate(previous)
        adding = false; returnTo = null
        true
    }
    private suspend fun <T> change(block: suspend () -> T): T = operation.withLock {
        busy = true
        try { AccountSessionGate.replace(block) } finally { busy = false }
    }
    private suspend fun activate(target: Session?) {
        val previous = UserApiKeyAuthManager.currentUser?.let { synchronized(this) { sessions[key(it)] } }
        applying = true
        try {
            pendingSwitch = true
            pendingUsername = target?.profile?.let(::key)
            publish()
            try {
                CookieSentinel.clearAuthCookiesOnly()
                target?.cookies?.takeIf { it.isNotBlank() }?.let { CookieSentinel.ingestRawCookieString(it, domain = "linux.do", hostOnly = true) }
                replaceBrowserCookies?.invoke(target?.cookies.orEmpty())
                UserApiKeyAuthManager.replaceActiveSession(target?.profile, target?.apiKey, target?.cookies,
                    target?.readTopics, target?.drafts)
            } catch (failure: Exception) {
                try {
                    CookieSentinel.clearAuthCookiesOnly()
                    previous?.cookies?.takeIf { it.isNotBlank() }?.let { CookieSentinel.ingestRawCookieString(it, domain = "linux.do", hostOnly = true) }
                    replaceBrowserCookies?.invoke(previous?.cookies.orEmpty())
                    UserApiKeyAuthManager.replaceActiveSession(previous?.profile, previous?.apiKey, previous?.cookies,
                        previous?.readTopics, previous?.drafts)
                    pendingSwitch = false; pendingUsername = null; publish()
                } catch (_: Exception) {
                    blocked = true
                    UserApiKeyAuthManager.replaceActiveSession(null, null, null, null, null)
                }
                throw failure
            }
        } finally { applying = false }
        blocked = false
        pendingSwitch = false; pendingUsername = null
        publish()
    }
    internal fun resetForTest() {
        sessions.clear(); persist = null; enabled = false; applying = false; adding = false; busy = false
        returnTo = null; blocked = false; pendingSwitch = false; pendingUsername = null; accounts = emptyList(); replaceBrowserCookies = null; refreshBrowserCookies = null
    }
}
