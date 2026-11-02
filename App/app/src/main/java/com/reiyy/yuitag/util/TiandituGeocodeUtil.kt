package com.reiyy.yuitag.util

import android.content.Context
import android.content.SharedPreferences
import com.reiyy.yuitag.ble.FileLog
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 天地图 (Tianditu) 逆地理编码工具类。
 *
 * 官方服务地址：https://lbs.tianditu.gov.cn/server/geocoding.html
 * 接口：GET https://api.tianditu.gov.cn/geocoder?postStr={"lon":...,"lat":...,"ver":1}&type=geocode&tk=...
 *
 * 密钥说明：
 * 逆地理编码需在天地图控制台 (console.tianditu.gov.cn) 申请【浏览器端】API Key。
 * 与地图底图所需的【服务端】Key 彻底解耦独立，不进行复用。
 *
 * 坐标系说明：
 * 天地图基于 CGCS2000 国家大地坐标系，在米级民用精度下与 WGS-84 基本完全重合。
 * 因此直接传入 WGS-84 坐标，严禁转为 GCJ-02 火星坐标。
 */
class TiandituGeocodeUtil(private val context: Context) : GeocodeProvider {
    companion object {
        private const val TAG = "TiandituGeocode"
        private const val PREF_NAME = "yuitag_settings"
        private const val KEY_TIANDITU_GEOCODE = "tianditu_geocode_key"
        private const val CACHE_PREFIX = "tianditu_geocode_v1_"
        private const val BASE_URL = "https://api.tianditu.gov.cn/geocoder"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    // 内存缓存与磁盘缓存（SharedPreferences 按坐标存 JSON），避免跨会话重复请求
    private val cache = mutableMapOf<String, GeocodeAddress?>()

    /** 逆地理编码实际使用的 Key（浏览器端 Key）：未填则为空，不复用底图 Key */
    fun getGeocodeKey(): String {
        return prefs.getString(KEY_TIANDITU_GEOCODE, "")?.trim() ?: ""
    }

    /** 获取专门为逆地理编码设置的 Key（若未填则为空，用于设置页输入框回显） */
    fun getDirectGeocodeKey(): String = getGeocodeKey()

    /** 保存专门为逆地理编码设置的 Key */
    fun saveGeocodeKey(key: String) {
        prefs.edit().putString(KEY_TIANDITU_GEOCODE, key.trim()).apply()
    }

    /** 快速从内存或磁盘缓存获取已解析的地址，不发起网络请求 */
    override fun getCached(lat: Double, lon: Double): GeocodeAddress? {
        val cacheKey = "${"%.3f".format(lat)}_${"%.3f".format(lon)}"
        cache[cacheKey]?.let { return it }
        readDiskCache(cacheKey)?.let { addr ->
            cache[cacheKey] = addr
            return addr
        }
        return null
    }

    /**
     * 发起逆地理编码网络查询。
     * 输入坐标直接为 WGS-84（与 FindMy 原始上报一致）。
     */
    override suspend fun lookup(lat: Double, lon: Double): GeocodeAddress? {
        val cached = getCached(lat, lon)
        if (cached != null) return cached

        val key = getGeocodeKey()
        if (key.isBlank()) return null
        if (!Wgs84ToGcj02.isInChina(lat, lon)) return null

        val cacheKey = "${"%.3f".format(lat)}_${"%.3f".format(lon)}"

        try {
            val postJson = JSONObject().apply {
                put("lon", lon)
                put("lat", lat)
                put("ver", 1)
            }.toString()
            val encodedPostStr = URLEncoder.encode(postJson, "UTF-8")
            val urlStr = "$BASE_URL?postStr=$encodedPostStr&type=geocode&tk=$key"

            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "GET"
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; YuiTag)")

                if (conn.responseCode != 200) {
                    val err = conn.errorStream?.use { it.readBytes() }?.toString(Charsets.UTF_8) ?: ""
                    FileLog.w(TAG, "天地图 API 返回 ${conn.responseCode}: $err")
                    cache[cacheKey] = null
                    return null
                }

                val body = conn.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
                val json = JSONObject(body)

                // 天地图状态码："0" 为成功，"1" / "404" 为出错
                val status = json.optString("status", "")
                if (status != "0") {
                    FileLog.w(TAG, "天地图 API status=$status, msg=${json.optString("msg")}")
                    cache[cacheKey] = null
                    return null
                }

                val resultObj = json.optJSONObject("result") ?: run {
                    cache[cacheKey] = null
                    return null
                }

                val addressComponent = resultObj.optJSONObject("addressComponent")
                val formatted = clean(resultObj.optString("formatted_address", ""))

                val rawCity = clean(addressComponent?.optString("city", "") ?: "")
                val rawRoad = clean(addressComponent?.optString("road", "") ?: "")
                val rawPoi = clean(addressComponent?.optString("poi", "") ?: "")
                val rawAddress = clean(addressComponent?.optString("address", "") ?: "")

                var province = clean(addressComponent?.optString("province", "") ?: "")
                var city = rawCity
                val rawCounty = addressComponent?.optString("county", "") ?: ""
                val rawDistrict = addressComponent?.optString("district", "") ?: ""
                var district = clean(rawCounty.ifEmpty { rawDistrict })

                // 天地图常见格式：province/district 为空，但 city 为 "北京市西城区" 或 "广东省深圳市南山区"
                if (province.isEmpty() && city.isNotEmpty()) {
                    when {
                        city.startsWith("北京市") || city.startsWith("上海市") ||
                        city.startsWith("天津市") || city.startsWith("重庆市") -> {
                            province = city.substring(0, 3)
                            val remainder = city.substring(3).trim()
                            if (district.isEmpty() && remainder.isNotEmpty()) {
                                district = remainder
                            }
                            city = province
                        }
                        city.contains("省") -> {
                            val idx = city.indexOf("省")
                            province = city.substring(0, idx + 1)
                            val remainder = city.substring(idx + 1).trim()
                            if (remainder.contains("市")) {
                                val cityIdx = remainder.indexOf("市")
                                city = remainder.substring(0, cityIdx + 1)
                                val distRemainder = remainder.substring(cityIdx + 1).trim()
                                if (district.isEmpty() && distRemainder.isNotEmpty()) {
                                    district = distRemainder
                                }
                            } else if (remainder.isNotEmpty()) {
                                city = remainder
                            }
                        }
                    }
                }

                val result = GeocodeAddress(
                    formattedAddress = formatted,
                    province = province,
                    city = city,
                    district = district,
                    township = "",
                    neighborhood = "",
                    building = rawPoi,
                    street = rawRoad,
                    number = rawAddress
                )
                cache[cacheKey] = result
                writeDiskCache(cacheKey, result)
                return result
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            FileLog.w(TAG, "天地图逆地理编码失败: ${e.message}")
            cache[cacheKey] = null
            return null
        }
    }

    private fun clean(s: String): String {
        val t = s.trim()
        return t.takeUnless { it.isEmpty() || it == "[]" || it == "{}" || it == "null" } ?: ""
    }

    private fun readDiskCache(key: String): GeocodeAddress? {
        val json = prefs.getString(CACHE_PREFIX + key, null) ?: return null
        return try {
            val obj = JSONObject(json)
            GeocodeAddress(
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

    private fun writeDiskCache(key: String, addr: GeocodeAddress) {
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
