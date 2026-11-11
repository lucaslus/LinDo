package do_.linux.lindo.core.network

import kotlinx.serialization.json.*
import java.io.IOException

class ApiMutationException(message: String) : IOException(message)

internal sealed interface PostSubmission<out T> {
    data class Published<T>(val value: T) : PostSubmission<T>
    data object PendingReview : PostSubmission<Nothing>
}

internal class PostSubmissionUnconfirmed : IOException("发布结果暂未确认。草稿已保留，请核对后再试。")

internal var onAuthenticationInvalidated: (() -> Unit)? = {
    if (UserApiKeyAuthManager.isAuthorized()) {
        LinuxDoRepository.scheduleAuthVerificationIfNeeded()
    }
}

internal fun requireMutationSuccess(response: Pair<Int, String>?) {
    if (response != null && response.first in 200..299 && response.second.isBlank()) return
    requireMutationObject(response)
}

internal fun requireMutationObject(response: Pair<Int, String>?): JsonObject {
    val status = response?.first ?: 0
    val rawBody = response?.second
    val root = rawBody?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
    val errorType = root?.get("error_type")?.jsonPrimitive?.contentOrNull?.lowercase().orEmpty()
    if (((status == 401 && !looksLikeCloudflareChallenge(rawBody)) || (status == 403 && errorType == "not_logged_in")) &&
        UserApiKeyAuthManager.isAuthorized()
    ) {
        onAuthenticationInvalidated?.invoke()
    }
    val messages = when (val errors = root?.get("errors")) {
        is JsonArray -> errors.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        is JsonPrimitive -> listOfNotNull(errors.contentOrNull)
        else -> emptyList()
    }
    if (messages.isNotEmpty()) {
        throw ApiMutationException(org.jsoup.Jsoup.parse(messages.joinToString("；")).text().take(400))
    }
    if (status == 409) throw ApiMutationException("草稿已在其他设备更新，当前内容已保存在本机。请重新打开草稿核对后继续。")
    return readApiObject(response)
}

enum class ApiFailureKind { Connection, Authentication, PermissionDenied, Verification, RateLimited, Server, InvalidPayload }

internal const val CF_MITIGATED_CHALLENGE_PREFIX = "<!--lindo-cf-challenge-->"

internal fun looksLikeCloudflareChallenge(rawBody: String?): Boolean {
    if (rawBody.isNullOrBlank()) return false
    if (rawBody.startsWith(CF_MITIGATED_CHALLENGE_PREFIX)) return true
    val trimmed = rawBody.trimStart()
    if (trimmed.startsWith("{") || trimmed.startsWith("[")) return false
    val lower = trimmed.take(4096).lowercase()
    return lower.contains("_cf_chl_opt") ||
        lower.contains("cdn-cgi/challenge-platform") ||
        lower.contains("challenges.cloudflare.com") ||
        lower.contains("id=\"challenge-running\"") ||
        lower.contains("id=\"challenge-stage\"") ||
        lower.contains("class=\"cf-turnstile\"") ||
        lower.contains("<title>just a moment") ||
        (lower.contains("请稍候") && lower.contains("cloudflare"))
}

class ApiReadException(
    val kind: ApiFailureKind,
    cause: Throwable? = null,
    val customMessage: String? = null
) : IOException(
    customMessage?.takeIf { it.isNotBlank() } ?: when (kind) {
        ApiFailureKind.Connection -> "网络连接失败"
        ApiFailureKind.Authentication -> "登录状态已失效，请重新登录后重试"
        ApiFailureKind.PermissionDenied -> "当前账号暂无访问权限"
        ApiFailureKind.Verification -> "需要完成站点验证，请打开官网检查"
        ApiFailureKind.RateLimited -> "请求过于频繁，请稍后重试"
        ApiFailureKind.Server -> "服务暂时不可用，请稍后重试"
        ApiFailureKind.InvalidPayload -> "返回的数据不完整，请重试"
    },
    cause
)

internal fun readApiObject(response: Pair<Int, String>?): JsonObject {
    if (response == null || response.first == 0) throw ApiReadException(ApiFailureKind.Connection)
    val (status, rawBody) = response
    val body = rawBody.removePrefix(CF_MITIGATED_CHALLENGE_PREFIX)
    if (status !in 200..299) {
        val jsonErrRoot = body.trimStart().takeIf { it.startsWith("{") }
            ?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        val errorType = jsonErrRoot?.get("error_type")?.jsonPrimitive?.contentOrNull?.lowercase().orEmpty()
        val serverMsg = when (val errs = jsonErrRoot?.get("errors")) {
            is JsonArray -> errs.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            is JsonPrimitive -> listOfNotNull(errs.contentOrNull)
            else -> emptyList()
        }.joinToString("；").let { if (it.isNotBlank()) org.jsoup.Jsoup.parse(it).text().take(200) else null }

        val isCfChallenge = errorType != "not_logged_in" && looksLikeCloudflareChallenge(rawBody)
        if (!isCfChallenge &&
            (status == 401 || (status == 403 && errorType == "not_logged_in")) &&
            UserApiKeyAuthManager.isAuthorized()
        ) {
            onAuthenticationInvalidated?.invoke()
        }

        if (isCfChallenge) {
            throw ApiReadException(ApiFailureKind.Verification)
        }

        throw when (status) {
            401 -> ApiReadException(ApiFailureKind.Authentication, customMessage = serverMsg)
            403 -> if (errorType == "not_logged_in") {
                ApiReadException(ApiFailureKind.Authentication, customMessage = serverMsg)
            } else {
                ApiReadException(ApiFailureKind.PermissionDenied, customMessage = serverMsg)
            }
            429 -> ApiReadException(ApiFailureKind.RateLimited, customMessage = serverMsg)
            else -> ApiReadException(ApiFailureKind.Server, customMessage = serverMsg)
        }
    }
    if (looksLikeCloudflareChallenge(rawBody)) {
        throw ApiReadException(ApiFailureKind.Verification)
    }
    try {
        val root = Json.parseToJsonElement(body) as? JsonObject ?: throw ApiReadException(ApiFailureKind.InvalidPayload)
        if ((root["errors"] as? JsonArray)?.isNotEmpty() == true ||
            (root["errors"] as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true ||
            root["error_type"]?.let { it !is JsonNull } == true) throw ApiReadException(ApiFailureKind.InvalidPayload)
        return root
    } catch (error: ApiReadException) { throw error }
    catch (error: Exception) { throw ApiReadException(ApiFailureKind.InvalidPayload, cause = error) }
}
internal fun JsonObject.requireArray(key: String): JsonArray = this[key] as? JsonArray
    ?: throw ApiReadException(ApiFailureKind.InvalidPayload)
