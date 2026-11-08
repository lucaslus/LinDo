package do_.linux.lindo.core.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.util.Base64

internal data class EchDnsAnswer(val data: String, val ttlSeconds: Int)

private val echParameter = Regex("""(?:^|\s)ech="?([A-Za-z0-9+/]+={0,2})(?="|\s|$)""")
private val ipv4Pattern = Regex("""\d{1,3}(\.\d{1,3}){3}""")

internal fun parseEchDnsAnswers(payload: String, name: String, type: Int): List<EchDnsAnswer> {
    val root = runCatching { Json.parseToJsonElement(payload) as? JsonObject }.getOrNull()
        ?: return emptyList()
    if ((root["Status"] as? JsonPrimitive)?.intOrNull != 0) return emptyList()
    return (root["Answer"] as? JsonArray).orEmpty().mapNotNull { element ->
        val answer = element as? JsonObject ?: return@mapNotNull null
        val owner = (answer["name"] as? JsonPrimitive)?.contentOrNull?.trimEnd('.')
        if (!name.equals(owner, ignoreCase = true) || (answer["type"] as? JsonPrimitive)?.intOrNull != type) {
            return@mapNotNull null
        }
        val data = (answer["data"] as? JsonPrimitive)?.contentOrNull?.trim() ?: return@mapNotNull null
        val ttl = (answer["TTL"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 } ?: return@mapNotNull null
        val accepted = when (type) {
            1 -> isPublicEchAddress(data) && !LinuxDoRepository.isIpPoisoned(data)
            65 -> extractEchConfig(data) != null
            else -> false
        }
        if (accepted) EchDnsAnswer(data, ttl) else null
    }.distinctBy { it.data }
}

private fun isPublicEchAddress(address: String): Boolean {
    if (!ipv4Pattern.matches(address)) return false
    val octets = address.split('.').map { it.toInt() }
    if (octets.any { it !in 0..255 }) return false
    val (first, second) = octets
    return first in 1..223 && first != 10 && first != 127 &&
        !(first == 100 && second in 64..127) && !(first == 169 && second == 254) &&
        !(first == 172 && second in 16..31) && !(first == 192 && second == 168) &&
        !(first == 198 && second in 18..19)
}

internal fun extractEchConfig(data: String): String? {
    val config = echParameter.find(data)?.groupValues?.get(1) ?: return null
    val bytes = runCatching { Base64.getDecoder().decode(config) }.getOrNull() ?: return null
    fun shortAt(offset: Int) = ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)
    if (bytes.size < 6 || shortAt(0) != bytes.size - 2) return null
    var offset = 2
    var hasSupportedVersion = false
    while (offset + 4 <= bytes.size) {
        val length = shortAt(offset + 2)
        if (length == 0 || offset + 4 + length > bytes.size) return null
        val end = offset + 4 + length
        if (shortAt(offset) == 0xfe0d) {
            var field = offset + 4 + 3 // config ID and KEM ID
            if (field + 2 > end) return null
            val keyLength = shortAt(field)
            field += 2 + keyLength
            if (keyLength == 0 || field + 2 > end) return null
            val suitesLength = shortAt(field)
            field += 2 + suitesLength + 1 // maximum name length
            if (suitesLength == 0 || suitesLength % 4 != 0 || field >= end) return null
            val publicNameLength = bytes[field].toInt() and 0xff
            field += 1 + publicNameLength
            if (publicNameLength == 0 || field + 2 > end || field + 2 + shortAt(field) != end) return null
            hasSupportedVersion = true
        }
        offset = end
    }
    return config.takeIf { offset == bytes.size && hasSupportedVersion }
}
