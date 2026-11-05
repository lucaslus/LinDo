package do_.linux.lindo.core.network

import kotlinx.serialization.json.*
import java.io.IOException

enum class ApiFailureKind { Connection, Authentication, Verification, RateLimited, Server, InvalidPayload }
class ApiReadException(val kind: ApiFailureKind, cause: Throwable? = null) : IOException(when (kind) {
    ApiFailureKind.Connection -> "网络连接失败，请检查网络后重试"
    ApiFailureKind.Authentication -> "登录状态已失效，请重新登录后重试"
    ApiFailureKind.Verification -> "没有访问权限或需要站点验证，请打开官网检查"
    ApiFailureKind.RateLimited -> "请求过于频繁，请稍后重试"
    ApiFailureKind.Server -> "服务暂时不可用，请稍后重试"
    ApiFailureKind.InvalidPayload -> "返回的数据不完整，请重试"
}, cause)

internal fun readApiObject(response: Pair<Int, String>?): JsonObject {
    if (response == null || response.first == 0) throw ApiReadException(ApiFailureKind.Connection)
    val (status, body) = response
    if (status !in 200..299) throw ApiReadException(when (status) {
        401 -> ApiFailureKind.Authentication
        403 -> ApiFailureKind.Verification
        429 -> ApiFailureKind.RateLimited
        else -> ApiFailureKind.Server
    })
    try {
        val root = Json.parseToJsonElement(body) as? JsonObject ?: throw ApiReadException(ApiFailureKind.InvalidPayload)
        if ((root["errors"] as? JsonArray)?.isNotEmpty() == true ||
            (root["errors"] as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true ||
            root["error_type"]?.let { it !is JsonNull } == true) throw ApiReadException(ApiFailureKind.InvalidPayload)
        return root
    } catch (error: ApiReadException) { throw error }
    catch (error: Exception) { throw ApiReadException(ApiFailureKind.InvalidPayload, error) }
}
internal fun JsonObject.requireArray(key: String): JsonArray = this[key] as? JsonArray
    ?: throw ApiReadException(ApiFailureKind.InvalidPayload)
