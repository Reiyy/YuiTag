package com.reiyy.yuitag.util

import android.content.Context
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager

object WifiNetworkUtil {

    /**
     * 清理并规范化 Wi-Fi SSID：
     * - 去除首尾的双引号与空白字符
     * - 过滤系统无效标识（如 "<unknown ssid>"、"0x" 等）
     */
    fun sanitizeSsid(rawSsid: String?): String? {
        if (rawSsid.isNullOrBlank()) return null
        val clean = rawSsid.removeSurrounding("\"").trim()
        if (clean.isEmpty() || clean == "<unknown ssid>" || clean.equals("0x", ignoreCase = true)) {
            return null
        }
        return clean
    }

    /**
     * 检查当前网络是否为 Wi-Fi 连接
     */
    fun isWifiConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /**
     * 检查系统定位开关是否已开启（Android 10+ 获取真实 SSID 必需）
     */
    fun isLocationEnabled(context: Context): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return try {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 主动读取当前连接的 Wi-Fi SSID
     * 双重兜底：优先从 NetworkCapabilities 读取，备用从 WifiManager 读取
     */
    fun getCurrentlyConnectedWifiSsid(context: Context): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val activeNetwork = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null

        val wifiInfo = caps.transportInfo as? WifiInfo
        val ssidFromCaps = sanitizeSsid(wifiInfo?.ssid)
        if (ssidFromCaps != null) return ssidFromCaps

        @Suppress("DEPRECATION")
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val ssidFromWm = sanitizeSsid(wm?.connectionInfo?.ssid)
        if (ssidFromWm != null) return ssidFromWm

        return null
    }

    /**
     * 读取系统已保存的 Wi-Fi 网络列表
     * 注：Android 10+（API 29+）对第三方应用实施了隐私限制，部分系统可能返回空列表，
     * 但在部分定制 ROM 或特定设备上依然可用。
     */
    @Suppress("DEPRECATION")
    fun getSystemSavedWifiList(context: Context): List<String> {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return emptyList()
        return try {
            val configs = wm.configuredNetworks ?: emptyList()
            configs.mapNotNull { sanitizeSsid(it.SSID) }.distinct().sorted()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 读取周围扫描到的可用 Wi-Fi 列表（作为已保存网络的实用补充与回退）
     */
    @Suppress("DEPRECATION")
    fun getNearbyWifiList(context: Context): List<String> {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return emptyList()
        return try {
            val results = wm.scanResults ?: emptyList()
            results.mapNotNull { sanitizeSsid(it.SSID) }.distinct().sorted()
        } catch (_: Exception) {
            emptyList()
        }
    }
}
