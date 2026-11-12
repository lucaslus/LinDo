package do_.linux.lindo.core.compose

import do_.linux.lindo.core.network.ApiFailureKind
import do_.linux.lindo.core.network.ApiReadException
import do_.linux.lindo.core.network.CloudDraftItem
import do_.linux.lindo.core.network.LinuxDoRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class ComposerAction { NEW_TOPIC, REPLY, EDIT }

@Serializable
data class ComposerDraft(
    val owner: String,
    val key: String,
    val action: ComposerAction,
    val raw: String = "",
    val title: String = "",
    val categoryId: Int? = null,
    val tags: List<String> = emptyList(),
    val topicId: Int? = null,
    val postId: Int? = null,
    val postNumber: Int? = null,
    val replyToPostNumber: Int? = null,
    val replyToUsername: String? = null,
    val sequence: Int? = null,
    val updatedAt: Long = System.currentTimeMillis(),
    val recipients: String = ""
) {
    val hasContent: Boolean
        get() = raw.isNotBlank() ||
            (action == ComposerAction.NEW_TOPIC && (title.isNotBlank() || recipients.isNotBlank()))

    fun sameContent(other: ComposerDraft) = copy(sequence = null, updatedAt = 0) == other.copy(sequence = null, updatedAt = 0)
}

data class RemoteDraftHydration(
    val conflict: CloudDraftItem? = null
)

/** Account-scoped local recovery. Android persists this encrypted, outside the backup scope. */
object LocalComposerDrafts {
    private val json = Json { ignoreUnknownKeys = true }
    private val drafts = java.util.concurrent.ConcurrentHashMap<String, ComposerDraft>()
    private val queues = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicReference<CompletableDeferred<Unit>>>()
    private val sequences = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val persistLock = Any()
    private var persistJob: Job? = null
    @Volatile
    private var dirtyPersist = false
    var onPersist: ((String) -> Unit)? = null
    private fun id(owner: String, key: String) = "${owner.lowercase()}\u0000$key"
    fun restore(encoded: String?) {
        if (encoded.isNullOrBlank()) return
        runCatching { json.decodeFromString<List<ComposerDraft>>(encoded) }.getOrNull()?.forEach {
            drafts[id(it.owner, it.key)] = it
        }
    }
    fun get(owner: String, key: String): ComposerDraft? = drafts[id(owner, key)]
    fun all(owner: String): List<ComposerDraft> = drafts.values.filter { it.owner.equals(owner, true) }.sortedByDescending { it.updatedAt }
    fun save(draft: ComposerDraft) {
        if (draft.owner.isBlank()) return
        drafts.compute(id(draft.owner, draft.key)) { _, existing -> if (existing != null && existing.updatedAt > draft.updatedAt) existing else draft }
        schedulePersist(immediate = false)
    }
    internal fun queue(owner: String, key: String) = queues.computeIfAbsent(id(owner, key)) { java.util.concurrent.atomic.AtomicReference(CompletableDeferred(Unit)) }
    internal fun sequence(owner: String, key: String): Int? = sequences[id(owner, key)]
    internal fun recordSequence(owner: String, key: String, value: Int) { sequences[id(owner, key)] = value }
    fun forgetSequence(owner: String, key: String) { sequences.remove(id(owner, key)) }
    fun remove(owner: String, key: String) { drafts.remove(id(owner, key)); schedulePersist(immediate = true) }
    fun flushPendingPersist() {
        val shouldWrite = synchronized(persistLock) {
            persistJob?.cancel()
            persistJob = null
            val wasDirty = dirtyPersist
            dirtyPersist = false
            wasDirty
        }
        if (shouldWrite) {
            onPersist?.invoke(json.encodeToString(drafts.values.toList()))
        }
    }
    private fun schedulePersist(immediate: Boolean) {
        if (onPersist == null) return
        if (immediate) {
            synchronized(persistLock) {
                persistJob?.cancel()
                persistJob = null
                dirtyPersist = false
            }
            val snapshot = drafts.values.toList()
            persistScope.launch {
                onPersist?.invoke(json.encodeToString(snapshot))
            }
            return
        }
        synchronized(persistLock) {
            dirtyPersist = true
            persistJob?.cancel()
            persistJob = persistScope.launch {
                delay(400L)
                synchronized(persistLock) {
                    dirtyPersist = false
                    persistJob = null
                }
                onPersist?.invoke(json.encodeToString(drafts.values.toList()))
            }
        }
    }
}

/** One transient writer per editor session. Sync, publish, and deletion share the draft sequence. */
class ComposerDraftSession(
    initial: ComposerDraft,
    initialExistsOnServer: Boolean = initial.sequence != null
) {
    // A text change can cancel a debounce while a write is in flight; serialization protects the
    // sequence and ensures publication cannot race an older auto-save that would recreate a draft.
    private val pending = LocalComposerDrafts.queue(initial.owner, initial.key)
    private suspend fun <T> serialWrite(block: suspend () -> T): T {
        val done = CompletableDeferred<Unit>()
        val previous = pending.getAndSet(done)
        // Once a mutation starts, retain its response even if the UI's debounce is cancelled.
        return withContext(NonCancellable) {
            previous.await()
            try { block() } finally { done.complete(Unit) }
        }
    }
    var current: ComposerDraft = initial
        private set
    var savedSequence: Int? = initial.sequence
        private set
    var existsOnServer: Boolean = initialExistsOnServer
        internal set
    private var synced: ComposerDraft? = null
    private var completed = false

    private fun applyRemoteItem(remote: CloudDraftItem): ComposerDraft {
        val cleanRemoteTitle = remote.title.takeUnless {
            it.startsWith("未命名") || it.startsWith("回复话题 #") || it.startsWith("私信 @")
        }.orEmpty()
        val hydrated = current.copy(
            raw = remote.raw,
            title = if (current.action == ComposerAction.NEW_TOPIC) cleanRemoteTitle else current.title,
            categoryId = remote.categoryId ?: current.categoryId,
            tags = if (remote.tags.isNotEmpty() || current.action == ComposerAction.NEW_TOPIC) remote.tags else current.tags,
            topicId = remote.topicId.takeIf { it > 0 } ?: current.topicId,
            postId = remote.postId ?: current.postId,
            replyToPostNumber = remote.replyToPostNumber ?: current.replyToPostNumber,
            recipients = remote.recipients.ifBlank { current.recipients },
            sequence = remote.sequence,
            updatedAt = System.currentTimeMillis()
        )
        existsOnServer = true
        savedSequence = remote.sequence
        current = hydrated
        synced = hydrated
        LocalComposerDrafts.recordSequence(hydrated.owner, hydrated.key, remote.sequence)
        LocalComposerDrafts.save(hydrated)
        return hydrated
    }

    fun acceptRemoteDraft(remote: CloudDraftItem): ComposerDraft = applyRemoteItem(remote)

    fun keepLocalOverRemote(remoteSequence: Int): ComposerDraft {
        existsOnServer = true
        savedSequence = remoteSequence
        current = current.copy(sequence = remoteSequence, updatedAt = System.currentTimeMillis())
        synced = null
        LocalComposerDrafts.recordSequence(current.owner, current.key, remoteSequence)
        LocalComposerDrafts.save(current)
        return current
    }

    /**
     * Queries `GET /drafts/{key}.json` on composer open to hydrate Web drafts or surface conflicts:
     * - Case 1 (`!hasLocalEdits && remote.draft != null`): hydrates `current` directly from `remote.draft`.
     * - Case 2 (`hasLocalEdits && remote.draft != null` and content differs): keeps local `current` untouched
     *   without stamping `remote.sequence` onto `LocalComposerDrafts` yet, and returns `remote.draft` for conflict UI.
     * - Case 3 (`remote.draft == null`): sets `existsOnServer = false` and records `savedSequence = remote.sequence`.
     */
    suspend fun prepareWithRemote(untouchedBaselineRaw: String? = null): CloudDraftItem? {
        if (completed || current.owner.isBlank()) return null
        val remote = try {
            LinuxDoRepository.fetchCloudDraftByKey(current.key)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            return null
        }
        val remoteDraft = remote.draft
        if (remoteDraft == null) {
            existsOnServer = false
            savedSequence = remote.sequence
            LocalComposerDrafts.recordSequence(current.owner, current.key, remote.sequence)
            if (current.hasContent) {
                current = current.copy(sequence = remote.sequence)
                LocalComposerDrafts.save(current)
            }
            return null
        }

        val hasLocalEdits = if (untouchedBaselineRaw != null) {
            current.raw != untouchedBaselineRaw
        } else {
            current.hasContent
        }
        if (!hasLocalEdits) {
            applyRemoteItem(remoteDraft.copy(sequence = remote.sequence, sequenceKnown = true))
            return null
        }

        val cleanRemoteTitle = remoteDraft.title.takeUnless {
            it.startsWith("未命名") || it.startsWith("回复话题 #") || it.startsWith("私信 @")
        }.orEmpty()
        val isPrivateMessageDraft = current.key.startsWith("private_message") || current.key.startsWith("new_private_message")
        val contentDiffers = remoteDraft.raw.trim() != current.raw.trim() ||
            (current.action == ComposerAction.NEW_TOPIC && cleanRemoteTitle.trim() != current.title.trim()) ||
            (isPrivateMessageDraft && remoteDraft.recipients.trim() != current.recipients.trim())

        existsOnServer = true
        if (contentDiffers) {
            // Do NOT overwrite current and do NOT stamp remote.sequence onto LocalComposerDrafts yet.
            return remoteDraft.copy(sequence = remote.sequence, sequenceKnown = true)
        }

        savedSequence = remote.sequence
        current = current.copy(sequence = remote.sequence)
        synced = current
        LocalComposerDrafts.recordSequence(current.owner, current.key, remote.sequence)
        LocalComposerDrafts.save(current)
        return null
    }

    suspend fun hydrateFromServer(untouchedBaselineRaw: String? = null): RemoteDraftHydration =
        RemoteDraftHydration(conflict = prepareWithRemote(untouchedBaselineRaw))

    fun update(value: ComposerDraft) {
        if (completed) return
        current = value.copy(sequence = savedSequence)
        LocalComposerDrafts.save(current)
    }
    fun isSynced() = synced?.sameContent(current) == true

    fun syncInBackground() {
        if (completed || !current.hasContent) return
        LocalComposerDrafts.save(current)
        LinuxDoRepository.launchDetachedDraftOp {
            runCatching { sync() }
        }
    }

    suspend fun sync(): Boolean = serialWrite {
        if (completed || !current.hasContent || isSynced()) return@serialWrite true
        val snapshot = current
        val sequence = LocalComposerDrafts.sequence(snapshot.owner, snapshot.key) ?: savedSequence
            ?: LinuxDoRepository.fetchDraftSequence(snapshot.key).also { savedSequence = it }
        val wireAction = when {
            snapshot.key.startsWith("private_message") || snapshot.key.startsWith("new_private_message") -> "privateMessage"
            snapshot.action == ComposerAction.NEW_TOPIC -> "createTopic"
            snapshot.action == ComposerAction.REPLY -> "reply"
            snapshot.action == ComposerAction.EDIT -> "edit"
            else -> "reply"
        }
        val next = LinuxDoRepository.saveCloudDraft(
            draftKey = snapshot.key,
            sequence = sequence,
            replyRaw = snapshot.raw,
            title = snapshot.title,
            categoryId = snapshot.categoryId,
            tags = snapshot.tags,
            replyToPostNumber = snapshot.replyToPostNumber,
            action = wireAction,
            topicId = snapshot.topicId,
            postId = snapshot.postId,
            recipients = snapshot.recipients
        ) ?: return@serialWrite false
        existsOnServer = true
        savedSequence = next
        LocalComposerDrafts.recordSequence(snapshot.owner, snapshot.key, next)
        current = current.copy(sequence = next)
        LocalComposerDrafts.save(current)
        synced = snapshot
        true
    }

    suspend fun <T> publish(block: suspend () -> T?): T? = serialWrite {
        if (completed) return@serialWrite null
        val cleanupSequence = LocalComposerDrafts.sequence(current.owner, current.key) ?: savedSequence
            ?: if (existsOnServer) runCatching { LinuxDoRepository.fetchDraftSequence(current.key) }.getOrNull() else null
        val result = block() ?: return@serialWrite null
        val targetOwner = current.owner
        val targetKey = current.key
        val shouldDeleteCloud = existsOnServer || cleanupSequence != null || synced != null
        completed = true
        existsOnServer = false
        LocalComposerDrafts.remove(targetOwner, targetKey)
        LocalComposerDrafts.forgetSequence(targetOwner, targetKey)
        // Publishing is already successful; run cloud draft cleanup asynchronously on the repository's
        // detached scope so ComposerSheet dismissal is never blocked or cancelled on disposal, while
        // keeping the per-key serialization queue ordered.
        if (shouldDeleteCloud) {
            val cleanupDone = CompletableDeferred<Unit>()
            val afterPublish = pending.getAndSet(cleanupDone)
            LinuxDoRepository.launchDetachedDraftOp {
                try {
                    withContext(NonCancellable) {
                        afterPublish.await()
                        val seq = cleanupSequence ?: runCatching { LinuxDoRepository.fetchDraftSequence(targetKey) }.getOrNull()
                        if (seq != null) {
                            runCatching { LinuxDoRepository.deleteCloudDraft(targetKey, seq) }
                        }
                    }
                } finally {
                    cleanupDone.complete(Unit)
                }
            }
        }
        result
    }

    suspend fun discard(): Boolean = serialWrite {
        val targetOwner = current.owner
        val targetKey = current.key
        if (!existsOnServer && synced == null) {
            LocalComposerDrafts.remove(targetOwner, targetKey)
            completed = true
            LocalComposerDrafts.forgetSequence(targetOwner, targetKey)
            return@serialWrite true
        }
        val knownSequence = LocalComposerDrafts.sequence(targetOwner, targetKey) ?: savedSequence
        val cloudRemoved = try {
            val sequence = knownSequence ?: LinuxDoRepository.fetchDraftSequence(targetKey)
            LinuxDoRepository.deleteCloudDraft(targetKey, sequence)
        } catch (error: ApiReadException) {
            when (error.kind) {
                ApiFailureKind.Verification,
                ApiFailureKind.Authentication -> throw error
                ApiFailureKind.Connection,
                ApiFailureKind.RateLimited,
                ApiFailureKind.Server -> {
                    LinuxDoRepository.launchDetachedDraftOp {
                        val seq = knownSequence ?: runCatching { LinuxDoRepository.fetchDraftSequence(targetKey) }.getOrNull()
                        if (seq != null) {
                            runCatching { LinuxDoRepository.deleteCloudDraft(targetKey, seq) }
                        }
                    }
                    true
                }
                else -> true
            }
        }
        if (!cloudRemoved) return@serialWrite false
        LocalComposerDrafts.remove(targetOwner, targetKey)
        existsOnServer = false
        completed = true
        LocalComposerDrafts.forgetSequence(targetOwner, targetKey)
        true
    }
}

fun ComposerDraft.toCloudItem(): CloudDraftItem {
    val isPm = key.startsWith("private_message") || key.startsWith("new_private_message")
    val resolvedAction = when {
        isPm -> "privateMessage"
        action == ComposerAction.NEW_TOPIC -> "createTopic"
        action == ComposerAction.REPLY -> "reply"
        action == ComposerAction.EDIT -> "edit"
        else -> "reply"
    }
    return CloudDraftItem(
        draftKey = key,
        sequence = sequence ?: 0,
        title = title.ifBlank {
            when {
                isPm -> if (recipients.isNotBlank()) "私信 @$recipients" else "未命名私信草稿"
                action == ComposerAction.NEW_TOPIC -> "未命名话题草稿"
                action == ComposerAction.REPLY -> "话题回复草稿"
                else -> postNumber?.takeIf { it > 1 }?.let { "${it} 楼 · 编辑草稿" } ?: "帖子编辑草稿"
            }
        },
        excerpt = raw,
        topicId = topicId ?: 0,
        createdAtText = "本机草稿",
        raw = raw,
        categoryId = categoryId,
        tags = tags,
        replyToPostNumber = replyToPostNumber,
        action = resolvedAction,
        postId = postId,
        sequenceKnown = sequence != null,
        recipients = recipients
    )
}
