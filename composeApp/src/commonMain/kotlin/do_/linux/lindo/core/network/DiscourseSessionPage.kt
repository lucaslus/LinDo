package do_.linux.lindo.core.network

import kotlinx.serialization.json.*
import org.jsoup.Jsoup

/** Independent same-origin evidence before treating two failed session probes as a logout. */
internal sealed interface DiscourseSessionPage {
    data class Authenticated(val user: JsonObject) : DiscourseSessionPage
    data object Guest : DiscourseSessionPage
    data object Unknown : DiscourseSessionPage
}

internal fun readDiscourseSessionPage(status: Int, body: String): DiscourseSessionPage {
    if (status != 200 || looksLikeCloudflareChallenge(body)) return DiscourseSessionPage.Unknown
    return runCatching {
        val doc = Jsoup.parse(body)
        val preload = doc.getElementById("data-preloaded") ?: return DiscourseSessionPage.Unknown
        val source = preload.data().ifBlank { preload.attr("data-preloaded") }
        val data = Json.parseToJsonElement(source).jsonObject
        val raw = data["currentUser"]
        val user = when (raw) {
            is JsonObject -> raw
            is JsonPrimitive -> if (raw.isString) Json.parseToJsonElement(raw.content) as? JsonObject else null
            else -> null
        }
        val name = user?.get("username")?.jsonPrimitive?.contentOrNull
        if (user != null && (user["id"]?.jsonPrimitive?.intOrNull ?: 0) > 0 &&
            !name.isNullOrBlank() && name != "linuxdo_user") {
            DiscourseSessionPage.Authenticated(user)
        } else if ((raw == null || raw is JsonNull || (raw is JsonPrimitive && raw.content == "null")) &&
            data.isNotEmpty() && (
                data.containsKey("site") ||
                    data.containsKey("siteSettings") ||
                    doc.selectFirst("meta[name=csrf-token], meta[name=discourse_current_homepage], html.anon") != null
            )) {
            DiscourseSessionPage.Guest
        } else DiscourseSessionPage.Unknown
    }.getOrDefault(DiscourseSessionPage.Unknown)
}
