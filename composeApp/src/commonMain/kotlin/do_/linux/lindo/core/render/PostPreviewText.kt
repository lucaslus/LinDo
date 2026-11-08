package do_.linux.lindo.core.render

import org.jsoup.Jsoup
import org.jsoup.nodes.TextNode

private val namedImagePlaceholder = Regex("\\[(?:IMG|PXL|DSC|Screenshot)[-_ ][^\\]\\r\\n]+\\]", RegexOption.IGNORE_CASE)
private val closingQuote = Regex("\\[/quote]", RegexOption.IGNORE_CASE)

internal fun postPreviewText(cookedHtml: String): String {
    val body = Jsoup.parseBodyFragment(cookedHtml).body()
    body.select("aside.quote, blockquote").remove()
    body.select(".spoiler, .spoilered").forEach { it.text("［剧透内容］") }
    body.select(".lightbox-wrapper, a.lightbox").forEach { it.replaceWith(TextNode(" 图片 ")) }
    body.select("img:not(.emoji):not(.avatar)").forEach { it.replaceWith(TextNode(" 图片 ")) }
    return body.text().replace(namedImagePlaceholder, "图片")
}

internal fun postQuoteMarkup(username: String, floor: Int, topicId: Int, selectedText: String): String {
    val text = selectedText.trim().replace(closingQuote, "&#91;/quote]")
    return "[quote=\"$username, post:$floor, topic:$topicId\"]\n$text\n[/quote]\n\n"
}
