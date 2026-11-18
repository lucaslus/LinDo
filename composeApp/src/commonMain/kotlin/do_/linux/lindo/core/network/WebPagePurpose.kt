package do_.linux.lindo.core.network

import java.net.URI

/** The entry intent survives redirects; visiting Connect must never complete app login. */
internal enum class WebPagePurpose { LOGIN, VERIFICATION, BROWSE }

internal fun webPagePurpose(url: String): WebPagePurpose {
    val uri = runCatching { URI(url) }.getOrNull() ?: return WebPagePurpose.BROWSE
    if (uri.scheme != "https" || !uri.host.equals("linux.do", true)) return WebPagePurpose.BROWSE
    val path = uri.path.orEmpty()
    if (uri.fragment == "lindo-verify" || path == "/challenge" || path.startsWith("/challenge/")) {
        return WebPagePurpose.VERIFICATION
    }
    return if (path == "/login" || path.startsWith("/login/") ||
        path.startsWith("/session/otp/") || path.startsWith("/user-api-key/")) {
        WebPagePurpose.LOGIN
    } else WebPagePurpose.BROWSE
}

internal fun webPageToolbarLabel(initialUrl: String, currentUrl: String): String {
    val host = runCatching { URI(currentUrl).host }.getOrNull().orEmpty()
    // Preserve the actual origin on third-party auth pages; never duplicate their page heading.
    if (!host.equals("linux.do", true)) return host
    return when (webPagePurpose(initialUrl)) {
        WebPagePurpose.LOGIN -> "登录"
        WebPagePurpose.VERIFICATION -> host
        WebPagePurpose.BROWSE -> host
    }
}
