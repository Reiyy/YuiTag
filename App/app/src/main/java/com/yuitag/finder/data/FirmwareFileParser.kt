package com.yuitag.finder.data

import android.content.ContentResolver
import android.net.Uri
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 固件文件解析：支持直接选择 MCUboot 签名的 .bin，或 NCS 构建产物 dfu_application.zip。
 * zip 内部扫描 .bin 条目（优先 *.signed.bin / app_update.bin）。
 */
object FirmwareFileParser {

    /** 读取固件文件并返回镜像字节（zip 时自动提取 .bin） */
    suspend fun readFirmwareImage(uri: Uri, contentResolver: ContentResolver): Result<ByteArray> =
        withContext(Dispatchers.IO) {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: return@withContext Result.failure(Exception("无法读取文件"))
                val image = extractFirmwareBin(bytes) ?: bytes
                if (image.isEmpty()) {
                    Result.failure(Exception("固件文件为空"))
                } else {
                    Result.success(image)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /** 若为 zip 则提取其中的 .bin（优先 .signed.bin / app_update.bin），否则返回 null */
    fun extractFirmwareBin(data: ByteArray): ByteArray? {
        if (!isZip(data)) return null
        var candidate: ByteArray? = null
        var candidateScore = -1
        try {
            ZipInputStream(ByteArrayInputStream(data)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val name = entry.name.lowercase()
                        if (name.endsWith(".bin")) {
                            val content = zis.readBytes()
                            if (content.isNotEmpty()) {
                                var score = 0
                                if (name.contains("signed")) score += 3
                                if (name.contains("update")) score += 2
                                if (name.contains("app")) score += 1
                                if (score > candidateScore) {
                                    candidate = content
                                    candidateScore = score
                                }
                            }
                        }
                    }
                    entry = zis.nextEntry
                }
            }
        } catch (_: Exception) {
        }
        return candidate
    }

    private fun isZip(data: ByteArray): Boolean {
        if (data.size < 4) return false
        // PK 签名：0x50 0x4B 0x03/0x05/0x07 0x04
        return data[0].toInt() == 0x50 && data[1].toInt() == 0x4B &&
            (data[2].toInt() == 0x03 || data[2].toInt() == 0x05 || data[2].toInt() == 0x07) &&
            data[3].toInt() == 0x04
    }
}
