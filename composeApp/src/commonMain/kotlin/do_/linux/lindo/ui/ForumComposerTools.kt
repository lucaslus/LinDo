package do_.linux.lindo.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import do_.linux.lindo.core.compose.*
import do_.linux.lindo.core.model.TopicDetailModel
import do_.linux.lindo.core.network.LinuxDoRepository
import do_.linux.lindo.core.network.UserApiKeyAuthManager
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException

internal data class ForumComposerContext(
    val detail: TopicDetailModel? = null,
    val topicId: Int? = null,
    val topicTitle: String = "",
    val quoteFloor: Int? = null,
    val replyUsername: String? = null,
    val replyName: String? = null,
    val firstPost: Boolean = false
) {
    fun variables(): Map<String, String?> {
        val profile = UserApiKeyAuthManager.currentUser
        val url = topicId?.let { "https://linux.do/t/$it" }
        return mapOf(
            "my_username" to profile?.username, "my_name" to profile?.displayName,
            "context_title" to topicTitle, "topic_title" to topicTitle, "context_url" to url, "topic_url" to url,
            "original_poster_username" to detail?.originalPosterUsername, "original_poster_name" to detail?.originalPosterDisplayName,
            "reply_to_username" to replyUsername, "reply_to_name" to replyName, "last_poster_username" to detail?.lastPosterUsername,
            "reply_to_or_last_poster_username" to (replyUsername ?: detail?.lastPosterUsername)
        )
    }

    suspend fun quote(): String {
        val id = requireNotNull(topicId)
        val floor = requireNotNull(quoteFloor)
        val post = detail?.posts?.find { it.postNumber == floor }
            ?: LinuxDoRepository.fetchTopicDetail(id, trackVisit = false, targetPostNumber = floor).posts.find { it.postNumber == floor }
            ?: error("未找到要引用的楼层")
        val raw = post.raw?.takeIf { it.isNotBlank() } ?: LinuxDoRepository.fetchPostRawMarkdown(post.id, id, floor)
            ?: error("读取原帖失败")
        return "[quote=\"${post.username}, post:${post.postNumber}, topic:$id\"]\n$raw\n[/quote]"
    }
}

@Composable
internal fun ForumTemplatePicker(
    onInsert: (ForumTemplate) -> Unit,
    load: suspend () -> List<ForumTemplate> = LinuxDoRepository::fetchForumTemplates
) {
    val owner = UserApiKeyAuthManager.currentUser?.username
    var query by remember(owner) { mutableStateOf("") }
    var attempt by remember(owner) { mutableIntStateOf(0) }
    var templates by remember(owner, attempt) { mutableStateOf<List<ForumTemplate>?>(null) }
    var error by remember(owner, attempt) { mutableStateOf<String?>(null) }
    LaunchedEffect(owner, attempt) {
        try { templates = load() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (e: Exception) { error = e.message ?: "模板加载失败" }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ForumToolField("搜索模板", query, { query = it })
        val found = templates?.filter { it.matches(query) }
        when {
            error != null -> { Text(error.orEmpty()); TextButton(onClick = { attempt++ }) { Text("重试") } }
            found == null -> Text("正在加载模板…")
            found.isEmpty() -> Text(if (templates.isNullOrEmpty()) "当前账号没有可用模板" else "没有匹配的模板")
            else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                items(found, key = { it.id }) { template ->
                    Column(Modifier.fillMaxWidth().clickable { onInsert(template) }.padding(vertical = 10.dp)) {
                        Text(template.title, color = LocalPalette.current.inkPrimary)
                        Text(template.content, color = LocalPalette.current.inkSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun ForumToolField(label: String, value: String, change: (String) -> Unit, multiline: Boolean = false, modifier: Modifier = Modifier.fillMaxWidth()) {
    val p = LocalPalette.current
    OutlinedTextField(value = value, onValueChange = change, label = { Text(label) }, singleLine = !multiline,
        maxLines = if (multiline) 4 else 1, modifier = modifier,
        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = p.inkPrimary, unfocusedTextColor = p.inkPrimary,
            focusedBorderColor = p.activeChipText, unfocusedBorderColor = p.hairline,
            focusedLabelColor = p.activeChipText, unfocusedLabelColor = p.inkSecondary, cursorColor = p.activeChipText))
}

@Composable
internal fun ForumInsertionPanel(panel: WorkbenchSubPanel, selected: String, context: ForumComposerContext,
                                onTemplate: (ForumTemplate) -> Unit, onInsert: (String, Boolean) -> Unit) {
    val insert by rememberUpdatedState(onInsert)
    when (panel) {
        WorkbenchSubPanel.TEMPLATES -> ForumTemplatePicker(onTemplate)
        WorkbenchSubPanel.QUOTE_POST -> {
            var attempt by remember { mutableIntStateOf(0) }
            var error by remember(attempt) { mutableStateOf<String?>(null) }
            LaunchedEffect(context.topicId, context.quoteFloor, attempt) {
                try { insert(context.quote(), false) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (e: Exception) { error = e.message ?: "读取原帖失败" }
            }
            Text(error ?: "正在读取原帖…")
            if (error != null) TextButton(onClick = { attempt++ }) { Text("重试") }
        }
        WorkbenchSubPanel.AI_CONTENT -> {
            for ((action, label) in listOf(ForumMarkup.AiQuote to "引用", ForumMarkup.AiDetails to "折叠内容", ForumMarkup.AiCode to "代码")) {
                TextButton(onClick = { insert(forumMarkup(action, selected), false) }) { Text(label) }
            }
        }
        WorkbenchSubPanel.CHART, WorkbenchSubPanel.WRAP, WorkbenchSubPanel.DATE_TIME -> key(panel) {
            var name by remember { mutableStateOf("") }
            var axis by remember { mutableStateOf("") }
            val rows = remember { mutableStateListOf(ChartRow()) }
            var data by remember { mutableStateOf("") }
            var date by remember { mutableStateOf(LocalDate.now().toString()) }
            var time by remember { mutableStateOf("") }
            var zone by remember { mutableStateOf(ZoneId.systemDefault().id) }
            Column(Modifier.verticalScroll(rememberScrollState()).imePadding(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val markup = when (panel) {
                    WorkbenchSubPanel.CHART -> {
                        ForumToolField("图表标题（选填）", name, { name = it })
                        ForumToolField("横轴标题（选填）", axis, { axis = it })
                        rows.forEachIndexed { index, row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                ForumToolField("名称", row.label, { rows[index] = row.copy(label = it) }, modifier = Modifier.weight(1.5f))
                                ForumToolField("数值", row.value, { rows[index] = row.copy(value = it) }, modifier = Modifier.weight(1f))
                                if (rows.size > 1) TextButton(onClick = { rows.removeAt(index) }) { Text("删除") }
                            }
                        }
                        TextButton(onClick = { rows.add(ChartRow()) }) { Text("＋ 添加一行") }
                        chartMarkup(name, axis, rows)
                    }
                    WorkbenchSubPanel.WRAP -> {
                        ForumToolField("包裹名称", name, { name = it })
                        ForumToolField("属性（选填，每行 key=value）", data, { data = it }, multiline = true)
                        wrapMarkup(name.trim(), data, selected)
                    }
                    else -> {
                        ForumToolField("日期（YYYY-MM-DD）", date, { date = it })
                        ForumToolField("时间（选填，HH:mm）", time, { time = it })
                        ForumToolField("时区", zone, { zone = it })
                        dateMarkup(date, time, zone.trim())
                    }
                }
                TextButton(onClick = { markup?.let { insert(it, panel == WorkbenchSubPanel.DATE_TIME) } }, enabled = markup != null) { Text("插入") }
            }
        }
        else -> Unit
    }
}
