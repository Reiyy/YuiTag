package com.reiyy.yuitag.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.reiyy.yuitag.ble.CryptoUtils
import com.reiyy.yuitag.ble.FileLog
import com.reiyy.yuitag.data.local.AddressComponents
import com.reiyy.yuitag.data.local.AddressLevel
import com.reiyy.yuitag.data.local.LocationEntity
import com.reiyy.yuitag.util.AmapGeocodeUtil
import com.reiyy.yuitag.util.GeocodeProvider
import com.reiyy.yuitag.util.TiandituGeocodeUtil
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FindMyQueryService(private val context: Context) {
    companion object {
        const val GEOCODE_PROVIDER_AMAP = "amap"
        const val GEOCODE_PROVIDER_TIANDITU = "tianditu"

        private const val TAG = "FindMyQuery"
        private const val EXTENSION_DAYS = 7
        private const val PREF_NAME = "yuitag_settings"
        private const val KEY_GEOCODE_PROVIDER = "geocode_provider"
        private const val KEY_ENDPOINT_URL = "endpoint_url"
        private const val KEY_ENDPOINT_URL_FALLBACK = "endpoint_url_fallback"
        private const val KEY_ENDPOINT_USER = "endpoint_user"
        private const val KEY_ENDPOINT_PASS = "endpoint_pass"
        private const val KEY_QUERY_EXTENSION_TYPE = "query_extension_type"
        private const val KEY_QUERY_EXTENSION_VALUE = "query_extension_value"
        private const val KEY_ADDRESS_LEVEL = "address_level"
        private const val KEY_BATCH_KEY_LIMIT = "query_batch_key_limit"
        private const val KEY_CHUNK_RETRY_COUNT = "query_chunk_retry_count"
        private const val KEY_CHUNK_RETRY_DELAY = "query_chunk_retry_delay"
        private const val KEY_TIANDITU_KEY = "tianditu_key"
        private const val KEY_TIANDITU_STYLE = "tianditu_style"
        private const val KEY_HEALTH_NORMAL_DAYS = "health_normal_days"
        private const val KEY_LITE_MODE = "lite_mode_enabled"
        private const val KEY_DEBUG_MODE = "debug_mode_enabled"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val amapGeocode = AmapGeocodeUtil(context)
    private val tiandituGeocode = TiandituGeocodeUtil(context)

    fun getEndpointUrl(): String = prefs.getString(KEY_ENDPOINT_URL, "") ?: ""
    fun getEndpointUrlFallback(): String = prefs.getString(KEY_ENDPOINT_URL_FALLBACK, "") ?: ""
    fun getEndpointUser(): String = prefs.getString(KEY_ENDPOINT_USER, "") ?: ""
    fun getEndpointPass(): String = prefs.getString(KEY_ENDPOINT_PASS, "") ?: ""
    fun getExtensionType(): String = prefs.getString(KEY_QUERY_EXTENSION_TYPE, "time") ?: "time"
    fun getExtensionValue(): Float = prefs.getFloat(KEY_QUERY_EXTENSION_VALUE, 2.0f)
    fun getAddressLevel(): String = prefs.getString(KEY_ADDRESS_LEVEL, AddressLevel.STREET.key) ?: AddressLevel.STREET.key

    fun saveEndpointUrl(url: String) { prefs.edit().putString(KEY_ENDPOINT_URL, url).apply() }
    fun saveEndpointUrlFallback(url: String) { prefs.edit().putString(KEY_ENDPOINT_URL_FALLBACK, url).apply() }
    fun saveEndpointUser(user: String) { prefs.edit().putString(KEY_ENDPOINT_USER, user).apply() }
    fun saveEndpointPass(pass: String) { prefs.edit().putString(KEY_ENDPOINT_PASS, pass).apply() }
    fun saveExtensionType(type: String) { prefs.edit().putString(KEY_QUERY_EXTENSION_TYPE, type).apply() }
    fun saveExtensionValue(value: Float) { prefs.edit().putFloat(KEY_QUERY_EXTENSION_VALUE, value).apply() }
    fun saveAddressLevel(level: String) { prefs.edit().putString(KEY_ADDRESS_LEVEL, level).apply() }

    fun getBatchKeyLimit(): Int = prefs.getInt(KEY_BATCH_KEY_LIMIT, 100)
    fun saveBatchKeyLimit(value: Int) { prefs.edit().putInt(KEY_BATCH_KEY_LIMIT, value).apply() }
    fun getChunkRetryCount(): Int = prefs.getInt(KEY_CHUNK_RETRY_COUNT, 1)
    fun saveChunkRetryCount(value: Int) { prefs.edit().putInt(KEY_CHUNK_RETRY_COUNT, value).apply() }
    fun getChunkRetryDelaySec(): Int = prefs.getInt(KEY_CHUNK_RETRY_DELAY, 1)
    fun saveChunkRetryDelaySec(value: Int) { prefs.edit().putInt(KEY_CHUNK_RETRY_DELAY, value).apply() }

    fun getGeocodeProvider(): String =
        prefs.getString(KEY_GEOCODE_PROVIDER, GEOCODE_PROVIDER_AMAP) ?: GEOCODE_PROVIDER_AMAP
    fun saveGeocodeProvider(provider: String) {
        prefs.edit().putString(KEY_GEOCODE_PROVIDER, provider).apply()
    }

    fun getAmapKey(): String = amapGeocode.getAmapKey()
    fun saveAmapKey(key: String) { amapGeocode.saveAmapKey(key) }

    fun getTiandituGeocodeKey(): String = tiandituGeocode.getDirectGeocodeKey()
    fun saveTiandituGeocodeKey(key: String) { tiandituGeocode.saveGeocodeKey(key) }

    // --- 天地图瓦片（可选，用户自填 key；空则回退 OSM） ---
    fun getTiandituKey(): String = prefs.getString(KEY_TIANDITU_KEY, "") ?: ""
    fun saveTiandituKey(key: String) { prefs.edit().putString(KEY_TIANDITU_KEY, key).apply() }
    fun getTiandituStyle(): String = prefs.getString(KEY_TIANDITU_STYLE, "vector") ?: "vector"
    fun saveTiandituStyle(style: String) { prefs.edit().putString(KEY_TIANDITU_STYLE, style).apply() }

    /** 设备健康度"正常"判定天数：超过该天数无任何信号即判为"异常" */
    fun getHealthNormalDays(): Int = prefs.getInt(KEY_HEALTH_NORMAL_DAYS, 7)
    fun saveHealthNormalDays(days: Int) { prefs.edit().putInt(KEY_HEALTH_NORMAL_DAYS, days.coerceAtLeast(1)).apply() }

    fun isLiteModeEnabled(): Boolean = prefs.getBoolean(KEY_LITE_MODE, false)
    fun setLiteModeEnabled(enabled: Boolean) { prefs.edit().putBoolean(KEY_LITE_MODE, enabled).apply() }

    /** 调试模式：开启后文件日志才记录 V/D 级（含密钥等敏感信息）日志 */
    fun isDebugModeEnabled(): Boolean = prefs.getBoolean(KEY_DEBUG_MODE, false)
    fun setDebugModeEnabled(enabled: Boolean) { prefs.edit().putBoolean(KEY_DEBUG_MODE, enabled).apply() }

    /**
     * 设置页测试连接：探测主/备后端可达性，返回测试结果说明。
     * 任何 HTTP 响应（含 4xx/5xx）均视为服务器可达；连接超时/拒绝视为失败。
     * 传入当前表单填写值测试，不影响已保存配置。
     */
    suspend fun testConnection(primaryUrl: String, fallbackUrl: String): String = withContext(Dispatchers.IO) {
        val primary = primaryUrl.takeIf { it.isNotBlank() }
        val fallback = fallbackUrl.takeIf { it.isNotBlank() }
        if (primary == null && fallback == null) return@withContext "未配置后端地址"

        var primaryErr: String? = null
        if (primary != null) {
            val err = probeReachable(primary)
            if (err == null) return@withContext "主后端连接正常"
            primaryErr = err
        }
        if (fallback != null) {
            val err = probeReachable(fallback)
            if (err == null) {
                return@withContext if (primaryErr != null) "主后端失败（$primaryErr），备用后端连接正常" else "备用后端连接正常"
            }
        }
        return@withContext "连接失败：${primaryErr ?: "未配置后端地址"}"
    }

    private fun normalizeUrl(baseUrl: String): String {
        val trimmed = baseUrl.trim()
        return try {
            val uri = java.net.URI(trimmed)
            if (uri.path.isNullOrEmpty()) "$trimmed/" else trimmed
        } catch (_: Exception) {
            if (trimmed.endsWith("/")) trimmed else "$trimmed/"
        }
    }

    /** 返回 null 表示可达，否则返回失败原因 */
    private fun probeReachable(baseUrl: String): String? {
        val urlStr = normalizeUrl(baseUrl)
        return try {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "GET"
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                val code = conn.responseCode
                if (code in 200..599) null else "HTTP $code"
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            e.message ?: "无法连接"
        }
    }

    suspend fun queryLocations(
        uid: String,
        seed: ByteArray,
        periodSec: Int,
        lastLocationTime: Long?
    ): List<LocationEntity> {
        val nowSec = System.currentTimeMillis() / 1000
        val extension = computeExtensionSeconds(periodSec)
        val defaultStart = if (lastLocationTime != null) {
            maxOf(lastLocationTime - extension, nowSec - EXTENSION_DAYS * 86400L)
        } else {
            nowSec - 86400L - extension
        }
        val startSec = defaultStart
        val endSec = nowSec + extension

        val periodFrom = startSec / periodSec
        val periodTo = endSec / periodSec

        if (periodTo < periodFrom) return emptyList()

        FileLog.d(TAG, "查询范围: period $periodFrom -> $periodTo (${periodTo - periodFrom + 1} 个密钥)")

        val hashedKeyToPrivateKey = mutableMapOf<String, ByteArray>()
        val hashedKeyToPeriodIndex = mutableMapOf<String, Long>()

        for (periodIdx in periodFrom..periodTo) {
            val privateKey = CryptoUtils.deriveFindMyPrivateKey(seed, periodIdx)
            val pubX = CryptoUtils.deriveFindMyPublicKeyX(privateKey)
            val hashedKey = CryptoUtils.computeHashedAdvKey(pubX)
            val hashedKeyB64 = Base64.encodeToString(hashedKey, Base64.NO_WRAP)
            hashedKeyToPrivateKey[hashedKeyB64] = privateKey
            hashedKeyToPeriodIndex[hashedKeyB64] = periodIdx
        }

        return processKeyBatch(uid, hashedKeyToPrivateKey, hashedKeyToPeriodIndex)
    }

    /** 基于 Sparks 预生成固定密钥池查询位置 */
    suspend fun queryFixedLocations(
        uid: String,
        fixedKeys: List<CryptoUtils.FixedFindMyKey>
    ): List<LocationEntity> {
        if (fixedKeys.isEmpty()) return emptyList()
        FileLog.d(TAG, "固定密钥查询: uid=$uid, ${fixedKeys.size} 个密钥")

        val hashedKeyToPrivateKey = mutableMapOf<String, ByteArray>()
        val hashedKeyToPeriodIndex = mutableMapOf<String, Long>()

        for (k in fixedKeys) {
            val hashedKeyB64 = Base64.encodeToString(k.hashedAdvKey, Base64.NO_WRAP)
            hashedKeyToPrivateKey[hashedKeyB64] = k.privateKey
            hashedKeyToPeriodIndex[hashedKeyB64] = k.index.toLong()
        }

        return processKeyBatch(uid, hashedKeyToPrivateKey, hashedKeyToPeriodIndex)
    }

    private suspend fun processKeyBatch(
        uid: String,
        hashedKeyToPrivateKey: Map<String, ByteArray>,
        hashedKeyToPeriodIndex: Map<String, Long>
    ): List<LocationEntity> {
        val batchLimit = getBatchKeyLimit().coerceAtLeast(1)
        val retryCount = getChunkRetryCount().coerceAtLeast(0)
        val retryDelayMs = getChunkRetryDelaySec().coerceAtLeast(0) * 1000L
        val hashedKeys = hashedKeyToPrivateKey.keys.toList()
        val results = mutableListOf<LocationEntity>()

        val batchResults = mutableListOf<JSONObject>()
        var lastError: IOException? = null

        for (chunk in hashedKeys.chunked(batchLimit)) {
            var attempts = 0
            var chunkOk = false
            while (!chunkOk && attempts <= retryCount) {
                attempts++
                try {
                    FileLog.d(TAG, "分片请求: ${chunk.size} 个密钥 (第$attempts 次)")
                    batchResults += fetchBatch(chunk)
                    chunkOk = true
                } catch (e: NoEndpointException) {
                    throw e
                } catch (e: IOException) {
                    FileLog.w(TAG, "分片请求失败(第$attempts 次): ${e.message}")
                    lastError = e
                    if (attempts <= retryCount) delay(retryDelayMs)
                }
            }
        }

        if (batchResults.isEmpty() && lastError != null) {
            throw IOException("所有分片请求均失败", lastError)
        }

        data class DecryptedItem(
            val decrypted: CryptoUtils.DecryptedFindMyPayload,
            val resultKeyId: String,
            val periodIndex: Long?
        )

        val decryptedItems = mutableListOf<DecryptedItem>()
        for (result in batchResults) {
            val resultKeyId = result.optString("id", "")
            val payloadB64 = result.optString("payload", "")
            val privateKey = hashedKeyToPrivateKey[resultKeyId] ?: continue
            val periodIndex = hashedKeyToPeriodIndex[resultKeyId]

            if (payloadB64.isNotEmpty()) {
                try {
                    val payloadBytes = Base64.decode(payloadB64, Base64.NO_WRAP)
                    val decrypted = CryptoUtils.decryptFindMyPayload(payloadBytes, privateKey)
                    if (decrypted != null) {
                        decryptedItems.add(DecryptedItem(decrypted, resultKeyId, periodIndex))
                    } else {
                        FileLog.w(TAG, "FindMy decrypt returned null for keyId=${resultKeyId.take(8)}..., " +
                            "payloadSize=${payloadBytes.size}")
                    }
                } catch (e: Exception) {
                    FileLog.w(TAG, "FindMy payload processing failed for keyId=${resultKeyId.take(8)}...: ${e.message}")
                }
            }
        }

        if (decryptedItems.isEmpty()) return emptyList()

        // 优先逆地理编码最新一条记录（保证列表和卡片能立即展示最新地址，网络调用至多 1 次）
        val latestItem = decryptedItems.maxByOrNull { it.decrypted.timestamp }
        val latestAddressMap = mutableMapOf<Pair<Double, Double>, Pair<String?, AddressComponents?>>()

        val currentGeocode: GeocodeProvider = if (getGeocodeProvider() == GEOCODE_PROVIDER_TIANDITU) {
            tiandituGeocode
        } else {
            amapGeocode
        }

        if (latestItem != null) {
            try {
                val addr = currentGeocode.lookup(latestItem.decrypted.latitude, latestItem.decrypted.longitude)
                val display = addr?.display()
                val detail = addr?.toAddressComponents()
                latestAddressMap[latestItem.decrypted.latitude to latestItem.decrypted.longitude] = (display to detail)
            } catch (_: Exception) {}
        }

        for (item in decryptedItems) {
            val dec = item.decrypted
            val coord = dec.latitude to dec.longitude
            var (address, addressDetail) = latestAddressMap[coord] ?: (null to null)

            // 其余历史点仅从本地缓存查询，不发起网络请求阻塞主流程
            // 优先查询当前选中的服务商缓存，未命中时回退尝试另一家服务商缓存
            if (address == null && addressDetail == null) {
                try {
                    val cachedAddr = currentGeocode.getCached(dec.latitude, dec.longitude)
                        ?: if (currentGeocode === tiandituGeocode) amapGeocode.getCached(dec.latitude, dec.longitude)
                           else tiandituGeocode.getCached(dec.latitude, dec.longitude)
                    if (cachedAddr != null) {
                        address = cachedAddr.display()
                        addressDetail = cachedAddr.toAddressComponents()
                    }
                } catch (_: Exception) {}
            }

            results.add(LocationEntity(
                deviceUid = uid,
                latitude = dec.latitude,
                longitude = dec.longitude,
                accuracy = dec.accuracy,
                confidence = dec.confidence,
                timestamp = dec.timestamp,
                batteryStatus = dec.batteryStatus,
                counter = dec.counter,
                periodIndex = item.periodIndex,
                hashedKey = item.resultKeyId.takeIf { it.isNotEmpty() },
                payloadType = dec.payloadType,
                address = address,
                addressDetail = addressDetail
            ))
        }

        return results
    }

    private fun computeExtensionSeconds(periodSec: Int): Long {
        val type = getExtensionType()
        val value = getExtensionValue()
        return if (type == "keys") {
            (value * periodSec).toLong()
        } else {
            (value * 3600).toLong()
        }
    }

    private fun fetchBatch(ids: List<String>): List<JSONObject> {
        val primaryUrl = getEndpointUrl().takeIf { it.isNotBlank() }
        val fallbackUrl = getEndpointUrlFallback().takeIf { it.isNotBlank() }

        if (primaryUrl == null) {
            throw NoEndpointException("未配置后端地址，请先在设置中配置")
        }

        var lastError: Exception? = null
        val urls = listOfNotNull(primaryUrl, fallbackUrl)

        for (url in urls) {
            try {
                return doFetch(url, ids)
            } catch (e: IOException) {
                FileLog.w(TAG, "请求 $url 失败: ${e.message}")
                lastError = e
            }
        }

        throw IOException("所有后端地址请求均失败", lastError)
    }

    private fun doFetch(baseUrl: String, ids: List<String>): List<JSONObject> {
        val urlStr = normalizeUrl(baseUrl)
        val requestBody = JSONObject().apply {
            put("ids", JSONArray(ids))
            put("days", 7)
        }

        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 15000
            conn.readTimeout = 15000

            val user = getEndpointUser()
            val pass = getEndpointPass()
            if (user.isNotBlank() && pass.isNotBlank()) {
                val auth = Base64.encodeToString("$user:$pass".toByteArray(), Base64.NO_WRAP)
                conn.setRequestProperty("Authorization", "Basic $auth")
            }

            conn.doOutput = true
            val bodyBytes = requestBody.toString().toByteArray()
            conn.outputStream.use { it.write(bodyBytes) }

            val responseCode = conn.responseCode
            if (responseCode != 200) {
                val errorBody = try {
                    conn.errorStream?.use { it.readBytes() }?.toString(Charsets.UTF_8) ?: ""
                } catch (_: Exception) { "" }
                throw IOException("服务器返回 $responseCode${if (errorBody.isNotEmpty()) ": $errorBody" else ""}")
            }

            val responseBody = conn.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
            val json = JSONObject(responseBody)
            val results = json.optJSONArray("results") ?: return emptyList()

            val list = mutableListOf<JSONObject>()
            for (i in 0 until results.length()) {
                list.add(results.getJSONObject(i))
            }
            return list
        } finally {
            conn.disconnect()
        }
    }
}

class NoEndpointException(message: String) : Exception(message)
