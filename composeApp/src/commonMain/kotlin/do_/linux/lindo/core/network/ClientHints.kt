package do_.linux.lindo.core.network

/**
 * Chromium 标准低熵 Client Hints 生成器（严格对齐 Chromium `components/embedder_support/user_agent_utils.cc` GREASE 置换算法）：
 * 当使用 Chromium / Android WebView User-Agent 发起 HTTPS 请求（如静态 CDN 或兜底通道）时，同步附加与主版本号严格吻合的
 * `sec-ch-ua`、`sec-ch-ua-mobile`、`sec-ch-ua-platform`，消除「携带 Chrome UA 却缺失 Client Hints」的协议栈不一致特征。
 */
object ClientHints {
    private val chromeMajorRegex = Regex("""(?:Chrome|CriOS)/(\d+)\.""")
    private val greaseChars = listOf(" ", "(", ":", "-", ".", "/", ")", ";", "=", "?", "_")
    private val greaseVersions = listOf("8", "99", "24")
    private val brandOrders = listOf(
        intArrayOf(0, 1, 2),
        intArrayOf(0, 2, 1),
        intArrayOf(1, 0, 2),
        intArrayOf(1, 2, 0),
        intArrayOf(2, 0, 1),
        intArrayOf(2, 1, 0)
    )

    fun fromUserAgent(userAgent: String?): Map<String, String> {
        if (userAgent.isNullOrBlank()) return emptyMap()
        // Safari / Firefox 不发送 Chromium Sec-CH-UA 标头
        if (!userAgent.contains("Chrome/") && !userAgent.contains("CriOS/")) {
            return emptyMap()
        }
        val majorStr = chromeMajorRegex.find(userAgent)?.groupValues?.getOrNull(1) ?: return emptyMap()
        val seed = majorStr.toIntOrNull() ?: return emptyMap()
        if (seed < 89) return emptyMap()

        val c1 = greaseChars[seed % greaseChars.size]
        val c2 = greaseChars[(seed + 1) % greaseChars.size]
        val greaseBrand = "Not${c1}A${c2}Brand"
        val greaseVer = greaseVersions[seed % greaseVersions.size]

        val productBrand = when {
            userAgent.contains("Edg/") || userAgent.contains("EdgA/") -> "Microsoft Edge"
            userAgent.contains("; wv)") || userAgent.contains(" wv)") -> "Android WebView"
            else -> "Google Chrome"
        }

        val pairs = arrayOf(
            greaseBrand to greaseVer,
            "Chromium" to majorStr,
            productBrand to majorStr
        )
        val order = brandOrders[seed % brandOrders.size]
        val secChUa = order.joinToString(", ") { idx ->
            val (b, v) = pairs[idx]
            "\"$b\";v=\"$v\""
        }

        val isMobile = userAgent.contains("Mobile", ignoreCase = true)
        val platform = when {
            userAgent.contains("Android", ignoreCase = true) -> "Android"
            userAgent.contains("Windows", ignoreCase = true) -> "Windows"
            userAgent.contains("Macintosh", ignoreCase = true) || userAgent.contains("Mac OS X", ignoreCase = true) -> "macOS"
            userAgent.contains("iPhone", ignoreCase = true) || userAgent.contains("iPad", ignoreCase = true) -> "iOS"
            else -> "Linux"
        }

        return mapOf(
            "sec-ch-ua" to secChUa,
            "sec-ch-ua-mobile" to if (isMobile) "?1" else "?0",
            "sec-ch-ua-platform" to "\"$platform\""
        )
    }
}
