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

class BleScanner(private val context: Context) {

    private val bluetoothAdapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter

    val isBluetoothEnabled: Boolean get() = bluetoothAdapter?.isEnabled == true

    val isBleSupported: Boolean
        get() = context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_BLUETOOTH_LE)

    @SuppressLint("MissingPermission")
    fun scanResults(
        scanMode: Int = ScanSettings.SCAN_MODE_LOW_LATENCY
    ): Flow<ScanResult> = callbackFlow {
        val adapter = bluetoothAdapter
            ?: throw IllegalStateException("Bluetooth not supported on this device")
        val scanner: BluetoothLeScanner = adapter.bluetoothLeScanner
            ?: throw IllegalStateException("BLE scanner not available")

        // 只匹配 YuiTag 厂商数据（0x7722）：在协议栈/芯片硬件内部过滤，避免无关广播回调消耗 CPU。
        // 绑定与未绑定模式均携带厂商数据；名称解析路径保留作回退。
        val scanFilters = listOf(YuiTagProtocol.createScanFilter())

        val settings = ScanSettings.Builder()
            .setScanMode(scanMode)
            .build()

        // 扫描失败自动重试（针对可恢复错误），最多 3 次
        var retries = 0
        var firstResultLogged = false

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                // 诊断：记录首个扫描结果，确认扫描器确实在投递结果
                if (!firstResultLogged) {
                    firstResultLogged = true
                    FileLog.d("Scan", "首个扫描结果: name=${result.device.name} addr=${result.device.address} rssi=${result.rssi}")
                }
                trySend(result)
            }

            override fun onScanFailed(errorCode: Int) {
                val self = this
                if (retries < MAX_RETRIES && isRecoverable(errorCode)) {
                    retries++
                    FileLog.w("Scan", "扫描失败 errorCode=$errorCode 自动重试($retries/$MAX_RETRIES)")
                    try { scanner.stopScan(self) } catch (_: Exception) {}
                    launch {
                        delay(RETRY_DELAY_MS)
                        try { scanner.startScan(scanFilters, settings, self) } catch (e: Exception) {
                            close(IllegalStateException("BLE扫描重启失败: ${e.message}", e))
                        }
                    }
                } else {
                    val reason = when (errorCode) {
                        SCAN_FAILED_ALREADY_STARTED -> "扫描已启动"
                        SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "应用注册失败"
                        SCAN_FAILED_INTERNAL_ERROR -> "内部错误"
                        SCAN_FAILED_FEATURE_UNSUPPORTED -> "功能不支持"
                        else -> "未知错误($errorCode)"
                    }
                    FileLog.e("Scan", "扫描失败: $reason (errorCode=$errorCode)")
                    close(IllegalStateException("BLE扫描失败: $reason"))
                }
            }
        }

        try {
            FileLog.d("Scan", "扫描启动")
            scanner.startScan(scanFilters, settings, callback)
        } catch (e: Exception) {
            FileLog.e("Scan", "扫描启动失败: ${e.message}", e)
            close(IllegalStateException("BLE扫描启动失败: ${e.message}", e))
            return@callbackFlow
        }

        awaitClose {
            try {
                scanner.stopScan(callback)
            } catch (_: Exception) {
                // Scanner may already be stopped
            }
        }
    }

    private fun isRecoverable(errorCode: Int): Boolean = when (errorCode) {
        ScanCallback.SCAN_FAILED_ALREADY_STARTED,
        ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> true
        else -> false
    }

    companion object {
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 2000L
    }
}
