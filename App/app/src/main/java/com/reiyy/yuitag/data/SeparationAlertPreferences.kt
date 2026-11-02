package com.reiyy.yuitag.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * 遗落提醒触发档位（与查找页信号等级对齐：4在此处, 3在附近, 2距离近, 1距离远, 0信号弱, -1无信号）
 */
enum class SeparationAlertLevel(
    val key: String,
    val title: String,
    val description: String,
    val minFindLevel: Int
) {
    NEARBY("nearby", "在附近", "极近范围", 3),
    CLOSE("close", "距离近", "中等距离", 2),
    FAR("far", "距离远", "较远距离", 1),
    WEAK("weak", "信号弱", "信号极弱", 0),
    NO_SIGNAL("no_signal", "无信号", "断开连接", -1);

    companion object {
        fun fromKey(key: String): SeparationAlertLevel =
            entries.find { it.key.equals(key, ignoreCase = true) } ?: NO_SIGNAL

        fun levelFromRssi(rssi: Float): Int = when {
            rssi > -50 -> 4
            rssi > -65 -> 3
            rssi >= -85 -> 2
            rssi >= -95 -> 1
            else -> 0
        }

        fun levelToStatusText(level: Int): String = when (level) {
            4 -> "在此处"
            3 -> "在附近"
            2 -> "距离近"
            1 -> "距离远"
            0 -> "信号弱"
            else -> "无信号"
        }
    }
}

class SeparationAlertPreferences(context: Context) {

    companion object {
        const val PREF_NAME = "yuitag_settings"
        const val KEY_MASTER_ENABLED = "separation_alert_master_enabled"
        const val KEY_TRIGGER_LEVEL = "separation_alert_trigger_level"
        const val KEY_DELAY_SECONDS = "separation_alert_delay_seconds"
        const val KEY_FINAL_VERIFICATION_ENABLED = "separation_alert_final_verification_enabled"
        const val KEY_SAFE_WIFI_ENABLED = "separation_alert_safe_wifi_enabled"
        const val KEY_SAFE_WIFI_SSIDS = "separation_alert_safe_wifi_ssids"

        /** 最小确认延时（秒），用于抑制偶发丢包与扫描占空比波动 */
        const val MIN_DELAY_SECONDS = 30
        const val DEFAULT_DELAY_SECONDS = 120
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun isMasterEnabled(): Boolean =
        prefs.getBoolean(KEY_MASTER_ENABLED, false)

    fun masterEnabledFlow(): Flow<Boolean> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_MASTER_ENABLED) {
                trySend(isMasterEnabled())
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(isMasterEnabled())
        awaitClose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    fun setMasterEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MASTER_ENABLED, enabled).apply()
    }

    fun getTriggerLevel(): SeparationAlertLevel {
        val key = prefs.getString(KEY_TRIGGER_LEVEL, SeparationAlertLevel.NO_SIGNAL.key) ?: SeparationAlertLevel.NO_SIGNAL.key
        return SeparationAlertLevel.fromKey(key)
    }

    fun setTriggerLevel(level: SeparationAlertLevel) {
        prefs.edit().putString(KEY_TRIGGER_LEVEL, level.key).apply()
    }

    fun getDelaySeconds(): Int =
        prefs.getInt(KEY_DELAY_SECONDS, DEFAULT_DELAY_SECONDS).coerceAtLeast(MIN_DELAY_SECONDS)

    fun setDelaySeconds(seconds: Int) {
        prefs.edit().putInt(KEY_DELAY_SECONDS, seconds.coerceAtLeast(MIN_DELAY_SECONDS)).apply()
    }

    fun isFinalVerificationEnabled(): Boolean =
        prefs.getBoolean(KEY_FINAL_VERIFICATION_ENABLED, false)

    fun setFinalVerificationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_FINAL_VERIFICATION_ENABLED, enabled).apply()
    }

    fun isSafeWifiEnabled(): Boolean =
        prefs.getBoolean(KEY_SAFE_WIFI_ENABLED, false)

    fun setSafeWifiEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SAFE_WIFI_ENABLED, enabled).apply()
    }

    fun safeWifiEnabledFlow(): Flow<Boolean> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_SAFE_WIFI_ENABLED) {
                trySend(isSafeWifiEnabled())
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(isSafeWifiEnabled())
        awaitClose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    fun getSafeWifiSet(): Set<String> {
        val rawSet = prefs.getStringSet(KEY_SAFE_WIFI_SSIDS, null) ?: emptySet()
        return rawSet.toSet()
    }

    fun setSafeWifiSet(ssids: Set<String>) {
        val sanitized = ssids.mapNotNull {
            val c = it.removeSurrounding("\"").trim()
            if (c.isNotEmpty()) c else null
        }.toSet()
        prefs.edit().putStringSet(KEY_SAFE_WIFI_SSIDS, HashSet(sanitized)).apply()
    }

    fun safeWifiSetFlow(): Flow<Set<String>> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_SAFE_WIFI_SSIDS) {
                trySend(getSafeWifiSet())
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(getSafeWifiSet())
        awaitClose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    fun addSafeWifi(ssid: String): Boolean {
        val clean = ssid.removeSurrounding("\"").trim()
        if (clean.isEmpty() || clean == "<unknown ssid>" || clean == "0x") return false
        val current = getSafeWifiSet().toMutableSet()
        val added = current.add(clean)
        if (added) {
            setSafeWifiSet(current)
        }
        return added
    }

    fun removeSafeWifi(ssid: String): Boolean {
        val clean = ssid.removeSurrounding("\"").trim()
        val current = getSafeWifiSet().toMutableSet()
        val removed = current.remove(clean)
        if (removed) {
            setSafeWifiSet(current)
        }
        return removed
    }
}
