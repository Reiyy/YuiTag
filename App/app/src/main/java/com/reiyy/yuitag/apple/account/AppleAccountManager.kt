package com.reiyy.yuitag.apple.account

import android.content.Context
import android.content.SharedPreferences
import com.reiyy.yuitag.apple.anisette.AnisetteClient
import com.reiyy.yuitag.apple.anisette.AnisetteProvider
import com.reiyy.yuitag.apple.anisette.createAndroidAnisetteProvider
import com.reiyy.yuitag.apple.gsa.GsaClient
import com.reiyy.yuitag.apple.http.HttpTransport
import com.reiyy.yuitag.apple.http.OkHttpTransport
import com.reiyy.yuitag.apple.mobileme.MobileMeClient
import com.reiyy.yuitag.apple.reports.LocationReportsClient
import com.reiyy.yuitag.ble.FileLog

/**
 * Singleton managing Apple ID credentials, Anisette emulation, and network clients.
 */
class AppleAccountManager private constructor(private val appContext: Context) {

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    val http: HttpTransport = OkHttpTransport()

    val anisetteProvider: AnisetteProvider by lazy {
        createAndroidAnisetteProvider(appContext, http)
    }

    val anisetteClient: AnisetteClient by lazy {
        AnisetteClient(anisetteProvider)
    }

    val gsaClient: GsaClient by lazy {
        GsaClient(http, anisetteClient)
    }

    val mobileMeClient: MobileMeClient by lazy {
        MobileMeClient(http, anisetteClient)
    }

    val reportsClient: LocationReportsClient by lazy {
        LocationReportsClient(http, anisetteClient)
    }

    private var currentAccount: AppleAccount = loadAccount()

    fun getAccount(): AppleAccount = currentAccount

    @Synchronized
    fun saveAccount(account: AppleAccount) {
        currentAccount = account
        prefs.edit().putString(KEY_ACCOUNT_JSON, account.exportToJson()).apply()
        FileLog.i(TAG, "AppleAccount saved, state: ${account.loginState}")
    }

    @Synchronized
    fun clearAccount() {
        currentAccount = AppleAccount()
        prefs.edit().remove(KEY_ACCOUNT_JSON).apply()
        FileLog.i(TAG, "AppleAccount cleared")
    }

    fun isLoggedIn(): Boolean =
        currentAccount.loginState == LoginState.LOGGED_IN && currentAccount.getSearchPartyToken() != null

    fun createLoginService(): AppleLoginService {
        // 每次登录创建全新实例，避免旧会话残留或 2FA 死锁
        val freshAccount = AppleAccount()
        return AppleLoginService(
            account = freshAccount,
            http = http,
            anisette = anisetteClient,
            gsa = gsaClient,
            mobileMe = mobileMeClient,
        )
    }

    private fun loadAccount(): AppleAccount {
        val json = prefs.getString(KEY_ACCOUNT_JSON, null)
        if (!json.isNullOrEmpty()) {
            try {
                return AppleAccount.restoreFromJson(json)
            } catch (e: Exception) {
                FileLog.w(TAG, "Failed to restore AppleAccount from prefs: ${e.message}")
            }
        }
        return AppleAccount()
    }

    companion object {
        private const val TAG = "AppleAccountManager"
        private const val PREF_NAME = "yuitag_apple_account"
        private const val KEY_ACCOUNT_JSON = "apple_account_json"

        @Volatile
        private var INSTANCE: AppleAccountManager? = null

        fun getInstance(context: Context): AppleAccountManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AppleAccountManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
