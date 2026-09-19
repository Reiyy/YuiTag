package com.yuitag.finder.viewmodel

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yuitag.finder.ble.BleGattClient
import com.yuitag.finder.ble.NcsOtaSession
import com.yuitag.finder.ble.OtaSession
import com.yuitag.finder.ble.YuiTagProtocol
import com.yuitag.finder.data.DeviceRepository
import com.yuitag.finder.data.FirmwareFileParser
import com.yuitag.finder.data.YuiTagDevice
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class OtaViewModel(application: Application) : AndroidViewModel(application) {

    private var uid: String? = null
    private val gattClient = BleGattClient()
    lateinit var repository: DeviceRepository
        private set

    fun initRepository(repo: DeviceRepository) {
        repository = repo
    }

    private val _uiState = MutableStateFlow(OtaUiState())
    val uiState: StateFlow<OtaUiState> = _uiState.asStateFlow()

    private var otaSession: OtaSession? = null
    private var ncsSession: NcsOtaSession? = null

    fun initialize(deviceUid: String) {
        if (uid != null) return
        uid = deviceUid

        val device = repository.getDevice(deviceUid)
        if (device != null) {
            _uiState.update {
                it.copy(
                    firmwareVersion = device.firmwareVersion.ifEmpty { "未知" },
                    otaPlatform = YuiTagProtocol.otaPlatformOf(device.firmwarePlatform),
                    otaSupported = YuiTagProtocol.supportsOta(device.productModel)
                )
            }
        }

        // 进入 OTA 页面时主动断开既有会话
        repository.bleConnectionManager.disconnect(deviceUid)

        // 预扫描：仅按广播（名 + MAC）检测设备是否已在 bootloader 模式。
        // 独立 bootloader 广播（YuiTag-OTA）仅存在于 bg22，NCS 无需此检测。
        val bleDevice = repository.getBluetoothDevice(deviceUid) ?: return
        if (_uiState.value.otaPlatform != YuiTagProtocol.OtaPlatform.NCS) {
            viewModelScope.launch {
                if (detectDeviceState(bleDevice) == DeviceState.OTA) {
                    _uiState.update { it.copy(isInOtaMode = true) }
                }
            }
        }
    }

    fun selectFirmwareFile(uri: Uri) {
        _uiState.update { it.copy(selectedFileUri = uri, error = null) }
    }

    fun startOta() {
        // 重入保护：传输/校验进行中禁止再次启动
        if (_uiState.value.isTransferring) return

        val deviceUid = uid ?: return
        val uri = _uiState.value.selectedFileUri ?: run {
            _uiState.update { it.copy(error = "请先选择固件文件") }
            return
        }
        val bleDevice = repository.getBluetoothDevice(deviceUid) ?: run {
            _uiState.update { it.copy(error = "设备不在范围内，请靠近后重试") }
            return
        }
        val device = repository.getDevice(deviceUid) ?: return

        // --- OTA 能力与平台分支 ---
        if (!YuiTagProtocol.supportsOta(device.productModel)) {
            _uiState.update { it.copy(error = "该设备型号不支持固件更新") }
            return
        }
        if (YuiTagProtocol.otaPlatformOf(device.firmwarePlatform) == YuiTagProtocol.OtaPlatform.NCS) {
            startNcsOta(bleDevice, device, uri)
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isTransferring = true, status = "正在检测设备状态...", progress = 0f, error = null, completed = false) }

            // 每次开始都重新按广播（名 + MAC）检测
            var inOtaMode = detectDeviceState(bleDevice) == DeviceState.OTA
            _uiState.update { it.copy(isInOtaMode = inOtaMode) }

            // --- Phase 1: OTA 授权与触发 DFU 跳转 ---
            if (!inOtaMode) {
                val aesKey = device.aesKey ?: run {
                    _uiState.update { it.copy(isTransferring = false, error = "设备未绑定，缺少加密密钥") }
                    return@launch
                }

                // 确保断开既有会话并确认物理断开（已断开时立即返回，有残留会话时安全释放）
                repository.bleConnectionManager.disconnectAndAwait(deviceUid)

                _uiState.update { it.copy(status = "正在进行OTA授权...") }

                val appSessionResult = gattClient.connectForOta(bleDevice, aesKey)
                if (appSessionResult.isFailure) {
                    _uiState.update { it.copy(isTransferring = false, error = "OTA授权失败: ${appSessionResult.exceptionOrNull()?.message}") }
                    return@launch
                }

                val appSession = appSessionResult.getOrThrow()

                // connectForOta 通过 GATT 服务表推断“已在 bootloader 模式”时，
                // 必须再用广播（名 + MAC）二次确认，避免旧 GATT 缓存造成误判。
                if (!appSession.needAuth) {
                    val confirmedOta = detectDeviceState(bleDevice) == DeviceState.OTA
                    appSession.disconnect()
                    if (!confirmedOta) {
                        _uiState.update { it.copy(isTransferring = false, isInOtaMode = false, error = "设备状态异常（检测到旧的服务缓存），请返回后重新进入OTA页面重试") }
                        return@launch
                    }
                    _uiState.update { it.copy(isInOtaMode = true, status = "设备已处于OTA模式，跳过授权...") }
                    inOtaMode = true
                } else {
                    appSession.gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    delay(200)

                    // 准备监听 Phase 1 断开（设备将跳转 DFU bootloader）
                    val phase1Disconnected = CompletableDeferred<Unit>()
                    appSession.onDisconnected = {
                        phase1Disconnected.complete(Unit)
                    }

                    _uiState.update { it.copy(status = "正在触发设备进入升级模式...") }
                    val triggerOk = appSession.writeControl(YuiTagProtocol.OTA_OPCODE_BEGIN)
                    if (!triggerOk) {
                        appSession.disconnect()
                        _uiState.update { it.copy(isTransferring = false, error = "触发升级模式失败") }
                        return@launch
                    }

                    // 等待设备断开（跳转 bootloader 会导致连接断开）
                    _uiState.update { it.copy(status = "等待设备重启进入升级模式...") }
                    try {
                        withTimeout(15000) {
                            phase1Disconnected.await()
                        }
                    } catch (_: Exception) {
                        appSession.disconnect()
                        _uiState.update { it.copy(isTransferring = false, error = "设备未响应，请靠近后重试") }
                        return@launch
                    }

                    // 主动扫描 bootloader 广播 (同一 MAC + YuiTag-OTA 名)
                    // 设备已确认断开（正在复位），先等它进 bootloader，再扫描确认
                    delay(1500)
                    _uiState.update { it.copy(status = "正在搜索设备升级模式...") }
                    var bootloaderFound = false
                    for (scanAttempt in 1..3) {
                        if (scanAttempt > 1) {
                            _uiState.update { it.copy(status = "未发现升级模式，正在重试扫描... ($scanAttempt/3)") }
                            delay(2000)
                        }
                        if (awaitBootloader(bleDevice)) {
                            bootloaderFound = true
                            break
                        }
                    }
                    if (!bootloaderFound) {
                        _uiState.update { it.copy(isTransferring = false, error = "设备升级模式未响应，请靠近后重试") }
                        return@launch
                    }

                    // 等待 BLE 扫描完全停止 + Android 栈释放旧连接资源
                    delay(3000)
                }
            }

            // --- Phase 2: 连接 Bootloader & 传输固件 ---
            _uiState.update { it.copy(status = "正在连接升级模式...") }

            val adapter = (getApplication<Application>()
                .getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            val bootloaderDevice = adapter.getRemoteDevice(bleDevice.address)

            var bootSessionResult: Result<OtaSession> = Result.failure(Exception("未尝试"))
            var bootRetries = 0
            while (bootRetries < 3) {
                bootSessionResult = gattClient.connectForFirmwareTransfer(bootloaderDevice)
                if (bootSessionResult.isSuccess) break
                bootRetries++
                if (bootRetries < 3) {
                    _uiState.update { it.copy(status = "正在重试连接升级模式... (${bootRetries}/3)") }
                    delay(1500L * bootRetries)
                }
            }

            if (bootSessionResult.isFailure) {
                _uiState.update { it.copy(isTransferring = false, error = "连接升级模式失败（Bootloader未响应）: ${bootSessionResult.exceptionOrNull()?.message}") }
                return@launch
            }

            val bootSession = bootSessionResult.getOrThrow()
            otaSession = bootSession

            bootSession.gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)

            val transferOk = doFirmwareTransfer(bootSession, uri)

            // --- Phase 3: 传输完成后校验设备是否已重启进入正常模式 ---
            if (transferOk) {
                verifyRebootToApp(bleDevice)
            }
        }
    }

    /**
     * NCS (nRF52832/52810) OTA：MCUmgr SMP over BLE。
     * 授权(0x6F) → 发现 SMP 服务 → image upload → image test/confirm → reset → 校验重启。
     */
    private fun startNcsOta(bleDevice: BluetoothDevice, device: YuiTagDevice, uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isTransferring = true, status = "正在检测设备状态...", progress = 0f, error = null, completed = false) }

            val aesKey = device.aesKey ?: run {
                _uiState.update { it.copy(isTransferring = false, error = "设备未绑定，缺少加密密钥") }
                return@launch
            }

            // 确保断开既有会话并确认物理断开（已断开时立即返回，有残留会话时安全释放）
            repository.bleConnectionManager.disconnectAndAwait(device.uid)

            // Phase 1: 连接 + OTA 授权 + 发现 SMP 服务
            val sessionResult = gattClient.connectForNcsOta(bleDevice, aesKey) { statusText ->
                _uiState.update { it.copy(status = statusText) }
            }
            if (sessionResult.isFailure) {
                _uiState.update { it.copy(isTransferring = false, error = "OTA授权失败: ${sessionResult.exceptionOrNull()?.message}") }
                return@launch
            }
            val session = sessionResult.getOrThrow()
            ncsSession = session
            session.gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)

            // Phase 2: 读取固件文件（支持 .bin / .zip 并提取 SHA256 哈希）
            _uiState.update { it.copy(status = "正在读取固件文件...") }
            val firmwareResult = FirmwareFileParser.readFirmware(
                uri, getApplication<Application>().contentResolver
            )
            if (firmwareResult.isFailure) {
                session.disconnect(); ncsSession = null
                _uiState.update { it.copy(isTransferring = false, error = "读取固件文件失败: ${firmwareResult.exceptionOrNull()?.message}") }
                return@launch
            }
            val firmware = firmwareResult.getOrThrow()
            val image = firmware.data
            _uiState.update { it.copy(totalBytes = image.size, status = "正在传输固件...") }

            // Phase 3: SMP image upload (image 0)
            val ok = session.upload(image) { progress ->
                _uiState.update {
                    it.copy(
                        progress = progress,
                        transferredBytes = (progress * image.size).toInt(),
                        status = "传输中... ${(progress * image.size).toInt()}/${image.size} 字节"
                    )
                }
            }
            if (!ok) {
                session.disconnect(); ncsSession = null
                _uiState.update { it.copy(isTransferring = false, error = "固件传输失败，请重试") }
                return@launch
            }

            // Phase 4: 固件校验与确认 (MCUboot Test & Confirm)
            // 必须向 IMG_STATE 发送带 hash 的写请求，MCUboot 才会将 Slot 1 标记为待生效并在重启时搬运刷写
            _uiState.update { it.copy(status = "正在校验并确认固件...") }
            val deviceImages = session.getImages()
            val targetHash = deviceImages.firstOrNull { it.slot == 1 }?.hash
                ?: firmware.sha256
                ?: run {
                    session.disconnect(); ncsSession = null
                    _uiState.update { it.copy(isTransferring = false, error = "未获取到固件校验哈希，升级取消") }
                    return@launch
                }

            val confirmOk = session.testAndConfirm(targetHash, confirm = true)
            if (!confirmOk) {
                session.disconnect(); ncsSession = null
                _uiState.update { it.copy(isTransferring = false, error = "固件确认失败（MCUboot未就绪），请重试") }
                return@launch
            }

            // Phase 5: reset
            _uiState.update { it.copy(status = "正在触发设备重启...") }
            val resetOk = session.reset()
            delay(500)
            session.disconnect()
            ncsSession = null
            if (!resetOk) {
                _uiState.update { it.copy(isTransferring = false, error = "重启指令未确认，请重试或断电重启") }
                return@launch
            }

            _uiState.update { it.copy(progress = 1f, status = "固件传输完成，等待设备重启...") }

            // Phase 6: 校验设备已重启进入正常模式
            verifyRebootToApp(bleDevice)
        }
    }

    /**
     * 固件传输（BEGIN → 流式写入 → END → 断连）。返回 true 表示 END 已被设备接收。
     * 是否真正安装并重启由 [verifyRebootToApp] 校验。
     */
    private suspend fun doFirmwareTransfer(bootSession: OtaSession, uri: Uri): Boolean {
        _uiState.update { it.copy(status = "正在读取固件文件...") }

        val firmwareData = try {
            withContext(Dispatchers.IO) {
                val contentResolver = getApplication<Application>().contentResolver
                contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }
        } catch (e: Exception) {
            bootSession.disconnect(); otaSession = null
            _uiState.update { it.copy(isTransferring = false, error = "读取固件文件失败: ${e.message}") }
            return false
        }

        if (firmwareData == null || firmwareData.isEmpty()) {
            bootSession.disconnect(); otaSession = null
            _uiState.update { it.copy(isTransferring = false, error = "固件文件为空") }
            return false
        }

        _uiState.update { it.copy(status = "正在传输固件...", totalBytes = firmwareData.size) }

        // Step 1: BEGIN
        _uiState.update { it.copy(status = "DFU Begin (0x00)...") }
        if (!bootSession.writeControl(YuiTagProtocol.OTA_OPCODE_BEGIN)) {
            bootSession.disconnect(); otaSession = null
            _uiState.update { it.copy(isTransferring = false, error = "DFU Begin 失败") }
            return false
        }
        delay(200)

        // Step 2: 流式写入 GBL -> OTA Data
        val chunkSize = (bootSession.mtu - 3).coerceAtLeast(20)
        val totalBytes = firmwareData.size
        var offset = 0
        var retries = 0

        while (offset < totalBytes) {
            val end = minOf(offset + chunkSize, totalBytes)
            val chunk = firmwareData.copyOfRange(offset, end)
            val success = bootSession.writeFirmware(chunk, useWriteNoResponse = true)
            if (!success) {
                retries++
                if (retries > 5) {
                    bootSession.disconnect(); otaSession = null
                    _uiState.update { it.copy(isTransferring = false, error = "固件写入失败 (${offset}/${totalBytes})") }
                    return false
                }
                delay(50L * retries)
                continue
            }
            retries = 0
            offset = end
            delay(27)
            val progress = offset.toFloat() / totalBytes.toFloat()
            _uiState.update { it.copy(
                progress = progress,
                transferredBytes = offset,
                status = "传输中... ${offset}/${totalBytes} 字节"
            ) }
        }

        // Step 3: 短暂等待 bootloader FIFO 排空
        _uiState.update { it.copy(status = "等待设备处理数据...") }
        delay(3000)

        // Step 4: END (0x03) 间隔轮询，直到设备响应，兜底 60s 超时
        _uiState.update { it.copy(status = "DFU End (0x03)...") }
        var endOk = false
        try {
            withTimeout(120000L) {
                var attempt = 0
                while (true) {
                    attempt++
                    _uiState.update { it.copy(status = "DFU End 尝试 (${attempt})...") }
                    if (bootSession.writeControl(YuiTagProtocol.OTA_OPCODE_END, timeoutMs = 10000L)) {
                        endOk = true
                        return@withTimeout
                    }
                    delay(3000)
                }
            }
        } catch (_: Exception) { }

        if (!endOk) {
            bootSession.disconnect(); otaSession = null
            _uiState.update { it.copy(isTransferring = false, error =                 "DFU End 超时（2分钟未响应），请返回重试或重新上电" )}
            return false
        }

        // Step 5: 断连让 bootloader verify+install+reboot
        delay(500)
        bootSession.disconnect()
        otaSession = null
        _uiState.update { it.copy(progress = 1f, status = "固件传输完成，等待设备重启...") }
        return true
    }

    /** 扫描目标地址的广播，直到出现 YuiTag-OTA 广播（名 + MAC），用于 Phase 1 跳转确认 */
    private suspend fun awaitBootloader(device: BluetoothDevice): Boolean {
        return detectDeviceState(device, timeoutMs = 10000L) == DeviceState.OTA
    }

    /** 设备当前广播状态：只按“广播名 + MAC”判定，避免 Android GATT 服务缓存的误判 */
    enum class DeviceState { OTA, APP, UNKNOWN }

    @SuppressLint("MissingPermission")
    private fun bluetoothAdapter(): android.bluetooth.BluetoothAdapter =
        (getApplication<Application>()
            .getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    /**
     * 扫描目标地址的广播，判定设备当前模式：
     *  - OTA  ：广播名 == "YuiTag-OTA"（bootloader 模式）
     *  - APP  ：同一 MAC 且为 YuiTag 正常模式（广播名非 OTA，或含 YuiTag 厂商数据 0x7722）
     *  - UNKNOWN：超时未发现可判定广播
     */
    @SuppressLint("MissingPermission")
    private suspend fun detectDeviceState(device: BluetoothDevice, timeoutMs: Long = 4000L): DeviceState {
        val scanner = bluetoothAdapter().bluetoothLeScanner ?: return DeviceState.UNKNOWN
        val deferred = CompletableDeferred<DeviceState>()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (result.device.address != device.address) return
                val name = result.scanRecord?.deviceName
                val isYuiTagAdv = result.scanRecord?.getManufacturerSpecificData(YuiTagProtocol.COMPANY_ID) != null
                val state = when {
                    name == "YuiTag-OTA" -> DeviceState.OTA
                    (name != null && name != "YuiTag-OTA") || isYuiTagAdv -> DeviceState.APP
                    else -> null
                }
                state?.let { if (!deferred.isCompleted) deferred.complete(it) }
            }

            override fun onScanFailed(errorCode: Int) {
                if (!deferred.isCompleted) deferred.complete(DeviceState.UNKNOWN)
            }
        }

        scanner.startScan(null, settings, callback)
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (_: Exception) {
            DeviceState.UNKNOWN
        } finally {
            try { scanner.stopScan(callback) } catch (_: Exception) {}
        }
    }

    /** 传输完成后校验设备是否已重启进入正常模式（一次连续扫描，广播名 + MAC），最多等待 12s */
    private suspend fun verifyRebootToApp(bleDevice: BluetoothDevice) {
        _uiState.update { it.copy(status = "正在确认设备重启结果...") }
        val scanner = bluetoothAdapter().bluetoothLeScanner ?: run {
            _uiState.update { it.copy(isTransferring = false, completed = true, status = "固件更新完成，设备正在重启，请稍后确认") }
            return
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val appSeen = CompletableDeferred<Unit>()
        val lastState = AtomicReference(DeviceState.UNKNOWN)
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (result.device.address != bleDevice.address) return
                val name = result.scanRecord?.deviceName
                val isYuiTagAdv = result.scanRecord?.getManufacturerSpecificData(YuiTagProtocol.COMPANY_ID) != null
                val state = when {
                    name == "YuiTag-OTA" -> DeviceState.OTA
                    (name != null && name != "YuiTag-OTA") || isYuiTagAdv -> DeviceState.APP
                    else -> null
                }
                if (state != null) lastState.set(state)
                // 一旦看到 APP（正常模式）广播即视为重启成功
                if (state == DeviceState.APP && !appSeen.isCompleted) appSeen.complete(Unit)
            }

            override fun onScanFailed(errorCode: Int) {
                // 扫描异常时提前结束，避免空等
                if (!appSeen.isCompleted) appSeen.complete(Unit)
            }
        }

        scanner.startScan(null, settings, callback)
        try {
            withTimeout(12000L) { appSeen.await() }
        } catch (_: Exception) {
            // 12s 超时未观察到 APP 广播
        } finally {
            try { scanner.stopScan(callback) } catch (_: Exception) {}
        }

        when (lastState.get()) {
            DeviceState.APP -> {
                _uiState.update { it.copy(isTransferring = false, completed = true, status = "固件更新完成，设备已重启进入正常模式") }
            }
            DeviceState.OTA -> {
                _uiState.update { it.copy(isTransferring = false, error = "固件已传输，但设备仍停留在升级模式，请重试或断电重启") }
            }
            DeviceState.UNKNOWN -> {
                _uiState.update { it.copy(isTransferring = false, completed = true, status = "固件更新完成，设备正在重启，请稍后确认") }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        try { otaSession?.disconnect() } catch (_: Exception) {}
        otaSession = null
        try { ncsSession?.disconnect() } catch (_: Exception) {}
        ncsSession = null
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    data class OtaUiState(
        val firmwareVersion: String = "未知",
        val selectedFileUri: Uri? = null,
        val isTransferring: Boolean = false,
        val progress: Float = 0f,
        val status: String = "",
        val error: String? = null,
        val totalBytes: Int = 0,
        val transferredBytes: Int = 0,
        val completed: Boolean = false,
        val isInOtaMode: Boolean = false,
        val otaPlatform: YuiTagProtocol.OtaPlatform = YuiTagProtocol.OtaPlatform.UNKNOWN,
        val otaSupported: Boolean = true
    )
}
