package do_.linux.lindo.core.network

import do_.linux.lindo.core.copy.LinDoNetworkCopy
import kotlinx.serialization.json.*
import java.io.IOException

class ApiMutationException(message: String) : IOException(message)

internal sealed interface PostSubmission<out T> {
    data class Published<T>(val value: T) : PostSubmission<T>
    data object PendingReview : PostSubmission<Nothing>
}

internal class PostSubmissionUnconfirmed : IOException(LinDoNetworkCopy.POST_SUBMISSION_UNCONFIRMED.text)

internal enum class DiscourseErrorType(val wireCode: String) {
    NOT_LOGGED_IN("not_logged_in"),
    INVALID_ACCESS("invalid_access"),
    OTHER("");

    companion object {
        fun fromRaw(raw: String?): DiscourseErrorType {
            val clean = raw?.trim()?.lowercase().orEmpty()
            return when (clean) {
                NOT_LOGGED_IN.wireCode -> NOT_LOGGED_IN
                INVALID_ACCESS.wireCode -> INVALID_ACCESS
                else -> OTHER
            }
        }
    }
}

internal enum class CloudflareChallengeMarker(val token: String) {
    CHL_OPT("_cf_chl_opt"),
    CHL_TK("__cf_chl_tk"),
    CHL_BYPASS("cf-chl-bypass"),
    CHALLENGE_PLATFORM("cdn-cgi/challenge-platform"),
    CHALLENGES_HOST("challenges.cloudflare.com"),
    CHALLENGE_RUNNING_ID("id=\"challenge-running\""),
    CHALLENGE_STAGE_ID("id=\"challenge-stage\""),
    TURNSTILE_CLASS("class=\"cf-turnstile\""),
    CF_ERROR_DETAILS("cf-error-details"),
    TITLE_JUST_A_MOMENT("<title>just a moment"),
    TITLE_PLEASE_WAIT_ZH("<title>请稍候"),
    TITLE_ATTENTION_REQUIRED("<title>attention required"),
    ENABLE_JS_AND_COOKIES("enable javascript and cookies to continue")
}

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
    val rawErrorType = root?.get("error_type")?.jsonPrimitive?.contentOrNull?.lowercase().orEmpty()
    val errorType = DiscourseErrorType.fromRaw(rawErrorType)
    val isCfChallenge = errorType != DiscourseErrorType.NOT_LOGGED_IN && looksLikeCloudflareChallenge(rawBody)
    val isKeyOnlyJson403 = status == 403 &&
        !isCfChallenge &&
        !UserApiKeyAuthManager.hasValidWebSessionCookie() &&
        !UserApiKeyAuthManager.getApiKey().isNullOrBlank() &&
        (root?.containsKey("errors") == true || rawErrorType.isNotEmpty())
    if (!isCfChallenge &&
        (status == 401 || (status == 403 && errorType == DiscourseErrorType.NOT_LOGGED_IN) || isKeyOnlyJson403) &&
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
    if (status == 409) throw ApiMutationException(LinDoNetworkCopy.DRAFT_CONFLICT_409.text)
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
    return CloudflareChallengeMarker.entries.any { lower.contains(it.token) } ||
        (lower.contains("请稍候") && lower.contains("cloudflare"))
}

class ApiReadException(
    val kind: ApiFailureKind,
    cause: Throwable? = null,
    val customMessage: String? = null
) : IOException(
    customMessage?.takeIf { it.isNotBlank() } ?: when (kind) {
        ApiFailureKind.Connection -> LinDoNetworkCopy.CONNECTION_FAILED.text
        ApiFailureKind.Authentication -> if (UserApiKeyAuthManager.isAuthorized()) {
            LinDoNetworkCopy.AUTH_EXPIRED_RELOGIN_RETRY.text
        } else {
            LinDoNetworkCopy.GUEST_CONTENT_LOGIN_REQUIRED.text
        }
        ApiFailureKind.PermissionDenied -> if (UserApiKeyAuthManager.isAuthorized()) {
            LinDoNetworkCopy.PERMISSION_DENIED.text
        } else {
            LinDoNetworkCopy.GUEST_CONTENT_LOGIN_REQUIRED.text
        }
        ApiFailureKind.Verification -> LinDoNetworkCopy.SITE_VERIFICATION_REQUIRED.text
        ApiFailureKind.RateLimited -> LinDoNetworkCopy.RATE_LIMITED_RETRY.text
        ApiFailureKind.Server -> LinDoNetworkCopy.SERVER_UNAVAILABLE_RETRY.text
        ApiFailureKind.InvalidPayload -> LinDoNetworkCopy.INVALID_PAYLOAD_RETRY.text
    },
    cause
)

private fun normalizeGuestAuthMessage(serverMsg: String?): String? {
    if (UserApiKeyAuthManager.isAuthorized()) return serverMsg
    val trimmed = serverMsg?.trim().orEmpty()
    if (trimmed.isEmpty()) return LinDoNetworkCopy.GUEST_CONTENT_LOGIN_REQUIRED.text
    val parsedErrorType = DiscourseErrorType.fromRaw(trimmed)
    val isGenericDiscourseDenial = trimmed.contains("您无权查看") ||
        trimmed.contains("您需要登录") ||
        parsedErrorType == DiscourseErrorType.NOT_LOGGED_IN ||
        parsedErrorType == DiscourseErrorType.INVALID_ACCESS
    return if (isGenericDiscourseDenial) LinDoNetworkCopy.GUEST_CONTENT_LOGIN_REQUIRED.text else trimmed
}

internal fun readApiObject(response: Pair<Int, String>?): JsonObject {
    if (response == null || response.first == 0) throw ApiReadException(ApiFailureKind.Connection)
    val (status, rawBody) = response
    val body = rawBody.removePrefix(CF_MITIGATED_CHALLENGE_PREFIX)
    if (status !in 200..299) {
        val jsonErrRoot = body.trimStart().takeIf { it.startsWith("{") }
            ?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        val rawErrorType = jsonErrRoot?.get("error_type")?.jsonPrimitive?.contentOrNull?.lowercase().orEmpty()
        val errorType = DiscourseErrorType.fromRaw(rawErrorType)
        val serverMsg = when (val errs = jsonErrRoot?.get("errors")) {
            is JsonArray -> errs.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            is JsonPrimitive -> listOfNotNull(errs.contentOrNull)
            else -> emptyList()
        }.joinToString("；").let { if (it.isNotBlank()) org.jsoup.Jsoup.parse(it).text().take(200) else null }

        val isCfChallenge = errorType != DiscourseErrorType.NOT_LOGGED_IN && looksLikeCloudflareChallenge(rawBody)
        val isKeyOnlyJson403 = status == 403 &&
            !isCfChallenge &&
            !UserApiKeyAuthManager.hasValidWebSessionCookie() &&
            !UserApiKeyAuthManager.getApiKey().isNullOrBlank() &&
            (jsonErrRoot?.containsKey("errors") == true || rawErrorType.isNotEmpty())
        if (!isCfChallenge &&
            (status == 401 || (status == 403 && errorType == DiscourseErrorType.NOT_LOGGED_IN) || isKeyOnlyJson403) &&
            UserApiKeyAuthManager.isAuthorized()
        ) {
            onAuthenticationInvalidated?.invoke()
        }

        if (isCfChallenge) {
            throw ApiReadException(ApiFailureKind.Verification)
        }

        throw when (status) {
            401 -> ApiReadException(ApiFailureKind.Authentication, customMessage = normalizeGuestAuthMessage(serverMsg))
            403 -> if (errorType == DiscourseErrorType.NOT_LOGGED_IN) {
                ApiReadException(ApiFailureKind.Authentication, customMessage = normalizeGuestAuthMessage(serverMsg))
            } else {
                ApiReadException(ApiFailureKind.PermissionDenied, customMessage = normalizeGuestAuthMessage(serverMsg))
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
