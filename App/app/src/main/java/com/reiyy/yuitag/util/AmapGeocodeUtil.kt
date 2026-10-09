package com.reiyy.yuitag.util

import android.content.Context
import android.content.SharedPreferences
import com.reiyy.yuitag.ble.FileLog
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class AmapGeocodeUtil(private val context: Context) : GeocodeProvider {
    companion object {
        private const val TAG = "AmapGeocode"
        private const val PREF_NAME = "yuitag_settings"
        private const val KEY_AMAP = "amap_web_api_key"
        private const val CACHE_PREFIX = "amap_geocode_v2_"
        private const val BASE_URL = "https://restapi.amap.com/v3/geocode/regeo"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    // 内存缓存与磁盘缓存（SharedPreferences 按坐标存 JSON），避免跨会话重复请求高德 API
    private val cache = mutableMapOf<String, AmapAddress?>()

    fun getAmapKey(): String = prefs.getString(KEY_AMAP, "") ?: ""
    fun saveAmapKey(key: String) { prefs.edit().putString(KEY_AMAP, key).apply() }

    /** 快速从内存或磁盘缓存获取已解析的地址，不发起网络请求 */
    override fun getCached(lat: Double, lon: Double): AmapAddress? {
        val cacheKey = "${"%.3f".format(lat)}_${"%.3f".format(lon)}"
        cache[cacheKey]?.let { return it }
        readDiskCache(cacheKey)?.let { addr ->
            cache[cacheKey] = addr
            return addr
        }
        return null
    }

    override suspend fun lookup(lat: Double, lon: Double): AmapAddress? {
        val cached = getCached(lat, lon)
        if (cached != null) return cached

        val key = getAmapKey()
        if (key.isBlank()) return null
        if (!Wgs84ToGcj02.isInChina(lat, lon)) return null

        val cacheKey = "${"%.3f".format(lat)}_${"%.3f".format(lon)}"

        try {
            val (gcjLat, gcjLon) = Wgs84ToGcj02.convert(lat, lon)
            val urlStr = "$BASE_URL?key=$key&location=$gcjLon,$gcjLat"

            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "GET"
                conn.connectTimeout = 3000
                conn.readTimeout = 3000

                if (conn.responseCode != 200) {
                    FileLog.w(TAG, "高德 API 返回 ${conn.responseCode}")
                    cache[cacheKey] = null
                    return null
                }

                val body = conn.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
                val json = JSONObject(body)

                if (json.optString("status") != "1") {
                    FileLog.w(TAG, "高德 API status=${json.optString("status")}")
                    cache[cacheKey] = null
                    return null
                }

                val regeocode = json.optJSONObject("regeocode") ?: run {
                    cache[cacheKey] = null
                    return null
                }

                val addressComponent = regeocode.optJSONObject("addressComponent")
                val streetNumber = addressComponent?.optJSONObject("streetNumber")

                val formatted = clean(regeocode.optString("formatted_address", ""))
                val province = clean(addressComponent?.optString("province", "") ?: "")
                val city = clean(addressComponent?.optString("city", "") ?: "")
                val district = clean(addressComponent?.optString("district", "") ?: "")
                val township = clean(addressComponent?.optString("township", "") ?: "")
                val neighborhood = clean(addressComponent?.optJSONObject("neighborhood")?.optString("name", "") ?: "")
                val building = clean(addressComponent?.optJSONObject("building")?.optString("name", "") ?: "")
                val street = clean(streetNumber?.optString("street", "") ?: "")
                val number = clean(streetNumber?.optString("number", "") ?: "")

                val result = AmapAddress(
                    formattedAddress = formatted,
                    province = province,
                    city = city,
                    district = district,
                    township = township,
                    neighborhood = neighborhood,
                    building = building,
                    street = street,
                    number = number
                )
                cache[cacheKey] = result
                writeDiskCache(cacheKey, result)
                return result
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            FileLog.w(TAG, "逆地理编码失败: ${e.message}")
            cache[cacheKey] = null
            return null
        }
    }

    /** 高德无数据时返回空数组 [] 而非空串，统一按“无数据”清洗 */
    private fun clean(s: String): String {
        val t = s.trim()
        return t.takeUnless { it.isEmpty() || it == "[]" || it == "{}" || it == "null" } ?: ""
    }

    private fun readDiskCache(key: String): AmapAddress? {
        val json = prefs.getString(CACHE_PREFIX + key, null) ?: return null
        return try {
            val obj = JSONObject(json)
            AmapAddress(
                formattedAddress = clean(obj.optString("formatted", "")),
                province = clean(obj.optString("province", "")),
                city = clean(obj.optString("city", "")),
                district = clean(obj.optString("district", "")),
                township = clean(obj.optString("township", "")),
                neighborhood = clean(obj.optString("neighborhood", "")),
                building = clean(obj.optString("building", "")),
                street = clean(obj.optString("street", "")),
                number = clean(obj.optString("number", ""))
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun writeDiskCache(key: String, addr: AmapAddress) {
        try {
            val obj = JSONObject().apply {
                put("formatted", addr.formattedAddress)
                put("province", addr.province)
                put("city", addr.city)
                put("district", addr.district)
                put("township", addr.township)
                put("neighborhood", addr.neighborhood)
                put("building", addr.building)
                put("street", addr.street)
                put("number", addr.number)
            }
            prefs.edit().putString(CACHE_PREFIX + key, obj.toString()).apply()
        } catch (_: Exception) {}
    }
}
