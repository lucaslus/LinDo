package do_.linux.lindo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import do_.linux.lindo.core.model.UserNotificationItem
import do_.linux.lindo.ui.components.LinDoIcons
import do_.linux.lindo.ui.components.SmartAvatar

@Composable
internal fun LinDoNotificationActions(
    canMarkAll: Boolean,
    enabled: Boolean,
    onMarkAll: () -> Unit,
    onRefresh: () -> Unit
) {
    val palette = LocalPalette.current
    if (canMarkAll) {
        TextButton(
            onClick = onMarkAll, enabled = enabled,
            colors = ButtonDefaults.textButtonColors(contentColor = palette.inkSecondary)
        ) {
            Text("全部标为已读", fontSize = 12.sp, maxLines = 1)
        }
    }
    IconButton(onClick = onRefresh, enabled = enabled) {
        Icon(
            LinDoIcons.Refresh, "刷新通知", Modifier.size(18.dp),
            tint = if (enabled) palette.inkSecondary else palette.inkTertiary
        )
    }
}

@Composable
internal fun LinDoNotificationRow(
    item: UserNotificationItem,
    onClick: (() -> Unit)?,
    onInspectUser: ((String) -> Unit)? = null
) {
    val palette = LocalPalette.current
    val actor = item.actorUsername
    val activity = remember(item, actor, palette.inkPrimary) {
        buildAnnotatedString {
            if (actor != null) {
                withStyle(SpanStyle(color = palette.inkPrimary, fontWeight = FontWeight.SemiBold)) {
                    append(item.actingUsername)
                }
                append(" ")
            }
            append(item.typeLabel)
        }
    }
    val title = item.title
    val excerpt = item.excerpt.takeUnless { it.isBlank() || it.equals(title, ignoreCase = true) }

    Row(
        modifier = Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .semantics { stateDescription = if (item.read) "已读" else "未读" }
            .padding(start = 12.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier.size(48.dp).then(
                if (actor != null && onInspectUser != null) Modifier.clickable(
                    onClickLabel = "查看 $actor 的资料", role = Role.Button, onClick = { onInspectUser(actor) }
                ) else Modifier
            ),
            contentAlignment = Alignment.Center
        ) {
            if (actor != null) {
                SmartAvatar(
                    username = actor,
                    avatarTemplate = item.actingAvatarTemplate,
                    animatedAvatar = null,
                    size = 36.dp,
                    enableAnimations = false
                )
            } else {
                val icon = when {
                    item.isGroupedReply -> LinDoIcons.MessageCircle
                    item.badgeId > 0 -> LinDoIcons.AwardBadge
                    else -> LinDoIcons.Bell
                }
                Icon(icon, contentDescription = null, modifier = Modifier.size(21.dp), tint = palette.inkSecondary)
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    activity, modifier = Modifier.weight(1f),
                    color = palette.inkSecondary, fontSize = 13.sp, lineHeight = 18.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (item.createdAtText.isNotBlank()) {
                        Text(item.createdAtText, color = palette.inkSecondary, fontSize = 11.5.sp, maxLines = 1)
                    }
                    Box(
                        Modifier.size(6.dp).background(
                            if (item.read) Color.Transparent else palette.tabIndicatorColor,
                            CircleShape
                        )
                    )
                }
            }
            if (title.isNotBlank()) {
                Text(
                    title, color = palette.inkPrimary, fontSize = 14.sp, lineHeight = 20.sp,
                    fontWeight = if (item.read) FontWeight.Normal else FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
            }
            excerpt?.let {
                Text(
                    it, color = palette.inkSecondary, fontSize = 12.sp, lineHeight = 17.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
    HorizontalDivider(Modifier.padding(start = 68.dp, end = 16.dp), thickness = 0.5.dp, color = palette.hairline)
}
