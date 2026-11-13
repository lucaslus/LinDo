package do_.linux.lindo.core

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import java.util.concurrent.atomic.AtomicBoolean

private val androidSnapshotNotifyScheduled = AtomicBoolean(false)

@PublishedApi
internal actual fun scheduleUiSnapshotNotifications() {
    val mainLooper = runCatching { Looper.getMainLooper() }.getOrNull() ?: return
    if (androidSnapshotNotifyScheduled.compareAndSet(false, true)) {
        val posted = runCatching {
            Handler(mainLooper).post {
                androidSnapshotNotifyScheduled.set(false)
                Snapshot.sendApplyNotifications()
            }
        }.getOrDefault(false)
        if (!posted) {
            androidSnapshotNotifyScheduled.set(false)
        }
    }
}
