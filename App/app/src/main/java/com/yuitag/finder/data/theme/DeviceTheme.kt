package com.yuitag.finder.data.theme

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.File

data class DeviceTheme(
    @SerializedName("trendHere") val trendHere: String = "",
    @SerializedName("trendApproaching") val trendApproaching: String = "",
    @SerializedName("trendAway") val trendAway: String = "",
    @SerializedName("trendStable") val trendStable: String = ""
) {
    companion object {
        private val gson = Gson()

        fun loadFromDir(themeDir: String): DeviceTheme? {
            val file = File(themeDir, "theme.json")
            if (!file.exists()) return null
            return try {
                gson.fromJson(file.readText(), DeviceTheme::class.java)
            } catch (e: Exception) {
                null
            }
        }

        fun saveToDir(themeDir: String, theme: DeviceTheme) {
            val dir = File(themeDir)
            if (!dir.exists()) dir.mkdirs()
            File(themeDir, "theme.json").writeText(gson.toJson(theme))
        }
    }
}