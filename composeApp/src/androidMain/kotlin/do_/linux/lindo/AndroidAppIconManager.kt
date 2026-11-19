package do_.linux.lindo

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import do_.linux.lindo.ui.LinDoAppIcon

/**
 * Android 桌面图标无缝切换管理器（零跳首页、零前台强退）：
 * - 在 Android（尤其 Samsung One UI / Android 14~16）上，前台直接禁用当前 launcher `<activity-alias>`
 *   即使传入 `DONT_KILL_APP` 也常会触发系统关闭前台任务或杀进程；
 * - 采用业界标准「前台立即更新态 + 退至后台 `onStop()` 时原子切换 alias」策略：
 *   用户点击「应用图标」时留在当前页并立刻提示「已应用新图标，返回桌面即可查看效果」，
 *   返回桌面瞬间由 `onStop()` 执行 `setComponentEnabledSettings`，桌面图标顺滑刷新且绝不打断阅读。
 */
internal class AndroidAppIconManager(private val activity: Activity) {
    private companion object {
        private const val PREFS_NAME = "lindo_launcher_icon_prefs"
        private const val KEY_PENDING_ICON = "pending_launcher_icon_key"

        @Volatile
        var pendingIcon: LinDoAppIcon? = null
    }

    private val packages get() = activity.packageManager
    private val iconPrefs get() = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun component(icon: LinDoAppIcon) =
        ComponentName(activity.packageName, "${activity.packageName}.LauncherIcon_${icon.key}")

    private fun loadPersistedPendingIcon(): LinDoAppIcon? {
        val raw = runCatching { iconPrefs.getString(KEY_PENDING_ICON, null) }.getOrNull() ?: return null
        return LinDoAppIcon.Choices.firstOrNull { it.key == raw }
    }

    private fun persistPendingIcon(icon: LinDoAppIcon?) {
        runCatching {
            val editor = iconPrefs.edit()
            if (icon == null) editor.remove(KEY_PENDING_ICON)
            else editor.putString(KEY_PENDING_ICON, icon.key)
            editor.apply()
        }
    }

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

    fun initialIcon(): LinDoAppIcon {
        val savedPending = pendingIcon ?: loadPersistedPendingIcon()
        if (savedPending != null) {
            val enabled = LinDoAppIcon.Choices.filter(::isEnabled)
            if (enabled.size == 1 && enabled.first() == savedPending) {
                pendingIcon = null
                persistPendingIcon(null)
            } else {
                pendingIcon = savedPending
            }
            return savedPending
        }
        return restore()
    }

    fun apply(current: LinDoAppIcon, selected: LinDoAppIcon): Boolean {
        if (selected == current && pendingIcon == null) return true
        return try {
            val enabled = LinDoAppIcon.Choices.filter(::isEnabled)
            if (enabled.size == 1 && enabled.first() == selected) {
                // 用户离开应用前又切回原图标：直接取消待生效队列，零 PackageManager 变更
                pendingIcon = null
                persistPendingIcon(null)
            } else {
                pendingIcon = selected
                persistPendingIcon(selected)
            }
            true
        } catch (error: Exception) {
            Log.w("LinDoIcon", "Could not stage launcher icon change", error)
            false
        }
    }

    fun flushPendingOnBackground(isChangingConfigurations: Boolean) {
        if (isChangingConfigurations) return
        val target = pendingIcon ?: loadPersistedPendingIcon() ?: return
        runCatching {
            selectOnly(target)
            pendingIcon = null
            persistPendingIcon(null)
            Log.i("LinDoIcon", "Applied launcher icon on background: ${target.key}")
        }.onFailure { error ->
            Log.w("LinDoIcon", "Could not apply pending launcher icon on background", error)
        }
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
