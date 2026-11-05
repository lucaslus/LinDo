package do_.linux.lindo.core.network

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpMethod
import io.ktor.http.Parameters

internal suspend fun executeApiRequest(
    client: HttpClient,
    url: String,
    method: String,
    formParams: Map<String, String>?,
    headers: Map<String, String>
): HttpResponse = client.request(url) {
    this.method = HttpMethod.parse(method.uppercase())
    headers.forEach { (key, value) -> header(key, value) }
    if (formParams != null) setBody(FormDataContent(Parameters.build {
        formParams.forEach { (key, value) -> append(key, value) }
    }))
}
