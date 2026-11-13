package do_.linux.lindo

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Memory is immediate; serialized disk snapshots never block pointer or Compose callbacks. */
internal class DesktopPreferenceStore(private val file: File) {
    private val stateLock = Any()
    private val writeLock = Any()
    private val state = Properties().apply { if (file.isFile) runCatching { file.inputStream().use { load(it) } } }
    private val writer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "lindo-preferences").apply { isDaemon = true } }
    private var pending: ScheduledFuture<*>? = null
    fun load(): Properties = synchronized(stateLock) { Properties().apply { putAll(state) } }
    fun update(immediate: Boolean = false, mutator: Properties.() -> Unit) {
        synchronized(stateLock) {
            state.mutator()
            pending?.cancel(false)
            if (!immediate) pending = writer.schedule({ runCatching { persistLatest() } }, 120, TimeUnit.MILLISECONDS)
        }
        if (immediate) persistLatest()
    }
    private fun persistLatest() = synchronized(writeLock) {
        val snapshot = load()
        file.parentFile.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        if (!temp.exists()) temp.createNewFile()
        runCatching { Files.setPosixFilePermissions(temp.toPath(), PosixFilePermissions.fromString("rw-------")) }
        temp.outputStream().use { snapshot.store(it, "Lindo Desktop State") }
        try { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
        catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
    }
    fun flush() { synchronized(stateLock) { pending?.cancel(false) }; persistLatest() }
}
