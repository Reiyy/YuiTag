package com.reiyy.yuitag.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * V2 蓝牙协议帧加解密与通知解析单元测试（纯 JVM 运行）
 */
class YuiTagProtocolV2Test {

    private val testAesKey = ByteArray(16) { (it + 0x10).toByte() }

    private fun decryptV2Frame(frame: ByteArray, aesKey: ByteArray = testAesKey): ByteArray {
        assertTrue("V2 frame too short: ${frame.size}", frame.size >= YuiTagProtocol.V2_HEADER_LEN + 2)
        val iv = ByteArray(16)
        System.arraycopy(frame, 0, iv, 0, YuiTagProtocol.V2_HEADER_LEN)
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(frame, YuiTagProtocol.V2_HEADER_LEN, frame.size - YuiTagProtocol.V2_HEADER_LEN)
    }

    private fun assertValidCrc(plaintext: ByteArray) {
        val expected = CryptoUtils.crc8(plaintext, 0, plaintext.size - 1)
        assertEquals(expected, plaintext.last())
    }

    @Test
    fun encryptFrameV2_prependsSeqAndSaltAndRoundTrips() {
        val seq = 0x5A.toByte()
        val payload = byteArrayOf(YuiTagProtocol.CMD_ID_RING, YuiTagProtocol.CMD_ACTION_ON, 0x00)
        payload[2] = CryptoUtils.crc8(payload, 0, 2)

        val frame = CryptoUtils.encryptFrameV2(payload, testAesKey, seq)

        assertEquals(YuiTagProtocol.V2_HEADER_LEN + payload.size, frame.size)
        assertEquals(seq, frame[0])
        val decrypted = decryptV2Frame(frame)
        assertArrayEquals(payload, decrypted)
        assertValidCrc(decrypted)
    }

    @Test
    fun buildOtaAuthFrame_usesV2HeaderAndExecAction() {
        val seq = 0x7E.toByte()
        val frame = CryptoUtils.buildOtaAuthFrame(testAesKey, seq)

        assertEquals(7, frame.size)
        assertEquals(seq, frame[0])
        val decrypted = decryptV2Frame(frame)
        assertEquals(3, decrypted.size)
        assertEquals(YuiTagProtocol.CMD_OTA_AUTH, decrypted[0])
        assertEquals(YuiTagProtocol.CMD_ACTION_EXEC, decrypted[1])
        assertValidCrc(decrypted)
    }

    @Test
    fun buildKeysSyncBatchDataFrame_singleKeyUsesUnified37ByteFormat() {
        val seq = 0x03.toByte()
        val key0 = ByteArray(28) { (it + 1).toByte() }
        val frame = CryptoUtils.buildKeysSyncBatchDataFrame(
            startIndex = 5,
            pubKeysX = listOf(key0),
            aesKey = testAesKey,
            seq = seq
        )

        // 4B 帧头 + 4B 指令头 + 28B 公钥 + 1B CRC = 37B
        assertEquals(37, frame.size)
        assertEquals(seq, frame[0])

        val decrypted = decryptV2Frame(frame)
        assertEquals(33, decrypted.size)
        assertEquals(YuiTagProtocol.CMD_FINDMY_KEYS_SYNC, decrypted[0])
        assertEquals(YuiTagProtocol.SUB_OP_KEYS_DATA, decrypted[1])
        assertEquals(5.toByte(), decrypted[2])
        assertEquals(1.toByte(), decrypted[3])
        assertArrayEquals(key0, decrypted.copyOfRange(4, 32))
        assertValidCrc(decrypted)
    }

    @Test
    fun buildKeysSyncBatchDataFrame_fourKeysFits128ByteMtuBudget() {
        val seq = 0x11.toByte()
        val keys = (0 until 4).map { idx ->
            ByteArray(28) { b -> ((idx shl 5) + b).toByte() }
        }
        val frame = CryptoUtils.buildKeysSyncBatchDataFrame(
            startIndex = 8,
            pubKeysX = keys,
            aesKey = testAesKey,
            seq = seq
        )

        // 4B 帧头 + (4 + 4*28 + 1) = 121B <= 128B (MTU 131 - 3)
        assertEquals(121, frame.size)
        assertTrue(frame.size <= 128)

        val decrypted = decryptV2Frame(frame)
        assertEquals(117, decrypted.size)
        assertEquals(YuiTagProtocol.CMD_FINDMY_KEYS_SYNC, decrypted[0])
        assertEquals(YuiTagProtocol.SUB_OP_KEYS_DATA, decrypted[1])
        assertEquals(8.toByte(), decrypted[2])
        assertEquals(4.toByte(), decrypted[3])
        for (i in 0 until 4) {
            val start = 4 + i * 28
            assertArrayEquals(keys[i], decrypted.copyOfRange(start, start + 28))
        }
        assertValidCrc(decrypted)
    }

    @Test(expected = IllegalArgumentException::class)
    fun buildKeysSyncBatchDataFrame_rejectsEmptyList() {
        CryptoUtils.buildKeysSyncBatchDataFrame(0, emptyList(), testAesKey, 0x01)
    }

    @Test(expected = IllegalArgumentException::class)
    fun buildKeysSyncBatchDataFrame_rejectsInvalidKeyLength() {
        CryptoUtils.buildKeysSyncBatchDataFrame(0, listOf(ByteArray(27)), testAesKey, 0x01)
    }

    @Test
    fun buildBatchParamWriteFrame_encodesTlvItemsAndCrc() {
        val seq = 0x42.toByte()
        val items = listOf(
            YuiTagProtocol.CMD_INTERVAL_CONFIG to byteArrayOf(0xDC.toByte(), 0x05),
            YuiTagProtocol.CMD_TX_POWER_CONFIG to byteArrayOf(0x04)
        )
        val frame = CryptoUtils.buildBatchParamWriteFrame(items, testAesKey, seq)

        val decrypted = decryptV2Frame(frame)
        // [0x70][0x54, 0x02, 0xDC, 0x05][0x58, 0x01, 0x04][CRC]
        assertEquals(9, decrypted.size)
        assertEquals(YuiTagProtocol.CMD_PARAM_BATCH_WRITE, decrypted[0])
        assertEquals(YuiTagProtocol.CMD_INTERVAL_CONFIG, decrypted[1])
        assertEquals(2.toByte(), decrypted[2])
        assertEquals(0xDC.toByte(), decrypted[3])
        assertEquals(0x05.toByte(), decrypted[4])
        assertEquals(YuiTagProtocol.CMD_TX_POWER_CONFIG, decrypted[5])
        assertEquals(1.toByte(), decrypted[6])
        assertEquals(0x04.toByte(), decrypted[7])
        assertValidCrc(decrypted)
    }

    @Test
    fun notificationParsers_enforceV2TwoByteFormat() {
        assertTrue(YuiTagProtocol.isNotificationSuccess(byteArrayOf(0x12, 0x0A)))
        assertFalse(YuiTagProtocol.isNotificationSuccess(byteArrayOf(0x0A)))
        assertFalse(YuiTagProtocol.isNotificationSuccess(byteArrayOf(0x12, 0x0B)))

        assertEquals(0xAB, YuiTagProtocol.extractSeq(byteArrayOf(0xAB.toByte(), 0x0A)))
        assertEquals(-1, YuiTagProtocol.extractSeq(byteArrayOf(0x0A)))

        assertEquals(
            YuiTagProtocol.STATUS_AES_DECRYPT_ERROR,
            YuiTagProtocol.extractStatus(byteArrayOf(0x05, 0x0C))
        )
        assertEquals(
            YuiTagProtocol.STATUS_UNKNOWN,
            YuiTagProtocol.extractStatus(byteArrayOf(0x0A))
        )

        assertEquals(YuiTagProtocol.BIND_VERIFY_OK, YuiTagProtocol.extractBindVerifyStatus(byteArrayOf(0x01)))
        assertEquals(YuiTagProtocol.BIND_VERIFY_FAIL, YuiTagProtocol.extractBindVerifyStatus(byteArrayOf(0x00)))
        assertEquals(YuiTagProtocol.STATUS_SUCCESS, YuiTagProtocol.extractBindVerifyStatus(byteArrayOf(0x07, 0x0A)))
        assertEquals(YuiTagProtocol.STATUS_UNKNOWN, YuiTagProtocol.extractBindVerifyStatus(byteArrayOf()))
    }
}
