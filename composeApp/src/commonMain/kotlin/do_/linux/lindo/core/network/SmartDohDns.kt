package do_.linux.lindo.core.network

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 智能防污染 DNS 解析器（吸收 CFluxDo / linuxdo-android 网络自愈经验）：
 * 1. 当系统处于 VPN / Clash TUN / 显式代理环境时，100% 透传系统 DNS（完整保留 `198.18.0.0/15` Fake-IP 路由）；
 * 2. 当处于直连运营商 / 校园网环境时，优先走毫秒级系统 DNS，自动识别并剔除 DNS 抢答污染地址（`0.0.0.0`、`127.x`、无 VPN 下的残留 `198.18.x` 等）；
 * 3. 一旦系统 DNS 解析失败或返回污染结果，自动切换至纯 IP 直连的阿里云 (`223.5.5.5`) / DNSPod (`1.12.12.12`) HTTPS DoH，并按 TTL 内存缓存。
 */
object SmartDohDns : Dns {
    private data class CachedDnsEntry(
        val addresses: List<InetAddress>,
        val expiresAtMs: Long
    )

    private val cache = ConcurrentHashMap<String, CachedDnsEntry>()
    private val json = Json { ignoreUnknownKeys = true }

    private val dohEndpoints = listOf(
        "https://223.5.5.5/resolve",
        "https://1.12.12.12/resolve"
    )

    private val bootstrapClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(Dns.SYSTEM)
            .connectTimeout(2500, TimeUnit.MILLISECONDS)
            .readTimeout(2500, TimeUnit.MILLISECONDS)
            .callTimeout(3500, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    @Volatile
    var vpnOrProxyDetector: (() -> Boolean)? = null

    fun isProxyOrVpnActive(): Boolean {
        val customActive = runCatching { vpnOrProxyDetector?.invoke() == true }.getOrDefault(false)
        if (customActive) return true
        val httpsProxy = System.getProperty("https.proxyHost").orEmpty()
        val httpProxy = System.getProperty("http.proxyHost").orEmpty()
        val socksProxy = System.getProperty("socksProxyHost").orEmpty()
        return httpsProxy.isNotBlank() || httpProxy.isNotBlank() || socksProxy.isNotBlank()
    }

    internal fun isPoisonedDirectIp(addr: InetAddress): Boolean {
        if (addr.isAnyLocalAddress || addr.isLoopbackAddress || addr.isLinkLocalAddress || addr.isMulticastAddress) {
            return true
        }
        if (addr is Inet4Address) {
            val b = addr.address
            val o0 = b[0].toInt() and 0xFF
            val o1 = b[1].toInt() and 0xFF
            // RFC 2544 benchmark / Clash Fake-IP range (198.18.0.0/15) is invalid when NO VPN/proxy is running
            if (o0 == 198 && (o1 == 18 || o1 == 19)) return true
            // Reserved documentation / 0.x / 240+ bogons
            if (o0 == 0 || o0 >= 240) return true
        }
        return false
    }

    override fun lookup(hostname: String): List<InetAddress> {
        val cleanHost = hostname.trim().lowercase()
        if (cleanHost.isEmpty()) throw UnknownHostException("Empty hostname")

        // IP literals & localhost never need DoH
        if (cleanHost == "localhost" || cleanHost.endsWith(".local")) {
            return Dns.SYSTEM.lookup(hostname)
        }

        // Preserve Fake-IP & TUN DNS strictly when VPN or proxy is active
        if (isProxyOrVpnActive()) {
            return Dns.SYSTEM.lookup(hostname)
        }

        val now = System.currentTimeMillis()
        cache[cleanHost]?.let { cached ->
            if (cached.expiresAtMs > now && cached.addresses.isNotEmpty()) {
                return cached.addresses
            }
        }

        val systemResolved = runCatching { Dns.SYSTEM.lookup(hostname) }.getOrNull()
        val cleanSystemIps = systemResolved?.filterNot(::isPoisonedDirectIp).orEmpty()
        if (cleanSystemIps.isNotEmpty()) {
            return cleanSystemIps
        }

        // System DNS failed or returned poisoned sinkhole IPs -> resolve via IP-literal HTTPS DoH
        val dohResolved = resolveViaDoh(cleanHost)
        if (dohResolved.isNotEmpty()) {
            return dohResolved
        }

        if (!systemResolved.isNullOrEmpty()) {
            return systemResolved
        }
        throw UnknownHostException("Unable to resolve host $hostname via system DNS or DoH")
    }

    internal fun parseDohJsonAnswers(hostname: String, rawJson: String): Pair<List<InetAddress>, Long> {
        val root = runCatching { json.parseToJsonElement(rawJson).jsonObject }.getOrNull()
            ?: return emptyList<InetAddress>() to 120_000L
        val status = root["Status"]?.jsonPrimitive?.intOrNull ?: 0
        if (status != 0) return emptyList<InetAddress>() to 60_000L

        val answers = root["Answer"]?.let { runCatching { it.jsonArray }.getOrNull() }
            ?: return emptyList<InetAddress>() to 60_000L

        val out = ArrayList<InetAddress>()
        var minTtlSec = 300L
        for (el in answers) {
            val obj = runCatching { el.jsonObject }.getOrNull() ?: continue
            val type = obj["type"]?.jsonPrimitive?.intOrNull ?: continue
            val data = obj["data"]?.jsonPrimitive?.content?.trim().orEmpty()
            val ttl = obj["TTL"]?.jsonPrimitive?.intOrNull?.toLong() ?: 300L
            if (type == 1 || type == 28) {
                runCatching {
                    val parsedIp = InetAddress.getByName(data)
                    if (!isPoisonedDirectIp(parsedIp) &&
                        ((type == 1 && parsedIp is Inet4Address) || (type == 28 && parsedIp is Inet6Address))
                    ) {
                        out += InetAddress.getByAddress(hostname, parsedIp.address)
                        minTtlSec = minOf(minTtlSec, ttl.coerceIn(60L, 900L))
                    }
                }
            }
        }
        return out to (minTtlSec * 1000L)
    }

    private fun resolveViaDoh(hostname: String): List<InetAddress> {
        for (endpoint in dohEndpoints) {
            val url = "$endpoint?name=$hostname&type=1"
            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/dns-json")
                .build()
            val body = runCatching {
                bootstrapClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) resp.body?.string() else null
                }
            }.getOrNull()
            if (!body.isNullOrBlank()) {
                val (addrs, ttlMs) = parseDohJsonAnswers(hostname, body)
                if (addrs.isNotEmpty()) {
                    cache[hostname] = CachedDnsEntry(
                        addresses = addrs,
                        expiresAtMs = System.currentTimeMillis() + ttlMs
                    )
                    return addrs
                }
            }
        }
        return emptyList()
    }

    fun clearCache() {
        cache.clear()
    }
}
