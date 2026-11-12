package do_.linux.lindo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
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
            .padding(start = 12.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier.size(48.dp).then(
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
