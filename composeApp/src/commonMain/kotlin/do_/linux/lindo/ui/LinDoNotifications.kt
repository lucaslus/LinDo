package do_.linux.lindo.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import do_.linux.lindo.core.copy.DiscourseNotificationKind
import do_.linux.lindo.core.copy.LinDoCommonCopy
import do_.linux.lindo.core.copy.LinDoMessagesCopy
import do_.linux.lindo.core.copy.LinDoNetworkCopy
import do_.linux.lindo.core.model.TopicUser
import do_.linux.lindo.core.model.UserNotificationItem
import do_.linux.lindo.core.network.LinuxDoRepository
import do_.linux.lindo.ui.components.LinDoIcons
import do_.linux.lindo.ui.components.LocalLinDoNetworkRestoreVersion
import do_.linux.lindo.ui.components.SmartAvatar
import lindo.composeapp.generated.resources.Res
import lindo.composeapp.generated.resources.lindo_linuxdo_mark
import org.jetbrains.compose.resources.painterResource

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
            Text(LinDoMessagesCopy.MARK_ALL_AS_READ.text, fontSize = 12.sp, maxLines = 1)
        }
    }
    IconButton(onClick = onRefresh, enabled = enabled) {
        Icon(
            LinDoIcons.Refresh, LinDoMessagesCopy.REFRESH_NOTIFICATIONS_DESC.text, Modifier.size(18.dp),
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
    val networkRestoreVersion = LocalLinDoNetworkRestoreVersion.current
    val siteVerificationRestoreVersion = do_.linux.lindo.ui.components.LocalSiteVerificationRestoreVersion.current
    val notificationKind = DiscourseNotificationKind.fromCode(item.notificationType)
    val canResolvePostAuthor = item.actorUsername == null &&
        notificationKind.needsPostAuthorLookup &&
        (item.originalPostId > 0 || item.topicId > 0)
    var resolvedActorUser by remember(item.id, item.actingUsername) {
        mutableStateOf<TopicUser?>(null)
    }
    var postAuthorResolveDone by remember(item.id, canResolvePostAuthor) {
        mutableStateOf(!canResolvePostAuthor)
    }
    if (canResolvePostAuthor && !postAuthorResolveDone) {
        LaunchedEffect(item.id, item.actorUsername, item.originalPostId, item.topicId, networkRestoreVersion, siteVerificationRestoreVersion) {
            val resolved = LinuxDoRepository.resolveNotificationActor(item)
            if (resolved != null) {
                resolvedActorUser = resolved
            }
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

    val effectiveActorUsername = effectiveItem.actorUsername
    val initialAvatarTemplate = remember(effectiveActorUsername, effectiveItem.actingAvatarTemplate) {
        effectiveItem.actingAvatarTemplate?.takeIf { it.isNotBlank() }
            ?: effectiveActorUsername?.let { LinuxDoRepository.getCachedUserAvatar(it) }
    }
    var effectiveAvatarTemplate by remember(effectiveActorUsername, initialAvatarTemplate) {
        mutableStateOf(initialAvatarTemplate)
    }
    var avatarResolveDone by remember(effectiveActorUsername, initialAvatarTemplate) {
        mutableStateOf(effectiveActorUsername == null || !initialAvatarTemplate.isNullOrBlank())
    }
    if (effectiveActorUsername != null && effectiveAvatarTemplate.isNullOrBlank() && !avatarResolveDone) {
        LaunchedEffect(effectiveActorUsername, effectiveAvatarTemplate, networkRestoreVersion, siteVerificationRestoreVersion) {
            val resolved = LinuxDoRepository.resolveUserAvatarTemplate(effectiveActorUsername)
            if (!resolved.isNullOrBlank()) {
                effectiveAvatarTemplate = resolved
                avatarResolveDone = true
            }
        }
    }
    val actor = effectiveActorUsername
    val resolvedAvatarTemplate = effectiveAvatarTemplate
    val displayActor = remember(effectiveItem, actor) {
        if (actor != null) {
            effectiveItem.actingUsername
                .takeUnless { UserNotificationItem.CONSOLIDATED_NOTIFICATION_ACTOR_REGEX.matches(it.trim()) }
                ?: actor
        } else {
            ""
        }
    }

    val primaryHeadline = remember(effectiveItem, actor, displayActor, notificationKind) {
        when {
            effectiveItem.isGroupedReply && actor == null ->
                "${effectiveItem.count.coerceAtLeast(2)} 个回复"
            notificationKind == DiscourseNotificationKind.GRANTED_BADGE ->
                "获得徽章"
            actor != null && effectiveItem.count > 1 &&
                (notificationKind == DiscourseNotificationKind.LIKED ||
                    notificationKind == DiscourseNotificationKind.LIKED_CONSOLIDATED) ->
                "$displayActor 等 ${effectiveItem.count} 人"
            actor != null -> displayActor
            else -> "LINUX DO"
        }
    }

    val title = effectiveItem.title.trim()
    val excerpt = effectiveItem.excerpt.takeUnless { it.isBlank() || it.equals(title, ignoreCase = true) }
    val secondaryContent = remember(effectiveItem, notificationKind, title, excerpt) {
        when {
            notificationKind == DiscourseNotificationKind.GRANTED_BADGE ->
                effectiveItem.badgeName.ifBlank { title.ifBlank { effectiveItem.typeLabel } }
            title.isNotBlank() -> title
            excerpt != null -> excerpt
            else -> effectiveItem.typeLabel
        }
    }

    val cornerIcon = remember(effectiveItem) { resolveNotificationCornerBadgeIcon(effectiveItem) }
    val unreadRowTint = if (!effectiveItem.read) {
        if (palette.isDark) Color(0xFF0E7490).copy(alpha = 0.22f) else Color(0xFF0EA5E9).copy(alpha = 0.08f)
    } else {
        Color.Transparent
    }
    val cutoutRingColor = if (!effectiveItem.read) {
        if (palette.isDark) Color(0xFF122B35) else Color(0xFFE6F4FE)
    } else {
        palette.cardSurface
    }

    Row(
        modifier = Modifier.fillMaxWidth()
            .background(unreadRowTint)
            .then(
                if (onClick != null) {
                    Modifier
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(onClick = onClick)
                } else {
                    Modifier
                }
            )
            .semantics { stateDescription = if (effectiveItem.read) LinDoCommonCopy.READ.text else LinDoCommonCopy.UNREAD.text }
            .padding(start = 14.dp, end = 16.dp, top = 11.dp, bottom = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier.padding(top = 1.dp).size(40.dp).then(
                if (actor != null && onInspectUser != null) {
                    Modifier
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClickLabel = LinDoMessagesCopy.INSPECT_USER_PROFILE_LABEL.format(actor),
                            role = Role.Button,
                            onClick = { onInspectUser(actor) }
                        )
                } else Modifier
            ),
            contentAlignment = Alignment.Center
        ) {
            if (actor != null && (!resolvedAvatarTemplate.isNullOrBlank() || !avatarResolveDone)) {
                SmartAvatar(
                    username = actor,
                    avatarTemplate = resolvedAvatarTemplate,
                    animatedAvatar = effectiveItem.actingAnimatedAvatar,
                    primaryGroupName = effectiveItem.actingPrimaryGroupName,
                    size = 38.dp,
                    enableAnimations = false
                )
            } else if (!postAuthorResolveDone) {
                SmartAvatar(
                    username = "L",
                    avatarTemplate = null,
                    animatedAvatar = null,
                    size = 38.dp,
                    enableAnimations = false
                )
            } else {
                Image(
                    painter = painterResource(Res.drawable.lindo_linuxdo_mark),
                    contentDescription = "LINUX DO",
                    modifier = Modifier.size(38.dp).clip(CircleShape)
                )
            }

            // 右上角镂空语义动作角标（对齐官方消息中心：未读天蓝圆底白标，已读深浅自适应切边）
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 3.dp, y = (-2).dp)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(if (!effectiveItem.read) Color(0xFF0EA5E9) else cutoutRingColor)
                    .border(1.5.dp, cutoutRingColor, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = cornerIcon,
                    contentDescription = null,
                    modifier = Modifier.size(10.5.dp),
                    tint = if (!effectiveItem.read) {
                        Color.White
                    } else if (palette.isDark) {
                        Color(0xFFE5E7EB)
                    } else {
                        Color(0xFF4B5563)
                    }
                )
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    primaryHeadline, modifier = Modifier.weight(1f),
                    color = palette.inkPrimary, fontSize = 13.8.sp, lineHeight = 18.5.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                if (item.createdAtText.isNotBlank()) {
                    Text(item.createdAtText, color = palette.inkTertiary, fontSize = 11.2.sp, maxLines = 1)
                }
            }
            if (secondaryContent.isNotBlank()) {
                Text(
                    secondaryContent,
                    color = if (item.read) palette.inkSecondary else palette.inkPrimary,
                    fontSize = 13.6.sp, lineHeight = 19.5.sp,
                    fontWeight = if (item.read) FontWeight.Normal else FontWeight.Medium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
    HorizontalDivider(Modifier.padding(start = 65.dp, end = 16.dp), thickness = 0.5.dp, color = palette.hairline)
}

internal fun resolveNotificationCornerBadgeIcon(item: UserNotificationItem): ImageVector {
    val rx = item.reactionIcon.trim().lowercase()
    when (rx) {
        "rocket" -> return LinDoIcons.BoostRocket
        "+1", "thumbsup", "clap", "tieba_087" -> return LinDoIcons.ThumbUp
        "fire", "flame" -> return LinDoIcons.Flame
        "tada", "confetti_ball", "sparkles" -> return LinDoIcons.Sparkles
        "laughing", "joy", "open_mouth", "hugs", "distorted_face" -> return LinDoIcons.Smile
    }
    val boostKeyword = LinDoNetworkCopy.NOTIF_BOOSTED_POST.text.take(2)
    val isBoostLabel = item.typeLabel.contains(boostKeyword) ||
        item.typeLabel.contains("boost", ignoreCase = true)
    if (isBoostLabel) return LinDoIcons.BoostRocket

    return when (DiscourseNotificationKind.fromCode(item.notificationType)) {
        DiscourseNotificationKind.BOOST_LEGACY,
        DiscourseNotificationKind.BOOST,
        DiscourseNotificationKind.PLUGIN_BOOST -> LinDoIcons.BoostRocket

        DiscourseNotificationKind.LIKED,
        DiscourseNotificationKind.LIKED_CONSOLIDATED,
        DiscourseNotificationKind.REACTION,
        DiscourseNotificationKind.CIRCLE_REACTION,
        DiscourseNotificationKind.PLUGIN_REACTION_EXT -> when {
            item.isGroupedReply -> LinDoIcons.Reply
            item.badgeId > 0 -> LinDoIcons.Trophy
            else -> LinDoIcons.HeartFilled
        }

        DiscourseNotificationKind.REPLIED,
        DiscourseNotificationKind.POSTED,
        DiscourseNotificationKind.FOLLOWING_REPLIED,
        DiscourseNotificationKind.PLUGIN_FOLLOW_POST,
        DiscourseNotificationKind.WATCHING_FIRST_POST,
        DiscourseNotificationKind.FOLLOWING_CREATED_TOPIC,
        DiscourseNotificationKind.PLUGIN_FOLLOW_TOPIC -> LinDoIcons.Reply

        DiscourseNotificationKind.QUOTED,
        DiscourseNotificationKind.CHAT_QUOTED -> LinDoIcons.Quote

        DiscourseNotificationKind.MENTIONED,
        DiscourseNotificationKind.GROUP_MENTIONED,
        DiscourseNotificationKind.CHAT_MENTION,
        DiscourseNotificationKind.CHAT_GROUP_MENTION -> LinDoIcons.AtSign

        DiscourseNotificationKind.PRIVATE_MESSAGE,
        DiscourseNotificationKind.INVITED_TO_PRIVATE_MESSAGE,
        DiscourseNotificationKind.GROUP_MESSAGE_SUMMARY -> LinDoIcons.Inbox

        DiscourseNotificationKind.GRANTED_BADGE -> LinDoIcons.Trophy

        DiscourseNotificationKind.WATCHING_CATEGORY_OR_TAG,
        DiscourseNotificationKind.PLUGIN_FOLLOWED -> LinDoIcons.User

        else -> resolveNotificationIcon(item)
    }
}

internal fun resolveNotificationIcon(item: UserNotificationItem): ImageVector {
    val boostKeyword = LinDoNetworkCopy.NOTIF_BOOSTED_POST.text.take(2)
    val isBoostLabel = item.typeLabel.contains(boostKeyword) ||
        item.typeLabel.contains("boost", ignoreCase = true)
    return when (DiscourseNotificationKind.fromCode(item.notificationType)) {
        DiscourseNotificationKind.WATCHING_CATEGORY_OR_TAG,
        DiscourseNotificationKind.PLUGIN_FOLLOWED -> LinDoIcons.User

        DiscourseNotificationKind.WATCHING_FIRST_POST,
        DiscourseNotificationKind.FOLLOWING_CREATED_TOPIC,
        DiscourseNotificationKind.PLUGIN_FOLLOW_TOPIC -> LinDoIcons.FileText

        DiscourseNotificationKind.FOLLOWING_REPLIED,
        DiscourseNotificationKind.PLUGIN_FOLLOW_POST -> LinDoIcons.Reply

        DiscourseNotificationKind.BOOST_LEGACY,
        DiscourseNotificationKind.BOOST,
        DiscourseNotificationKind.PLUGIN_BOOST -> LinDoIcons.BoostRocket

        DiscourseNotificationKind.MENTIONED,
        DiscourseNotificationKind.REPLIED,
        DiscourseNotificationKind.QUOTED,
        DiscourseNotificationKind.PRIVATE_MESSAGE,
        DiscourseNotificationKind.POSTED,
        DiscourseNotificationKind.CHAT_MENTION,
        DiscourseNotificationKind.CHAT_MESSAGE,
        DiscourseNotificationKind.CHAT_QUOTED ->
            if (isBoostLabel) LinDoIcons.BoostRocket else LinDoIcons.MessageCircle

        DiscourseNotificationKind.GRANTED_BADGE -> when {
            isBoostLabel -> LinDoIcons.BoostRocket
            item.isGroupedReply -> LinDoIcons.MessageCircle
            else -> LinDoIcons.AwardBadge
        }

        DiscourseNotificationKind.LIKED,
        DiscourseNotificationKind.LIKED_CONSOLIDATED,
        DiscourseNotificationKind.REACTION -> when {
            isBoostLabel -> LinDoIcons.BoostRocket
            item.isGroupedReply -> LinDoIcons.MessageCircle
            item.badgeId > 0 -> LinDoIcons.AwardBadge
            else -> LinDoIcons.Heart
        }

        DiscourseNotificationKind.EDITED,
        DiscourseNotificationKind.INVITED_TO_PRIVATE_MESSAGE,
        DiscourseNotificationKind.INVITEE_ACCEPTED,
        DiscourseNotificationKind.MOVED_POST,
        DiscourseNotificationKind.LINKED,
        DiscourseNotificationKind.INVITED_TO_TOPIC,
        DiscourseNotificationKind.CUSTOM,
        DiscourseNotificationKind.GROUP_MENTIONED,
        DiscourseNotificationKind.GROUP_MESSAGE_SUMMARY,
        DiscourseNotificationKind.TOPIC_REMINDER,
        DiscourseNotificationKind.POST_APPROVED,
        DiscourseNotificationKind.CODE_REVIEW_COMMIT_APPROVED,
        DiscourseNotificationKind.MEMBERSHIP_REQUEST_ACCEPTED,
        DiscourseNotificationKind.MEMBERSHIP_REQUEST_CONSOLIDATED,
        DiscourseNotificationKind.BOOKMARK_REMINDER,
        DiscourseNotificationKind.VOTES_RELEASED,
        DiscourseNotificationKind.EVENT_REMINDER,
        DiscourseNotificationKind.EVENT_INVITATION,
        DiscourseNotificationKind.CHAT_INVITATION,
        DiscourseNotificationKind.CHAT_GROUP_MENTION,
        DiscourseNotificationKind.ASSIGNED,
        DiscourseNotificationKind.CIRCLE_REACTION,
        DiscourseNotificationKind.PLUGIN_REACTION_EXT,
        DiscourseNotificationKind.UNKNOWN -> when {
            isBoostLabel -> LinDoIcons.BoostRocket
            item.isGroupedReply -> LinDoIcons.MessageCircle
            item.badgeId > 0 -> LinDoIcons.AwardBadge
            else -> LinDoIcons.Bell
        }
    }
}
