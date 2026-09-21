package com.reiyy.yuitag.data

import android.content.ContentResolver
import android.net.Uri
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import java.security.MessageDigest

/**
 * 固件文件解析：支持直接选择 MCUboot 签名的 .bin，或 NCS 构建产物 dfu_application.zip。
 * zip 内部扫描 .bin 条目（优先 *.signed.bin / app_update.bin）。
 */
object FirmwareFileParser {

    data class ParsedFirmware(
        val data: ByteArray,
        val sha256: ByteArray?
    )

    /** 读取固件文件并解析（提取 .bin 与 MCUboot SHA256 哈希） */
    suspend fun readFirmware(uri: Uri, contentResolver: ContentResolver): Result<ParsedFirmware> =
        withContext(Dispatchers.IO) {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: return@withContext Result.failure(Exception("无法读取文件"))
                val binData = extractFirmwareBin(bytes) ?: bytes
                if (binData.isEmpty()) {
                    Result.failure(Exception("固件文件为空"))
                } else {
                    val hash = parseMcubootSha256(binData)
                        ?: runCatching { MessageDigest.getInstance("SHA-256").digest(binData) }.getOrNull()
                    Result.success(ParsedFirmware(binData, hash))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /** 读取固件文件并返回镜像字节（兼容旧调用） */
    suspend fun readFirmwareImage(uri: Uri, contentResolver: ContentResolver): Result<ByteArray> =
        readFirmware(uri, contentResolver).map { it.data }

    /**
     * 从 MCUboot 镜像头部与 TLV 区域提取 SHA256 哈希。
     * MCUboot 格式：
     *  0..3: magic (0x96f3b83d, 小端 3D B8 F3 96)
     *  8..9: hdr_size (uint16)
     *  10..11: protect_tlv_size (uint16)
     *  12..15: img_size (uint32)
     *  TLV 偏移 = hdr_size + img_size + protect_tlv_size
     *  TLV 结构：magic (0x6907 / 0x6908, 2 字节) + tot_len (2 字节)
     *  每个 TLV 项：type (0x10 为 SHA256) + pad (1 字节) + len (2 字节) + value (len 字节)
     */
    fun parseMcubootSha256(data: ByteArray): ByteArray? {
        if (data.size < 32) return null
        // 检查 magic: 0x96f3b83d (小端)
        if (data[0] != 0x3d.toByte() || data[1] != 0xb8.toByte() ||
            data[2] != 0xf3.toByte() || data[3] != 0x96.toByte()) {
            return null
        }
        val hdrSize = (data[8].toInt() and 0xFF) or ((data[9].toInt() and 0xFF) shl 8)
        val protectTlvSize = (data[10].toInt() and 0xFF) or ((data[11].toInt() and 0xFF) shl 8)
        val imgSize = (data[12].toInt() and 0xFF) or
            ((data[13].toInt() and 0xFF) shl 8) or
            ((data[14].toInt() and 0xFF) shl 16) or
            ((data[15].toInt() and 0xFF) shl 24)

        val tlvOffset = hdrSize + imgSize + protectTlvSize
        if (tlvOffset + 4 > data.size) return null

        val tlvMagic = (data[tlvOffset].toInt() and 0xFF) or ((data[tlvOffset + 1].toInt() and 0xFF) shl 8)
        // 0x6907 (IMAGE_TLV_PROT_INFO_MAGIC) 或 0x6908 (IMAGE_TLV_INFO_MAGIC)
        if (tlvMagic != 0x6907 && tlvMagic != 0x6908) return null

        val totLen = (data[tlvOffset + 2].toInt() and 0xFF) or ((data[tlvOffset + 3].toInt() and 0xFF) shl 8)
        val end = minOf(tlvOffset + totLen, data.size)
        var pos = tlvOffset + 4
        while (pos + 4 <= end) {
            val type = data[pos].toInt() and 0xFF
            val len = (data[pos + 2].toInt() and 0xFF) or ((data[pos + 3].toInt() and 0xFF) shl 8)
            pos += 4
            if (pos + len > end) break
            // 0x10 = IMAGE_TLV_SHA256
            if (type == 0x10 && len == 32) {
                return data.copyOfRange(pos, pos + 32)
            }
            pos += len
        }
        return null
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
