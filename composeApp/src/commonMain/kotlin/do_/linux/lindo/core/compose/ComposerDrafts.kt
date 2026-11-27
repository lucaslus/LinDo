package do_.linux.lindo.core.compose

import do_.linux.lindo.core.copy.CloudDraftKind
import do_.linux.lindo.core.copy.LinDoNetworkCopy
import do_.linux.lindo.core.network.ApiFailureKind
import do_.linux.lindo.core.network.ApiReadException
import do_.linux.lindo.core.network.CloudDraftItem
import do_.linux.lindo.core.network.LinuxDoRepository
import do_.linux.lindo.core.network.UserApiKeyAuthManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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

    val cloudKind: CloudDraftKind
        get() {
            val actionHint = when (action) {
                ComposerAction.NEW_TOPIC -> CloudDraftKind.NEW_TOPIC.wireAction
                ComposerAction.EDIT -> CloudDraftKind.EDIT_POST.wireAction
                ComposerAction.REPLY -> CloudDraftKind.TOPIC_REPLY.wireAction
            }
            return CloudDraftKind.resolve(action = actionHint, draftKey = key)
        }

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
    var onPersist: ((String?) -> Unit)? = null
    private fun id(owner: String, key: String) = "${owner.lowercase()}\u0000$key"
    fun restore(encoded: String?) {
        if (encoded.isNullOrBlank()) return
        runCatching { json.decodeFromString<List<ComposerDraft>>(encoded) }.getOrNull()?.filter { it.hasContent || it.action == ComposerAction.EDIT }?.forEach {
            drafts[id(it.owner, it.key)] = it
        }
    }
    internal fun exportForAccount(owner: String): String = json.encodeToString(all(owner))
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
    fun remove(owner: String, key: String) {
        if (drafts.remove(id(owner, key)) != null) schedulePersist(immediate = true)
    }
    fun clearAll() {
        synchronized(persistLock) {
            persistJob?.cancel()
            persistJob = null
            dirtyPersist = false
            drafts.clear()
            sequences.clear()
            queues.clear()
            onPersist?.invoke(null)
        }
    }
    // Read and write the snapshot under the same lock, including lifecycle flushes.
    private fun persistDirtyLocked() {
        if (!dirtyPersist) return
        onPersist?.invoke(json.encodeToString(drafts.values.toList()))
        dirtyPersist = false
    }
    fun flushPendingPersist() {
        synchronized(persistLock) {
            persistJob?.cancel()
            persistJob = null
            persistDirtyLocked()
        }
    }
    private fun schedulePersist(immediate: Boolean) {
        if (onPersist == null) return
        synchronized(persistLock) {
            dirtyPersist = true
            persistJob?.cancel()
            persistJob = persistScope.launch {
                if (!immediate) delay(400L)
                synchronized(persistLock) {
                    if (isActive) {
                        persistDirtyLocked()
                        persistJob = null
                    }
                }
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
    var userModifiedInSession: Boolean = false
        private set
    private var synced: ComposerDraft? = null
    private var completed = false
    private val openingDraft = initial
    private val accountGeneration = UserApiKeyAuthManager.sessionGeneration
    private val restoredDraft = initialExistsOnServer || LocalComposerDrafts.get(initial.owner, initial.key) != null
    private var keepLocalDraft = initial.hasContent
    private var knownCloudSequence = initial.sequence.takeIf { initialExistsOnServer }
    private var knownCloudFloor = initial.replyToPostNumber
    private var openingLookup: CompletableDeferred<Unit>? = null
    private var checkedCloudAtOpen = false

    private fun rememberCloudVersion(sequence: Int, draft: ComposerDraft) {
        knownCloudSequence = sequence
        knownCloudFloor = draft.replyToPostNumber
    }

    private fun stripGeneratedDraftTitle(rawTitle: String): String {
        val untitledPrefix = LinDoNetworkCopy.DRAFT_UNTITLED_TOPIC.text.take(3)
        val replyPrefix = LinDoNetworkCopy.DRAFT_REPLY_TOPIC_ID.format("")
        val pmPrefix = LinDoNetworkCopy.DRAFT_PM_RECIPIENT.format("")
        return rawTitle.takeUnless {
            it.startsWith(untitledPrefix) ||
                it.startsWith(replyPrefix) ||
                it.startsWith(pmPrefix)
        }.orEmpty()
    }

    private fun applyRemoteItem(remote: CloudDraftItem): ComposerDraft {
        val cleanRemoteTitle = stripGeneratedDraftTitle(remote.title)
        val hydrated = current.copy(
            raw = remote.raw,
            title = if (current.action == ComposerAction.NEW_TOPIC) cleanRemoteTitle else current.title,
            categoryId = remote.categoryId ?: current.categoryId,
            tags = if (remote.tags.isNotEmpty() || current.action == ComposerAction.NEW_TOPIC) remote.tags else current.tags,
            topicId = remote.topicId.takeIf { it > 0 } ?: current.topicId,
            postId = remote.postId ?: current.postId,
            replyToPostNumber = if (current.action == ComposerAction.REPLY) remote.replyToPostNumber else current.replyToPostNumber,
            replyToUsername = current.replyToUsername.takeIf { current.replyToPostNumber == remote.replyToPostNumber },
            recipients = remote.recipients.ifBlank { current.recipients },
            sequence = remote.sequence,
            updatedAt = System.currentTimeMillis()
        )
        existsOnServer = true
        savedSequence = remote.sequence
        rememberCloudVersion(remote.sequence, hydrated)
        keepLocalDraft = true
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
        rememberCloudVersion(remoteSequence, current)
        current = current.copy(sequence = remoteSequence, updatedAt = System.currentTimeMillis())
        synced = null
        LocalComposerDrafts.recordSequence(current.owner, current.key, remoteSequence)
        if (keepLocalDraft) LocalComposerDrafts.save(current)
        return current
    }

    private fun matchesRemote(draft: ComposerDraft, remote: CloudDraftItem): Boolean =
        draft.raw.trim() == remote.raw.trim() &&
            (draft.action != ComposerAction.REPLY || draft.replyToPostNumber == remote.replyToPostNumber) &&
            (draft.action != ComposerAction.NEW_TOPIC || (
                draft.title.trim() == stripGeneratedDraftTitle(remote.title).trim() && draft.tags == remote.tags &&
                    (remote.categoryId == null || draft.categoryId == remote.categoryId))) &&
            (draft.cloudKind != CloudDraftKind.PRIVATE_MESSAGE || draft.recipients.trim() == remote.recipients.trim())

    /**
     * Queries `GET /drafts/{key}.json` on composer open to hydrate Web drafts or surface conflicts:
     * - Case 1 (`!hasLocalEdits && remote.draft != null`): hydrates `current` directly from `remote.draft`.
     * - Case 2 (`hasLocalEdits && remote.draft != null` and content differs): keeps local `current` untouched
     *   without stamping `remote.sequence` onto `LocalComposerDrafts` yet, and returns `remote.draft` for conflict UI.
     * - Case 3 (`remote.draft == null`): sets `existsOnServer = false` and records `savedSequence = remote.sequence`.
     */
    suspend fun prepareWithRemote(untouchedBaselineRaw: String? = null): CloudDraftItem? {
        if (completed || current.owner.isBlank()) return null
        val lookup = CompletableDeferred<Unit>()
        openingLookup = lookup
        return try {
            loadRemote(untouchedBaselineRaw)
        } finally {
            lookup.complete(Unit)
            if (openingLookup === lookup) openingLookup = null
        }
    }

    private suspend fun loadRemote(untouchedBaselineRaw: String?): CloudDraftItem? {
        val remote = try {
            LinuxDoRepository.fetchCloudDraftByKey(current.key)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            return null
        }
        if (completed) return null
        checkedCloudAtOpen = true
        val remoteDraft = remote.draft
        if (remoteDraft == null) {
            existsOnServer = false
            knownCloudSequence = null
            savedSequence = remote.sequence
            LocalComposerDrafts.recordSequence(current.owner, current.key, remote.sequence)
            if (keepLocalDraft && current.hasContent && (current.raw != untouchedBaselineRaw || userModifiedInSession)) {
                current = current.copy(sequence = remote.sequence)
                LocalComposerDrafts.save(current)
            }
            return null
        }

        val hasLocalEdits = userModifiedInSession || if (untouchedBaselineRaw != null) {
            current.raw != untouchedBaselineRaw
        } else {
            current.hasContent
        }
        val differentReplyTarget = current.action == ComposerAction.REPLY && current.replyToPostNumber != remoteDraft.replyToPostNumber
        if (!hasLocalEdits && !differentReplyTarget) {
            applyRemoteItem(remoteDraft.copy(sequence = remote.sequence, sequenceKnown = true))
            return null
        }

        existsOnServer = true
        if (!matchesRemote(current, remoteDraft)) {
            if (userModifiedInSession && openingDraft.hasContent && matchesRemote(openingDraft, remoteDraft)) {
                rememberCloudVersion(remote.sequence, openingDraft)
                savedSequence = remote.sequence
                if (!keepLocalDraft) return null
            }
            // Do NOT overwrite current and do NOT stamp remote.sequence onto LocalComposerDrafts yet.
            return remoteDraft.copy(sequence = remote.sequence, sequenceKnown = true)
        }

        savedSequence = remote.sequence
        rememberCloudVersion(remote.sequence, current)
        current = current.copy(sequence = remote.sequence)
        synced = current
        LocalComposerDrafts.recordSequence(current.owner, current.key, remote.sequence)
        if (keepLocalDraft) LocalComposerDrafts.save(current)
        return null
    }

    suspend fun hydrateFromServer(untouchedBaselineRaw: String? = null): RemoteDraftHydration =
        RemoteDraftHydration(conflict = prepareWithRemote(untouchedBaselineRaw))

    fun update(value: ComposerDraft, fromUserEdit: Boolean = true, keepLocal: Boolean = value.hasContent) {
        if (completed) return
        if (fromUserEdit) {
            userModifiedInSession = true
        }
        current = value.copy(sequence = savedSequence)
        keepLocalDraft = keepLocal
        if (keepLocal) LocalComposerDrafts.save(current) else LocalComposerDrafts.remove(current.owner, current.key)
    }
    fun isSynced() = synced?.sameContent(current) == true

    fun syncInBackground() {
        if (completed || !keepLocalDraft || !current.hasContent) return
        LocalComposerDrafts.save(current)
        LinuxDoRepository.launchDetachedDraftOp {
            runCatching { sync() }
        }
    }

    suspend fun sync(): Boolean = serialWrite {
        if (completed || !keepLocalDraft || !current.hasContent || isSynced()) return@serialWrite true
        val snapshot = current
        val sequence = LocalComposerDrafts.sequence(snapshot.owner, snapshot.key) ?: savedSequence
            ?: LinuxDoRepository.fetchDraftSequence(snapshot.key).also { savedSequence = it }
        val wireAction = snapshot.cloudKind.wireAction
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
        rememberCloudVersion(next, snapshot)
        LocalComposerDrafts.recordSequence(snapshot.owner, snapshot.key, next)
        current = current.copy(sequence = next)
        if (keepLocalDraft) LocalComposerDrafts.save(current)
        synced = snapshot
        true
    }

    /** Drop only the cloud revision this editor displayed or saved. Other reply targets share the topic key. */
    suspend fun closeClearedDraft() {
        if (restoredDraft) openingLookup?.await()
        serialWrite {
            if (completed || accountGeneration != UserApiKeyAuthManager.sessionGeneration) return@serialWrite
            if (restoredDraft && !checkedCloudAtOpen && knownCloudSequence == null && openingDraft.hasContent) {
                val lookup = LinuxDoRepository.fetchCloudDraftByKey(current.key)
                if (lookup.draft?.let { matchesRemote(openingDraft, it) } == true) rememberCloudVersion(lookup.sequence, openingDraft)
                checkedCloudAtOpen = true
            }
            if (accountGeneration != UserApiKeyAuthManager.sessionGeneration) return@serialWrite
            knownCloudSequence?.takeIf { current.action != ComposerAction.REPLY || knownCloudFloor == current.replyToPostNumber }?.let {
                check(LinuxDoRepository.deleteCloudDraft(current.key, it, background = true, preserveNewerVersion = true))
            }
            LocalComposerDrafts.remove(current.owner, current.key)
            LocalComposerDrafts.forgetSequence(current.owner, current.key)
            completed = true
        }
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
                            runCatching { LinuxDoRepository.deleteCloudDraft(targetKey, seq, background = true) }
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
                            runCatching { LinuxDoRepository.deleteCloudDraft(targetKey, seq, background = true) }
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
    val kind = cloudKind
    return CloudDraftItem(
        draftKey = key,
        sequence = sequence ?: 0,
        title = title.ifBlank {
            when (kind) {
                CloudDraftKind.PRIVATE_MESSAGE -> if (recipients.isNotBlank()) {
                    LinDoNetworkCopy.DRAFT_PM_RECIPIENT.format(recipients)
                } else {
                    LinDoNetworkCopy.DRAFT_UNTITLED_PM.text
                }
                CloudDraftKind.NEW_TOPIC -> LinDoNetworkCopy.DRAFT_UNTITLED_TOPIC.text
                CloudDraftKind.TOPIC_REPLY -> LinDoNetworkCopy.DRAFT_TOPIC_REPLY.text
                CloudDraftKind.EDIT_POST -> postNumber?.takeIf { it > 1 }
                    ?.let { LinDoNetworkCopy.DRAFT_FLOOR_EDIT.format(it) }
                    ?: LinDoNetworkCopy.DRAFT_POST_EDIT.text
            }
        },
        excerpt = raw,
        topicId = topicId ?: 0,
        createdAtText = LinDoNetworkCopy.DRAFT_LOCAL_TAG.text,
        raw = raw,
        categoryId = categoryId,
        tags = tags,
        replyToPostNumber = replyToPostNumber,
        action = kind.wireAction,
        postId = postId,
        sequenceKnown = sequence != null,
        recipients = recipients
    )
}
