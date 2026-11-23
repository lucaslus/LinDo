package do_.linux.lindo.core.site

import do_.linux.lindo.core.model.TopicSummary

data class HistorySearchEntry(
    val topic: TopicSummary,
    val fromCloudSearch: Boolean = false
)

/**
 * 阅读历史双层检索（即时本地多词过滤 + Discourse 官方 `in:seen` 云端历史补查合并）。
 */
object ReadingHistorySearch {
    private val inSeenRegex = Regex("""(?:^|\s)in:seen(?:\s|$)""", RegexOption.IGNORE_CASE)

    fun buildSeenQuery(rawQuery: String): String? {
        val cleaned = rawQuery.trim().replace(Regex("\\s+"), " ")
        if (cleaned.isEmpty()) return null
        return if (inSeenRegex.containsMatchIn(cleaned)) cleaned else "$cleaned in:seen"
    }

    private fun matchesTokens(topic: TopicSummary, tokens: List<String>): Boolean {
        if (tokens.isEmpty()) return true
        val haystack = buildString {
            append(topic.title).append(' ')
            append(topic.originalPoster.name.orEmpty()).append(' ')
            append(topic.originalPoster.username).append(' ')
            append(topic.categoryName).append(' ')
            topic.tags.forEach { append(it).append(' ') }
            append(topic.id).append(" #").append(topic.id)
        }.lowercase()
        return tokens.all { tok ->
            val numeric = if (tok.startsWith("#") && tok.drop(1).all { it.isDigit() }) tok.drop(1) else tok
            haystack.contains(tok) || haystack.contains(numeric)
        }
    }

    fun filterAndMerge(
        localAndLoaded: List<TopicSummary>,
        cloudSeenHits: List<TopicSummary>,
        rawQuery: String
    ): List<HistorySearchEntry> {
        val trimmed = rawQuery.trim()
        if (trimmed.isEmpty()) {
            return localAndLoaded.map { HistorySearchEntry(it, fromCloudSearch = false) }
        }
        val tokens = trimmed
            .lowercase()
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() && it != "in:seen" }

        val out = ArrayList<HistorySearchEntry>()
        val seenIds = HashSet<Int>()

        for (item in localAndLoaded) {
            if (!seenIds.add(item.id)) continue
            if (matchesTokens(item, tokens)) {
                out.add(HistorySearchEntry(item, fromCloudSearch = false))
            }
        }
        for (hit in cloudSeenHits) {
            if (!seenIds.add(hit.id)) continue
            out.add(HistorySearchEntry(hit, fromCloudSearch = true))
        }
        return out
    }
}
