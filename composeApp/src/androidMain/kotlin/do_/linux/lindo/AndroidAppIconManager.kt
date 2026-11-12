package do_.linux.lindo

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.widget.Toast
import do_.linux.lindo.core.copy.LinDoMessagesCopy
import do_.linux.lindo.ui.LinDoAppIcon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

internal class AndroidAppIconManager(private val activity: Activity) {
    private class Restart(val owner: Any, val current: LinDoAppIcon, val selected: LinDoAppIcon) {
        val oldActivityDestroyed = CompletableDeferred<Unit>()
    }

    private companion object {
        var pendingRestart: Restart? = null
    }

    private val owner = Any()
    private val packages get() = activity.packageManager

    fun component(icon: LinDoAppIcon) = ComponentName(activity.packageName, "${activity.packageName}.LauncherIcon_${icon.key}")

    fun restore(): LinDoAppIcon {
        val enabled = LinDoAppIcon.Choices.filter(::isEnabled)
        val selected = enabled.firstOrNull { component(it) == activity.intent.component }
            ?: enabled.firstOrNull()
            ?: LinDoAppIcon.Default
        if (enabled.size != 1) {
            runCatching { selectOnly(selected) }
                .onFailure { Log.w("LinDoIcon", "Could not reconcile launcher icons", it) }
        }
        return selected
    }

    fun apply(current: LinDoAppIcon, selected: LinDoAppIcon): Boolean {
        if (selected == current) return true
        if (pendingRestart != null) return false
        val restart = Restart(owner, current, selected)
        pendingRestart = restart
        return try {
            // The task must adopt MainActivity's identity before its launcher alias is disabled.
            val main = ComponentName(activity, MainActivity::class.java)
            activity.startActivity(Intent.makeRestartActivityTask(main))
            true
        } catch (error: Exception) {
            pendingRestart = null
            Log.w("LinDoIcon", "Could not restart for launcher icon change", error)
            false
        }
    }

    fun initialIcon(): LinDoAppIcon = pendingRestart?.takeIf { it.owner !== owner }?.selected ?: restore()

    suspend fun finishRestart(): LinDoAppIcon? {
        val restart = pendingRestart?.takeIf { it.owner !== owner } ?: return null
        return try {
            // A package change can finish the entire task while the old alias is still in it.
            check(withTimeoutOrNull(5_000L) { restart.oldActivityDestroyed.await() } != null) {
                "Previous activity is still finishing"
            }
            selectOnly(restart.selected)
            restart.selected
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            runCatching { selectOnly(restart.current) }
                .onFailure { Log.w("LinDoIcon", "Could not restore launcher icon", it) }
            Log.w("LinDoIcon", "Could not switch launcher icon", error)
            Toast.makeText(activity, LinDoMessagesCopy.SWITCH_ICON_FAILED_RETRY.text, Toast.LENGTH_SHORT).show()
            restore()
        } finally {
            if (pendingRestart === restart) pendingRestart = null
        }
    }

    fun onActivityDestroyed() {
        pendingRestart?.takeIf { it.owner === owner }?.oldActivityDestroyed?.complete(Unit)
    }

    private fun isEnabled(icon: LinDoAppIcon): Boolean =
        when (packages.getComponentEnabledSetting(component(icon))) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> icon == LinDoAppIcon.Default
            else -> false
        }

    private fun selectOnly(selected: LinDoAppIcon) {
        // Enable the replacement first on Android versions without atomic component updates.
        val updates = (listOf(selected) + LinDoAppIcon.Choices.filterNot { it == selected })
            .mapNotNull { icon ->
                val enabled = icon == selected
                if (isEnabled(icon) == enabled) null
                else component(icon) to if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
        if (updates.isEmpty()) return
        val flags = PackageManager.DONT_KILL_APP
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packages.setComponentEnabledSettings(updates.map { (name, state) ->
                PackageManager.ComponentEnabledSetting(name, state, flags)
            })
        } else {
            updates.forEach { (name, state) -> packages.setComponentEnabledSetting(name, state, flags) }
        }
    }
}
