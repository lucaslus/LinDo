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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import do_.linux.lindo.core.model.TopicUser
import do_.linux.lindo.core.model.UserNotificationItem
import do_.linux.lindo.core.network.LinuxDoRepository
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
    val canResolvePostAuthor = item.actorUsername == null &&
        item.notificationType in setOf(17, 36, 801, 802) &&
        (item.originalPostId > 0 || item.topicId > 0)
    var resolvedActorUser by remember(item.id, item.actingUsername) {
        mutableStateOf<TopicUser?>(null)
    }
    var postAuthorResolveDone by remember(item.id, canResolvePostAuthor) {
        mutableStateOf(!canResolvePostAuthor)
    }
    if (canResolvePostAuthor && !postAuthorResolveDone) {
        LaunchedEffect(item.id) {
            resolvedActorUser = LinuxDoRepository.resolveNotificationActor(item)
            postAuthorResolveDone = true
        }
    }

    val effectiveItem = remember(item, resolvedActorUser) {
        val u = resolvedActorUser
        if (u != null && u.username.isNotBlank()) {
            item.copy(
                actingUsername = u.username,
                actingAvatarTemplate = u.avatarTemplate.takeIf { it.isNotBlank() } ?: item.actingAvatarTemplate,
                actingPrimaryGroupName = u.primaryGroupName ?: item.actingPrimaryGroupName,
                actingAnimatedAvatar = u.animatedAvatar ?: item.actingAnimatedAvatar,
                actingFlair = if (u.flair.hasFlair) u.flair else item.actingFlair
            )
        } else {
            item
        }
    }

    val actor = effectiveItem.actorUsername
    val initialAvatarTemplate = remember(actor, effectiveItem.actingAvatarTemplate) {
        effectiveItem.actingAvatarTemplate?.takeIf { it.isNotBlank() }
            ?: actor?.let { LinuxDoRepository.getCachedUserAvatar(it) }
    }
    var resolvedAvatarTemplate by remember(actor, initialAvatarTemplate) {
        mutableStateOf(initialAvatarTemplate)
    }
    var avatarResolveDone by remember(actor, initialAvatarTemplate) {
        mutableStateOf(actor == null || !initialAvatarTemplate.isNullOrBlank())
    }
    if (actor != null && resolvedAvatarTemplate.isNullOrBlank() && !avatarResolveDone) {
        LaunchedEffect(actor) {
            val fetched = LinuxDoRepository.resolveUserAvatarTemplate(actor)
            if (!fetched.isNullOrBlank()) {
                resolvedAvatarTemplate = fetched
            }
            avatarResolveDone = true
        }
    }
    val activity = remember(effectiveItem, actor, palette.inkPrimary) {
        buildAnnotatedString {
            if (actor != null) {
                val displayActor = effectiveItem.actingUsername
                    .takeUnless { UserNotificationItem.CONSOLIDATED_NOTIFICATION_ACTOR_REGEX.matches(it.trim()) }
                    ?: actor
                withStyle(SpanStyle(color = palette.inkPrimary, fontWeight = FontWeight.SemiBold)) {
                    append(displayActor)
                }
                append(" ")
            }
            append(effectiveItem.typeLabel)
        }
    }
    val title = effectiveItem.title
    val excerpt = effectiveItem.excerpt.takeUnless { it.isBlank() || it.equals(title, ignoreCase = true) }

    Row(
        modifier = Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .semantics { stateDescription = if (effectiveItem.read) "已读" else "未读" }
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
            if (actor != null && (!resolvedAvatarTemplate.isNullOrBlank() || !avatarResolveDone)) {
                SmartAvatar(
                    username = actor,
                    avatarTemplate = resolvedAvatarTemplate,
                    animatedAvatar = effectiveItem.actingAnimatedAvatar,
                    primaryGroupName = effectiveItem.actingPrimaryGroupName,
                    size = 36.dp,
                    enableAnimations = false
                )
            } else if (!postAuthorResolveDone) {
                SmartAvatar(
                    username = "L",
                    avatarTemplate = null,
                    animatedAvatar = null,
                    size = 36.dp,
                    enableAnimations = false
                )
            } else {
                val icon = resolveNotificationIcon(effectiveItem)
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(palette.subtleSurface),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = palette.inkSecondary)
                }
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

internal fun resolveNotificationIcon(item: UserNotificationItem): ImageVector = when {
    item.notificationType in setOf(36, 800) -> LinDoIcons.User
    item.notificationType in setOf(17, 37, 801) -> LinDoIcons.FileText
    item.notificationType in setOf(38, 802) -> LinDoIcons.Reply
    item.notificationType in setOf(40, 803) ||
        item.typeLabel.contains("助力") ||
        item.typeLabel.contains("boost", ignoreCase = true) -> LinDoIcons.BoostRocket
    item.isGroupedReply || item.notificationType in setOf(1, 2, 3, 6, 9, 29, 30, 33) -> LinDoIcons.MessageCircle
    item.badgeId > 0 || item.notificationType == 12 -> LinDoIcons.AwardBadge
    item.notificationType in setOf(5, 19, 25) -> LinDoIcons.Heart
    else -> LinDoIcons.Bell
}
