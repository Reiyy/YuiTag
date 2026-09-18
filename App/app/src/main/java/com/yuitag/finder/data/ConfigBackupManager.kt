package com.yuitag.finder.data

import android.content.Context
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.stream.JsonWriter
import com.yuitag.finder.BuildConfig
import com.yuitag.finder.ble.FileLog
import com.yuitag.finder.data.local.DeviceEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.io.OutputStreamWriter

/**
 * 配置备份与恢复管理器。
 *
 * 导出内容（单文件 JSON，SAF 写入，无需存储权限）：
 *  1. App 设置（yuitag_settings 白名单键，含高德/天地图 Key，排除地理编码缓存）
 *  2. 已绑定 Tag 及全部 Tag 配置（devices 表字段，含 AES Key / FindMy Seed）
 *  3. 每台设备的主题包文件（filesDir/theme/tag/<uid>/ 下全部文件，Base64 内嵌）
 *
 * 恢复采用"合并导入"：备份中的 Tag 覆盖同名 UID（UPDATE，不触发位置历史级联删除），
 * 本机独有且不在备份中的 Tag 保留不动；不涉及 location_history 表。
 */
object ConfigBackupManager {

    const val FORMAT = "yuitag-backup"
    const val VERSION = 1
    private const val PREF_NAME = "yuitag_settings"
    private const val TAG = "ConfigBackup"
    private const val MAX_THEME_FILE_SIZE = 20L * 1024 * 1024

    private val gson = Gson()

    // --- 备份文件模型 ---

    data class BackupEnvelope(
        val format: String,
        val version: Int,
        val exportedAt: Long,
        val appVersion: String,
        val settings: BackupSettings = BackupSettings(),
        val devices: List<BackupDevice> = emptyList()
    )

    data class BackupSettings(
        val endpoint_url: String = "",
        val endpoint_url_fallback: String = "",
        val endpoint_user: String = "",
        val endpoint_pass: String = "",
        val query_extension_type: String = "",
        val query_extension_value: Float = 2.0f,
        val address_level: String = "",
        val query_batch_key_limit: Int = 100,
        val query_chunk_retry_count: Int = 1,
        val query_chunk_retry_delay: Int = 1,
        val amap_web_api_key: String = "",
        val tianditu_key: String = "",
        val tianditu_style: String = "",
        val health_normal_days: Int = 7,
        val lite_mode_enabled: Boolean = false,
        val debug_mode_enabled: Boolean = false
    )

    data class BackupDevice(
        val uid: String,
        val serialNumber: String = "",
        val customName: String = "",
        val deviceType: String = "",
        val customIcon: String = "",
        val protocolVersion: Int = 0,
        val firmwareVersion: String = "",
        val firmwarePlatform: String = "",
        val hardwareVersion: String = "",
        val manufacturer: String = "",
        val productName: String = "",
        val productModel: String = "",
        val aesKey: String? = null,             // Base64
        val boundAt: Long = 0L,
        val broadcastName: String = "",
        val advIntervalNormal: Int? = null,
        val advIntervalFind: Int? = null,
        val findmyEnabled: Boolean? = null,
        val findmySupported: Boolean? = null,
        val findmySeed: String? = null,         // Base64
        val findmyPeriodSec: Int? = null,
        val macAddress: String = "",
        val deviceName: String = "",
        val sortOrder: Int = 0,
        val lastSeen: Long = 0L,
        val lastRssi: Int = 0,
        val battery: Int = -1,
        val theme: Map<String, String> = emptyMap()   // 文件名 -> Base64
    )

    data class ImportResult(
        val devicesRestored: Int,
        val settingsRestored: Boolean
    )

    data class ImportPreview(
        val deviceCount: Int,
        val hasSettings: Boolean
    )

    class BackupException(message: String) : Exception(message)

    /** 导入前预检：校验文件格式并返回概览，不产生任何修改 */
    fun previewConfig(json: String): ImportPreview {
        val envelope = try {
            gson.fromJson(json, BackupEnvelope::class.java)
        } catch (e: Exception) {
            throw BackupException("配置文件格式不正确")
        }
        if (envelope == null || envelope.format != FORMAT) {
            throw BackupException("不是有效的 YuiTag 备份文件")
        }
        if (envelope.version > VERSION) {
            throw BackupException("备份文件版本过新，请升级 App 后再导入")
        }
        return ImportPreview(
            deviceCount = envelope.devices?.size ?: 0,
            hasSettings = envelope.settings != null
        )
    }

    // --- 导出 ---

    /**
     * 流式导出：直接写入 [outputStream]（SAF Uri 的输出流），
     * 避免构建整份 JSON 大字符串导致内存峰值；主题文件逐个读取、逐个写入。
     */
    suspend fun exportConfig(context: Context, repository: DeviceRepository, outputStream: OutputStream) {
        withContext(Dispatchers.IO) {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val devices = repository.getAllBoundEntities()

            val writer = JsonWriter(
                OutputStreamWriter(
                    BufferedOutputStream(outputStream, 64 * 1024),
                    Charsets.UTF_8
                )
            )
            try {
                writer.beginObject()
                writer.name("format").value(FORMAT)
                writer.name("version").value(VERSION)
                writer.name("exportedAt").value(System.currentTimeMillis())
                writer.name("appVersion").value(BuildConfig.VERSION_NAME)

                writer.name("settings")
                writer.beginObject()
                writer.name("endpoint_url").value(prefs.getString("endpoint_url", "") ?: "")
                writer.name("endpoint_url_fallback").value(prefs.getString("endpoint_url_fallback", "") ?: "")
                writer.name("endpoint_user").value(prefs.getString("endpoint_user", "") ?: "")
                writer.name("endpoint_pass").value(prefs.getString("endpoint_pass", "") ?: "")
                writer.name("query_extension_type").value(prefs.getString("query_extension_type", "") ?: "")
                writer.name("query_extension_value").value(prefs.getFloat("query_extension_value", 2.0f))
                writer.name("address_level").value(prefs.getString("address_level", "") ?: "")
                writer.name("query_batch_key_limit").value(prefs.getInt("query_batch_key_limit", 100))
                writer.name("query_chunk_retry_count").value(prefs.getInt("query_chunk_retry_count", 1))
                writer.name("query_chunk_retry_delay").value(prefs.getInt("query_chunk_retry_delay", 1))
                writer.name("amap_web_api_key").value(prefs.getString("amap_web_api_key", "") ?: "")
                writer.name("tianditu_key").value(prefs.getString("tianditu_key", "") ?: "")
                writer.name("tianditu_style").value(prefs.getString("tianditu_style", "") ?: "")
                writer.name("health_normal_days").value(prefs.getInt("health_normal_days", 7))
                writer.name("lite_mode_enabled").value(prefs.getBoolean("lite_mode_enabled", false))
                writer.name("debug_mode_enabled").value(prefs.getBoolean("debug_mode_enabled", false))
                writer.endObject()

                writer.name("devices")
                writer.beginArray()
                for (entity in devices) {
                    writer.beginObject()
                    writer.name("uid").value(entity.uid)
                    writer.name("serialNumber").value(entity.serialNumber)
                    writer.name("customName").value(entity.customName)
                    writer.name("deviceType").value(entity.deviceType)
                    writer.name("customIcon").value(entity.customIcon)
                    writer.name("protocolVersion").value(entity.protocolVersion)
                    writer.name("firmwareVersion").value(entity.firmwareVersion)
                    writer.name("firmwarePlatform").value(entity.firmwarePlatform)
                    writer.name("hardwareVersion").value(entity.hardwareVersion)
                    writer.name("manufacturer").value(entity.manufacturer)
                    writer.name("productName").value(entity.productName)
                    writer.name("productModel").value(entity.productModel)
                    writer.name("aesKey")
                    if (entity.aesKey != null) {
                        writer.value(Base64.encodeToString(entity.aesKey, Base64.NO_WRAP))
                    } else {
                        writer.nullValue()
                    }
                    writer.name("boundAt").value(entity.boundAt)
                    writer.name("broadcastName").value(entity.broadcastName)
                    writer.name("advIntervalNormal")
                    if (entity.advIntervalNormal != null) writer.value(entity.advIntervalNormal) else writer.nullValue()
                    writer.name("advIntervalFind")
                    if (entity.advIntervalFind != null) writer.value(entity.advIntervalFind) else writer.nullValue()
                    writer.name("findmyEnabled")
                    if (entity.findmyEnabled != null) writer.value(entity.findmyEnabled) else writer.nullValue()
                    writer.name("findmySupported")
                    if (entity.findmySupported != null) writer.value(entity.findmySupported) else writer.nullValue()
                    writer.name("findmySeed")
                    if (entity.findmySeed != null) {
                        writer.value(Base64.encodeToString(entity.findmySeed, Base64.NO_WRAP))
                    } else {
                        writer.nullValue()
                    }
                    writer.name("findmyPeriodSec")
                    if (entity.findmyPeriodSec != null) writer.value(entity.findmyPeriodSec) else writer.nullValue()
                    writer.name("macAddress").value(entity.macAddress)
                    writer.name("deviceName").value(entity.deviceName)
                    writer.name("sortOrder").value(entity.sortOrder)
                    writer.name("lastSeen").value(entity.lastSeen)
                    writer.name("lastRssi").value(entity.lastRssi)
                    writer.name("battery").value(entity.battery)

                    writer.name("theme")
                    writer.beginObject()
                    writeThemeFilesToJson(context, entity.uid, writer)
                    writer.endObject()
                    writer.endObject()
                }
                writer.endArray()
                writer.endObject()
                writer.flush()
            } finally {
                try { writer.close() } catch (_: Exception) {}
            }
        }
    }

    // --- 导入 ---

    /**
     * 校验并恢复备份。任何格式错误在应用前抛出 BackupException，不产生部分修改。
     * 设备级别错误逐台跳过（不影响其余设备）。
     */
    suspend fun importConfig(
        context: Context,
        repository: DeviceRepository,
        json: String
    ): ImportResult = withContext(Dispatchers.IO) {
        val envelope = try {
            gson.fromJson(json, BackupEnvelope::class.java)
        } catch (e: Exception) {
            throw BackupException("配置文件格式不正确")
        }
        if (envelope == null || envelope.format != FORMAT) {
            throw BackupException("不是有效的 YuiTag 备份文件")
        }
        if (envelope.version > VERSION) {
            throw BackupException("备份文件版本过新，请升级 App 后再导入")
        }

        // 1) 恢复设置（白名单，仅覆盖备份中存在的键）
        var settingsRestored = false
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val s = envelope.settings
        if (s != null) {
            prefs.edit()
                .putString("endpoint_url", s.endpoint_url)
                .putString("endpoint_url_fallback", s.endpoint_url_fallback)
                .putString("endpoint_user", s.endpoint_user)
                .putString("endpoint_pass", s.endpoint_pass)
                .putString("query_extension_type", s.query_extension_type)
                .putFloat("query_extension_value", s.query_extension_value)
                .putString("address_level", s.address_level)
                .putInt("query_batch_key_limit", s.query_batch_key_limit.coerceAtLeast(1))
                .putInt("query_chunk_retry_count", s.query_chunk_retry_count.coerceAtLeast(0))
                .putInt("query_chunk_retry_delay", s.query_chunk_retry_delay.coerceAtLeast(0))
                .putString("amap_web_api_key", s.amap_web_api_key)
                .putString("tianditu_key", s.tianditu_key)
                .putString("tianditu_style", s.tianditu_style)
                .putInt("health_normal_days", s.health_normal_days.coerceAtLeast(1))
                .putBoolean("lite_mode_enabled", s.lite_mode_enabled)
                .putBoolean("debug_mode_enabled", s.debug_mode_enabled)
                .apply()
            settingsRestored = true
        }

        // 2) 恢复设备（合并导入：同名 UID 覆盖，本机独有保留）
        var restored = 0
        envelope.devices?.forEach { bd ->
            if (bd.uid.isBlank()) return@forEach
            try {
                val entity = toEntity(bd, context)
                repository.restoreDevice(entity)
                restored++
            } catch (e: Exception) {
                FileLog.w(TAG, "恢复设备 ${bd.uid} 失败: ${e.message}")
            }
        }

        ImportResult(devicesRestored = restored, settingsRestored = settingsRestored)
    }

    private fun toEntity(bd: BackupDevice, context: Context): DeviceEntity {
        val themePath = if (bd.theme.isNotEmpty()) writeThemeFiles(context, bd.uid, bd.theme) else ""
        return DeviceEntity(
            uid = bd.uid,
            serialNumber = bd.serialNumber,
            customName = bd.customName.ifBlank { "YuiTag_${bd.uid.take(4)}" },
            deviceType = bd.deviceType,
            customIcon = bd.customIcon,
            themePath = themePath,
            protocolVersion = bd.protocolVersion,
            firmwareVersion = bd.firmwareVersion,
            firmwarePlatform = bd.firmwarePlatform,
            hardwareVersion = bd.hardwareVersion,
            manufacturer = bd.manufacturer,
            productName = bd.productName,
            productModel = bd.productModel,
            aesKey = bd.aesKey?.let { Base64.decode(it, Base64.NO_WRAP) },
            boundAt = if (bd.boundAt > 0) bd.boundAt else System.currentTimeMillis(),
            broadcastName = bd.broadcastName,
            advIntervalNormal = bd.advIntervalNormal,
            advIntervalFind = bd.advIntervalFind,
            findmyEnabled = bd.findmyEnabled,
            findmySupported = bd.findmySupported,
            findmySeed = bd.findmySeed?.let { Base64.decode(it, Base64.NO_WRAP) },
            findmyPeriodSec = bd.findmyPeriodSec,
            macAddress = bd.macAddress,
            deviceName = bd.deviceName,
            sortOrder = bd.sortOrder,
            lastSeen = bd.lastSeen,
            lastRssi = bd.lastRssi,
            battery = bd.battery
        )
    }

    // --- 主题文件 ---

    private fun themeDir(context: Context, uid: String): File =
        File(File(context.filesDir, "theme/tag"), uid)

    /** 逐个文件读取并写入 JSON（流式，避免一次性加载全部主题文件到内存） */
    private fun writeThemeFilesToJson(context: Context, uid: String, writer: JsonWriter) {
        val dir = themeDir(context, uid)
        if (!dir.exists()) return
        dir.listFiles()?.forEach { f ->
            if (f.isFile) {
                try {
                    if (f.length() > MAX_THEME_FILE_SIZE) {
                        FileLog.w(TAG, "跳过超大主题文件 ${f.name} (${f.length()}B)")
                        return@forEach
                    }
                    writer.name(f.name).value(Base64.encodeToString(f.readBytes(), Base64.NO_WRAP))
                } catch (e: Exception) {
                    FileLog.w(TAG, "读取主题文件 ${f.name} 失败: ${e.message}")
                }
            }
        }
    }

    private fun writeThemeFiles(context: Context, uid: String, files: Map<String, String>): String {
        val dir = themeDir(context, uid)
        if (!dir.exists()) dir.mkdirs()
        files.forEach { (name, b64) ->
            try {
                // 防御：仅允许普通文件名，防止路径穿越
                if (name.isBlank() || name.contains("/") || name.contains("\\") || name == "." || name == "..") {
                    return@forEach
                }
                val bytes = Base64.decode(b64, Base64.NO_WRAP)
                File(dir, name).writeBytes(bytes)
            } catch (_: Exception) {}
        }
        return dir.absolutePath
    }
}
