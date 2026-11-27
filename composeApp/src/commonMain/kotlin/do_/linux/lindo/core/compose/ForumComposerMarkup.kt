package do_.linux.lindo.core.compose

import java.time.LocalDate
import java.time.ZoneId

internal enum class ForumMarkup {
    Toc, Scroll, Mermaid, Graphviz, Footnote, AiQuote, AiCode, AiDetails
}

internal fun forumMarkup(tool: ForumMarkup, selected: String): String {
    val body = selected.ifEmpty { "内容" }
    return when (tool) {
        ForumMarkup.Toc -> "<div data-theme-toc=\"true\">${selected.ifEmpty { " " }}</div>"
        ForumMarkup.Scroll -> "<div data-theme-scrollable=\"true\">\n\n$body\n\n</div>"
        ForumMarkup.Mermaid -> "```mermaid\n${selected.ifEmpty { "graph TD\n    A[开始] --> B[结束]" }}\n```"
        ForumMarkup.Graphviz -> "[graphviz]\n${selected.ifEmpty { "digraph { A -> B }" }}\n[/graphviz]"
        ForumMarkup.Footnote -> "^[${selected.ifEmpty { "脚注内容" }}]"
        ForumMarkup.AiQuote -> "> [!AI]\n" + body.lines().joinToString("\n") { "> $it" }
        ForumMarkup.AiDetails -> "[details=\"摘要\" ai]\n$body\n[/details]"
        ForumMarkup.AiCode -> {
            val fence = "`".repeat(1 + maxOf(2, Regex("`+").findAll(body).maxOfOrNull { it.value.length } ?: 0))
            "$fence text ai\n$body\n$fence"
        }
    }
}

internal data class ChartRow(val label: String = "", val value: String = "")

internal fun chartMarkup(title: String, axis: String, rows: List<ChartRow>): String? {
    val attributes = mutableListOf("type='horizontalBar'")
    for ((name, value) in listOf("title" to title.trim(), "xAxisTitle" to axis.trim())) {
        if (value.isEmpty()) continue
        if (value.any { it in "[]\r\n" } || ('\'' in value && '"' in value)) return null
        val quote = if ('\'' in value) '"' else '\''
        attributes.add("$name=$quote$value$quote")
    }
    val data = mutableListOf<String>()
    for (row in rows) {
        val label = row.label.trim()
        val value = row.value.trim()
        if (label.isEmpty() && value.isEmpty()) continue
        if (label.isEmpty() || label.any { it in "|[]\r\n" } || !value.matches(Regex("""[+-]?(?:\d+(?:\.\d+)?|\.\d+)"""))) return null
        data.add("$label | $value")
    }
    if (data.isEmpty()) return null
    return "[chart ${attributes.joinToString(" ")}]\n${data.joinToString("\n")}\n[/chart]"
}

internal fun wrapMarkup(name: String, attributes: String, selected: String): String? {
    val identifier = Regex("""[\w-]+""")
    if (name.isNotEmpty() && !identifier.matches(name)) return null
    val attrs = mutableListOf<String>()
    for (line in attributes.lines().filter { it.isNotBlank() }) {
        val pos = line.indexOf('=')
        if (pos < 1) return null
        val key = line.substring(0, pos).trim()
        val value = line.substring(pos + 1).trim()
        if (!identifier.matches(key) || value.isEmpty() || value.any { it in "\"[]\\" }) return null
        attrs.add(" $key=\"$value\"")
    }
    if (name.isEmpty() && attrs.isEmpty()) return null
    return "[wrap${if (name.isEmpty()) "" else "=$name"}${attrs.joinToString("")}]\n${selected.ifEmpty { "内容" }}\n[/wrap]"
}

internal fun dateMarkup(date: String, time: String, zone: String): String? {
    if (!date.matches(Regex("""\d{4}-\d\d-\d\d""")) || runCatching { LocalDate.parse(date); ZoneId.of(zone) }.isFailure) return null
    if (time.isNotEmpty() && !time.matches(Regex("""([01]\d|2[0-3]):[0-5]\d"""))) return null
    return "[date=$date${if (time.isEmpty()) "" else " time=$time:00"} timezone=\"$zone\"]"
}

internal fun separatedForumBlock(text: String, start: Int, end: Int, block: String): String {
    val before = text.take(start)
    val after = text.drop(end)
    val lead = when {
        before.isEmpty() || before.endsWith("\n\n") -> ""
        before.endsWith('\n') -> "\n"
        else -> "\n\n"
    }
    val trail = if (after.isEmpty() || after.startsWith('\n')) "\n" else "\n\n"
    return lead + block + trail
}
