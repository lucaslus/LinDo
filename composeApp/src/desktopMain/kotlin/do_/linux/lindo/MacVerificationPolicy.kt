package do_.linux.lindo

import do_.linux.lindo.core.network.looksLikeCloudflareChallenge
import kotlinx.serialization.json.*
import java.net.URI

/** Recovery proves the interrupted read works; only login needs a current-user probe. */
internal object MacVerificationPolicy {
    fun isRecoverableApiUrl(url: String?): Boolean {
        val uri = runCatching { URI(url ?: "") }.getOrNull() ?: return false
        return uri.scheme == "https" && uri.host == "linux.do" &&
            uri.port in listOf(-1, 443) && uri.userInfo == null &&
            !uri.path.startsWith("/session/") && !uri.path.startsWith("/user-api-key/") &&
            uri.path.endsWith(".json")
    }

    fun probeUrl(login: Boolean, interruptedUrl: String?): String {
        if (login) return "https://linux.do/session/current.json"
        return interruptedUrl?.takeIf(::isRecoverableApiUrl) ?: "https://linux.do/latest.json"
    }

    fun isUnauthenticatedResponse(response: Pair<Int, String>): Boolean {
        if (looksLikeCloudflareChallenge(response.second)) return false
        if (response.first == 401) return true
        if (response.first == 404 && response.second.isBlank()) return true
        if (response.first == 403) {
            val body = runCatching { Json.parseToJsonElement(response.second) as? JsonObject }.getOrNull()
            return body?.get("error_type")?.jsonPrimitive?.contentOrNull.equals("not_logged_in", ignoreCase = true)
        }
        return false
    }

    fun isReadRecovered(response: Pair<Int, String>): Boolean {
        if (looksLikeCloudflareChallenge(response.second) || isUnauthenticatedResponse(response)) return false
        val body = runCatching { Json.parseToJsonElement(response.second) }.getOrNull()
        if (response.first in 200..299) return body is JsonObject || body is JsonArray
        // A normal Discourse permission/not-found response ends verification too.
        // Let the original screen show its real error, without another challenge loop.
        return response.first in setOf(403, 404) && body is JsonObject && body["errors"] is JsonArray
    }
}
