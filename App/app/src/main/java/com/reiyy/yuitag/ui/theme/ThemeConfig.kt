package com.reiyy.yuitag.ui.theme

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class ThemeMode(val value: Int, val label: String) {
    SYSTEM(0, "跟随系统"),
    LIGHT(1, "浅色模式"),
    DARK(2, "深色模式");

    companion object {
        fun fromValue(value: Int) = entries.find { it.value == value } ?: SYSTEM
    }
}

object ThemeConfig {
    private const val PREF_NAME = "yuitag_theme_prefs"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_DYNAMIC_COLOR = "dynamic_color"

    var currentThemeMode by mutableStateOf(ThemeMode.SYSTEM)
        private set
    var isDynamicColor by mutableStateOf(false)
        private set

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        currentThemeMode = ThemeMode.fromValue(prefs.getInt(KEY_THEME_MODE, ThemeMode.SYSTEM.value))
        isDynamicColor = prefs.getBoolean(KEY_DYNAMIC_COLOR, false)
    }

    fun setThemeMode(context: Context, mode: ThemeMode) {
        currentThemeMode = mode
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_THEME_MODE, mode.value)
            .apply()
    }

    fun setDynamicColor(context: Context, enabled: Boolean) {
        isDynamicColor = enabled
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DYNAMIC_COLOR, enabled)
            .apply()
    }
}
