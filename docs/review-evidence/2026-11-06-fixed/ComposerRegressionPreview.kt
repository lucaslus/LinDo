package do_.linux.lindo.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import do_.linux.lindo.core.compose.*
import do_.linux.lindo.core.model.*
import do_.linux.lindo.core.network.*
import kotlinx.coroutines.delay

/** Invoked only by the Android debuggable preview gate. No request reaches a real server. */
@Composable
fun LinDoComposerRegressionPreview(scene: String, onClose: () -> Unit) {
    val owner = "preview-fixture"
    var opened by remember(scene) { mutableStateOf(true) }
    var outcome by remember(scene) { mutableStateOf("编辑器已打开") }
    var published by remember { mutableIntStateOf(0) }
    var deleted by remember { mutableIntStateOf(0) }
    var archived by remember { mutableIntStateOf(0) }
    var draftSequence by remember { mutableIntStateOf(2) }
    var cloudDraft by remember { mutableStateOf<String?>(null) }
    val fixturePost = remember { PostItemModel(900002, 2, 900001, owner, cookedHtml = "<p><strong>original markdown from server</strong></p>", canEdit = true, canDelete = true, topicId = 900001) }
    val topic = remember { TopicSummary(900001, "创作与确认操作回归", originalPoster = TopicUser(900001, owner)) }
    var detail by remember { mutableStateOf(TopicDetailModel(900001, topic.title, "技术交流", posts = listOf(
        PostItemModel(900001, 1, 900001, owner, cookedHtml = "<p>话题正文</p>", raw = "话题正文", topicId = 900001), fixturePost), streamIds = listOf(900001, 900002), postsCount = 2)) }
    DisposableEffect(scene) {
        val previous = UserApiKeyAuthManager.webViewHttpExecutor
        UserApiKeyAuthManager.webViewHttpExecutor = { url, method, form, _ ->
            when {
                url.contains("/posts/900002.json") && method == "GET" -> {
                    delay(1500)
                    200 to """{"id":900002,"topic_id":900001,"post_number":2,"username":"preview-fixture","raw":"**original markdown from server**","cooked":"<p><strong>original markdown from server</strong></p>"}"""
                }
                url.contains("/posts/") && method == "DELETE" -> { deleted++; 204 to "" }
                url.contains("archive-message") -> { archived++; 204 to "" }
                url.contains("/posts") && method in setOf("POST", "PUT") -> {
                    published++
                    delay(700)
                    200 to """{"id":900003,"topic_id":900001,"post_number":3,"username":"preview-fixture","raw":"fixture body","cooked":"<p>已提交的示例内容</p>"}"""
                }
                url.contains("/tags/filter/") -> 200 to """{"results":[{"name":"人工智能"},{"name":"快问快答"}]}"""
                url.contains("/site.json") -> 200 to """{"categories":[]}"""
                url.endsWith("/drafts.json") && method == "GET" -> 200 to """{"drafts":[]}"""
                url.contains("/drafts") && method == "POST" -> { draftSequence++; cloudDraft = form?.get("data"); 200 to """{"draft_sequence":$draftSequence}""" }
                url.contains("/drafts") && method == "DELETE" -> { deleted++; cloudDraft = null; 200 to """{"success":true}""" }
                url.contains("/drafts") -> 200 to """{"draft":${cloudDraft?.let { kotlinx.serialization.json.JsonPrimitive(it).toString() } ?: "null"},"draft_sequence":$draftSequence}"""
                url.contains("/private-messages/") -> 200 to """{"users":[],"topic_list":{"topics":[{"id":900001,"title":"归档确认示例","posts_count":2,"category_id":4}]}}"""
                else -> 503 to """{"errors":["本地回归场景未提供此数据"]}"""
            }
        }
        onDispose { UserApiKeyAuthManager.webViewHttpExecutor = previous }
    }
    val dismiss = { opened = false; outcome = "已退出，草稿保留" }
    CompositionLocalProvider(LocalCurrentAccountUsername provides owner) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    scene == "composer-reader" -> CommunityTopicReaderPane(topic, detail, isLoading = false,
                        enableAnimations = false, showBackButton = true, onBack = onClose, onRefresh = {},
                        onDetailUpdated = { detail = it }, onInspectBadge = {}, onInspectUser = {}, onRequestLogin = {})
                    scene == "composer-messages" -> UserPrivateMessagesSheet(owner, null, onClose, {})
                    opened && scene == "composer-drafts" -> UserDraftsSheet(dismiss, {}, accountUsername = owner)
                    opened -> ComposerSheet(
                        action = when (scene) { "composer-new" -> ComposerAction.NEW_TOPIC; "composer-edit" -> ComposerAction.EDIT; else -> ComposerAction.REPLY },
                        topicId = 900001, topicTitle = topic.title, accountUsername = owner,
                        post = if (scene == "composer-edit") fixturePost else null,
                        replyToPostNumber = if (scene == "composer-reply") 2 else null,
                        replyToUsername = if (scene == "composer-reply") owner else null,
                        onDismiss = dismiss, onPublished = { opened = false; outcome = "提交已确认" })
                    else -> Column(Modifier.padding(16.dp)) {
                        Text(outcome)
                        TextButton(onClick = { opened = true }) { Text("重新打开编辑器") }
                        TextButton(onClick = onClose) { Text("结束预览") }
                    }
                }
            }
            Text("回归请求 发布=$published 删除=$deleted 归档=$archived", Modifier.fillMaxWidth().padding(8.dp).navigationBarsPadding(), fontSize = 12.sp)
        }
    }
}
