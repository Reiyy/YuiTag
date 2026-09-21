package com.reiyy.yuitag.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

class FindMyBroadcastScanner(private val context: Context) {

    private val bluetoothAdapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter

    val isBluetoothEnabled: Boolean get() = bluetoothAdapter?.isEnabled == true

    /**
     * 扫描匹配目标 MAC 地址集的 Find My 邻近广播
     *
     * @param targetMacs 候选 MAC 地址集 (大写 "XX:XX:XX:XX:XX:XX" 格式)
     */
    @SuppressLint("MissingPermission")
    fun scan(targetMacs: Set<String>): Flow<FindMyScanResult> = callbackFlow {
        val adapter = bluetoothAdapter
            ?: throw IllegalStateException("Bluetooth not supported on this device")
        val scanner: BluetoothLeScanner = adapter.bluetoothLeScanner
            ?: throw IllegalStateException("BLE scanner not available")

        if (targetMacs.isEmpty()) {
            close(IllegalStateException("No target MAC addresses provided"))
            return@callbackFlow
        }

        val normalizedMacs = targetMacs.map { it.uppercase().replace("-", ":") }.toSet()

        val scanFilter = ScanFilter.Builder()
            .setManufacturerData(APPLE_COMPANY_ID, byteArrayOf(), byteArrayOf())
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        var retries = 0
        var firstResultLogged = false

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (!firstResultLogged) {
                    firstResultLogged = true
                    FileLog.d("FindMyScan", "首个扫描结果: addr=${result.device.address} rssi=${result.rssi}")
                }

                val address = result.device.address.uppercase()
                if (address !in normalizedMacs) return

                val record = result.scanRecord ?: return
                val md = record.getManufacturerSpecificData(APPLE_COMPANY_ID) ?: return

                // Find My 邻近广播格式: [0x12, 0x19, status, ...]
                if (md.size < 3 || md[0] != PROXIMITY_TYPE.toByte()) return

                val batteryStatus = (md[2].toInt() shr 6) and 0x03

                trySend(
                    FindMyScanResult(
                        macAddress = address,
                        rssi = result.rssi,
                        batteryStatus = batteryStatus,
                        timestamp = System.currentTimeMillis()
                    )
                )
            }

            override fun onScanFailed(errorCode: Int) {
                val self = this
                if (retries < MAX_RETRIES && isRecoverable(errorCode)) {
                    retries++
                    FileLog.w("FindMyScan", "扫描失败 errorCode=$errorCode 自动重试($retries/$MAX_RETRIES)")
                    try { scanner.stopScan(self) } catch (_: Exception) {}
                    launch {
                        delay(RETRY_DELAY_MS)
                        try { scanner.startScan(listOf(scanFilter), settings, self) } catch (e: Exception) {
                            close(IllegalStateException("FindMy BLE扫描重启失败: ${e.message}", e))
                        }
                    }
                } else {
                    val reason = when (errorCode) {
                        ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "扫描已启动"
                        ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "应用注册失败"
                        ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "内部错误"
                        ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "功能不支持"
                        else -> "未知错误($errorCode)"
                    }
                    FileLog.e("FindMyScan", "扫描失败: $reason (errorCode=$errorCode)")
                    close(IllegalStateException("FindMy BLE扫描失败: $reason"))
                }
            }
        }

        try {
            FileLog.d("FindMyScan", "FindMy扫描启动: ${normalizedMacs.size} 候选MAC")
            scanner.startScan(listOf(scanFilter), settings, callback)
        } catch (e: Exception) {
            FileLog.e("FindMyScan", "扫描启动失败: ${e.message}", e)
            close(IllegalStateException("FindMy BLE扫描启动失败: ${e.message}", e))
            return@callbackFlow
        }

        awaitClose {
            try {
                scanner.stopScan(callback)
                FileLog.d("FindMyScan", "扫描停止")
            } catch (_: Exception) { }
        }
    }

    private fun isRecoverable(errorCode: Int): Boolean = when (errorCode) {
        ScanCallback.SCAN_FAILED_ALREADY_STARTED,
        ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> true
        else -> false
    }

    data class FindMyScanResult(
        val macAddress: String,
        val rssi: Int,
        val batteryStatus: Int,  // 0=正常, 1=中等, 2=低, 3=极低
        val timestamp: Long
    )

    companion object {
        private const val APPLE_COMPANY_ID = 0x004C
        private const val PROXIMITY_TYPE = 0x12
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 2000L
    }
}
