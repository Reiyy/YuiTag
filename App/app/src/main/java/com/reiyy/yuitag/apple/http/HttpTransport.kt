package com.reiyy.yuitag.apple.http

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Minimal suspend HTTP abstraction used by the GSA / MobileMe / anisette bridge clients.
 */
interface HttpTransport {
    suspend fun execute(request: HttpRequest): HttpResponse
}

/**
 * Single HTTP request. [headers] preserves insertion order.
 */
data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val timeoutMs: Long? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HttpRequest) return false
        if (method != other.method) return false
        if (url != other.url) return false
        if (headers != other.headers) return false
        if (timeoutMs != other.timeoutMs) return false
        val a = body
        val b = other.body
        if (a == null && b == null) return true
        if (a == null || b == null) return false
        return a.contentEquals(b)
    }

    override fun hashCode(): Int {
        var h = method.hashCode()
        h = 31 * h + url.hashCode()
        h = 31 * h + headers.hashCode()
        h = 31 * h + (timeoutMs?.hashCode() ?: 0)
        h = 31 * h + (body?.contentHashCode() ?: 0)
        return h
    }
}

class HttpResponse(
    val statusCode: Int,
    headers: Map<String, String>,
    val body: ByteArray,
) {
    private val headersCaseInsensitive: Map<String, String> =
        headers.mapKeys { it.key.lowercase() }

    val headers: Map<String, String> = headers

    fun header(name: String): String? = headersCaseInsensitive[name.lowercase()]

    fun isOk(): Boolean = statusCode in 200..299
}

/**
 * Production [HttpTransport] backed by OkHttp.
 */
class OkHttpTransport(
    private val client: OkHttpClient = defaultClient,
) : HttpTransport {

    override suspend fun execute(request: HttpRequest): HttpResponse = withContext(Dispatchers.IO) {
        val reqBuilder = Request.Builder().url(request.url)

        for ((k, v) in request.headers) {
            reqBuilder.addHeader(k, v)
        }

        val requestBody = request.body?.let { bytes ->
            val contentType = request.headers.entries
                .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
                ?.value
            bytes.toRequestBody(contentType?.toMediaTypeOrNull())
        }

        reqBuilder.method(request.method, requestBody)

        val activeClient = if (request.timeoutMs != null && request.timeoutMs > 0) {
            client.newBuilder()
                .connectTimeout(request.timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(request.timeoutMs, TimeUnit.MILLISECONDS)
                .writeTimeout(request.timeoutMs, TimeUnit.MILLISECONDS)
                .build()
        } else {
            client
        }

        activeClient.newCall(reqBuilder.build()).execute().use { resp ->
            val respHeaders = mutableMapOf<String, String>()
            val headers = resp.headers
            for (i in 0 until headers.size) {
                respHeaders[headers.name(i)] = headers.value(i)
            }
            HttpResponse(
                statusCode = resp.code,
                headers = respHeaders,
                body = resp.body?.bytes() ?: ByteArray(0),
            )
        }
    }

    companion object {
        val defaultClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false)
            .apply {
                AppleTlsConfig.applyTo(this)
            }
            .build()
    }
}
