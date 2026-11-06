package com.reiyy.yuitag.apple.mobileme

import com.reiyy.yuitag.apple.anisette.AnisetteClient
import com.reiyy.yuitag.apple.anisette.MacAnisetteProvider
import com.reiyy.yuitag.apple.http.HttpRequest
import com.reiyy.yuitag.apple.http.HttpTransport
import com.reiyy.yuitag.apple.plist.PlistValue
import com.reiyy.yuitag.apple.plist.XmlPlist
import com.reiyy.yuitag.apple.plist.anyToPlist
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Exchanges GSA `idms_pet` token for iCloud delegates including `searchPartyToken`.
 */
@OptIn(ExperimentalEncodingApi::class)
class MobileMeClient(
    private val http: HttpTransport,
    private val anisette: AnisetteClient,
    private val endpoint: String = ENDPOINT,
) {

    suspend fun login(
        username: String,
        idmsPet: String,
        adsid: String,
    ): PlistValue.Dict {
        val machine = anisette.machine()
        val body = linkedMapOf<String, Any?>(
            "apple-id" to username,
            "delegates" to linkedMapOf<String, Any?>("com.apple.mobileme" to emptyMap<String, Any?>()),
            "password" to idmsPet,
            "client-id" to machine.localUserUuid,
        )
        val plistBody = XmlPlist.encode(anyToPlist(body))

        val headers = linkedMapOf<String, String>().apply {
            put("X-Apple-ADSID", adsid)
            put("User-Agent", "com.apple.iCloudHelper/282 ${MacAnisetteProvider.CFNETWORK_DARWIN}")
            put("X-Mme-Client-Info", machine.clientInfoFor("com.apple.AOSKit/282 (com.apple.accountsd/113)"))
            putAll(anisette.getHeaders())
            put("Authorization", basicAuth(username, idmsPet))
            put("Content-Type", "application/octet-stream")
            put("Accept", "*/*")
        }

        val resp = http.execute(
            HttpRequest(
                method = "POST",
                url = endpoint,
                headers = headers,
                body = plistBody,
            )
        )
        if (!resp.isOk()) {
            throw MobileMeRequestException("MobileMe login failed: HTTP ${resp.statusCode}")
        }
        val parsed = XmlPlist.parse(resp.body)
        require(parsed is PlistValue.Dict) { "MobileMe response is not a <dict>" }
        return parsed
    }

    private fun basicAuth(user: String, pass: String): String {
        val token = "$user:$pass"
        return "Basic " + Base64.encode(token.encodeToByteArray())
    }

    companion object {
        const val ENDPOINT: String = "https://setup.icloud.com/setup/iosbuddy/loginDelegates"
    }
}

class MobileMeRequestException(message: String) : RuntimeException(message)
