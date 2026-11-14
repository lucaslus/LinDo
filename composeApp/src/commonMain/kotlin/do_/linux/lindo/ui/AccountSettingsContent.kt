package do_.linux.lindo.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import do_.linux.lindo.core.network.AuthenticatedUserProfile
import do_.linux.lindo.core.network.UserAccountPreferencesSnapshot
import do_.linux.lindo.ui.components.LinDoIcons
import do_.linux.lindo.ui.components.SmartAvatar

/** Shared account layout: long values wrap below labels instead of competing for row width. */
@Composable
internal fun AccountSettingsColumn(content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 600.dp).fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp), content = content)
    }
}

@Composable
internal fun AccountSection(title: String) {
    Text(title, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = LocalPalette.current.inkTertiary,
        modifier = Modifier.padding(top = 24.dp, bottom = 6.dp))
}

@Composable
internal fun AccountDetailRow(
    title: String,
    detail: String? = null,
    web: Boolean = false,
    destructive: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium,
                color = if (destructive) Color(0xFFEF4444) else p.inkPrimary)
            if (!detail.isNullOrBlank()) Text(detail, fontSize = 13.sp, lineHeight = 19.sp,
                color = p.inkSecondary, modifier = Modifier.padding(top = 4.dp))
        }
        if (onClick != null) {
            Spacer(Modifier.width(12.dp))
            if (web) {
                Text("网页", fontSize = 11.sp, color = p.inkTertiary)
                Spacer(Modifier.width(5.dp))
            }
            Icon(LinDoIcons.ChevronRight, null, Modifier.size(15.dp), tint = p.inkTertiary)
        }
    }
    HorizontalDivider(thickness = 0.5.dp, color = p.hairline)
}

@Composable
internal fun AccountIdentity(user: AuthenticatedUserProfile) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        SmartAvatar(username = user.username, avatarTemplate = user.avatarTemplate,
            animatedAvatar = user.animatedAvatar, size = 44.dp, enableAnimations = false)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(user.displayName.ifBlank { user.username }, fontSize = 18.sp, lineHeight = 24.sp,
                fontWeight = FontWeight.SemiBold, color = p.inkPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("@${user.username} · Lv.${user.trustLevel}", fontSize = 13.sp, lineHeight = 19.sp,
                color = p.inkSecondary, modifier = Modifier.padding(top = 4.dp))
        }
    }
    HorizontalDivider(thickness = 0.5.dp, color = p.hairline)
}

@Composable
private fun AccountPrivacySwitch(title: String, detail: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
        .padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, lineHeight = 21.sp, color = p.inkPrimary)
            Text(detail, fontSize = 12.sp, lineHeight = 18.sp, color = p.inkSecondary,
                modifier = Modifier.padding(top = 4.dp))
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, enabled = enabled, onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedTrackColor = p.brandGold, checkedThumbColor = Color.White))
    }
    HorizontalDivider(thickness = 0.5.dp, color = p.hairline)
}

internal fun associatedAccountLabel(raw: String): Pair<String, String?> {
    val provider = raw.substringBefore(" (")
    val detail = raw.substringAfter(" (", "").removeSuffix(")").takeIf { it.isNotBlank() }
    val title = when (provider.lowercase()) {
        "google_oauth2" -> "Google 账号"
        "google_one_tap" -> "Google 快捷登录"
        "github" -> "GitHub"
        "apple" -> "Apple"
        "facebook" -> "Facebook"
        "twitter", "twitter2" -> "X"
        "discord" -> "Discord"
        else -> "其他关联账号"
    }
    return title to detail
}

@Composable
internal fun AccountPreferencesContent(
    snapshot: UserAccountPreferencesSnapshot,
    hidePresence: Boolean,
    allowPm: Boolean,
    saving: Boolean,
    onPrivacyChange: (Boolean, Boolean) -> Unit,
    onEditProfile: () -> Unit,
    onOpenWeb: (String) -> Unit
) {
    AccountSettingsColumn {
        AccountSection("个人资料")
        AccountDetailRow("昵称与个人资料", "${snapshot.displayName.ifBlank { snapshot.username }} · @${snapshot.username}", onClick = onEditProfile)
        AccountDetailRow("个人头衔", snapshot.title.ifBlank { "未设置" }, onClick = onEditProfile)
        AccountDetailRow("绑定邮箱", snapshot.email.ifBlank { "在网页查看与管理" }, web = true,
            onClick = { onOpenWeb("https://linux.do/my/preferences/account") })
        AccountSection("隐私与互动")
        AccountPrivacySwitch("隐藏公开资料与在线状态", "其他成员无法查看你的公开资料与在线状态", hidePresence, !saving) {
            onPrivacyChange(it, allowPm)
        }
        AccountPrivacySwitch("允许站内私信", "关闭后仅管理员可向你发送私信", allowPm, !saving) {
            onPrivacyChange(hidePresence, it)
        }
        AccountSection("账号安全")
        AccountDetailRow("两步验证", when (snapshot.twoFactorEnabled) {
            true -> "已开启"; false -> "未开启"; null -> "在网页查看与管理"
        }, web = true, onClick = { onOpenWeb("https://linux.do/my/preferences/security") })
        AccountDetailRow("登录设备", snapshot.activeDeviceCount?.let { "$it 个登录会话" } ?: "查看设备与登录记录", web = true,
            onClick = { onOpenWeb("https://linux.do/my/preferences/security") })
        if (snapshot.associatedAccounts.isNotEmpty()) {
            AccountSection("关联账号")
            snapshot.associatedAccounts.forEach { raw ->
                val (name, detail) = associatedAccountLabel(raw)
                AccountDetailRow(name, detail, web = true, onClick = { onOpenWeb("https://linux.do/my/preferences/account") })
            }
        }
        AccountSection("更多网站偏好")
        AccountDetailRow("通知", "回复、提及与互动提醒", web = true,
            onClick = { onOpenWeb("https://linux.do/my/preferences/notifications") })
        AccountDetailRow("邮件", "邮件通知与社区摘要", web = true,
            onClick = { onOpenWeb("https://linux.do/my/preferences/emails") })
        AccountDetailRow("全部网站设置", "分类、标签、用户屏蔽等偏好", web = true,
            onClick = { onOpenWeb("https://linux.do/my/preferences") })
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
internal fun AccountSessionContent(
    user: AuthenticatedUserProfile?,
    status: String?,
    onLogin: () -> Unit,
    onBrowserAuth: () -> Unit,
    onLogout: () -> Unit
) {
    AccountSettingsColumn {
        if (user != null) AccountIdentity(user)
        if (!status.isNullOrBlank()) Text(status, color = LocalPalette.current.inkSecondary,
            fontSize = 13.sp, lineHeight = 20.sp, modifier = Modifier.padding(vertical = 16.dp))
        AccountSection(if (user != null) "登录管理" else "登录方式")
        AccountDetailRow(if (user != null) "重新登录" else "使用 LINUX DO 登录", "在应用内打开登录网页", onClick = onLogin)
        AccountDetailRow("系统浏览器授权", "使用浏览器中的账号完成授权", onClick = onBrowserAuth)
        if (user != null) {
            AccountSection("退出")
            AccountDetailRow("退出当前账号", "退出后可以登录其他账号", destructive = true, onClick = onLogout)
        }
        Spacer(Modifier.height(24.dp))
    }
}
