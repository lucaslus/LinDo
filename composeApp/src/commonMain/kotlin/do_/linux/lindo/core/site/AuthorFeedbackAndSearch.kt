package do_.linux.lindo.core.site

/**
 * Author feedback PM specification (@jedilus), advanced Discourse search query builder,
 * and Discourse `/p/{postId}` short permalink parser.
 */
object AuthorFeedbackSpec {
    const val AUTHOR_USERNAME = "jedilus"

    enum class Category(val label: String) {
        BUG("Bug 反馈"),
        FEATURE("功能建议"),
        UX("体验优化"),
        OTHER("其他反馈")
    }

    private val sensitiveSecretRegex = Regex(
        """(?i)\b(_t|_forum_session|cf_clearance|user-api-key|x-csrf-token|cookie|authorization)\s*[=:]\s*[^\s;,\n]+"""
    )

    fun sanitizeLogSnippet(raw: String): String =
        sensitiveSecretRegex.replace(raw) { m -> "${m.groupValues[1]}=[REDACTED]" }

    fun buildPayload(
        category: Category,
        subject: String,
        body: String,
        appVersion: String,
        platformInfo: String,
        includeDeviceInfo: Boolean = true,
        diagnosticSnippet: String? = null
    ): Pair<String, String> {
        val cleanSubject = subject.trim().ifBlank { "客户端使用反馈" }
        val title = "[${category.label}] $cleanSubject"
        val renderedBody = buildString {
            append(body.trim())
            if (includeDeviceInfo) {
                append("\n\n---\n")
                append("**客户端信息**：$appVersion · $platformInfo")
            }
            val cleanDiag = diagnosticSnippet?.trim()?.takeIf { it.isNotEmpty() }
            if (cleanDiag != null) {
                val redacted = sanitizeLogSnippet(cleanDiag).take(2200)
                append("\n\n[details=\"匿名化诊断摘要\"]\n```text\n")
                append(redacted)
                append("\n```\n[/details]")
            }
        }
        return title to renderedBody
    }
}

data class AdvancedSearchFilter(
    val categorySlug: String = "",
    val tag: String = "",
    val author: String = "",
    val inTitle: Boolean = false,
    val solved: Boolean = false,
    val afterDateIso: String = ""
) {
    val hasActiveFilters: Boolean
        get() = categorySlug.isNotBlank() || tag.isNotBlank() || author.isNotBlank() ||
            inTitle || solved || afterDateIso.isNotBlank()
}

fun buildAdvancedSearchQuery(keyword: String, filter: AdvancedSearchFilter): String {
    val parts = mutableListOf<String>()
    val cleanKeyword = keyword.trim()
    if (cleanKeyword.isNotEmpty()) parts += cleanKeyword
    val cat = filter.categorySlug.trim().removePrefix("#")
    if (cat.isNotEmpty()) parts += "#$cat"
    val tag = filter.tag.trim().removePrefix("#")
    if (tag.isNotEmpty()) parts += "tags:$tag"
    val author = filter.author.trim().removePrefix("@")
    if (author.isNotEmpty()) parts += "@$author"
    if (filter.inTitle) parts += "in:title"
    if (filter.solved) parts += "status:solved"
    val after = filter.afterDateIso.trim()
    if (after.isNotEmpty()) parts += "after:$after"
    return parts.joinToString(" ").trim()
}

private val shortPostPermalinkRegex = Regex(
    """^(?:https?:)?//(?:www\.)?linux\.do/p/(\d+)(?:[/?#].*)?$""",
    RegexOption.IGNORE_CASE
)

fun parseShortPostPermalink(rawUrl: String): Int? {
    val trimmed = rawUrl.trim()
    val normalized = if (trimmed.startsWith("/p/", ignoreCase = true)) {
        "https://linux.do$trimmed"
    } else trimmed
    return shortPostPermalinkRegex.find(normalized)
        ?.groupValues?.getOrNull(1)
        ?.toIntOrNull()
        ?.takeIf { it > 0 }
}
