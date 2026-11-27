package do_.linux.lindo.core.compose

internal data class ForumTemplate(
    val id: Int,
    val title: String,
    val content: String,
    val tags: List<String>,
    val usages: Int
) {
    fun matches(query: String): Boolean = query.trim().let { term ->
        title.contains(term, true) || content.contains(term, true) || tags.any { it.contains(term, true) }
    }
}

private val templateVariables = setOf(
    "my_username", "my_name", "context_title", "context_url", "topic_title", "topic_url",
    "original_poster_username", "original_poster_name", "reply_to_username", "reply_to_name",
    "last_poster_username", "reply_to_or_last_poster_username", "chat_channel_name",
    "chat_channel_url", "chat_thread_name", "chat_thread_url"
)
private val variablePattern = Regex("""%\{(\w+)(?:,fallback:([^}]*))?}""")

internal fun expandForumTemplate(text: String, variables: Map<String, String?>): String =
    variablePattern.replace(text) { token ->
        val key = token.groupValues[1]
        if (key !in templateVariables) token.value
        else variables[key]?.takeIf(String::isNotBlank) ?: token.groupValues[2]
    }
