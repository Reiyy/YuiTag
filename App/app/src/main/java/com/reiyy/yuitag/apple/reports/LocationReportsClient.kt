package com.reiyy.yuitag.apple.reports

import com.reiyy.yuitag.apple.account.AppleAccount
import com.reiyy.yuitag.apple.account.AppleLoginException
import com.reiyy.yuitag.apple.account.LoginState
import com.reiyy.yuitag.apple.anisette.AnisetteClient
import com.reiyy.yuitag.apple.http.HttpRequest
import com.reiyy.yuitag.apple.http.HttpTransport
import com.reiyy.yuitag.ble.FileLog
import org.json.JSONArray
import org.json.JSONObject
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Fetcher for Apple's FindMy location reports endpoint (`https://gateway.icloud.com/acsnservice/fetch`).
 */
@OptIn(ExperimentalEncodingApi::class)
class LocationReportsClient(
    private val http: HttpTransport,
    private val anisette: AnisetteClient,
    private val endpoint: String = ENDPOINT_REPORTS_FETCH,
) {

    suspend fun fetchRawJson(
        account: AppleAccount,
        startEpochMs: Long,
        endEpochMs: Long,
        hashedAdvKeysB64: List<String>,
    ): List<JSONObject> {
        if (account.loginState != LoginState.LOGGED_IN) {
            throw AppleLoginException(
                AppleLoginException.Kind.INVALID_STATE,
                "查询位置要求处于已登录状态 (LOGGED_IN)，当前为 ${account.loginState}",
            )
        }

        val dsid = account.getDsid()
            ?: throw AppleLoginException(
                AppleLoginException.Kind.INVALID_STATE,
                "Apple 账户状态缺失 dsid",
            )
        val searchPartyToken = account.getSearchPartyToken()
            ?: throw AppleLoginException(
                AppleLoginException.Kind.INVALID_STATE,
                "Apple 账户缺失 searchPartyToken (尚未获取位置服务授权令牌)",
            )

        val searchItem = JSONObject().apply {
            put("startDate", startEpochMs)
            put("endDate", endEpochMs)
            put("ids", JSONArray(hashedAdvKeysB64))
        }
        val requestBody = JSONObject().apply {
            put("search", JSONArray().put(searchItem))
        }

        val payload = requestBody.toString().encodeToByteArray()
        val basic = Base64.encode("$dsid:$searchPartyToken".encodeToByteArray())

        val headers = linkedMapOf<String, String>(
            "User-Agent" to USER_AGENT_SEARCHPARTY,
            "Content-Type" to "application/json",
            "Accept" to "application/json",
            "Authorization" to "Basic $basic",
        )
        headers.putAll(anisette.getHeaders())

        val resp = http.execute(
            HttpRequest(
                method = "POST",
                url = endpoint,
                headers = headers,
                body = payload,
            )
        )

        FileLog.d(TAG, "acsnservice/fetch: HTTP ${resp.statusCode}, keys=${hashedAdvKeysB64.size}, bodyBytes=${resp.body.size}")

        if (resp.statusCode == 401) {
            throw AppleLoginException(
                AppleLoginException.Kind.UNAUTHORIZED,
                "位置查询认证失败 (HTTP 401)，searchPartyToken 可能已过期，需重新登录",
            )
        }
        if (resp.statusCode == 403) {
            throw AppleForbiddenException("Apple 拒绝访问 (HTTP 403，可能被风控或 IP 受限)")
        }
        if (resp.statusCode == 429) {
            throw AppleRateLimitException("Apple 请求过于频繁 (HTTP 429，已被限流)")
        }
        if (!resp.isOk()) {
            throw ReportsFetchException("Apple 报告端点请求失败: HTTP ${resp.statusCode}")
        }

        val bodyStr = resp.body.decodeToString()
        val json = JSONObject(bodyStr)
        val status = json.optString("statusCode", "")
        if (status.isNotEmpty() && status != "200") {
            throw ReportsFetchException("Apple 报告端点返回 statusCode=$status")
        }

        val results = json.optJSONArray("results") ?: return emptyList()
        val out = mutableListOf<JSONObject>()
        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i)
            if (item != null) out.add(item)
        }
        return out
    }

    /**
     * 发起轻量真实探测，向 Apple FindMy 网关发送最小化请求，
     * 验证网络连通性、TLS 握手、Anisette 机器头及 searchPartyToken 的有效性。
     *
     * @param timeoutMs 单次探测超时（默认 12 秒）
     * @return 往返耗时（毫秒）
     */
    suspend fun probeConnection(account: AppleAccount, timeoutMs: Long = PROBE_TIMEOUT_MS): Long {
        if (account.loginState != LoginState.LOGGED_IN) {
            throw AppleLoginException(
                AppleLoginException.Kind.INVALID_STATE,
                "查询位置要求处于已登录状态 (LOGGED_IN)，当前为 ${account.loginState}",
            )
        }

        val dsid = account.getDsid()
            ?: throw AppleLoginException(
                AppleLoginException.Kind.INVALID_STATE,
                "Apple 账户状态缺失 dsid",
            )
        val searchPartyToken = account.getSearchPartyToken()
            ?: throw AppleLoginException(
                AppleLoginException.Kind.INVALID_STATE,
                "Apple 账户缺失 searchPartyToken (尚未获取位置服务授权令牌)",
            )

        val nowMs = System.currentTimeMillis()
        val dummyId = Base64.encode(ByteArray(32))
        val searchItem = JSONObject().apply {
            put("startDate", nowMs)
            put("endDate", nowMs)
            put("ids", JSONArray().put(dummyId))
        }
        val requestBody = JSONObject().apply {
            put("search", JSONArray().put(searchItem))
        }

        val payload = requestBody.toString().encodeToByteArray()
        val basic = Base64.encode("$dsid:$searchPartyToken".encodeToByteArray())

        val headers = linkedMapOf<String, String>(
            "User-Agent" to USER_AGENT_SEARCHPARTY,
            "Content-Type" to "application/json",
            "Accept" to "application/json",
            "Authorization" to "Basic $basic",
        )
        headers.putAll(anisette.getHeaders())

        val startTick = System.currentTimeMillis()
        val resp = http.execute(
            HttpRequest(
                method = "POST",
                url = endpoint,
                headers = headers,
                body = payload,
                timeoutMs = timeoutMs,
            )
        )
        val durationMs = (System.currentTimeMillis() - startTick).coerceAtLeast(1L)

        FileLog.d(TAG, "probeConnection: HTTP ${resp.statusCode}, cost=${durationMs}ms")

        if (resp.statusCode == 401) {
            throw AppleLoginException(
                AppleLoginException.Kind.UNAUTHORIZED,
                "位置查询认证失败 (HTTP 401)，searchPartyToken 可能已过期，需重新登录",
            )
        }
        if (resp.statusCode == 403) {
            throw AppleForbiddenException("Apple 拒绝访问 (HTTP 403，可能被风控或 IP 受限)")
        }
        if (resp.statusCode == 429) {
            throw AppleRateLimitException("Apple 请求过于频繁 (HTTP 429，已被限流)")
        }
        if (!resp.isOk()) {
            throw ReportsFetchException("Apple 报告端点请求失败: HTTP ${resp.statusCode}")
        }

        val bodyStr = resp.body.decodeToString()
        val json = try { JSONObject(bodyStr) } catch (_: Exception) { null }
        val status = json?.optString("statusCode", "")
        if (!status.isNullOrEmpty() && status != "200") {
            throw ReportsFetchException("Apple 报告端点返回 statusCode=$status")
        }

        return durationMs
    }

    companion object {
        private const val TAG = "LocationReportsClient"
        const val ENDPOINT_REPORTS_FETCH: String =
            "https://gateway.icloud.com/acsnservice/fetch"
        const val USER_AGENT_SEARCHPARTY: String =
            "searchpartyd/1.0 CFNetwork/1404.0.5 Darwin/22.2.0"
        const val PROBE_TIMEOUT_MS: Long = 12_000L
    }
}

open class ReportsFetchException(message: String) : RuntimeException(message)

class AppleRateLimitException(message: String = "Apple 请求过于频繁 (HTTP 429)，已被限流") : ReportsFetchException(message)

class AppleForbiddenException(message: String = "Apple 拒绝访问 (HTTP 403，可能被风控或 IP 受限)") : ReportsFetchException(message)
