package do_.linux.lindo.core.compose

import do_.linux.lindo.core.network.LinuxDoRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
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
    val updatedAt: Long = System.currentTimeMillis()
) {
    val hasContent: Boolean get() = raw.isNotBlank() || (action == ComposerAction.NEW_TOPIC && title.isNotBlank())
    fun sameContent(other: ComposerDraft) = copy(sequence = null, updatedAt = 0) == other.copy(sequence = null, updatedAt = 0)
}

/** Account-scoped local recovery. Android persists this encrypted, outside the backup scope. */
object LocalComposerDrafts {
    private val json = Json { ignoreUnknownKeys = true }
    private val drafts = java.util.concurrent.ConcurrentHashMap<String, ComposerDraft>()
    private val queues = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicReference<CompletableDeferred<Unit>>>()
    private val sequences = java.util.concurrent.ConcurrentHashMap<String, Int>()
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
        persist()
    }
    internal fun queue(owner: String, key: String) = queues.computeIfAbsent(id(owner, key)) { java.util.concurrent.atomic.AtomicReference(CompletableDeferred(Unit)) }
    internal fun sequence(owner: String, key: String): Int? = sequences[id(owner, key)]
    internal fun recordSequence(owner: String, key: String, value: Int) { sequences[id(owner, key)] = value }
    fun forgetSequence(owner: String, key: String) { sequences.remove(id(owner, key)) }
    fun remove(owner: String, key: String) { drafts.remove(id(owner, key)); persist() }
    private fun persist() { onPersist?.invoke(json.encodeToString(drafts.values.toList())) }
}

/** One transient writer per editor session. Sync, publish, and deletion share the draft sequence. */
class ComposerDraftSession(initial: ComposerDraft) {
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
    private var synced: ComposerDraft? = null
    private var completed = false

    fun update(value: ComposerDraft) {
        if (completed) return
        current = value.copy(sequence = savedSequence)
        LocalComposerDrafts.save(current)
    }
    fun isSynced() = synced?.sameContent(current) == true

    suspend fun sync(): Boolean = serialWrite {
        if (completed || !current.hasContent || isSynced()) return@serialWrite true
        val snapshot = current
        val sequence = LocalComposerDrafts.sequence(snapshot.owner, snapshot.key) ?: savedSequence
            ?: LinuxDoRepository.fetchDraftSequence(snapshot.key).also { savedSequence = it }
        val next = LinuxDoRepository.saveCloudDraft(
            draftKey = snapshot.key, sequence = sequence, replyRaw = snapshot.raw,
            title = snapshot.title, categoryId = snapshot.categoryId, tags = snapshot.tags,
            replyToPostNumber = snapshot.replyToPostNumber,
            action = when (snapshot.action) {
                ComposerAction.NEW_TOPIC -> "createTopic"
                ComposerAction.REPLY -> "reply"
                ComposerAction.EDIT -> "edit"
            }, topicId = snapshot.topicId, postId = snapshot.postId
        ) ?: return@serialWrite false
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
            ?: runCatching { LinuxDoRepository.fetchDraftSequence(current.key) }.getOrNull()
        val result = block() ?: return@serialWrite null
        completed = true
        LocalComposerDrafts.remove(current.owner, current.key)
        // Publishing is already successful even if cloud cleanup is unavailable. Keep cleanup
        // independent of the editor's composition lifetime, without retrying the actual post.
        runCatching {
            cleanupSequence?.let { LinuxDoRepository.deleteCloudDraft(current.key, it) }
        }
        LocalComposerDrafts.forgetSequence(current.owner, current.key)
        result
    }

    suspend fun discard(): Boolean = serialWrite {
        val knownSequence = LocalComposerDrafts.sequence(current.owner, current.key) ?: savedSequence
        val cloudRemoved = if (knownSequence == null && synced == null) {
            runCatching {
                val sequence = LinuxDoRepository.fetchDraftSequence(current.key)
                LinuxDoRepository.deleteCloudDraft(current.key, sequence)
            }.getOrDefault(true)
        } else {
            try {
                val sequence = knownSequence ?: LinuxDoRepository.fetchDraftSequence(current.key)
                LinuxDoRepository.deleteCloudDraft(current.key, sequence)
            } catch (_: do_.linux.lindo.core.network.ApiReadException) {
                true
            }
        }
        if (!cloudRemoved) return@serialWrite false
        completed = true
        LocalComposerDrafts.remove(current.owner, current.key)
        LocalComposerDrafts.forgetSequence(current.owner, current.key)
        true
    }
}

fun ComposerDraft.toCloudItem() = do_.linux.lindo.core.network.CloudDraftItem(
    draftKey = key, sequence = sequence ?: 0, title = title.ifBlank {
        when (action) { ComposerAction.NEW_TOPIC -> "未命名话题草稿"; ComposerAction.REPLY -> "回复话题 #$topicId"; ComposerAction.EDIT -> "编辑第 #${postNumber ?: ""} 楼" }
    }, excerpt = raw, topicId = topicId ?: 0, createdAtText = "本机草稿", raw = raw, categoryId = categoryId,
    tags = tags, replyToPostNumber = replyToPostNumber, action = when (action) {
        ComposerAction.NEW_TOPIC -> "createTopic"; ComposerAction.REPLY -> "reply"; ComposerAction.EDIT -> "edit"
    }, postId = postId, sequenceKnown = sequence != null
)
