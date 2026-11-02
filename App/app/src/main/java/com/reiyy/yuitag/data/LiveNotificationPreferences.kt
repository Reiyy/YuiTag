package com.reiyy.yuitag.data

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.net.toUri
import com.reiyy.yuitag.ble.FileLog

/**
 * 实况通知功能配置与系统能力检测
 */
class LiveNotificationPreferences(private val context: Context) {

    companion object {
        const val PREF_NAME = "yuitag_settings"
        const val KEY_LIVE_NOTIFICATION_ENABLED = "live_notification_enabled"

        /**
         * 检测当前设备是否为魅族 / Flyme 设备
         */
        fun isFlymeDevice(): Boolean {
            val manufacturer = Build.MANUFACTURER.lowercase()
            val brand = Build.BRAND.lowercase()
            val display = (Build.DISPLAY ?: "").lowercase()
            return manufacturer.contains("meizu") || brand.contains("meizu") || display.contains("flyme")
        }

        /**
         * 检测 Flyme 系统版本是否支持实况通知能力（Flyme 11+ / Flyme AIOS）
         */
        fun isFlymeSupported(context: Context): Boolean {
            if (!isFlymeDevice()) return false
            val display = Build.DISPLAY ?: ""
            // Flyme AIOS（即 Flyme 12，基于 Android 14+）全面支持实况通知
            if (display.contains("AIOS", ignoreCase = true)) return true
            val flymeVer = Regex("Flyme\\s*([0-9]+)", RegexOption.IGNORE_CASE)
                .find(display)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: -1
            if (flymeVer >= 11) return true
            // Android 13+ (API 33+) 上的魅族机型均支持实况通知
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return true
            return false
        }

        /**
         * 查询魅族系统“实况通知”权限开关状态
         */
        fun isFlymeSystemLiveNotificationEnabled(context: Context): Boolean {
            if (context.checkSelfPermission("flyme.permission.READ_NOTIFICATION_LIVE_STATE") != PackageManager.PERMISSION_GRANTED) {
                FileLog.w("LiveNotif", "未授予 flyme.permission.READ_NOTIFICATION_LIVE_STATE")
                return false
            }
            return try {
                val call = context.contentResolver.call(
                    "content://com.android.systemui.notification.provider".toUri(),
                    "isNotificationLiveEnabled",
                    null,
                    null
                )
                val enabled = call?.getBoolean("result", false) ?: false
                FileLog.i("LiveNotif", "Flyme isNotificationLiveEnabled: $enabled (call=$call)")
                enabled
            } catch (e: Exception) {
                FileLog.w("LiveNotif", "查询 Flyme isNotificationLiveEnabled 失败: ${e.message}")
                false
            }
        }

        /**
         * 检测当前系统是否具备实况通知能力（如魅族 Flyme 11+）
         */
        fun isSystemSupported(context: Context): Boolean {
            if (isFlymeSupported(context)) return true
            // 未来可在此处扩展 HyperOS / ColorOS 等
            return false
        }

        /**
         * 获取当前生效的实况通知驱动名称，如 "Flyme12", "Flyme11", "通知栏"
         */
        fun getDriverName(context: Context): String {
            if (isFlymeSupported(context)) {
                val display = (Build.DISPLAY ?: "").trim()
                val match = Regex("Flyme\\s*([0-9]+)", RegexOption.IGNORE_CASE).find(display)
                val ver = match?.groupValues?.getOrNull(1)
                if (ver != null) {
                    return "Flyme$ver"
                }
                if (display.contains("AIOS", ignoreCase = true) || Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    return "Flyme12"
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    return "Flyme11"
                }
                return "Flyme"
            }
            return "通知栏"
        }

        fun getSystemSupportDesc(context: Context): String {
            return when {
                isFlymeSupported(context) -> {
                    if (isFlymeSystemLiveNotificationEnabled(context)) {
                        "魅族 Flyme 实况通知已就绪"
                    } else {
                        "魅族 Flyme 系统实况通知权限未开启，请在系统设置允许"
                    }
                }
                else -> "当前设备暂不支持原生实况通知，寻物将使用通知栏"
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /**
     * 实况通知总开关（默认关闭）
     */
    fun isLiveNotificationEnabled(): Boolean {
        return prefs.getBoolean(KEY_LIVE_NOTIFICATION_ENABLED, false)
    }

    fun setLiveNotificationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LIVE_NOTIFICATION_ENABLED, enabled).apply()
    }
}
