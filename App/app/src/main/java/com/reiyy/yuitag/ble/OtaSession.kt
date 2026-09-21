package com.reiyy.yuitag.ble

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout

class OtaSession(
    val gatt: BluetoothGatt,
    private val otaControlChar: BluetoothGattCharacteristic?,
    private val otaDataChar: BluetoothGattCharacteristic?,
    val mtu: Int,
    val needAuth: Boolean = true
) {
    var onDisconnected: (() -> Unit)? = null
    private var writeDeferred: CompletableDeferred<Boolean>? = null

    suspend fun writeFirmware(data: ByteArray, useWriteNoResponse: Boolean = true): Boolean {
        val c = otaDataChar ?: return false
        if (useWriteNoResponse) {
            try {
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                c.value = data
                return gatt.writeCharacteristic(c)
            } catch (e: Exception) { return false }
        }
        val deferred = CompletableDeferred<Boolean>()
        writeDeferred = deferred
        try {
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            c.value = data
            val ok = gatt.writeCharacteristic(c)
            if (!ok) {
                writeDeferred = null
                return false
            }
        } catch (e: Exception) {
            writeDeferred = null
            return false
        }
        return try {
            withTimeout(15000L) { deferred.await() }
        } catch (_: Exception) {
            writeDeferred = null
            false
        }
    }

    suspend fun writeControl(opcode: Byte, timeoutMs: Long = 15000L): Boolean {
        val c = otaControlChar ?: return false
        val deferred = CompletableDeferred<Boolean>()
        writeDeferred = deferred
        try {
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            c.value = byteArrayOf(opcode)
            val ok = gatt.writeCharacteristic(c)
            if (!ok) {
                writeDeferred = null
                return false
            }
        } catch (e: Exception) {
            writeDeferred = null
            return false
        }
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (_: Exception) {
            writeDeferred = null
            false
        }
    }

    fun handleWriteComplete(status: Int) {
        val d = writeDeferred
        writeDeferred = null
        d?.complete(status == BluetoothGatt.GATT_SUCCESS)
    }

    fun disconnect() {
        try { gatt.disconnect() } catch (_: Exception) {}
        try { gatt.close() } catch (_: Exception) {}
        writeDeferred?.complete(false)
    }
}
