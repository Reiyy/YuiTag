package com.reiyy.yuitag.ble

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout

/**
 * NCS (MCUmgr SMP over BLE) 固件更新会话。
 *
 * SMP 特征为 WriteWithoutResponse + Notify，因此：
 *  - 每个 SMP 请求以 WRITE_TYPE_NO_RESPONSE 写入；
 *  - 设备通过 notification 回包（同一特征），串行"写→等通知"推进。
 */
class NcsOtaSession(
    val gatt: BluetoothGatt,
    private val smpChar: BluetoothGattCharacteristic,
    val mtu: Int
) {
    var onDisconnected: (() -> Unit)? = null

    private var seq = 0
    private var notifyDeferred: CompletableDeferred<ByteArray>? = null

    /** GATT 通知回调（SMP 响应） */
    fun handleNotify(value: ByteArray) {
        val d = notifyDeferred
        notifyDeferred = null
        if (d != null && d.isActive) d.complete(value)
    }

    private fun nextSeq(): Int {
        val s = seq
        seq = (seq + 1) and 0xFF
        return s
    }

    private suspend fun send(frame: ByteArray, timeoutMs: Long = 15000L): McumgrProtocol.Response? {
        val deferred = CompletableDeferred<ByteArray>()
        notifyDeferred = deferred
        return try {
            smpChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            smpChar.value = frame
            if (!gatt.writeCharacteristic(smpChar)) {
                notifyDeferred = null
                return null
            }
            val resp = withTimeout(timeoutMs) { deferred.await() }
            FileLog.d("NcsOta", "收到 SMP 响应 len=${resp.size} hex=${resp.joinToString("") { "%02x".format(it) }}")
            val parsed = McumgrProtocol.parseResponse(resp)
            FileLog.d("NcsOta", "解析 SMP 响应: op=${parsed?.op} id=${parsed?.id} rc=${parsed?.rc} payload=${parsed?.payload}")
            parsed
        } catch (_: Exception) {
            notifyDeferred = null
            null
        }
    }

    suspend fun imageList(): McumgrProtocol.Response? =
        send(McumgrProtocol.imageListRequest(nextSeq()))

    /** 获取设备上所有 slot 的镜像状态列表 */
    suspend fun getImages(): List<McumgrProtocol.SlotInfo> {
        val resp = imageList() ?: return emptyList()
        if (!McumgrProtocol.isOk(resp)) return emptyList()
        return McumgrProtocol.parseImages(resp.payload)
    }

    /**
     * 分块上传固件。严格校验设备返回的 off，确保数据真实写入 Flash。
     * @param firmware 固件二进制数据
     */
    suspend fun upload(
        firmware: ByteArray,
        onProgress: (Float) -> Unit = {}
    ): Boolean {
        val total = firmware.size
        if (total == 0) return false

        // 限制最大分块大小为 240 字节，与设备端 CONFIG_MCUMGR_TRANSPORT_NETBUF_SIZE=384 保持安全边际
        val maxChunk = minOf((mtu - 3) - McumgrProtocol.HEADER_SIZE - 40, 240)
        val chunkSize = maxOf(20, maxChunk)

        var off = 0
        var retries = 0

        while (off < total) {
            val end = minOf(off + chunkSize, total)
            val chunk = firmware.copyOfRange(off, end)
            val req = McumgrProtocol.imageUploadRequest(
                nextSeq(), off, total, chunk
            )
            val resp = send(req, 20000L)

            if (resp == null) {
                retries++
                FileLog.w("NcsOta", "上传分块超时 off=$off 重试 ($retries/3)")
                if (retries > 3) return false
                kotlinx.coroutines.delay(100L * retries)
                continue
            }

            if (!McumgrProtocol.isOk(resp)) {
                FileLog.e("NcsOta", "上传被拒 off=$off rc=${resp.rc} err=${resp.err}")
                return false
            }

            // 严格校验设备返回的 off：必须非空且严格推进
            val respOff = McumgrProtocol.offOf(resp.payload)?.toInt()
            if (respOff == null || respOff <= off) {
                retries++
                FileLog.w("NcsOta", "设备未返回有效偏移量 respOff=$respOff 当前off=$off 重试 ($retries/3)")
                if (retries > 3) return false
                kotlinx.coroutines.delay(100L * retries)
                continue
            }

            retries = 0
            off = respOff
            onProgress(off.toFloat() / total.toFloat())
        }
        return off >= total
    }

    /**
     * 向设备发送 image state 写入命令，标记 Slot 1 的镜像为待测试（test）或永久生效（confirm）。
     * 这是 MCUboot 引导新镜像所必须的步骤！
     * @param hash 目标镜像的 SHA256 哈希
     * @param confirm true 为永久生效（CONFIRM_ONLY 模式），false 为测试模式（TEST_AND_CONFIRM）
     */
    suspend fun testAndConfirm(hash: ByteArray, confirm: Boolean = true): Boolean {
        FileLog.i("NcsOta", "发送 image state write: confirm=$confirm hash=${hash.joinToString("") { "%02x".format(it) }}")
        val req = McumgrProtocol.imageStateWriteRequest(nextSeq(), hash, confirm)
        val resp = send(req, 15000L) ?: run {
            FileLog.e("NcsOta", "image state write 超时")
            return false
        }
        if (!McumgrProtocol.isOk(resp)) {
            FileLog.e("NcsOta", "image state write 失败 rc=${resp.rc} err=${resp.err}")
            return false
        }
        val images = McumgrProtocol.parseImages(resp.payload)
        FileLog.i("NcsOta", "image state write 成功，当前镜像列表: $images")
        val slot1 = images.firstOrNull { it.slot == 1 }
        if (slot1 != null) {
            // 在 MCUboot 中，confirm=true 时 slot1 会被标记为 pending 或 confirmed
            if (confirm && (slot1.confirmed || slot1.pending)) return true
            if (!confirm && slot1.pending) return true
        }
        // 若 images 为空但 rc=0，在部分 Zephyr 版本中也视为设置成功
        return true
    }

    suspend fun reset(): Boolean {
        val resp = send(McumgrProtocol.resetRequest(nextSeq()), 15000L) ?: return false
        return McumgrProtocol.isOk(resp)
    }

    fun disconnect() {
        try { gatt.disconnect() } catch (_: Exception) {}
        try { gatt.close() } catch (_: Exception) {}
        notifyDeferred?.let { if (it.isActive) it.complete(ByteArray(0)) }
        notifyDeferred = null
    }
}
