package com.reiyy.yuitag.data

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.reiyy.yuitag.BuildConfig
import com.reiyy.yuitag.ble.FileLog
import com.reiyy.yuitag.data.local.DeviceEntity
import com.reiyy.yuitag.service.SeparationAlertService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 配置备份与恢复管理器 (ZIP 架构)
 *
 * 备份包内部结构：
 * ├── manifest.json                  # 元数据清单（版本、导出时间、包含内容等）
 * ├── App/
 * │   └── config.json                # App 全局偏好配置（可选）
 * └── Tags/
 *     └── <安全过滤名称>_<4位短UID>/
 *         ├── config.json            # Tag 配置与密钥
 *         └── theme/                 # Tag 自定义主题资源（原样存储）
 *
 * 恢复采用"合并导入"：备份中的 Tag 覆盖同名 UID，本机其他 Tag 保留；不影响位置历史。
 */
object ConfigBackupManager {

    const val FORMAT = "yuitag-backup"
    const val VERSION = 2
    private const val PREF_NAME = "yuitag_settings"
    private const val TAG = "ConfigBackup"
    private const val MAX_THEME_FILE_SIZE = 20L * 1024 * 1024

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    // --- 数据模型 ---

    data class ExportOptions(
        val includeSettings: Boolean = true,
        val selectedDeviceUids: Set<String> = emptySet()
    )

    data class BackupManifest(
        val format: String = FORMAT,
        val version: Int = VERSION,
        val exportedAt: Long = System.currentTimeMillis(),
        val appVersion: String = BuildConfig.VERSION_NAME,
        val hasAppSettings: Boolean = false,
        val tags: List<ManifestTagItem> = emptyList()
    )

    data class ManifestTagItem(
        val uid: String,
        val name: String,
        val folderName: String
    )

    data class BackupSettings(
        val endpoint_url: String = "",
        val endpoint_url_fallback: String = "",
        val endpoint_user: String = "",
        val endpoint_pass: String = "",
        val query_extension_type: String = "time",
        val query_extension_value: Float = 2.0f,
        val address_level: String = "street",
        val query_batch_key_limit: Int = -1,
        val query_chunk_retry_count: Int = 1,
        val query_chunk_retry_delay: Int = 1,
        val amap_web_api_key: String = "",
        val geocode_provider: String = "amap",
        val tianditu_geocode_key: String = "",
        val tianditu_key: String = "",
        val tianditu_style: String = "vector",
        val health_normal_days: Int = 7,
        val lite_mode_enabled: Boolean = false,
        val debug_mode_enabled: Boolean = false,
        val auto_refresh_enabled: Boolean = true,
        val query_mode: String = "proxy",
        val live_notification_enabled: Boolean = false,
        val separation_alert_master_enabled: Boolean = false,
        val separation_alert_trigger_level: String = "no_signal",
        val separation_alert_delay_seconds: Int = 120,
        val separation_alert_final_verification_enabled: Boolean = false,
        val separation_alert_safe_wifi_enabled: Boolean = false,
        val separation_alert_safe_wifi_ssids: Set<String> = emptySet(),
        val update_channel: String = "release"
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
        val coreVersion: String = "",
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
        val separationAlertEnabled: Boolean = false,
        val txPower: Int = 0,
        val findmyTxPower: Int = 0,
        val findmyAdvInterval: Int = 2000,
        val fixedKeysJson: String = "",
        val fixedKeyCount: Int? = null
    )

    data class ImportPreview(
        val hasSettings: Boolean,
        val tags: List<ManifestTagItem> = emptyList(),
        val exportedAt: Long = 0L,
        val appVersion: String = ""
    ) {
        val deviceCount: Int get() = tags.size
        val deviceNames: List<String> get() = tags.map { it.name }
    }

    data class ImportOptions(
        val restoreSettings: Boolean = true,
        val selectedDeviceUids: Set<String> = emptySet()
    )

    data class ImportResult(
        val devicesRestored: Int,
        val settingsRestored: Boolean
    )

    class BackupException(message: String) : Exception(message)

    // --- 工具方法 ---

    /**
     * 生成安全的文件夹名称：Tags/<安全过滤后的名称>_<四位短UID>/
     */
    fun sanitizeFolderName(name: String, uid: String): String {
        val cleanName = name.replace(Regex("""[\\/:*?"<>|\r\n\t]"""), "_").trim().ifBlank { "Tag" }
        val shortUid = if (uid.length >= 4) uid.take(4) else uid
        return "${cleanName}_${shortUid}"
    }

    private fun themeDir(context: Context, uid: String): File =
        File(File(context.filesDir, "theme/tag"), uid)

    // --- 导出 ---

    /**
     * 导出为 ZIP 备份包
     */
    suspend fun exportBackup(
        context: Context,
        repository: DeviceRepository,
        outputStream: OutputStream,
        options: ExportOptions
    ) = withContext(Dispatchers.IO) {
        val allDevices = repository.getAllBoundEntities()
        val exportDevices = if (options.selectedDeviceUids.isEmpty()) {
            emptyList()
        } else {
            allDevices.filter { it.uid in options.selectedDeviceUids }
        }

        // 计算每台设备的文件夹名称，防止同名同短UID重复
        val folderMap = mutableMapOf<String, String>()
        val usedFolderNames = mutableSetOf<String>()
        for (dev in exportDevices) {
            val baseName = sanitizeFolderName(dev.customName, dev.uid)
            var finalName = baseName
            var counter = 2
            while (usedFolderNames.contains(finalName)) {
                finalName = "${baseName}_$counter"
                counter++
            }
            usedFolderNames.add(finalName)
            folderMap[dev.uid] = finalName
        }

        val manifest = BackupManifest(
            format = FORMAT,
            version = VERSION,
            exportedAt = System.currentTimeMillis(),
            appVersion = BuildConfig.VERSION_NAME,
            hasAppSettings = options.includeSettings,
            tags = exportDevices.map { dev ->
                ManifestTagItem(
                    uid = dev.uid,
                    name = dev.customName.ifBlank { "YuiTag_${dev.uid.take(4)}" },
                    folderName = folderMap[dev.uid] ?: sanitizeFolderName(dev.customName, dev.uid)
                )
            }
        )

        val zipOut = ZipOutputStream(BufferedOutputStream(outputStream, 64 * 1024))
        try {
            // 1. 写入 manifest.json
            val manifestJson = gson.toJson(manifest)
            zipOut.putNextEntry(ZipEntry("manifest.json"))
            zipOut.write(manifestJson.toByteArray(Charsets.UTF_8))
            zipOut.closeEntry()

            // 2. 写入 App/config.json
            if (options.includeSettings) {
                val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                val settings = BackupSettings(
                    endpoint_url = prefs.getString("endpoint_url", "") ?: "",
                    endpoint_url_fallback = prefs.getString("endpoint_url_fallback", "") ?: "",
                    endpoint_user = prefs.getString("endpoint_user", "") ?: "",
                    endpoint_pass = prefs.getString("endpoint_pass", "") ?: "",
                    query_extension_type = prefs.getString("query_extension_type", "time") ?: "time",
                    query_extension_value = prefs.getFloat("query_extension_value", 2.0f),
                    address_level = prefs.getString("address_level", "street") ?: "street",
                    query_batch_key_limit = prefs.getInt("query_batch_key_limit", -1),
                    query_chunk_retry_count = prefs.getInt("query_chunk_retry_count", 1),
                    query_chunk_retry_delay = prefs.getInt("query_chunk_retry_delay", 1),
                    amap_web_api_key = prefs.getString("amap_web_api_key", "") ?: "",
                    geocode_provider = prefs.getString("geocode_provider", "amap") ?: "amap",
                    tianditu_geocode_key = prefs.getString("tianditu_geocode_key", "") ?: "",
                    tianditu_key = prefs.getString("tianditu_key", "") ?: "",
                    tianditu_style = prefs.getString("tianditu_style", "vector") ?: "vector",
                    health_normal_days = prefs.getInt("health_normal_days", 7),
                    lite_mode_enabled = prefs.getBoolean("lite_mode_enabled", false),
                    debug_mode_enabled = prefs.getBoolean("debug_mode_enabled", false),
                    auto_refresh_enabled = prefs.getBoolean("auto_refresh_enabled", true),
                    query_mode = prefs.getString("query_mode", "proxy") ?: "proxy",
                    live_notification_enabled = prefs.getBoolean("live_notification_enabled", false),
                    separation_alert_master_enabled = prefs.getBoolean("separation_alert_master_enabled", false),
                    separation_alert_trigger_level = prefs.getString("separation_alert_trigger_level", "no_signal") ?: "no_signal",
                    separation_alert_delay_seconds = prefs.getInt("separation_alert_delay_seconds", 120),
                    separation_alert_final_verification_enabled = prefs.getBoolean("separation_alert_final_verification_enabled", false),
                    separation_alert_safe_wifi_enabled = prefs.getBoolean("separation_alert_safe_wifi_enabled", false),
                    separation_alert_safe_wifi_ssids = prefs.getStringSet("separation_alert_safe_wifi_ssids", emptySet()) ?: emptySet(),
                    update_channel = prefs.getString("update_channel", "release") ?: "release"
                )
                val settingsJson = gson.toJson(settings)
                zipOut.putNextEntry(ZipEntry("App/config.json"))
                zipOut.write(settingsJson.toByteArray(Charsets.UTF_8))
                zipOut.closeEntry()
            }

            // 3. 写入每个 Tag 的数据
            for (dev in exportDevices) {
                val folderName = folderMap[dev.uid] ?: continue
                val backupDevice = BackupDevice(
                    uid = dev.uid,
                    serialNumber = dev.serialNumber,
                    customName = dev.customName,
                    deviceType = dev.deviceType,
                    customIcon = dev.customIcon,
                    protocolVersion = dev.protocolVersion,
                    firmwareVersion = dev.firmwareVersion,
                    firmwarePlatform = dev.firmwarePlatform,
                    hardwareVersion = dev.hardwareVersion,
                    manufacturer = dev.manufacturer,
                    productName = dev.productName,
                    productModel = dev.productModel,
                    coreVersion = dev.coreVersion,
                    aesKey = dev.aesKey?.let { Base64.encodeToString(it, Base64.NO_WRAP) },
                    boundAt = dev.boundAt,
                    broadcastName = dev.broadcastName,
                    advIntervalNormal = dev.advIntervalNormal,
                    advIntervalFind = dev.advIntervalFind,
                    findmyEnabled = dev.findmyEnabled,
                    findmySupported = dev.findmySupported,
                    findmySeed = dev.findmySeed?.let { Base64.encodeToString(it, Base64.NO_WRAP) },
                    findmyPeriodSec = dev.findmyPeriodSec,
                    macAddress = dev.macAddress,
                    deviceName = dev.deviceName,
                    sortOrder = dev.sortOrder,
                    lastSeen = dev.lastSeen,
                    lastRssi = dev.lastRssi,
                    battery = dev.battery,
                    separationAlertEnabled = dev.separationAlertEnabled,
                    txPower = dev.txPower,
                    findmyTxPower = dev.findmyTxPower,
                    findmyAdvInterval = dev.findmyAdvInterval,
                    fixedKeysJson = dev.fixedKeysJson,
                    fixedKeyCount = dev.fixedKeyCount
                )

                // 写入 Tags/<folderName>/config.json
                val devJson = gson.toJson(backupDevice)
                zipOut.putNextEntry(ZipEntry("Tags/$folderName/config.json"))
                zipOut.write(devJson.toByteArray(Charsets.UTF_8))
                zipOut.closeEntry()

                // 写入主题文件 Tags/<folderName>/theme/...
                val tDir = themeDir(context, dev.uid)
                if (tDir.exists() && tDir.isDirectory) {
                    tDir.listFiles()?.forEach { file ->
                        if (file.isFile && file.length() <= MAX_THEME_FILE_SIZE) {
                            try {
                                zipOut.putNextEntry(ZipEntry("Tags/$folderName/theme/${file.name}"))
                                file.inputStream().use { input ->
                                    input.copyTo(zipOut)
                                }
                                zipOut.closeEntry()
                            } catch (e: Exception) {
                                FileLog.w(TAG, "写入主题文件 ${file.name} 失败: ${e.message}")
                            }
                        }
                    }
                }
            }
            zipOut.finish()
            zipOut.flush()
        } finally {
            try { zipOut.close() } catch (_: Exception) {}
        }
    }

    // --- 导入预检 ---

    /**
     * 预检备份文件：流式读取 manifest.json 获取内容概览
     */
    suspend fun previewBackup(context: Context, uri: Uri): ImportPreview = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { rawIn ->
            val zipIn = ZipInputStream(BufferedInputStream(rawIn))
            var entry: ZipEntry? = zipIn.nextEntry
            while (entry != null) {
                val entryName = entry.name.replace('\\', '/')
                if (entryName == "manifest.json") {
                    val json = zipIn.bufferedReader(Charsets.UTF_8).readText()
                    val manifest = try {
                        gson.fromJson(json, BackupManifest::class.java)
                    } catch (e: Exception) {
                        throw BackupException("清单文件格式错误")
                    }
                    if (manifest == null || manifest.format != FORMAT) {
                        throw BackupException("不是有效的 YuiTag 备份文件")
                    }
                    if (manifest.version > VERSION) {
                        throw BackupException("备份文件版本过新，请升级 App 后再导入")
                    }
                    return@withContext ImportPreview(
                        hasSettings = manifest.hasAppSettings,
                        tags = manifest.tags,
                        exportedAt = manifest.exportedAt,
                        appVersion = manifest.appVersion
                    )
                }
                zipIn.closeEntry()
                entry = zipIn.nextEntry
            }
            throw BackupException("未找到备份清单 manifest.json")
        } ?: throw BackupException("无法打开备份文件")
    }

    // --- 导入恢复 ---

    /**
     * 恢复备份：解压到缓存目录后合并导入选定的配置与设备
     */
    suspend fun importBackup(
        context: Context,
        repository: DeviceRepository,
        uri: Uri,
        options: ImportOptions = ImportOptions()
    ): ImportResult = withContext(Dispatchers.IO) {
        val tempDir = File(context.cacheDir, "backup_restore_${System.currentTimeMillis()}")
        if (!tempDir.mkdirs()) {
            throw BackupException("无法创建临时解压目录")
        }

        try {
            // 1. 解压整个 ZIP 到 tempDir，带 Zip Slip 防御与路径规范化
            context.contentResolver.openInputStream(uri)?.use { rawIn ->
                val zipIn = ZipInputStream(BufferedInputStream(rawIn))
                var entry: ZipEntry? = zipIn.nextEntry
                val canonicalTemp = tempDir.canonicalPath
                while (entry != null) {
                    val entryName = entry.name.replace('\\', '/')
                    val destFile = File(tempDir, entryName)
                    val canonicalDest = destFile.canonicalPath
                    // 防御路径穿越漏洞
                    if (!canonicalDest.startsWith(canonicalTemp + File.separator) && canonicalDest != canonicalTemp) {
                        throw BackupException("备份文件包含非法路径")
                    }
                    if (entry.isDirectory || entryName.endsWith("/")) {
                        destFile.mkdirs()
                    } else {
                        destFile.parentFile?.mkdirs()
                        destFile.outputStream().use { out ->
                            zipIn.copyTo(out)
                        }
                    }
                    zipIn.closeEntry()
                    entry = zipIn.nextEntry
                }
            } ?: throw BackupException("无法打开备份文件")

            // 2. 读取 manifest.json
            val manifestFile = File(tempDir, "manifest.json")
            if (!manifestFile.exists()) {
                throw BackupException("无效的备份文件：缺少 manifest.json")
            }
            val manifest = try {
                gson.fromJson(manifestFile.readText(Charsets.UTF_8), BackupManifest::class.java)
            } catch (e: Exception) {
                throw BackupException("清单文件损坏")
            }
            if (manifest == null || manifest.format != FORMAT) {
                throw BackupException("不是有效的 YuiTag 备份文件")
            }

            // 3. 恢复 App 设置
            var settingsRestored = false
            if (options.restoreSettings && manifest.hasAppSettings) {
                val configFile = File(tempDir, "App/config.json")
                if (configFile.exists()) {
                    val s = try {
                        gson.fromJson(configFile.readText(Charsets.UTF_8), BackupSettings::class.java)
                    } catch (e: Exception) {
                        null
                    }
                    if (s != null) {
                        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                        prefs.edit()
                            .putString("endpoint_url", s.endpoint_url)
                            .putString("endpoint_url_fallback", s.endpoint_url_fallback)
                            .putString("endpoint_user", s.endpoint_user)
                            .putString("endpoint_pass", s.endpoint_pass)
                            .putString("query_extension_type", s.query_extension_type.ifBlank { "time" })
                            .putFloat("query_extension_value", s.query_extension_value)
                            .putString("address_level", s.address_level.ifBlank { "street" })
                            .apply { if (s.query_batch_key_limit > 0) putInt("query_batch_key_limit", s.query_batch_key_limit) else remove("query_batch_key_limit") }
                            .putInt("query_chunk_retry_count", s.query_chunk_retry_count.coerceAtLeast(0))
                            .putInt("query_chunk_retry_delay", s.query_chunk_retry_delay.coerceAtLeast(0))
                            .putString("geocode_provider", s.geocode_provider.ifBlank { "amap" })
                            .putString("amap_web_api_key", s.amap_web_api_key)
                            .putString("tianditu_geocode_key", s.tianditu_geocode_key)
                            .putString("tianditu_key", s.tianditu_key)
                            .putString("tianditu_style", s.tianditu_style.ifBlank { "vector" })
                            .putInt("health_normal_days", s.health_normal_days.coerceAtLeast(1))
                            .putBoolean("lite_mode_enabled", s.lite_mode_enabled)
                            .putBoolean("debug_mode_enabled", s.debug_mode_enabled)
                            .putBoolean("auto_refresh_enabled", s.auto_refresh_enabled)
                            .putString("query_mode", s.query_mode.ifBlank { "proxy" })
                            .putBoolean("live_notification_enabled", s.live_notification_enabled)
                            .putBoolean("separation_alert_master_enabled", s.separation_alert_master_enabled)
                            .putString("separation_alert_trigger_level", s.separation_alert_trigger_level.ifBlank { "no_signal" })
                            .putInt("separation_alert_delay_seconds", s.separation_alert_delay_seconds.coerceAtLeast(SeparationAlertPreferences.MIN_DELAY_SECONDS))
                            .putBoolean("separation_alert_final_verification_enabled", s.separation_alert_final_verification_enabled)
                            .putBoolean("separation_alert_safe_wifi_enabled", s.separation_alert_safe_wifi_enabled)
                            .putStringSet("separation_alert_safe_wifi_ssids", s.separation_alert_safe_wifi_ssids)
                            .putString("update_channel", s.update_channel.ifBlank { "release" })
                            .apply()
                        settingsRestored = true
                    }
                }
            }

            // 4. 恢复选定的 Tag 设备
            var restoredCount = 0
            val targetTags = if (options.selectedDeviceUids.isEmpty()) {
                emptyList()
            } else {
                manifest.tags.filter { it.uid in options.selectedDeviceUids }
            }

            for (tagItem in targetTags) {
                if (tagItem.uid.isBlank()) continue
                val cleanFolderName = tagItem.folderName.replace('\\', '/').removePrefix("/").removeSuffix("/")
                if (cleanFolderName.contains("..")) {
                    FileLog.w(TAG, "跳过设备 ${tagItem.uid}：文件夹名称非法")
                    continue
                }
                val tagDir = File(tempDir, "Tags/$cleanFolderName")
                val tagConfigFile = File(tagDir, "config.json")
                if (!tagConfigFile.exists()) {
                    FileLog.w(TAG, "跳过设备 ${tagItem.uid}：找不到 config.json")
                    continue
                }
                try {
                    val bd = gson.fromJson(tagConfigFile.readText(Charsets.UTF_8), BackupDevice::class.java)
                        ?: continue

                    // 恢复主题资源
                    val srcThemeDir = File(tagDir, "theme")
                    val targetThemeDir = themeDir(context, bd.uid)
                    var hasThemeFiles = false
                    if (srcThemeDir.exists() && srcThemeDir.isDirectory) {
                        if (!targetThemeDir.exists()) targetThemeDir.mkdirs()
                        srcThemeDir.listFiles()?.forEach { f ->
                            if (f.isFile && !f.name.contains("..") && !f.name.contains("/") && !f.name.contains("\\") && f.length() <= MAX_THEME_FILE_SIZE) {
                                try {
                                    val destThemeFile = File(targetThemeDir, f.name)
                                    f.copyTo(destThemeFile, overwrite = true)
                                    hasThemeFiles = true
                                } catch (e: Exception) {
                                    FileLog.w(TAG, "复制主题文件 ${f.name} 失败: ${e.message}")
                                }
                            }
                        }
                    }

                    val entity = DeviceEntity(
                        uid = bd.uid,
                        serialNumber = bd.serialNumber,
                        customName = bd.customName.ifBlank { "YuiTag_${bd.uid.take(4)}" },
                        deviceType = bd.deviceType,
                        customIcon = bd.customIcon,
                        themePath = if (hasThemeFiles) targetThemeDir.absolutePath else "",
                        protocolVersion = bd.protocolVersion,
                        firmwareVersion = bd.firmwareVersion,
                        firmwarePlatform = bd.firmwarePlatform,
                        hardwareVersion = bd.hardwareVersion,
                        manufacturer = bd.manufacturer,
                        productName = bd.productName,
                        productModel = bd.productModel,
                        coreVersion = bd.coreVersion,
                        aesKey = bd.aesKey?.takeIf { it.isNotBlank() }?.let {
                            runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull()?.takeIf { k -> k.size == 16 }
                        },
                        boundAt = if (bd.boundAt > 0) bd.boundAt else System.currentTimeMillis(),
                        broadcastName = bd.broadcastName,
                        advIntervalNormal = bd.advIntervalNormal,
                        advIntervalFind = bd.advIntervalFind,
                        findmyEnabled = bd.findmyEnabled,
                        findmySupported = bd.findmySupported,
                        findmySeed = bd.findmySeed?.takeIf { it.isNotBlank() }?.let {
                            runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull()?.takeIf { s -> s.isNotEmpty() }
                        },
                        findmyPeriodSec = bd.findmyPeriodSec,
                        macAddress = bd.macAddress,
                        deviceName = bd.deviceName,
                        sortOrder = bd.sortOrder,
                        lastSeen = bd.lastSeen,
                        lastRssi = bd.lastRssi,
                        battery = bd.battery,
                        separationAlertEnabled = bd.separationAlertEnabled,
                        txPower = bd.txPower,
                        findmyTxPower = bd.findmyTxPower,
                        findmyAdvInterval = if (bd.findmyAdvInterval in 200..5000) bd.findmyAdvInterval else 2000,
                        fixedKeysJson = bd.fixedKeysJson,
                        fixedKeyCount = bd.fixedKeyCount
                    )
                    repository.restoreDevice(entity)
                    restoredCount++
                } catch (e: Exception) {
                    FileLog.w(TAG, "恢复设备 ${tagItem.uid} 失败: ${e.message}")
                }
            }

            if (settingsRestored || restoredCount > 0) {
                SeparationAlertService.syncServiceState(context)
            }

            ImportResult(devicesRestored = restoredCount, settingsRestored = settingsRestored)
        } finally {
            try {
                tempDir.deleteRecursively()
            } catch (_: Exception) {}
        }
    }
}
