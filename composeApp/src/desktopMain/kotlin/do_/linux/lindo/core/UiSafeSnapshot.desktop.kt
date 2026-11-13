package do_.linux.lindo.core

import androidx.compose.runtime.snapshots.Snapshot
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities

private val desktopSnapshotNotifyScheduled = AtomicBoolean(false)

@PublishedApi
internal actual fun scheduleUiSnapshotNotifications() {
    if (desktopSnapshotNotifyScheduled.compareAndSet(false, true)) {
        SwingUtilities.invokeLater {
            desktopSnapshotNotifyScheduled.set(false)
            Snapshot.sendApplyNotifications()
        }
    }
}
