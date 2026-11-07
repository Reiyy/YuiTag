/*
 * Portions of this file are derived from TagHistory (https://github.com/tieo/TagHistory)
 * Copyright (c) 2025 Shane B.
 * Copyright (c) 2025-2026 tieo.
 *
 * Licensed under the GNU General Public License v2.0 or later.
 */

package com.reiyy.yuitag.apple.account

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.reiyy.yuitag.apple.anisette.AnisetteClient
import com.reiyy.yuitag.apple.crypto.GsaCrypto
import com.reiyy.yuitag.apple.crypto.Srp6aGsa
import com.reiyy.yuitag.apple.gsa.GSA_CONNECTION
import com.reiyy.yuitag.apple.gsa.GsaClient
import com.reiyy.yuitag.apple.http.HttpRequest
import com.reiyy.yuitag.apple.http.HttpTransport
import com.reiyy.yuitag.apple.mobileme.MobileMeClient
import com.reiyy.yuitag.apple.plist.PlistValue
import com.reiyy.yuitag.apple.plist.XmlPlist
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Orchestrates Apple authentication across GSA SRP-6a, 2FA challenge dispatch,
 * and MobileMe delegate exchange to obtain [AppleAccount] credentials and `searchPartyToken`.
 */
@OptIn(ExperimentalEncodingApi::class)
class AppleLoginService(
    val account: AppleAccount,
    private val http: HttpTransport,
    private val anisette: AnisetteClient,
    private val gsa: GsaClient,
    private val mobileMe: MobileMeClient,
) : TwoFactorCoordinator {

    suspend fun login(username: String, password: String): LoginResult {
        if (account.loginState == LoginState.REQUIRE_2FA) {
            account.setLoginState(LoginState.LOGGED_OUT, null)
        }
        requireState(LoginState.LOGGED_OUT)
        val afterGsa = gsaAuthenticate(username, password)
        return if (afterGsa == LoginState.REQUIRE_2FA) {
            val methods = fetchTwoFactorMethods()
            if (methods.isEmpty()) {
                throw AppleLoginException(
                    AppleLoginException.Kind.UNHANDLED_PROTOCOL,
                    "Apple ID 要求双重认证，但未能获取到可用的验证设备或验证手机号，请检查网络或稍后重试",
                )
            }
            LoginResult.RequireTwoFactor(methods = methods)
        } else {
            loginMobileMe()
            LoginResult.LoggedIn
        }
    }

    private suspend fun fetchTwoFactorMethods(): List<TwoFactorChallenge> {
        requireState(LoginState.REQUIRE_2FA)
        val out = mutableListOf<TwoFactorChallenge>()

        if (account.accountInfo?.trustedDevice2fa == true) {
            out += TwoFactorChallenge.TrustedDevice(coordinator = this)
        }

        val html = twoFactorRequest("GET", ENDPOINT_2FA_METHODS, data = null, extraHeaders = null)
        for (number in extractPhoneNumbers(html)) {
            val id = (number["id"] as? Number)?.toInt() ?: -1
            val label = number["numberWithDialCode"] as? String ?: "-"
            out += TwoFactorChallenge.Sms(id, label, coordinator = this)
        }
        return out
    }

    override suspend fun requestSms(phoneNumberId: Int) {
        requireState(LoginState.REQUIRE_2FA)
        val data = mutableMapOf<String, Any?>(
            "phoneNumber" to mapOf("id" to phoneNumberId),
            "mode" to "sms",
        )
        twoFactorRequest("PUT", ENDPOINT_2FA_SMS_REQUEST, data, null)
    }

    override suspend fun submitSms(phoneNumberId: Int, code: String): LoginResult {
        requireState(LoginState.REQUIRE_2FA)
        val data = mutableMapOf<String, Any?>(
            "phoneNumber" to mapOf("id" to phoneNumberId),
            "securityCode" to mapOf("code" to code),
            "mode" to "sms",
        )
        twoFactorRequest("POST", ENDPOINT_2FA_SMS_SUBMIT, data, null)

        completeTwoFactor()
        return LoginResult.LoggedIn
    }

    override suspend fun requestTrustedDevice() {
        requireState(LoginState.REQUIRE_2FA)
        val headers = linkedMapOf(
            "Content-Type" to "text/x-xml-plist",
            "Accept" to "text/x-xml-plist",
        )
        twoFactorRequest("GET", ENDPOINT_2FA_TD_REQUEST, data = null, extraHeaders = headers)
    }

    override suspend fun submitTrustedDevice(code: String): LoginResult {
        requireState(LoginState.REQUIRE_2FA)
        val headers = linkedMapOf(
            "security-code" to code,
            "Content-Type" to "text/x-xml-plist",
            "Accept" to "text/x-xml-plist",
        )
        twoFactorRequest("GET", ENDPOINT_2FA_TD_SUBMIT, data = null, extraHeaders = headers)

        completeTwoFactor()
        return LoginResult.LoggedIn
    }

    private suspend fun completeTwoFactor() {
        val after = gsaAuthenticate(null, null)
        if (after != LoginState.AUTHENTICATED) {
            throw AppleLoginException(
                AppleLoginException.Kind.UNHANDLED_PROTOCOL,
                "Unexpected state after submitting 2FA: $after",
            )
        }
        loginMobileMe()
    }

    private suspend fun gsaAuthenticate(username: String?, password: String?): LoginState {
        username?.let { account.username = it }
        password?.let { account.password = it }
        val u = account.username ?: throw AppleLoginException(
            AppleLoginException.Kind.INVALID_STATE, "No username or password specified"
        )
        val p = account.password ?: throw AppleLoginException(
            AppleLoginException.Kind.INVALID_STATE, "No username or password specified"
        )

        val srp = Srp6aGsa(u)
        val a2k = srp.startAuthentication()

        val init = mutableMapOf<String, Any?>(
            "A2k" to a2k,
            "u" to u,
            "ps" to listOf("s2k", "s2k_fo"),
            "o" to "init",
        )
        val initResp = gsa.request(init)

        val initStatus = requireStatus(initResp)
        if (initStatus.int64("ec") != 0L) {
            throw AppleLoginException(
                AppleLoginException.Kind.INVALID_CREDENTIALS,
                "Email verification failed: ${initStatus.string("em")} (${initStatus.int64("ec")})",
            )
        }
        val sp = initResp.string("sp")
        if (sp != GsaCrypto.PROTOCOL_S2K && sp != GsaCrypto.PROTOCOL_S2K_FO) {
            throw AppleLoginException(
                AppleLoginException.Kind.UNHANDLED_PROTOCOL,
                "Unsupported SRP protocol: $sp",
            )
        }

        val salt = initResp.data("s")
            ?: throw protocolError("GSA init response missing 's' (salt)")
        val serverB = initResp.data("B")
            ?: throw protocolError("GSA init response missing 'B' (server ephemeral)")
        val iterations = initResp.int64("i")?.toInt()
            ?: throw protocolError("GSA init response missing 'i' (iterations)")
        val cookie = initResp["c"]
            ?: throw protocolError("GSA init response missing 'c' (cookie)")

        val pbkdfPw = GsaCrypto.encryptPassword(p, salt, iterations, sp)
        val m1 = srp.processChallenge(salt, serverB, pbkdfPw)

        val complete = mutableMapOf<String, Any?>(
            "c" to cookie,
            "M1" to m1,
            "u" to u,
            "o" to "complete",
        )
        val completeResp = gsa.request(complete)

        val completeStatus = requireStatus(completeResp)
        if (completeStatus.int64("ec") != 0L) {
            throw AppleLoginException(
                AppleLoginException.Kind.INVALID_CREDENTIALS,
                "Password authentication failed: ${completeStatus.string("em")} (${completeStatus.int64("ec")})",
            )
        }

        val m2 = completeResp.data("M2")
            ?: throw protocolError("GSA complete response missing 'M2'")
        if (!srp.verifySession(m2)) {
            throw AppleLoginException(
                AppleLoginException.Kind.UNHANDLED_PROTOCOL,
                "Failed to verify SRP session (M2 mismatch)",
            )
        }

        val spdCiphertext = completeResp.data("spd")
            ?: throw protocolError("GSA complete response missing 'spd'")
        val sessionKey = srp.getSessionKey()
            ?: throw protocolError("SRP session key unavailable")
        val spdPlain = GsaCrypto.decryptSpdAesCbc(sessionKey, spdCiphertext)
        val spd = XmlPlist.parse(spdPlain) as? PlistValue.Dict
            ?: throw protocolError("SPD payload is not a <dict>")

        val info = AccountInfo(
            accountName = spd.string("acname"),
            firstName = spd.string("fn"),
            lastName = spd.string("ln"),
            trustedDevice2fa = false,
        )
        account.accountInfo = info

        val au = completeStatus.string("au")
        return when (au) {
            "secondaryAuth", "trustedDeviceSecondaryAuth" -> {
                info.trustedDevice2fa = au == "trustedDeviceSecondaryAuth"
                account.setLoginState(
                    LoginState.REQUIRE_2FA,
                    mapOf(
                        "adsid" to spd.string("adsid").orEmpty(),
                        "idms_token" to spd.string("GsIdmsToken").orEmpty(),
                    ),
                )
                LoginState.REQUIRE_2FA
            }
            null -> {
                val idmsPet = spd.dict("t")
                    ?.dict("com.apple.gs.idms.pet")
                    ?.string("token")
                    .orEmpty()
                account.setLoginState(
                    LoginState.AUTHENTICATED,
                    mapOf(
                        "idms_pet" to idmsPet,
                        "adsid" to spd.string("adsid").orEmpty(),
                    ),
                )
                LoginState.AUTHENTICATED
            }
            else -> throw AppleLoginException(
                AppleLoginException.Kind.UNHANDLED_PROTOCOL,
                "Unknown auth value: $au",
            )
        }
    }

    private suspend fun loginMobileMe(): LoginState {
        requireState(LoginState.AUTHENTICATED)
        val idmsPet = account.loginStateString("idms_pet") ?: ""
        val adsid = account.loginStateString("adsid") ?: ""
        val uname = account.username
            ?: throw AppleLoginException(AppleLoginException.Kind.INVALID_STATE, "No username")

        val response = mobileMe.login(uname, idmsPet, adsid)

        val delegates = response.dict("delegates")
        val mobileMeData = delegates?.dict("com.apple.mobileme")

        val status = mobileMeData?.int64("status") ?: response.int64("status")
        if (status == null || status != 0L) {
            val msg = mobileMeData?.string("status-message") ?: response.string("status-message")
            throw AppleLoginException(
                AppleLoginException.Kind.UNHANDLED_PROTOCOL,
                "com.apple.mobileme login failed: $status / $msg",
            )
        }

        val dsidStr = when (val d = response["dsid"]) {
            is PlistValue.Str -> d.value
            is PlistValue.Int64 -> d.value.toString()
            else -> ""
        }
        val serviceData = mobileMeData?.get("service-data")?.let(::plistToAny) ?: emptyMap<String, Any?>()

        // 登录成功获取到授权令牌后，立即清空内存中的主密码，防止明文持久化到磁盘
        account.password = null
        account.setLoginState(
            LoginState.LOGGED_IN,
            mapOf(
                "dsid" to dsidStr,
                "mobileme_data" to serviceData,
            ),
        )
        return LoginState.LOGGED_IN
    }

    private suspend fun twoFactorRequest(
        method: String,
        url: String,
        data: Map<String, Any?>?,
        extraHeaders: Map<String, String>?,
    ): String {
        val adsid = account.loginStateString("adsid") ?: ""
        val idmsToken = account.loginStateString("idms_token") ?: ""
        val identityToken = Base64.encode("$adsid:$idmsToken".encodeToByteArray())

        val headers = linkedMapOf<String, String>(
            "User-Agent" to "Xcode",
            "Accept-Language" to "en-us",
            "X-Apple-Identity-Token" to identityToken,
            GSA_CONNECTION,
        )
        extraHeaders?.let { headers.putAll(it) }
        headers.putAll(anisette.getHeaders(withClientInfo = true))

        val body = data?.let {
            if ("Content-Type" !in headers) {
                headers["Content-Type"] = "application/json"
            }
            GSON.toJson(it).encodeToByteArray()
        }

        val resp = http.execute(
            HttpRequest(
                method = method,
                url = url,
                headers = headers,
                body = body,
            )
        )
        if (!resp.isOk()) {
            throw AppleLoginException(
                AppleLoginException.Kind.UNHANDLED_PROTOCOL,
                "2FA request failed: HTTP ${resp.statusCode}",
            )
        }
        return resp.body.decodeToString()
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractPhoneNumbers(html: String): List<Map<String, Any?>> {
        val pattern = Regex(
            """<script[^>]*class="boot_args"[^>]*>([\s\S]*?)</script>""",
            RegexOption.IGNORE_CASE,
        )
        val match = pattern.find(html) ?: return emptyList()
        val json = match.groupValues[1].trim()
        return try {
            val type = object : TypeToken<Map<String, Any?>>() {}.type
            val root: Map<String, Any?> = GSON.fromJson(json, type)
            val direct = root["direct"] as? Map<String, Any?> ?: return emptyList()
            val pnv = direct["phoneNumberVerification"] as? Map<String, Any?> ?: return emptyList()
            (pnv["trustedPhoneNumbers"] as? List<Map<String, Any?>>) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun requireState(expected: LoginState) {
        if (account.loginState != expected) {
            throw AppleLoginException(
                AppleLoginException.Kind.INVALID_STATE,
                "Required state $expected but account is ${account.loginState}",
            )
        }
    }

    private fun requireStatus(resp: PlistValue.Dict): PlistValue.Dict =
        resp.dict("Status")
            ?: throw protocolError("GSA response missing 'Status' dictionary")

    private fun protocolError(message: String): AppleLoginException =
        AppleLoginException(AppleLoginException.Kind.UNHANDLED_PROTOCOL, message)

    private fun plistToAny(value: PlistValue): Any? = when (value) {
        PlistValue.Null -> null
        is PlistValue.Bool -> value.value
        is PlistValue.Int64 -> value.value
        is PlistValue.Real -> value.value
        is PlistValue.Str -> value.value
        is PlistValue.Data -> Base64.encode(value.bytes)
        is PlistValue.Date -> value.epochMillis
        is PlistValue.Uid -> value.value
        is PlistValue.Dict -> value.entries.mapValues { plistToAny(it.value) }
        is PlistValue.Array -> value.items.map { plistToAny(it) }
    }

    companion object {
        private const val ENDPOINT_2FA_METHODS = "https://gsa.apple.com/auth"
        private const val ENDPOINT_2FA_SMS_REQUEST = "https://gsa.apple.com/auth/verify/phone"
        private const val ENDPOINT_2FA_SMS_SUBMIT =
            "https://gsa.apple.com/auth/verify/phone/securitycode"
        private const val ENDPOINT_2FA_TD_REQUEST =
            "https://gsa.apple.com/auth/verify/trusteddevice"
        private const val ENDPOINT_2FA_TD_SUBMIT =
            "https://gsa.apple.com/grandslam/GsService2/validate"

        private val GSON = Gson()
    }
}
