package com.yuitag.finder.ble

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
            McumgrProtocol.parseResponse(resp)
        } catch (_: Exception) {
            notifyDeferred = null
            null
        }
    }

    suspend fun imageList(): McumgrProtocol.Response? =
        send(McumgrProtocol.imageListRequest(nextSeq()))

    /**
     * 分块上传固件。返回 true 表示所有数据已被设备接收。
     */
    suspend fun upload(firmware: ByteArray, onProgress: (Float) -> Unit = {}): Boolean {
        val total = firmware.size
        if (total == 0) return false
        // 帧最大 = MTU - 3（ATT 头）；8 字节 SMP 头 + 约 40 字节 CBOR 开销
        val chunkSize = maxOf(20, (mtu - 3) - McumgrProtocol.HEADER_SIZE - 40)
        var off = 0
        while (off < total) {
            val end = minOf(off + chunkSize, total)
            val chunk = firmware.copyOfRange(off, end)
            val resp = send(
                McumgrProtocol.imageUploadRequest(nextSeq(), off, total, chunk),
                20000L
            ) ?: return false
            if (!McumgrProtocol.isOk(resp)) {
                FileLog.e("NcsOta", "上传被拒 off=$off rc=${resp.rc} err=${resp.err}")
                return false
            }
            // 用设备返回的 off 推进（设备写后 off = 旧off + data.len；响应丢失时设备会回正确 off）
            val respOff = ((resp.payload as? CborValue.MapVal)?.entries?.get("off"))?.asLong()?.toInt()
            val newOff = maxOf(end, respOff ?: end)
            if (newOff <= off) return false
            off = newOff
            onProgress(off.toFloat() / total.toFloat())
        }
        return off >= total
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
