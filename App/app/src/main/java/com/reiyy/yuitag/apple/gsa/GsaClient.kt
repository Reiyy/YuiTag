package com.reiyy.yuitag.apple.gsa

import com.reiyy.yuitag.apple.anisette.AnisetteClient
import com.reiyy.yuitag.apple.http.HttpRequest
import com.reiyy.yuitag.apple.http.HttpTransport
import com.reiyy.yuitag.apple.plist.PlistValue
import com.reiyy.yuitag.apple.plist.XmlPlist
import com.reiyy.yuitag.apple.plist.anyToPlist
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Low-level client for Apple's Grand Slam Authentication endpoint.
 *
 * Handles plist envelope wrapping (Header + Request.cpd + parameters)
 * and unwraps `["Response"]`. Supports Apple's `hsc 434` Resync challenge.
 */
class GsaClient(
    private val http: HttpTransport,
    private val anisette: AnisetteClient,
    private val endpoint: String = ENDPOINT_GSA,
) {

    suspend fun request(parameters: Map<String, Any?>): PlistValue.Dict {
        val first = send(parameters)
        val sim = resyncSim(first) ?: return first
        anisette.resync(sim)
        return send(parameters)
    }

    private suspend fun send(parameters: Map<String, Any?>): PlistValue.Dict {
        val bodyDict = linkedMapOf<String, Any?>(
            "Header" to linkedMapOf<String, Any?>("Version" to "1.0.1"),
            "Request" to linkedMapOf<String, Any?>().apply {
                put("cpd", anisette.getCpd())
                putAll(parameters)
            },
        )
        val plistBody = XmlPlist.encode(anyToPlist(bodyDict))

        val req = HttpRequest(
            method = "POST",
            url = endpoint,
            headers = linkedMapOf(
                "Content-Type" to "text/x-xml-plist",
                "Accept" to "*/*",
                "User-Agent" to "akd/1.0 CFNetwork/978.0.7 Darwin/18.7.0",
                "X-MMe-Client-Info" to anisette.machine().clientInfo,
                GSA_CONNECTION,
            ),
            body = plistBody,
        )

        val resp = http.execute(req)
        if (!resp.isOk()) {
            val body = resp.body.decodeToString()
            val title = Regex("<title>(.*?)</title>", RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1)?.trim()
            val detail = body.take(300).replace(Regex("\\s+"), " ").trim()
            throw GsaRequestException(
                "GSA request failed: HTTP " + when {
                    !title.isNullOrEmpty() -> title.removePrefix("${resp.statusCode}").trim().let { "${resp.statusCode} $it" }
                    detail.isEmpty() -> "${resp.statusCode}"
                    else -> "${resp.statusCode}: $detail"
                },
            )
        }

        val parsed = XmlPlist.parse(resp.body)
        require(parsed is PlistValue.Dict) { "GSA response is not a <dict>" }
        val response = parsed["Response"]
        require(response is PlistValue.Dict) { "GSA response missing 'Response' dictionary" }
        return response
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun resyncSim(response: PlistValue.Dict): ByteArray? {
        val status = response.dict("Status") ?: return null
        if (status.int64("hsc") != HSC_RESYNC) return null
        return when (val sim = status["X-Apple-I-MD-DATA"]) {
            is PlistValue.Data -> sim.bytes
            is PlistValue.Str -> Base64.decode(sim.value)
            else -> null
        }
    }

    companion object {
        const val ENDPOINT_GSA: String = "https://gsa.apple.com/grandslam/GsService2"
        private const val HSC_RESYNC = 434L
    }
}

class GsaRequestException(message: String) : RuntimeException(message)

val GSA_CONNECTION: Pair<String, String> = "Connection" to "close"
