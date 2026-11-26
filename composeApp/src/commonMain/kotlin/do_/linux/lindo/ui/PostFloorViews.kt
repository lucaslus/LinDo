package do_.linux.lindo.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import do_.linux.lindo.core.copy.GuestGateScenario
import do_.linux.lindo.core.copy.LinDoCommonCopy
import do_.linux.lindo.core.copy.LinDoReaderCopy
import do_.linux.lindo.core.copy.SmallActionKind
import do_.linux.lindo.core.copy.isSiteVerificationError
import do_.linux.lindo.core.model.BoostInputStats
import do_.linux.lindo.core.model.PostItemModel
import do_.linux.lindo.core.model.TopicDetailModel
import do_.linux.lindo.core.network.LinuxDoRepository
import do_.linux.lindo.core.network.UserApiKeyAuthManager
import do_.linux.lindo.core.render.postPreviewText
import do_.linux.lindo.core.site.LinuxDoEmojiCatalog
import do_.linux.lindo.ui.components.LinDoIcons
import do_.linux.lindo.ui.components.LocalRequestGuestLogin
import do_.linux.lindo.ui.components.SmartAvatar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Merges a MessageBus `/topic/{id}` refresh into the active `TopicDetailModel` without wiping
 * floors the user already scrolled/jumped to beyond page 1.
 */
internal fun mergeMessageBusTopicRefresh(
    current: TopicDetailModel,
    refreshed: TopicDetailModel,
    fetchedTailPosts: List<PostItemModel> = emptyList()
): TopicDetailModel {
    val validStreamSet = refreshed.streamIds.takeIf { it.isNotEmpty() }?.toHashSet()
    val mergedById = LinkedHashMap<Int, PostItemModel>()
    current.posts.forEach { p ->
        if (validStreamSet == null || p.id in validStreamSet) {
            mergedById[p.id] = p
        }
    }
    refreshed.posts.forEach { p -> mergedById[p.id] = p }
    fetchedTailPosts.forEach { p -> mergedById[p.id] = p }
    val mergedPosts = mergedById.values.sortedBy { it.postNumber }
    val mergedStreamIds = if (refreshed.streamIds.isNotEmpty()) refreshed.streamIds else current.streamIds
    return refreshed.copy(
        posts = mergedPosts,
        streamIds = mergedStreamIds
    )
}

@Composable
internal fun PostSmallActionTimelineStrip(
    post: PostItemModel,
    primaryName: String,
    onInspectUser: (String) -> Unit
) {
    val palette = LocalPalette.current
    val actionLabel = remember(post.actionCode, post.cookedHtml) {
        val code = post.actionCode.orEmpty().trim()
        val localized = if (code.isNotEmpty()) SmallActionKind.localize(code) else ""
        val bodyPlain = postPreviewText(post.cookedHtml).trim()
        when {
            localized.isNotEmpty() && bodyPlain.isNotEmpty() && !bodyPlain.equals(localized, ignoreCase = true) ->
                "$localized · $bodyPlain"
            localized.isNotEmpty() -> localized
            bodyPlain.isNotEmpty() -> bodyPlain
            else -> "系统记录"
        }
    }
    val actionIcon = remember(post.actionCode) {
        val code = post.actionCode.orEmpty().lowercase()
        when {
            code.contains("closed") || code.contains("archived") -> LinDoIcons.Lock
            code.contains("pinned") -> LinDoIcons.BookmarkFilled
            code.contains("invited") || code.contains("removed") -> LinDoIcons.Users
            else -> LinDoIcons.InfoCircle
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("post_small_action_strip_${post.postNumber}")
            .background(palette.subtleSurface.copy(alpha = 0.55f))
            .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = actionIcon,
            contentDescription = null,
            modifier = Modifier.size(13.5.dp),
            tint = palette.inkTertiary
        )
        SmartAvatar(
            username = post.username,
            avatarTemplate = post.avatarTemplate,
            animatedAvatar = null,
            primaryGroupName = post.primaryGroupName,
            flair = null,
            size = 18.dp,
            enableAnimations = false
        )
        Text(
            text = primaryName,
            fontSize = 11.8.sp,
            fontWeight = FontWeight.Medium,
            color = palette.inkSecondary,
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable { onInspectUser(post.username) }
        )
        Text(
            text = actionLabel,
            fontSize = 11.8.sp,
            color = palette.inkTertiary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (post.createdAtText.isNotBlank()) {
            Text(
                text = post.createdAtText,
                fontSize = 11.sp,
                color = palette.inkTertiary,
                maxLines = 1,
                softWrap = false
            )
        }
    }
    HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)
}

@Composable
internal fun PostDeletedTombstoneRow(
    post: PostItemModel,
    primaryName: String,
    isOwnPost: Boolean,
    onInspectUser: (String) -> Unit,
    onEditPost: (PostItemModel) -> Unit
) {
    val palette = LocalPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.cardSurface)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .graphicsLayer { alpha = 0.55f }
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onInspectUser(post.username) }
        ) {
            SmartAvatar(
                username = post.username,
                avatarTemplate = post.avatarTemplate,
                animatedAvatar = null,
                primaryGroupName = post.primaryGroupName,
                flair = null,
                size = 20.dp,
                enableAnimations = false
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = primaryName,
            fontSize = 12.2.sp,
            fontWeight = FontWeight.Medium,
            color = palette.inkTertiary,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable { onInspectUser(post.username) }
                .padding(horizontal = 3.dp, vertical = 1.dp)
        )
        Text(
            text = LinDoReaderCopy.POST_DELETED_BY_AUTHOR_SUFFIX.text,
            fontSize = 12.sp,
            color = palette.inkTertiary,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.weight(1f)
        )
        if (isOwnPost && post.canEdit) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable { onEditPost(post) },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = LinDoIcons.Edit,
                    contentDescription = LinDoCommonCopy.EDIT.text,
                    modifier = Modifier.size(14.dp),
                    tint = palette.inkSecondary
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
        }
        Text(
            text = buildString {
                if (post.createdAtText.isNotBlank()) {
                    append(post.createdAtText)
                    append(" ")
                }
                if (post.postNumber > 1) {
                    append(post.postNumber)
                    append(LinDoReaderCopy.FLOOR_SUFFIX.text)
                }
            },
            fontSize = 11.2.sp,
            color = palette.inkTertiary,
            maxLines = 1,
            softWrap = false
        )
    }
    HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun PostInlineBoostComposerPanel(
    postId: Int,
    visible: Boolean,
    boostInputText: String,
    onBoostInputChange: (String) -> Unit,
    showBoostEmojiPicker: Boolean,
    onToggleEmojiPicker: () -> Unit,
    selectedBoostEmojiGroupKey: String,
    onSelectEmojiGroupKey: (String) -> Unit,
    isSubmittingBoost: Boolean,
    floorActionError: String?,
    onOpenWebUrl: (String) -> Unit,
    onClearPendingAfterVerifyIfChanged: (String) -> Unit,
    onSubmitBoost: (String) -> Unit,
    onClose: () -> Unit,
    onAppendEmoji: (String) -> Unit
) {
    val palette = LocalPalette.current
    AnimatedVisibility(visible = visible) {
        val boostStats = remember(boostInputText) { BoostInputStats.compute(boostInputText) }
        val emojiCatalogVer = LinuxDoEmojiCatalog.catalogVersion
        val pickerGroups = remember(emojiCatalogVer) { LinuxDoEmojiCatalog.getPickerGroups() }
        val safeGroupIdx = remember(pickerGroups, selectedBoostEmojiGroupKey) {
            pickerGroups.indexOfFirst {
                it.id == selectedBoostEmojiGroupKey ||
                    it.iconKey == selectedBoostEmojiGroupKey ||
                    it.labelZh == selectedBoostEmojiGroupKey
            }.let { if (it >= 0) it else 0 }
        }
        val boostBringIntoViewRequester = remember { BringIntoViewRequester() }
        val boostFocusRequester = remember { FocusRequester() }
        val imeBottomPx = WindowInsets.ime.getBottom(LocalDensity.current)

        LaunchedEffect(Unit) {
            if (UserApiKeyAuthManager.isAuthorized() &&
                (!do_.linux.lindo.core.network.CfClearanceAuthority.hasHealthyIncumbent() ||
                    do_.linux.lindo.core.network.NetworkPolicyEngine.backgroundChallengeTracker.hasUnresolvedWriteChallenges())
            ) {
                runCatching { UserApiKeyAuthManager.onRequestClearancePrewarm?.invoke("composer_open") }
            }
            if (!floorActionError.isSiteVerificationError() && !PlatformSessionRecovery.verifying) {
                delay(60L)
                runCatching { boostFocusRequester.requestFocus() }
                delay(100L)
                runCatching { boostBringIntoViewRequester.bringIntoView() }
            }
        }

        LaunchedEffect(imeBottomPx, showBoostEmojiPicker, floorActionError) {
            if (imeBottomPx > 0 || showBoostEmojiPicker || floorActionError != null) {
                runCatching { boostBringIntoViewRequester.bringIntoView() }
                delay(120L)
                runCatching { boostBringIntoViewRequester.bringIntoView() }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .bringIntoViewRequester(boostBringIntoViewRequester)
                .padding(bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val counterOverLimit = boostStats.exceedsLength || boostStats.exceedsEmojiLimit
            val canSendNow = boostStats.isValid && !isSubmittingBoost && !floorActionError.isSiteVerificationError() && !PlatformSessionRecovery.verifying
            if (counterOverLimit) {
                Text(
                    if (boostStats.exceedsEmojiLimit) {
                        LinDoReaderCopy.BOOST_LIMIT_EMOJIS_TAG.format(BoostInputStats.MAX_BOOST_EMOJI_COUNT)
                    } else {
                        LinDoReaderCopy.BOOST_LIMIT_CHARS_TAG.format(BoostInputStats.MAX_BOOST_VISIBLE_LENGTH)
                    },
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.5.sp
                )
            }

            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(palette.subtleSurface)
                    .border(
                        0.6.dp,
                        if (counterOverLimit) Color(0xFFEF4444) else palette.hairline,
                        RoundedCornerShape(10.dp)
                    )
            ) {
                if (floorActionError.isSiteVerificationError()) {
                    VerificationInlineStatus(
                        onVerify = { onOpenWebUrl("https://linux.do/challenge#lindo-verify") },
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                    HorizontalDivider(thickness = 0.5.dp, color = palette.hairline)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (showBoostEmojiPicker) palette.activeChipBg else Color.Transparent)
                            .clickable { onToggleEmojiPicker() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = LinDoIcons.Smile,
                            contentDescription = LinDoReaderCopy.SELECT_EMOJI_DESC.text,
                            modifier = Modifier.size(17.5.dp),
                            tint = if (showBoostEmojiPicker) palette.brandGold else palette.inkSecondary
                        )
                    }

                    val boostInputStyle = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, color = palette.inkPrimary)
                    var boostFieldValue by remember(postId) {
                        mutableStateOf(TextFieldValue(boostInputText, TextRange(boostInputText.length)))
                    }
                    val syncedBoostFieldValue = if (boostFieldValue.text == boostInputText) {
                        boostFieldValue
                    } else {
                        TextFieldValue(boostInputText, TextRange(boostInputText.length))
                    }
                    BasicTextField(
                        value = syncedBoostFieldValue,
                        onValueChange = { next ->
                            do_.linux.lindo.core.network.NetworkPolicyEngine.noteUserTyping(
                                imeComposing = next.composition != null
                            )
                            val normalizedValue = ImeInputGuard.normalizeSingleLine(next)
                            boostFieldValue = normalizedValue
                            onBoostInputChange(normalizedValue.text)
                            onClearPendingAfterVerifyIfChanged(normalizedValue.text.trim())
                        },
                        singleLine = true,
                        textStyle = boostInputStyle,
                        cursorBrush = SolidColor(palette.brandGold),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            if (canSendNow && ImeInputGuard.canSubmitAction(syncedBoostFieldValue)) {
                                onSubmitBoost(boostInputText)
                            }
                        }),
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 4.dp)
                            .focusRequester(boostFocusRequester),
                        decorationBox = { inner ->
                            LinDoInputDecoration(
                                boostInputText.isEmpty(),
                                LinDoReaderCopy.BOOST_INPUT_PLACEHOLDER.text,
                                boostInputStyle,
                                palette.inkSecondary,
                                modifier = Modifier.fillMaxWidth(),
                                innerTextField = inner
                            )
                        }
                    )

                    Text(
                        text = "${boostStats.visibleLength}/${BoostInputStats.MAX_BOOST_VISIBLE_LENGTH}",
                        fontSize = 12.sp,
                        fontWeight = if (counterOverLimit) FontWeight.Bold else FontWeight.Medium,
                        color = if (counterOverLimit) Color(0xFFEF4444) else palette.inkTertiary,
                        maxLines = 1
                    )

                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (canSendNow) palette.activeChipBg else Color.Transparent)
                            .clickable(enabled = canSendNow) { onSubmitBoost(boostInputText) },
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSubmittingBoost) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                color = palette.brandGold,
                                strokeWidth = 1.8.dp
                            )
                        } else {
                            Icon(
                                imageVector = LinDoIcons.Send,
                                contentDescription = LinDoReaderCopy.SEND_ROCKET_DESC.text,
                                modifier = Modifier.size(16.dp),
                                tint = if (canSendNow) palette.brandGold else palette.inkTertiary.copy(alpha = 0.38f)
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .clickable { onClose() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = LinDoIcons.Close,
                            contentDescription = LinDoReaderCopy.COLLAPSE_ROCKET_DESC.text,
                            modifier = Modifier.size(14.dp),
                            tint = palette.inkTertiary
                        )
                    }
                }
            }

            if (boostInputText.contains(":")) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier.padding(horizontal = 6.dp)
                ) {
                    Text(
                        text = LinDoReaderCopy.PREVIEW_PREFIX.text,
                        fontSize = 12.sp,
                        color = palette.inkTertiary
                    )
                    BoostInlineContent(
                        plainText = boostInputText.trim(),
                        fontSize = 12.2.sp,
                        emojiSize = 15.dp,
                        textColor = palette.inkPrimary
                    )
                }
            }

            AnimatedVisibility(visible = showBoostEmojiPicker && pickerGroups.isNotEmpty()) {
                val activeGroup = pickerGroups.getOrNull(safeGroupIdx)
                if (activeGroup != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(palette.subtleSurface)
                            .border(0.5.dp, palette.hairline, RoundedCornerShape(12.dp))
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        LinDoEmojiGroupTabs(pickerGroups, safeGroupIdx, fontSize = 12.sp) { idx ->
                            pickerGroups.getOrNull(idx)?.let { grp -> onSelectEmojiGroupKey(grp.id) }
                        }
                        FlowRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 160.dp, max = 220.dp)
                                .verticalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            activeGroup.items.forEach { emojiItem ->
                                val token = emojiItem.unicode ?: emojiItem.markdownCode
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .clickable { onAppendEmoji(token) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    PostReactionGlyph(
                                        reactionId = emojiItem.shortcode,
                                        emojiFallback = emojiItem.unicode ?: emojiItem.markdownCode,
                                        isSelected = false,
                                        size = 19.dp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun PostInlineChildRepliesSection(
    post: PostItemModel,
    childReplies: List<PostItemModel>,
    showInlineReplies: Boolean,
    opUsername: String,
    enableAnimations: Boolean,
    onJumpToFloorNumber: (Int) -> Unit,
    onInspectUser: (String) -> Unit,
    onReplyFloor: (PostItemModel) -> Unit,
    onRequestLogin: () -> Unit
) {
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    val requestGuestLogin = LocalRequestGuestLogin.current

    var remoteChildReplies by remember(post.id) { mutableStateOf<List<PostItemModel>?>(null) }
    var loadingRemoteReplies by remember(post.id) { mutableStateOf(false) }
    var inlineLikedChildOverrides by remember(post.id) { mutableStateOf<Map<Int, Boolean>>(emptyMap()) }

    LaunchedEffect(showInlineReplies, post.id) {
        if (showInlineReplies && remoteChildReplies == null && post.id > 0) {
            loadingRemoteReplies = true
            val fetched = runCatching { LinuxDoRepository.fetchPostReplies(post.id, post.topicId) }.getOrNull()
            if (!fetched.isNullOrEmpty()) {
                remoteChildReplies = fetched
            }
            loadingRemoteReplies = false
        }
    }

    val mergedChildReplies = remember(childReplies, remoteChildReplies) {
        val remote = remoteChildReplies.orEmpty()
        if (remote.isEmpty()) childReplies
        else (childReplies + remote).distinctBy { it.id }.sortedBy { it.postNumber }
    }

    AnimatedVisibility(visible = showInlineReplies && (mergedChildReplies.isNotEmpty() || loadingRemoteReplies)) {
        val threadLineColor = if (palette.isDark) {
            palette.hairline.copy(alpha = 0.95f)
        } else {
            palette.inkTertiary.copy(alpha = 0.24f)
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("post_child_replies_list_${post.postNumber}")
                .padding(top = 2.dp, bottom = 6.dp)
                .drawBehind {
                    val strokeW = 1.5.dp.toPx()
                    val x = 8.dp.toPx()
                    val topY = 2.dp.toPx()
                    val bottomY = (size.height - 2.dp.toPx()).coerceAtLeast(topY + strokeW)
                    drawRoundRect(
                        color = threadLineColor,
                        topLeft = Offset(x, topY),
                        size = androidx.compose.ui.geometry.Size(strokeW, bottomY - topY),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(strokeW / 2f, strokeW / 2f)
                    )
                }
                .padding(start = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (loadingRemoteReplies && mergedChildReplies.isEmpty()) {
                Text("正在加载回复…", fontSize = 12.sp, color = palette.inkTertiary, modifier = Modifier.padding(vertical = 4.dp))
            }
            mergedChildReplies.forEachIndexed { cIdx, child ->
                if (cIdx > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 35.dp),
                        thickness = 0.5.dp,
                        color = palette.hairline.copy(alpha = 0.6f)
                    )
                }
                val childCleanText = remember(child.cookedHtml) { postPreviewText(child.cookedHtml) }
                val childIsOp = child.isTopicOwner || child.username.equals(opUsername, ignoreCase = true)
                val childFloorMeta = remember(child.postNumber, child.createdAtText) {
                    listOfNotNull(
                        child.postNumber.takeIf { it > 0 }?.let { LinDoReaderCopy.FLOOR_LABEL.format(it) },
                        child.createdAtText.takeIf { it.isNotBlank() }
                    ).joinToString(" · ")
                }
                val childLiked = inlineLikedChildOverrides[child.id] ?: child.likedByMe
                val likesDelta = when {
                    childLiked && !child.likedByMe -> 1
                    !childLiked && child.likedByMe -> -1
                    else -> 0
                }
                val displayLikes = (child.likeCount + likesDelta).coerceAtLeast(0)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(
                            onClickLabel = LinDoReaderCopy.JUMP_TO_FLOOR_LABEL.format(child.postNumber),
                            onClick = { onJumpToFloorNumber(child.postNumber) }
                        )
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Box(
                        modifier = Modifier
                            .padding(top = 1.5.dp)
                            .pointerHoverIcon(PointerIcon.Hand)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { onInspectUser(child.username) }
                    ) {
                        SmartAvatar(
                            username = child.username,
                            avatarTemplate = child.avatarTemplate,
                            animatedAvatar = child.animatedAvatar,
                            primaryGroupName = child.primaryGroupName,
                            flair = child.flair,
                            size = 28.dp,
                            enableAnimations = enableAnimations
                        )
                    }
                    Spacer(modifier = Modifier.width(9.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.5.dp)
                            ) {
                                Text(
                                    text = child.displayName?.takeIf { it.isNotBlank() } ?: child.username,
                                    fontSize = 13.sp,
                                    lineHeight = 16.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = palette.inkPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                if (childIsOp) {
                                    Text(
                                        text = LinDoReaderCopy.OP_BADGE.text,
                                        fontSize = 10.5.sp,
                                        lineHeight = 13.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = palette.brandGold,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                if (childFloorMeta.isNotEmpty()) {
                                    Text(
                                        text = childFloorMeta,
                                        fontSize = 12.sp,
                                        lineHeight = 14.sp,
                                        color = palette.inkTertiary,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                                Icon(
                                    imageVector = LinDoIcons.ChevronRight,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.5.dp),
                                    tint = palette.inkTertiary
                                )
                            }
                        }
                        if (childCleanText.isNotBlank()) {
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = childCleanText,
                                fontSize = 13.8.sp,
                                lineHeight = 20.5.sp,
                                color = palette.inkPrimary.copy(alpha = 0.88f),
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Row(
                            modifier = Modifier.padding(top = 5.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier
                                    .testTag("child_reply_like_btn_${child.id}")
                                    .clip(RoundedCornerShape(6.dp))
                                    .pointerHoverIcon(PointerIcon.Hand)
                                    .clickable {
                                        if (!UserApiKeyAuthManager.isAuthorized()) {
                                            requestGuestLogin?.invoke(
                                                GuestGateScenario.REACTION.titleCopy.text,
                                                GuestGateScenario.REACTION.subtitleCopy.text
                                            ) ?: onRequestLogin()
                                            return@clickable
                                        }
                                        val prevOverride = inlineLikedChildOverrides[child.id]
                                        val willLike = !childLiked
                                        inlineLikedChildOverrides = inlineLikedChildOverrides + (child.id to willLike)
                                        scope.launch {
                                            val ok = runCatching {
                                                LinuxDoRepository.togglePostReaction(child.id, "heart")
                                            }.getOrDefault(false)
                                            if (!ok) {
                                                inlineLikedChildOverrides = if (prevOverride == null) {
                                                    inlineLikedChildOverrides - child.id
                                                } else {
                                                    inlineLikedChildOverrides + (child.id to prevOverride)
                                                }
                                            }
                                        }
                                    }
                                    .padding(horizontal = 3.dp, vertical = 1.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = if (childLiked) LinDoIcons.HeartFilled else LinDoIcons.Heart,
                                    contentDescription = "点赞回复",
                                    modifier = Modifier.size(13.dp),
                                    tint = if (childLiked) Color(0xFFE11D48) else palette.inkTertiary
                                )
                                Text(
                                    text = if (displayLikes > 0) "$displayLikes" else "赞",
                                    fontSize = 11.5.sp,
                                    color = if (childLiked) Color(0xFFE11D48) else palette.inkTertiary
                                )
                            }
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .pointerHoverIcon(PointerIcon.Hand)
                                    .clickable { onReplyFloor(child) }
                                    .padding(horizontal = 3.dp, vertical = 1.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Icon(
                                    imageVector = LinDoIcons.Reply,
                                    contentDescription = "回复",
                                    modifier = Modifier.size(12.5.dp),
                                    tint = palette.inkTertiary
                                )
                                Text("回复", fontSize = 11.5.sp, color = palette.inkTertiary)
                            }
                        }
                    }
                }
            }
        }
    }
}
