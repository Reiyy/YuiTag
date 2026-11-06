package com.reiyy.yuitag.apple.account

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

/**
 * Persistent state for a single Apple account — identity, credentials,
 * login state, and tokens (PET, ADSID, searchPartyToken).
 */
class AppleAccount(
    uid: String? = null,
    devid: String? = null,
) {
    var uid: String = uid ?: UUID.randomUUID().toString()
        private set
    var devid: String = devid ?: UUID.randomUUID().toString()
        private set

    var username: String? = null
    var password: String? = null

    var accountInfo: AccountInfo? = null
        internal set

    var loginState: LoginState = LoginState.LOGGED_OUT
        private set

    private var _loginStateData: MutableMap<String, Any?> = mutableMapOf()
    val loginStateData: Map<String, Any?> get() = _loginStateData

    fun loginStateString(key: String): String? = _loginStateData[key] as? String

    fun getDsid(): String? = loginStateString("dsid")?.takeIf { it.isNotBlank() }

    @Suppress("UNCHECKED_CAST")
    fun getSearchPartyToken(): String? {
        val mobileme = _loginStateData["mobileme_data"] as? Map<String, Any?> ?: return null
        val tokens = mobileme["tokens"] as? Map<String, Any?> ?: return null
        return tokens["searchPartyToken"] as? String
    }

    fun setLoginState(state: LoginState, data: Map<String, Any?>?) {
        if (state.value < loginState.value) {
            accountInfo = null
        }
        loginState = state
        _loginStateData = data?.toMutableMap() ?: mutableMapOf()
    }

    fun exportToJson(): String {
        val map = mapOf(
            "ids" to mapOf("uid" to uid, "devid" to devid),
            "account" to mapOf(
                "username" to username,
                "password" to password,
                "info" to accountInfo
            ),
            "login_state" to mapOf(
                "state" to loginState.value,
                "data" to _loginStateData
            )
        )
        return GSON.toJson(map)
    }

    companion object {
        private val GSON = Gson()

        @Suppress("UNCHECKED_CAST")
        fun restoreFromJson(json: String): AppleAccount = try {
            val type = object : TypeToken<Map<String, Any?>>() {}.type
            val map: Map<String, Any?> = GSON.fromJson(json, type)
            val ids = map["ids"] as? Map<String, Any?> ?: emptyMap()
            val account = map["account"] as? Map<String, Any?> ?: emptyMap()
            val login = map["login_state"] as? Map<String, Any?> ?: emptyMap()

            val acc = AppleAccount(
                uid = ids["uid"] as? String,
                devid = ids["devid"] as? String,
            )
            acc.username = account["username"] as? String
            acc.password = account["password"] as? String

            account["info"]?.let { infoMap ->
                val infoJson = GSON.toJson(infoMap)
                acc.accountInfo = GSON.fromJson(infoJson, AccountInfo::class.java)
            }

            val stateNum = (login["state"] as? Number)?.toInt() ?: LoginState.LOGGED_OUT.value
            acc.loginState = LoginState.fromValue(stateNum)
            val d = login["data"] as? Map<String, Any?>
            acc._loginStateData = d?.toMutableMap() ?: mutableMapOf()
            acc
        } catch (e: Exception) {
            throw IllegalArgumentException("Failed to restore AppleAccount from JSON: ${e.message}", e)
        }
    }
}
