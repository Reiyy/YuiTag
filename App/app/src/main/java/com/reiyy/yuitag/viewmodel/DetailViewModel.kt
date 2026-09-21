package com.reiyy.yuitag.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.reiyy.yuitag.ble.FileLog
import com.reiyy.yuitag.ble.YuiTagProtocol
import com.reiyy.yuitag.data.DeviceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class DetailViewModel(application: Application) : AndroidViewModel(application) {

    private var uid: String? = null

    lateinit var repository: DeviceRepository
        private set

    fun initRepository(repo: DeviceRepository) {
        repository = repo
    }

    private val _uiState = MutableStateFlow(DetailUiState())
    val uiState: StateFlow<DetailUiState> = _uiState.asStateFlow()

    fun initialize(deviceUid: String) {
        if (uid != null) return
        uid = deviceUid

        val device = repository.getDevice(deviceUid)
        if (device != null) {
            _uiState.update { it.copy(
                customName = device.customName,
                deviceUid = device.uid,
                version = device.version,
                txPower = device.txPower,
                battery = device.battery,
                advInterval = device.advInterval,
                rssi = device.rssi,
                isBound = device.isBound,
                deviceTypeLabel = device.deviceType.label,
                // 预填缓存中的设备信息（后续 GATT 读取会刷新）
                deviceName = device.deviceName,
                serialNumber = device.serialNumber,
                firmwareVer = device.firmwareVersion,
                firmwarePlatform = device.firmwarePlatform,
                hardwareVersion = device.hardwareVersion,
                manufacturer = device.manufacturer,
                productName = device.productName,
                productModel = device.productModel,
                coreVersion = device.coreVersion,
                // 预填缓存中的广播配置
                broadcastName = device.broadcastName,
                advIntervalNormal = device.advIntervalNormal,
                advIntervalFind = device.advIntervalFind,
                findmyEnabled = device.findmyEnabled,
                findmySupported = device.findmySupported,
                findmyPeriodIndex = device.findmyPeriodSec?.let { YuiTagProtocol.FindMyPeriod.fromSeconds(it).index } ?: 2
            ) }
        }

        viewModelScope.launch {
            repository.devices.collectLatest { devices ->
                val dev = devices[deviceUid] ?: return@collectLatest
                _uiState.update { it.copy(
                    rssi = dev.rssi,
                    // 电量有效值判断（0% 为合法电量）
                    battery = if (dev.battery >= 0) dev.battery else it.battery,
                    isBound = dev.isBound,
                    customName = dev.customName.ifEmpty { it.customName }
                ) }
            }
        }

        loadDeviceInfo()
    }

    private fun loadDeviceInfo() {
        val deviceUid = uid ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, loadError = null, paramLoadError = null) }

            val bleDevice = findBluetoothDevice()
            if (bleDevice == null) {
                _uiState.update { it.copy(isLoading = false, loadError = "设备不在范围内") }
                return@launch
            }

            val device = repository.getDevice(deviceUid)
            val aesKey = device?.aesKey

            // 复用统一会话读取 DIS 与 PARAM，避免单连接冲突
            val result = repository.bleConnectionManager.withSession(deviceUid, bleDevice, aesKey) { session ->
                // --- 读取 DIS 设备信息 ---
                // 仅当本地尚未缓存完整的固定硬件/固件信息时读取 DIS，避免每次进入都产生 7+ 个特征值读取的链路占用
                val needReadDis = device?.serialNumber.isNullOrEmpty() ||
                                  device?.firmwareVersion.isNullOrEmpty() ||
                                  device?.hardwareVersion.isNullOrEmpty() ||
                                  device?.coreVersion.isNullOrEmpty()

                if (needReadDis) {
                    session.readDisInfo(device?.name ?: "").onSuccess { info ->
                        _uiState.update { it.copy(
                            deviceName = info.deviceName,
                            serialNumber = info.serialNumber,
                            firmwareVer = info.firmwareVer,
                            firmwarePlatform = info.firmwarePlatform,
                            hardwareVersion = info.hardwareVersion,
                            manufacturer = info.manufacturer,
                            productName = info.productName,
                            productModel = info.productModel,
                            coreVersion = info.coreVersion
                        ) }
                        val dev = repository.getDevice(deviceUid)
                        if (dev != null) {
                            repository.upsertScannedDevice(dev.copy(
                                name = info.deviceName,
                                serialNumber = info.serialNumber,
                                firmwareVersion = info.firmwareVer,
                                firmwarePlatform = info.firmwarePlatform,
                                hardwareVersion = info.hardwareVersion,
                                manufacturer = info.manufacturer,
                                productName = info.productName,
                                productModel = info.productModel,
                                coreVersion = info.coreVersion
                            ))
                        }
                        // 持久化 GATT 读取的 DIS 信息，重启后可直接从数据库取值显示
                        repository.updateDeviceReadInfo(
                            uid = deviceUid,
                            deviceName = info.deviceName,
                            serialNumber = info.serialNumber,
                            firmwareVersion = info.firmwareVer,
                            firmwarePlatform = info.firmwarePlatform,
                            hardwareVersion = info.hardwareVersion,
                            manufacturer = info.manufacturer,
                            productName = info.productName,
                            productModel = info.productModel,
                            coreVersion = info.coreVersion
                        )
                    }.onFailure { e ->
                        if (e !is CancellationException) {
                            _uiState.update { it.copy(loadError = "读取设备信息失败: ${e.message}") }
                        }
                    }
                }

                // --- 读取 PARAM 配置（广播名称、广播间隔） ---
                if (device?.isBound == true) {
                    session.readParamConfig().onSuccess { param ->
                        _uiState.update { it.copy(
                            broadcastName = param.broadcastName.ifEmpty { it.broadcastName },
                            advIntervalNormal = param.advIntervalNormal,
                            advIntervalFind = param.advIntervalFind,
                            findmyEnabled = param.findmyEnabled,
                            findmySupported = param.findmySupported,
                            findmyPeriodIndex = param.findmyPeriodIndex
                        ) }
                        repository.updateBroadcastConfig(
                            deviceUid,
                            param.broadcastName,
                            param.advIntervalNormal,
                            param.advIntervalFind
                        )
                        repository.updateFindMyEnabled(deviceUid, param.findmyEnabled)
                        repository.updateFindMySupport(deviceUid, param.findmySupported)
                        repository.updateFindMyPeriod(deviceUid,
                            YuiTagProtocol.FindMyPeriod.fromIndex(param.findmyPeriodIndex).seconds
                        )
                    }.onFailure { e ->
                        if (e !is CancellationException) {
                            // PARAM 读取失败为非致命，保留缓存值但单独提示
                            FileLog.w("DetailVM", "读取PARAM配置失败: ${e.message}")
                            _uiState.update { it.copy(paramLoadError = "读取设备配置失败，已显示缓存值") }
                        }
                    }
                }
                Unit
            }

            result.onFailure { e ->
                if (e !is CancellationException) {
                    _uiState.update { it.copy(loadError = "连接设备失败: ${e.message}") }
                }
            }

            _uiState.update { it.copy(isLoading = false) }
        }
    }

    // --- 解绑设备 ---
    fun unbindDevice() {
        val deviceUid = uid ?: return
        val bleDevice = findBluetoothDevice() ?: run {
            _uiState.update { it.copy(unbindError = "设备不在范围内，请靠近后重试") }
            return
        }
        val device = repository.getDevice(deviceUid) ?: return
        val aesKey = device.aesKey ?: run {
            _uiState.update { it.copy(unbindError = "设备未绑定，缺少密钥") }
            return
        }
        if (!device.isBound) return

        viewModelScope.launch {
            _uiState.update { it.copy(isUnbinding = true, unbindError = null) }

            // 复用统一会话发送解绑指令
            val result = repository.bleConnectionManager.withSession(deviceUid, bleDevice, aesKey) { session ->
                session.sendUnbind()
            }
            result.onSuccess { success ->
                if (success) {
                    repository.deleteDevice(deviceUid)
                    _uiState.update { it.copy(isUnbinding = false, unbindSuccess = true) }
                } else {
                    _uiState.update { it.copy(
                        isUnbinding = false,
                        unbindError = "解绑被设备拒绝，请重试"
                    ) }
                }
            }.onFailure { e ->
                _uiState.update { it.copy(
                    isUnbinding = false,
                    unbindError = "连接失败，请靠近设备后重试"
                ) }
            }
        }
    }

    /**
     * 强制解绑：发送解绑指令后不等待响应，直接清除本地数据。
     * 适用于设备已不在身边或正常解绑无法完成的场景。
     */
    fun forceUnbindDevice() {
        val deviceUid = uid ?: return
        val bleDevice = findBluetoothDevice()
        val device = repository.getDevice(deviceUid)
        val aesKey = device?.aesKey

        _uiState.update { it.copy(isUnbinding = true) }

        viewModelScope.launch {
            // 尽力发送解绑指令
            if (bleDevice != null && aesKey != null) {
                repository.bleConnectionManager.withSession(deviceUid, bleDevice, aesKey) { session ->
                    session.sendUnbind()
                    Unit
                }
            }
            // 清除本地数据
            repository.deleteDevice(deviceUid)
            _uiState.update { it.copy(isUnbinding = false, unbindSuccess = true) }
        }
    }

    fun dismissUnbindError() {
        _uiState.update { it.copy(unbindError = null) }
    }

    fun dismissUnbindSuccess() {
        _uiState.update { it.copy(unbindSuccess = false) }
    }

    // 清除加载错误状态（Snackbar 显示后调用）
    fun dismissLoadError() {
        _uiState.update { it.copy(loadError = null) }
    }

    // 清除 PARAM 读取提示（Snackbar 显示后调用）
    fun dismissParamLoadError() {
        _uiState.update { it.copy(paramLoadError = null) }
    }

    /**
     * 获取设备 BluetoothDevice 引用。
     */
    private fun findBluetoothDevice(): android.bluetooth.BluetoothDevice? {
        val deviceUid = uid ?: return null
        return repository.getBluetoothDevice(deviceUid)
    }

    data class DetailUiState(
        val customName: String = "",
        val deviceName: String = "",
        val deviceUid: String = "",
        val serialNumber: String = "",       // 完整序列号 (12B=24hex)
        val rssi: Int = 0,
        val version: Int = 0,
        val txPower: Int = 0,
        val battery: Int = 0,
        val advInterval: Int = 0,
        val isLoading: Boolean = false,
        val loadError: String? = null,
        val paramLoadError: String? = null,
        val isUnbinding: Boolean = false,
        val unbindError: String? = null,
        val unbindSuccess: Boolean = false,
        val isBound: Boolean = false,
        val deviceTypeLabel: String = "",
        // 设备信息（从 DIS 读取）
        val firmwareVer: String = "",
        val firmwarePlatform: String = "",
        val coreVersion: String = "",
        val hardwareVersion: String = "",
        val manufacturer: String = "",
        val productName: String = "",
        val productModel: String = "",
        // 广播配置（从 PARAM 读取），null = 尚未读取
        val broadcastName: String = "",
        val advIntervalNormal: Int? = null,
        val advIntervalFind: Int? = null,
        val findmyEnabled: Boolean? = null,
        val findmySupported: Boolean? = null,   // Find My 支持状态，false=设备不支持该功能
        val findmyPeriodIndex: Int = 2     // Find My 轮换周期索引
    )
}
