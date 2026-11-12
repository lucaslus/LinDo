package do_.linux.lindo.core

@PublishedApi
internal val uiSafeStateWriteLock = Any()

/**
 * Safely mutates Compose [androidx.compose.runtime.MutableState] holders from any thread
 * (including `Dispatchers.IO` background workers) without invoking `MutableSnapshot.apply()`
 * off the platform UI thread.
 *
 * On Compose Desktop (Skiko), `SnapshotInvalidationTracker` executes `SnapshotStateObserver`
 * callbacks synchronously on whichever thread calls `MutableSnapshot.apply()`. Writing to
 * `GlobalSnapshot` under [uiSafeStateWriteLock] makes the new value immediately visible to all
 * callers while coalescing `Snapshot.sendApplyNotifications()` strictly onto the UI thread
 * (`AWT-EventQueue-0` on Desktop, `Looper.getMainLooper()` on Android).
 */
internal inline fun runUiSafeStateWrite(block: () -> Unit) {
    synchronized(uiSafeStateWriteLock) {
        block()
    }
    scheduleUiSnapshotNotifications()
}

@PublishedApi
internal expect fun scheduleUiSnapshotNotifications()
