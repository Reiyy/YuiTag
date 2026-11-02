package com.reiyy.yuitag.ble

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 加密工具：ECDH 密钥协商 + AES-128-CTR + CRC-8
 * 支持设备配对认证、控制指令加密以及 Find My 广播载荷解密
 */
object CryptoUtils {
    private const val TAG = "YuiTagCrypto"

    // Find My 派生域分隔常量：必须与固件 yuitag_config.h 的 FINDMY_DERIVE_INFO 保持一致
    private const val FINDMY_DERIVE_INFO = "ciallo"

    // --- CRC-8 (多项式 0x07) ---
    fun crc8(data: ByteArray, offset: Int = 0, len: Int = data.size - offset): Byte {
        var crc: Int = 0x00
        for (i in offset until offset + len) {
            crc = crc xor (data[i].toInt() and 0xFF)
            for (j in 0 until 8) {
                crc = if ((crc and 0x80) != 0) ((crc shl 1) xor 0x07) and 0xFF else (crc shl 1) and 0xFF
            }
        }
        return crc.toByte()
    }

    fun crc8(data: ByteArray): Byte = crc8(data, 0, data.size)

    // --- ECDH 密钥对 (secp256r1 / P-256) ---
    data class EcdhKeyPair(
        val publicKey: ByteArray,   // 未压缩格式：0x04 + 32B X + 32B Y = 65 字节
        val privateKey: java.security.PrivateKey
    )

    fun generateEcdhKeyPair(): EcdhKeyPair {
        FileLog.i("Crypto", "生成 ECDH 密钥对 (secp256r1)")
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val kp: KeyPair = kpg.generateKeyPair()

        val rawPub = extractRawPublicKey(kp.public as ECPublicKey)
        FileLog.hex("Crypto", "App公钥", rawPub)
        return EcdhKeyPair(publicKey = rawPub, privateKey = kp.private)
    }

    /**
     * 从 ECPublicKey 提取原始 65 字节无压缩公钥 [0x04 || X(32) || Y(32)]
     * 使用 ECPoint.getW() 直接获取坐标，避免 X.509 编码解析的不确定性
     */
    private fun extractRawPublicKey(publicKey: ECPublicKey): ByteArray {
        val w = publicKey.w
        val x = toFixed32Bytes(w.affineX)
        val y = toFixed32Bytes(w.affineY)
        return byteArrayOf(0x04.toByte()) + x + y
    }

    /** 将 BigInteger 转换为固定 32 字节大端数组（不足前面补零） */
    private fun toFixed32Bytes(value: java.math.BigInteger): ByteArray {
        val raw = value.toByteArray()
        if (raw.size == 32) return raw
        val fixed = ByteArray(32)
        // raw[0] 可能是符号位 0x00，需要跳过
        val srcOff = if (raw.size > 32 && raw[0] == 0.toByte()) raw.size - 32 else 0
        val srcLen = minOf(raw.size - srcOff, 32)
        System.arraycopy(raw, srcOff, fixed, 32 - srcLen, srcLen)
        return fixed
    }

    /**
     * 从原始 65 字节未压缩公钥重建 ECPublicKey
     * 固件通过 mbedtls_ecp_point_write_binary 导出该格式
     */
    fun reconstructPublicKey(rawPoint: ByteArray): java.security.PublicKey {
        FileLog.hex("Crypto", "Tag公钥", rawPoint)
        // X.509 SubjectPublicKeyInfo for secp256r1
        val algorithmId = byteArrayOf(
            0x30.toByte(), 0x59.toByte(),
            0x30.toByte(), 0x13.toByte(),
            0x06.toByte(), 0x07.toByte(),
            0x2A.toByte(), 0x86.toByte(), 0x48.toByte(), 0xCE.toByte(), 0x3D.toByte(), 0x02.toByte(), 0x01.toByte(),
            0x06.toByte(), 0x08.toByte(),
            0x2A.toByte(), 0x86.toByte(), 0x48.toByte(), 0xCE.toByte(), 0x3D.toByte(), 0x03.toByte(), 0x01.toByte(), 0x07.toByte(),
            0x03.toByte(), 0x42.toByte(),
            0x00.toByte()
        )
        val x509Key = algorithmId + rawPoint
        val kf = java.security.KeyFactory.getInstance("EC")
        return kf.generatePublic(java.security.spec.X509EncodedKeySpec(x509Key))
    }

    // --- ECDH 共享秘密计算 ---
    fun computeSharedSecret(privateKey: java.security.PrivateKey, peerPublicKey: ByteArray): ByteArray {
        FileLog.i("Crypto", "计算 ECDH 共享秘密")
        val peerPub = reconstructPublicKey(peerPublicKey)
        val ka = javax.crypto.KeyAgreement.getInstance("ECDH")
        ka.init(privateKey)
        ka.doPhase(peerPub, true)
        val secret = ka.generateSecret()
        FileLog.hex("Crypto", "共享秘密", secret)
        return secret
    }

    // --- KDF：SHA-256 提取前 16 字节作为 AES-128 密钥 ---
    fun deriveAesKey(sharedSecret: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        val hash = md.digest(sharedSecret)
        val aesKey = hash.copyOf(16)
        FileLog.hex("Crypto", "AES密钥", aesKey)
        return aesKey
    }

    // --- AES-128-CTR ---
    // 固定全零 IV：Tag 控制帧短且 BLE 链路层已加密
    private val FIXED_IV = ByteArray(16)

    fun aesCtrEncrypt(key: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        val secretKey = SecretKeySpec(key, "AES")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, IvParameterSpec(FIXED_IV))
        val result = cipher.doFinal(plaintext)
        FileLog.d("Crypto", "AES加密 明文${plaintext.toHex()} → 密文${result.toHex()}")
        return result
    }

    fun aesCtrDecrypt(key: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        val secretKey = SecretKeySpec(key, "AES")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(FIXED_IV))
        val result = cipher.doFinal(ciphertext)
        FileLog.d("Crypto", "AES解密 密文${ciphertext.toHex()} → 明文${result.toHex()}")
        return result
    }

    // --- GATT 帧构建 ---
    // 帧格式: [CMD][Action(可选)][CRC] → AES 加密
    /** 构建绑定验证帧: [0x56][CRC] → AES 加密 */
    fun buildBindVerifyFrame(aesKey: ByteArray): ByteArray {
        val plaintext = byteArrayOf(YuiTagProtocol.CMD_BIND_VERIFY, 0x00.toByte())
        plaintext[1] = crc8(plaintext, 0, 1)
        FileLog.d(TAG, "构建绑定验证帧: CMD=0x56 CRC=0x${plaintext[1].toHex()}")
        return aesCtrEncrypt(aesKey, plaintext)
    }

    fun buildCommandFrame(cmdId: Byte, action: Byte, aesKey: ByteArray): ByteArray {
        val plaintext = byteArrayOf(cmdId, action, 0x00.toByte())
        plaintext[2] = crc8(plaintext, 0, 2)
        FileLog.d(TAG, "构建指令帧: CMD=0x${cmdId.toHex()} Act=0x${action.toHex()} CRC=0x${plaintext[2].toHex()}")
        return aesCtrEncrypt(aesKey, plaintext)
    }

    /** 构建解绑指令帧: [0x7F][0xFF][CRC] → AES 加密 */
    fun buildUnbindFrame(aesKey: ByteArray): ByteArray {
        val plaintext = byteArrayOf(YuiTagProtocol.CMD_ID_UNBIND, YuiTagProtocol.CMD_ACTION_EXEC, 0x00.toByte())
        plaintext[2] = crc8(plaintext, 0, 2)
        FileLog.d(TAG, "构建解绑帧: CMD=0x7F Act=0xFF CRC=0x${plaintext[2].toHex()}")
        return aesCtrEncrypt(aesKey, plaintext)
    }

    /** 构建心跳指令帧: [0x06][0xFF][CRC] → AES 加密 */
    fun buildHeartbeatFrame(aesKey: ByteArray): ByteArray {
        val plaintext = byteArrayOf(YuiTagProtocol.CMD_ID_HEARTBEAT, YuiTagProtocol.CMD_ACTION_EXEC, 0x00.toByte())
        plaintext[2] = crc8(plaintext, 0, 2)
        return aesCtrEncrypt(aesKey, plaintext)
    }

    /** 构建名称写入帧: [0x4E][Name(n)][CRC] → AES 加密 */
    fun buildNameWriteFrame(name: String, aesKey: ByteArray): ByteArray {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val plaintext = ByteArray(1 + nameBytes.size + 1)
        plaintext[0] = YuiTagProtocol.CMD_NAME_WRITE
        System.arraycopy(nameBytes, 0, plaintext, 1, nameBytes.size)
        plaintext[plaintext.size - 1] = crc8(plaintext, 0, plaintext.size - 1)
        FileLog.d(TAG, "构建名称写入帧: Name=$name")
        return aesCtrEncrypt(aesKey, plaintext)
    }

    /** 构建连接间隔配置帧: [0x54][Interval(2B LE)][CRC] → AES 加密 */
    fun buildIntervalWriteFrame(intervalMs: Int, aesKey: ByteArray): ByteArray {
        val plaintext = ByteArray(4)
        plaintext[0] = YuiTagProtocol.CMD_INTERVAL_CONFIG
        plaintext[1] = (intervalMs and 0xFF).toByte()
        plaintext[2] = ((intervalMs shr 8) and 0xFF).toByte()
        plaintext[3] = crc8(plaintext, 0, 3)
        FileLog.d(TAG, "构建间隔配置帧: Interval=${intervalMs}ms")
        return aesCtrEncrypt(aesKey, plaintext)
    }

    /** 构建查找模式间隔配置帧: [0x46][Interval(2B LE)][CRC] → AES 加密 */
    fun buildFindIntervalWriteFrame(intervalMs: Int, aesKey: ByteArray): ByteArray {
        val plaintext = ByteArray(4)
        plaintext[0] = YuiTagProtocol.CMD_FIND_INTERVAL_CONFIG
        plaintext[1] = (intervalMs and 0xFF).toByte()
        plaintext[2] = ((intervalMs shr 8) and 0xFF).toByte()
        plaintext[3] = crc8(plaintext, 0, 3)
        FileLog.d(TAG, "构建查找间隔配置帧: Interval=${intervalMs}ms")
        return aesCtrEncrypt(aesKey, plaintext)
    }

    /** 构建发射功率配置帧: [0x58][TxPower(1B)][CRC] → AES 加密 */
    fun buildTxPowerConfigFrame(txPowerDbm: Int, aesKey: ByteArray): ByteArray {
        val plaintext = ByteArray(3)
        plaintext[0] = YuiTagProtocol.CMD_TX_POWER_CONFIG
        plaintext[1] = txPowerDbm.toByte()
        plaintext[2] = crc8(plaintext, 0, 2)
        FileLog.d(TAG, "构建发射功率配置帧: TxPower=${txPowerDbm}dBm")
        return aesCtrEncrypt(aesKey, plaintext)
    }

    /** 构建 Find My 发射功率配置帧: [0x65][TxPower(1B)][CRC] → AES 加密 */
    fun buildFindMyTxPowerConfigFrame(txPowerDbm: Int, aesKey: ByteArray): ByteArray {
        val plaintext = ByteArray(3)
        plaintext[0] = YuiTagProtocol.CMD_FINDMY_TX_POWER_CONFIG
        plaintext[1] = txPowerDbm.toByte()
        plaintext[2] = crc8(plaintext, 0, 2)
        FileLog.d(TAG, "构建FindMy发射功率配置帧: TxPower=${txPowerDbm}dBm")
        return aesCtrEncrypt(aesKey, plaintext)
    }

    /** 构建 Find My 广播间隔配置帧: [0x67][Interval(2B LE)][CRC] → AES 加密 */
    fun buildFindMyIntervalConfigFrame(intervalMs: Int, aesKey: ByteArray): ByteArray {
        val plaintext = ByteArray(4)
        plaintext[0] = YuiTagProtocol.CMD_FINDMY_INTERVAL_CONFIG
        plaintext[1] = (intervalMs and 0xFF).toByte()
        plaintext[2] = ((intervalMs shr 8) and 0xFF).toByte()
        plaintext[3] = crc8(plaintext, 0, 3)
        FileLog.d(TAG, "构建FindMy广播间隔配置帧: Interval=${intervalMs}ms")
        return aesCtrEncrypt(aesKey, plaintext)
    }

    /** 构建 OTA 授权帧: [0x6F][0xFF][CRC] → AES 加密 */
    fun buildOtaAuthFrame(aesKey: ByteArray): ByteArray {
        val plaintext = byteArrayOf(YuiTagProtocol.CMD_OTA_AUTH, YuiTagProtocol.CMD_ACTION_EXEC, 0x00.toByte())
        plaintext[2] = crc8(plaintext, 0, 2)
        FileLog.d(TAG, "构建OTA授权帧: CMD=0x6F Act=0xFF CRC=0x${plaintext[2].toHex()}")
        return aesCtrEncrypt(aesKey, plaintext)
    }

    /** 构建电池状态查询帧: [0x50][0x01][CRC] → AES 加密 */
    fun buildBatteryRequestFrame(aesKey: ByteArray): ByteArray {
        val plaintext = byteArrayOf(YuiTagProtocol.CMD_BATTERY, YuiTagProtocol.CMD_ACTION_ON, 0x00.toByte())
        plaintext[2] = crc8(plaintext, 0, 2)
        FileLog.d(TAG, "构建电池请求帧: CMD=0x50")
        return aesCtrEncrypt(aesKey, plaintext)
    }

    /** 构建 Find My 开关帧: [0x60][action][CRC] → AES 加密 */
    fun buildFindMyEnableFrame(enabled: Boolean, aesKey: ByteArray): ByteArray {
        val action = if (enabled) YuiTagProtocol.CMD_ACTION_ON else YuiTagProtocol.CMD_ACTION_OFF
        val plaintext = byteArrayOf(YuiTagProtocol.FINDMY_ENABLE, action, 0x00.toByte())
        plaintext[2] = crc8(plaintext, 0, 2)
        FileLog.d(TAG, "构建FindMy帧: enabled=$enabled")
        return aesCtrEncrypt(aesKey, plaintext)
    }

    /** 构建 Find My 时间同步帧: [0x62][timestamp(4 LE)][CRC] → AES 加密 */
    fun buildFindMyTimeSyncFrame(unixTime: Long, aesKey: ByteArray): ByteArray {
        val plaintext = ByteArray(6)
        plaintext[0] = YuiTagProtocol.CMD_FINDMY_TIME_SYNC
        plaintext[1] = (unixTime and 0xFF).toByte()
        plaintext[2] = ((unixTime shr 8) and 0xFF).toByte()
        plaintext[3] = ((unixTime shr 16) and 0xFF).toByte()
        plaintext[4] = ((unixTime shr 24) and 0xFF).toByte()
        plaintext[5] = crc8(plaintext, 0, 5)
        FileLog.d(TAG, "构建FindMy TIME_SYNC帧: unix=$unixTime")
        return aesCtrEncrypt(aesKey, plaintext)
    }

    /** 构建 Find My 状态查询帧: [0x63][0x01][CRC] → AES 加密 */
    fun buildFindMyStatusFrame(aesKey: ByteArray): ByteArray {
        val plaintext = byteArrayOf(YuiTagProtocol.CMD_FINDMY_STATUS, YuiTagProtocol.CMD_ACTION_ON, 0x00.toByte())
        plaintext[2] = crc8(plaintext, 0, 2)
        FileLog.d(TAG, "构建FindMy STATUS帧")
        return aesCtrEncrypt(aesKey, plaintext)
    }

    /** 构建 Find My 轮换周期配置帧: [0x64][index(1)][CRC] → AES 加密 */
    fun buildFindMyPeriodConfigFrame(periodIndex: Int, aesKey: ByteArray): ByteArray {
        val plaintext = byteArrayOf(YuiTagProtocol.CMD_FINDMY_PERIOD_CONFIG, periodIndex.toByte(), 0x00.toByte())
        plaintext[2] = crc8(plaintext, 0, 2)
        FileLog.d(TAG, "构建FindMy PERIOD_CONFIG帧: index=$periodIndex")
        return aesCtrEncrypt(aesKey, plaintext)
    }

    /** 构建 Find My 固定密钥同步 START 帧: [0x66][0x01][Total(1B)][0x00][CRC] → AES 加密 */
    fun buildKeysSyncStartFrame(total: Int, aesKey: ByteArray): ByteArray {
        val plaintext = byteArrayOf(
            YuiTagProtocol.CMD_FINDMY_KEYS_SYNC,
            YuiTagProtocol.SUB_OP_KEYS_START,
            total.toByte(),
            0x00.toByte(),
            0x00.toByte()
        )
        plaintext[4] = crc8(plaintext, 0, 4)
        FileLog.d(TAG, "构建KeysSync START帧: total=$total")
        return aesCtrEncrypt(aesKey, plaintext)
    }

    /** 构建 Find My 固定密钥同步 DATA 帧: [0x66][0x02][Index(1B)][PubKeyX(28B)][CRC] → AES 加密 */
    fun buildKeysSyncDataFrame(index: Int, pubKeyX: ByteArray, aesKey: ByteArray): ByteArray {
        require(pubKeyX.size == 28) { "pubKeyX must be 28 bytes" }
        val plaintext = ByteArray(3 + 28 + 1)
        plaintext[0] = YuiTagProtocol.CMD_FINDMY_KEYS_SYNC
        plaintext[1] = YuiTagProtocol.SUB_OP_KEYS_DATA
        plaintext[2] = index.toByte()
        System.arraycopy(pubKeyX, 0, plaintext, 3, 28)
        plaintext[31] = crc8(plaintext, 0, 31)
        return aesCtrEncrypt(aesKey, plaintext)
    }

    /** 构建 Find My 固定密钥同步 FINISH 帧: [0x66][0x03][Total(1B)][0x00][CRC] → AES 加密 */
    fun buildKeysSyncFinishFrame(total: Int, aesKey: ByteArray): ByteArray {
        val plaintext = byteArrayOf(
            YuiTagProtocol.CMD_FINDMY_KEYS_SYNC,
            YuiTagProtocol.SUB_OP_KEYS_FINISH,
            total.toByte(),
            0x00.toByte(),
            0x00.toByte()
        )
        plaintext[4] = crc8(plaintext, 0, 4)
        FileLog.d(TAG, "构建KeysSync FINISH帧: total=$total")
        return aesCtrEncrypt(aesKey, plaintext)
    }

    /** 固定 Find My 密钥实体模型 (供低配版 Sparks 轮换与解密使用) */
    data class FixedFindMyKey(
        val index: Int,
        val privateKey: ByteArray,
        val publicKeyX: ByteArray,
        val hashedAdvKey: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is FixedFindMyKey) return false
            return index == other.index &&
                privateKey.contentEquals(other.privateKey) &&
                publicKeyX.contentEquals(other.publicKeyX) &&
                hashedAdvKey.contentEquals(other.hashedAdvKey)
        }

        override fun hashCode(): Int {
            var result = index
            result = 31 * result + privateKey.contentHashCode()
            result = 31 * result + publicKeyX.contentHashCode()
            result = 31 * result + hashedAdvKey.contentHashCode()
            return result
        }
    }

    /** 为 Sparks 设备预先批量生成 N 个固定密钥 (N 介于 5..50) */
    fun generateFixedFindMyKeys(
        seed: ByteArray,
        count: Int = YuiTagProtocol.FINDMY_DEFAULT_KEYS,
        periodSec: Int = 3600
    ): List<FixedFindMyKey> {
        val safeCount = count.coerceIn(YuiTagProtocol.FINDMY_MIN_KEYS, YuiTagProtocol.FINDMY_MAX_KEYS)
        val randomBase = (System.currentTimeMillis() / 1000L) - (1000..999999).random()
        return (0 until safeCount).map { i ->
            val fakeTimestamp = randomBase + i.toLong() * periodSec
            val periodIndex = fakeTimestamp / periodSec
            val priv = deriveFindMyPrivateKey(seed, periodIndex)
            val pubX = deriveFindMyPublicKeyX(priv)
            val hashed = computeHashedAdvKey(pubX)
            FixedFindMyKey(i, priv, pubX, hashed)
        }
    }

    /** 序列化固定密钥列表为 JSON 字符串 */
    fun serializeFixedKeys(keys: List<FixedFindMyKey>): String {
        val array = org.json.JSONArray()
        for (k in keys) {
            val obj = org.json.JSONObject()
            obj.put("index", k.index)
            obj.put("priv", android.util.Base64.encodeToString(k.privateKey, android.util.Base64.NO_WRAP))
            obj.put("pubX", android.util.Base64.encodeToString(k.publicKeyX, android.util.Base64.NO_WRAP))
            obj.put("hash", android.util.Base64.encodeToString(k.hashedAdvKey, android.util.Base64.NO_WRAP))
            array.put(obj)
        }
        return array.toString()
    }

    /** 从 JSON 字符串反序列化固定密钥列表 */
    fun deserializeFixedKeys(json: String): List<FixedFindMyKey> {
        if (json.isBlank()) return emptyList()
        return try {
            val array = org.json.JSONArray(json)
            val list = mutableListOf<FixedFindMyKey>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val idx = obj.getInt("index")
                val priv = android.util.Base64.decode(obj.getString("priv"), android.util.Base64.NO_WRAP)
                val pubX = android.util.Base64.decode(obj.getString("pubX"), android.util.Base64.NO_WRAP)
                val hash = android.util.Base64.decode(obj.getString("hash"), android.util.Base64.NO_WRAP)
                list.add(FixedFindMyKey(idx, priv, pubX, hash))
            }
            list
        } catch (e: Exception) {
            FileLog.w(TAG, "解析固定密钥JSON失败: ${e.message}")
            emptyList()
        }
    }

    // --- Find My 密钥派生 ---

    /**
     * 根据种子与周期索引派生 secp224r1 私钥
     * privateKey(28) = SHA256(seed(32) || period_index_le(4) || FINDMY_DERIVE_INFO)[0:28]
     * FINDMY_DERIVE_INFO 须与固件 yuitag_config.h 保持一致
     */
    fun deriveFindMyPrivateKey(seed: ByteArray, periodIndex: Long): ByteArray {
        val info = FINDMY_DERIVE_INFO.toByteArray(Charsets.UTF_8)
        val input = ByteArray(seed.size + 4 + info.size)
        System.arraycopy(seed, 0, input, 0, seed.size)
        val off = seed.size
        input[off]     = (periodIndex and 0xFF).toByte()
        input[off + 1] = ((periodIndex shr 8) and 0xFF).toByte()
        input[off + 2] = ((periodIndex shr 16) and 0xFF).toByte()
        input[off + 3] = ((periodIndex shr 24) and 0xFF).toByte()
        System.arraycopy(info, 0, input, off + 4, info.size)
        val md = MessageDigest.getInstance("SHA-256")
        val hash = md.digest(input)
        return hash.copyOf(28)
    }

    // --- Find My 种子生成 ---

    /**
     * 从 AES 会话密钥派生 Find My 种子
     * seed(32) = SHA256(AES_Key(16))，双方均持有 AES 密钥无需额外传输
     */
    fun deriveFindMySeed(aesKey: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(aesKey)
    }

    // --- 状态通知解析 ---

    /** 解析参数通道返回的 Find My 状态响应: [0x63][synced(1)][time(4)][period(4)] */
    fun parseFindMyStatusResponse(data: ByteArray): YuiTagProtocol.FindMyStatus? {
        if (data.size < 10 || data[0] != YuiTagProtocol.CMD_FINDMY_STATUS) return null
        val synced = data[1].toInt() != 0
        val time = ((data[2].toLong() and 0xFF) or
                    ((data[3].toLong() and 0xFF) shl 8) or
                    ((data[4].toLong() and 0xFF) shl 16) or
                    ((data[5].toLong() and 0xFF) shl 24))
        val period = ((data[6].toLong() and 0xFF) or
                      ((data[7].toLong() and 0xFF) shl 8) or
                      ((data[8].toLong() and 0xFF) shl 16) or
                      ((data[9].toLong() and 0xFF) shl 24))
        return YuiTagProtocol.FindMyStatus(synced, time, period)
    }

    /** 解密并校验 GATT 响应帧 */
    fun decryptFrame(encrypted: ByteArray, aesKey: ByteArray): ByteArray? {
        val plaintext = aesCtrDecrypt(aesKey, encrypted)
        val expectedCrc = crc8(plaintext, 0, plaintext.size - 1)
        return if (expectedCrc == plaintext.last()) plaintext else {
            FileLog.w(TAG, "CRC 校验失败! 期望=0x${expectedCrc.toHex()} 实际=0x${plaintext.last().toHex()}")
            null
        }
    }

    // --- 哈希与椭圆曲线计算 ---

    fun sha256(data: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(data)
    }

    // 缓存 secp224r1 曲线参数，避免密钥派生与载荷解密循环中重复反射查找
    private val SECP224R1_SPEC by lazy {
        org.bouncycastle.jce.ECNamedCurveTable.getParameterSpec("secp224r1")
    }

    // --- Find My secp224r1 公钥 X 坐标 ---

    /**
     * 计算 secp224r1 私钥对应的公钥 X 坐标 (28 字节)
     * Q = d * G (d 对曲线阶取模)，提取 X 坐标
     */
    fun deriveFindMyPublicKeyX(privateKey: ByteArray): ByteArray {
        val spec = SECP224R1_SPEC
        val G = spec.g
        val n = spec.n

        val d = java.math.BigInteger(1, privateKey)
        val dReduced = d.mod(n)

        val Q = G.multiply(dReduced).normalize()
        val x = Q.affineXCoord.toBigInteger()
        return toFixed28Bytes(x)
    }

    private fun toFixed28Bytes(value: java.math.BigInteger): ByteArray {
        val raw = value.toByteArray()
        val result = ByteArray(28)
        val srcLen = minOf(raw.size, 28)
        val srcOff = maxOf(0, raw.size - 28)
        System.arraycopy(raw, srcOff, result, 28 - srcLen, srcLen)
        return result
    }

    // --- 广播密钥与 MAC 派生 ---

    /**
     * 计算广播匹配公钥哈希: SHA-256(pubX)
     * 发送至网络服务用于检索匹配报告
     */
    fun computeHashedAdvKey(pubX: ByteArray): ByteArray {
        return sha256(pubX)
    }

    /**
     * 从 P-224 公钥 X 坐标派生 Find My 广播 BLE MAC 地址
     * 规则: MAC[0] = pubX[0] | 0xC0 (设置最高 2 位为静态随机地址), MAC[1..5] = pubX[1..5]
     */
    fun deriveFindMyMac(pubX: ByteArray): String {
        require(pubX.size >= 6) { "pubX must be at least 6 bytes, got ${pubX.size}" }
        val mac = ByteArray(6)
        mac[0] = (pubX[0].toInt() or 0xC0).toByte()
        System.arraycopy(pubX, 1, mac, 1, 5)
        return mac.joinToString(":") { "%02X".format(it) }
    }

    // --- Find My 载荷解密 ---

    /**
     * Find My 位置载荷解密结果
     */
    data class DecryptedFindMyPayload(
        val latitude: Double,
        val longitude: Double,
        val accuracy: Int,
        val confidence: Int,
        val timestamp: Long,
        val batteryStatus: Int,
        val counter: Int,
        val payloadType: String
    )

    /**
     * 解密 Apple Find My 位置载荷: ECDH(secp224r1) + KDF + AES-128-GCM
     *
     * 载荷字节布局:
     *   [0:4]   时间戳 (int32 大端, Apple epoch = 2001-01-01)
     *   [4]     置信度 (uint8)
     *   [5:62]  临时公钥字段 (57 字节, 0x04+XY 或 0x02/0x03+X 补齐)
     *   [62:72] 密文 (10 字节)
     *   [72:]   GCM 认证标签 (16 字节)
     *
     *   载荷大于 88 字节时需先剔除偏移 5 处的附加标志位
     */
    fun decryptFindMyPayload(payload: ByteArray, privateKey: ByteArray): DecryptedFindMyPayload? {
        try {
            val origSize = payload.size
            var hasFlag = false
            var flagByte = 0
            var data = payload
            if (data.size > 88) {
                flagByte = data[5].toInt() and 0xFF
                hasFlag = true
                val modified = ByteArray(data.size - 1)
                System.arraycopy(data, 0, modified, 0, 5)
                System.arraycopy(data, 6, modified, 5, data.size - 6)
                data = modified
            }

            if (data.size < 60) return null

            val timestampRaw = java.nio.ByteBuffer.wrap(data, 0, 4)
                .order(java.nio.ByteOrder.BIG_ENDIAN).int
            val seenTimestamp = timestampRaw.toLong() and 0xFFFFFFFFL
            val appleEpoch = 978307200L
            val timestamp = appleEpoch + seenTimestamp

            val confidence = (data[4].toInt() and 0xFF)

            val rawKeyField = data.copyOfRange(5, 62)
            val pointFormat = rawKeyField[0].toInt() and 0xFF
            val pointBytesForEcdh = when (pointFormat) {
                0x04 -> rawKeyField
                0x02, 0x03 -> rawKeyField.copyOfRange(0, 29)
                else -> {
                    FileLog.w(TAG, "Unknown FindMy EC point format: 0x%02X, payloadSize=${data.size}".format(pointFormat))
                    return null
                }
            }
            val ciphertext = data.copyOfRange(62, 72)
            val tag = data.copyOfRange(72, data.size)

            val spec = SECP224R1_SPEC
            val n = spec.n
            val ephemeralPoint = spec.curve.decodePoint(pointBytesForEcdh)

            val d = java.math.BigInteger(1, privateKey)
            val dReduced = d.mod(n)

            val sharedPoint = ephemeralPoint.multiply(dReduced).normalize()
            val sharedX = toFixed28Bytes(sharedPoint.affineXCoord.toBigInteger())

            val kdfCounter = java.nio.ByteBuffer.allocate(4)
                .order(java.nio.ByteOrder.BIG_ENDIAN).putInt(1).array()
            val kdfInput = sharedX + kdfCounter + rawKeyField
            val kdfOutput = sha256(kdfInput)

            val aesKey = kdfOutput.copyOfRange(0, 16)
            val iv = kdfOutput.copyOfRange(16, 32)

            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            val keySpec = javax.crypto.spec.SecretKeySpec(aesKey, "AES")
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, keySpec,
                javax.crypto.spec.GCMParameterSpec(128, iv))
            val plaintext = cipher.doFinal(ciphertext + tag)

            if (plaintext.size < 10) return null

            val latRaw = java.nio.ByteBuffer.wrap(plaintext, 0, 4)
                .order(java.nio.ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
            val lonRaw = java.nio.ByteBuffer.wrap(plaintext, 4, 4)
                .order(java.nio.ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
            val accuracy = (plaintext[8].toInt() and 0xFF)
            val status = (plaintext[9].toInt() and 0xFF)

            var latitude = latRaw / 10000000.0
            var longitude = lonRaw / 10000000.0

            val pointCorrection = 0xFFFFFFFFL / 10000000.0
            if (latitude > 90.0) latitude -= pointCorrection
            if (latitude < -90.0) latitude += pointCorrection
            if (longitude > 180.0) longitude -= pointCorrection
            if (longitude < -180.0) longitude += pointCorrection

            val batteryStatus = (status shr 6) and 0x03
            val counter = status and 0x3F

            val payloadType = if (hasFlag) {
                "flag(0x%02X) %dB".format(flagByte, origSize)
            } else {
                "0x%02X (%dB)".format(pointFormat, origSize)
            }

            return DecryptedFindMyPayload(
                latitude = latitude,
                longitude = longitude,
                accuracy = accuracy,
                confidence = confidence,
                timestamp = timestamp,
                batteryStatus = batteryStatus,
                counter = counter,
                payloadType = payloadType
            )
        } catch (e: Exception) {
            FileLog.w(TAG, "FindMy payload decrypt failed: payloadSize=${payload.size}, " +
                "firstBytes=${payload.take(8).joinToString { "%02X".format(it) }}, error=${e.message}")
            return null
        }
    }
}

// --- 辅助扩展函数 ---
internal fun ByteArray.toHex(): String =
    joinToString(" ") { "%02X".format(it) }

internal fun Byte.toHex(): String = "%02X".format(this.toInt() and 0xFF)
