package do_.linux.lindo.core.network

import do_.linux.lindo.core.diagnostics.DiagnosticCategory
import do_.linux.lindo.core.diagnostics.LinDoDiagnosticLogger
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.TimeUnit

// Discourse consumes the OTP and sets _t on its 302 response. Following that
// redirect before reading Set-Cookie loses the only copy of the new session.
internal fun createOtpExchangeClient(engine: HttpClientEngine = OkHttp.create {
    config {
        followRedirects(false)
        followSslRedirects(false)
        retryOnConnectionFailure(false) // A consumed OTP must not be retried blindly.
        connectTimeout(6, TimeUnit.SECONDS)
        readTimeout(15, TimeUnit.SECONDS)
    }
}): HttpClient = HttpClient(engine) { followRedirects = false }

internal suspend fun exchangeOtpSessionCookies(otp: String, client: HttpClient): Boolean {
    fun saveCookies(values: List<String>?) {
        values.orEmpty().forEach { cookie ->
            CookieSentinel.ingestSetCookieHeader(cookie, domain = "linux.do")
            UserApiKeyAuthManager.onSyncCookieToPlatform?.invoke(cookie)
        }
    }
    fun report(stage: String, status: Int, challenge: Boolean) {
        LinDoDiagnosticLogger.info(DiagnosticCategory.AUTH, "OtpExchange",
            "$stage: HTTP $status, challenge=$challenge")
    }
    val csrfResponse = client.get("https://linux.do/session/csrf.json") {
        header("Accept", "application/json")
        header("X-Requested-With", "XMLHttpRequest")
        UserApiKeyAuthManager.customUserAgent?.let { header("User-Agent", it) }
        CookieSentinel.buildCookieHeader(targetHost = "linux.do").takeIf { it.isNotBlank() }
            ?.let { header("Cookie", it) }
    }
    saveCookies(csrfResponse.headers.getAll("Set-Cookie"))
    val csrfBody = csrfResponse.bodyAsText()
    val csrfChallenge = looksLikeCloudflareChallenge(csrfBody) ||
        csrfResponse.headers["cf-mitigated"].equals("challenge", ignoreCase = true)
    report("csrf", csrfResponse.status.value, csrfChallenge)
    if (csrfResponse.status.value !in 200..299 || csrfChallenge) return false
    val csrf = runCatching {
        Json.parseToJsonElement(csrfBody).jsonObject["csrf"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: return false

    val response = client.submitForm(
        url = "https://linux.do/session/otp/${otp.trim()}", formParameters = Parameters.Empty
    ) {
        header("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
        header("X-CSRF-Token", csrf)
        header("X-Requested-With", "XMLHttpRequest")
        UserApiKeyAuthManager.customUserAgent?.let { header("User-Agent", it) }
        CookieSentinel.buildCookieHeader(targetHost = "linux.do").takeIf { it.isNotBlank() }
            ?.let { header("Cookie", it) }
    }
    // Persist before any profile fetch or auth notification can suspend/cancel.
    saveCookies(response.headers.getAll("Set-Cookie"))
    val body = response.bodyAsText()
    val challenge = looksLikeCloudflareChallenge(body) ||
        response.headers["cf-mitigated"].equals("challenge", ignoreCase = true)
    report("redeem", response.status.value, challenge)
    return response.status.value in 200..399 && !challenge
}
