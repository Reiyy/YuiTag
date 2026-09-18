package com.yuitag.finder.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yuitag.finder.ble.CryptoUtils
import com.yuitag.finder.ble.FileLog
import com.yuitag.finder.ble.YuiTagProtocol
import com.yuitag.finder.data.DeviceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class DeviceConfigViewModel(application: Application) : AndroidViewModel(application) {

    private var uid: String? = null

    lateinit var repository: DeviceRepository
        private set

    fun initRepository(repo: DeviceRepository) {
        repository = repo
    }

    private val _uiState = MutableStateFlow(ConfigUiState())
    val uiState: StateFlow<ConfigUiState> = _uiState.asStateFlow()

    /** 当前值（用于对比变更） */
    private var originalAdvIntervalNormal: Int? = null
    private var originalAdvIntervalFind: Int? = null
    private var originalFindmyEnabled: Boolean? = null
    private var originalFindmySupported: Boolean? = null
    private var originalFindmyPeriodIndex: Int? = null

    fun initialize(deviceUid: String) {
        if (uid != null) return
        uid = deviceUid

        // 以 Room 实体为首选数据源，避免双重取值导致的表单内容跳变
        viewModelScope.launch {
            try {
                val entity = repository.getDeviceEntity(deviceUid)
                val memory = repository.getDevice(deviceUid)

                originalAdvIntervalNormal = entity?.advIntervalNormal ?: memory?.advIntervalNormal
                originalAdvIntervalFind = entity?.advIntervalFind ?: memory?.advIntervalFind
                originalFindmyEnabled = entity?.findmyEnabled ?: memory?.findmyEnabled
                originalFindmySupported = entity?.findmySupported ?: memory?.findmySupported
                originalFindmyPeriodIndex = (entity?.findmyPeriodSec ?: memory?.findmyPeriodSec)?.let { YuiTagProtocol.FindMyPeriod.fromSeconds(it).index }

                _uiState.update {
                    it.copy(
                        advIntervalNormal = originalAdvIntervalNormal?.toString() ?: "",
                        advIntervalFind = originalAdvIntervalFind?.toString() ?: "",
                        findmyEnabled = originalFindmyEnabled,
                        findmySupported = originalFindmySupported,
                        findmyPeriodIndex = originalFindmyPeriodIndex
                    )
                }
            } catch (e: Exception) {
                FileLog.e("ConfigVM", "初始化异常: ${e.message}", e)
                _uiState.update { it.copy(connectError = "加载配置异常: ${e.message}") }
            }
        }

        // 进入页面即发起 GATT 连接，刷新设备端真实配置
        connectAndLoad()
    }

    /**
     * 进入页面时连接设备并读取 PARAM 配置刷新表单。
     * 连接失败时保留本地缓存值并提示，不阻止后续编辑。
     */
    private fun connectAndLoad() {
        val deviceUid = uid ?: return
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isConnecting = true, connectError = null) }

                val bleDevice = repository.getBluetoothDevice(deviceUid)
                if (bleDevice == null) {
                    _uiState.update { it.copy(isConnecting = false, connectError = "设备不在范围内，已显示本地缓存配置") }
                    return@launch
                }

                // 复用统一会话读取 PARAM 配置
                val device = repository.getDevice(deviceUid)
                repository.bleConnectionManager.withSession(deviceUid, bleDevice, device?.aesKey) { session ->
                    session.readParamConfig().onSuccess { param ->
                        // 刷新表单 + 同步 DB + original 快照
                        _uiState.update { it.copy(
                            advIntervalNormal = param.advIntervalNormal.toString(),
                            advIntervalFind = param.advIntervalFind.toString(),
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
                            YuiTagProtocol.FindMyPeriod.fromIndex(param.findmyPeriodIndex).seconds)
                        originalAdvIntervalNormal = param.advIntervalNormal
                        originalAdvIntervalFind = param.advIntervalFind
                        originalFindmyEnabled = param.findmyEnabled
                        originalFindmySupported = param.findmySupported
                        originalFindmyPeriodIndex = param.findmyPeriodIndex
                    }.onFailure { e ->
                        // PARAM read failure is non-fatal; keep cached values
                        FileLog.w("ConfigVM", "连接读取PARAM配置失败: ${e.message}")
                        _uiState.update { it.copy(connectError = "连接失败，已显示本地缓存配置") }
                    }
                    Unit
                }.onFailure { e ->
                    FileLog.w("ConfigVM", "连接设备失败: ${e.message}")
                    _uiState.update { it.copy(connectError = "连接失败，已显示本地缓存配置") }
                }

                _uiState.update { it.copy(isConnecting = false) }
            } catch (e: Exception) {
                // 读取异常写入日志并降级为错误提示
                FileLog.e("ConfigVM", "连接读取异常: ${e.message}", e)
                _uiState.update { it.copy(isConnecting = false, connectError = "读取配置异常: ${e.message}") }
            }
        }
    }

    fun dismissConnectError() {
        _uiState.update { it.copy(connectError = null) }
    }

    fun updateAdvIntervalNormal(ms: String) {
        val filtered = ms.filter { it.isDigit() }
        if (filtered.length > 5) return  // 最多 5 位数字（最大 10000）
        _uiState.update { it.copy(advIntervalNormal = filtered) }
    }

    fun updateAdvIntervalFind(ms: String) {
        val filtered = ms.filter { it.isDigit() }
        if (filtered.length > 5) return  // 最多 5 位数字（最大 10000）
        _uiState.update { it.copy(advIntervalFind = filtered) }
    }

    fun updateFindMyEnabled(enabled: Boolean) {
        _uiState.update { it.copy(findmyEnabled = enabled) }
    }

    /** 更新 Find My 轮换周期 */
    fun updateFindMyPeriodIndex(index: Int) {
        _uiState.update { it.copy(findmyPeriodIndex = index) }
    }

    fun saveConfig(onSaved: () -> Unit) {
        val deviceUid = uid ?: return
        // 防止重复提交
        if (_uiState.value.isSaving) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, saveError = null) }

            val state = _uiState.value
            val intervalNormal = state.advIntervalNormal.toIntOrNull()
            val intervalFind = state.advIntervalFind.toIntOrNull()
            val findmyEnabled = state.findmyEnabled
            val findmySupported = state.findmySupported
            val findmyPeriodIndex = state.findmyPeriodIndex

            // 从未读取到的配置不允许以默认值写入
            if (intervalNormal == null || intervalFind == null) {
                _uiState.update { it.copy(isSaving = false, saveError = "广播间隔未知，请先连接设备读取配置") }
                return@launch
            }
            // 设备不支持 Find My 时跳过其配置校验（相关 UI 已隐藏）
            if (findmySupported != false && (findmyEnabled == null || findmyPeriodIndex == null)) {
                _uiState.update { it.copy(isSaving = false, saveError = "Find My 配置未知，请先连接设备读取配置") }
                return@launch
            }
            // 不支持 Find My 时以默认值兜底（类型非空化，仅供下方受 gattFindmyChanged 门控的写入使用）
            val supportsFindMy = findmySupported != false
            val findmyEnabledSafe = findmyEnabled ?: false
            val findmyPeriodIndexSafe = findmyPeriodIndex ?: 2

            // 广播间隔范围须在 100-10000ms 之间
            if (intervalNormal !in 100..10000 || intervalFind !in 100..10000) {
                _uiState.update { it.copy(isSaving = false, saveError = "广播间隔必须在 100-10000ms 之间") }
                return@launch
            }

            // --- 变更检测 ---
            val gattNormalChanged = intervalNormal != originalAdvIntervalNormal
            val gattFindChanged = intervalFind != originalAdvIntervalFind
            val gattFindmyChanged = supportsFindMy && findmyEnabled != originalFindmyEnabled
            val gattPeriodChanged = supportsFindMy && findmyPeriodIndex != originalFindmyPeriodIndex
            val anyGattChanged = gattNormalChanged || gattFindChanged || gattFindmyChanged || gattPeriodChanged

            if (!anyGattChanged) {
                _uiState.update { it.copy(isSaving = false, saveError = null) }
                onSaved()
                return@launch
            }

            val bleDevice = repository.getBluetoothDevice(deviceUid)
            val device = repository.getDevice(deviceUid)
            val aesKey = device?.aesKey

            if (bleDevice == null || aesKey == null) {
                _uiState.update { it.copy(isSaving = false, saveError = "设备不在范围内或未绑定") }
                return@launch
            }

            // 经由连接管理器建立/复用单一连接，所有写入按序执行
            val writeResult = repository.bleConnectionManager.withSession(deviceUid, bleDevice, aesKey) { session ->
                // 逐字段写入，成功即更新数据库与本地快照
                val failedFields = mutableListOf<String>()

                if (gattFindmyChanged) {
                    var findmyOk = true
                    if (findmyEnabledSafe) {
                        val tsFrame = CryptoUtils.buildFindMyTimeSyncFrame(System.currentTimeMillis() / 1000, aesKey)
                        if (!session.writeCmdAwait(tsFrame)) {
                            findmyOk = false
                            failedFields += "FindMy校时"
                        }
                    }
                    if (findmyOk) {
                        val enFrame = CryptoUtils.buildFindMyEnableFrame(findmyEnabledSafe, aesKey)
                        if (session.writeParamAwait(enFrame)) {
                            repository.updateFindMyEnabled(deviceUid, findmyEnabledSafe)
                            originalFindmyEnabled = findmyEnabledSafe
                        } else {
                            failedFields += "FindMy开关"
                        }
                    }
                }
                if (gattNormalChanged) {
                    val frame = CryptoUtils.buildIntervalWriteFrame(intervalNormal, aesKey)
                    if (session.writeParamAwait(frame)) {
                        originalAdvIntervalNormal = intervalNormal
                    } else {
                        failedFields += "正常广播间隔"
                    }
                }
                if (gattFindChanged) {
                    val frame = CryptoUtils.buildFindIntervalWriteFrame(intervalFind, aesKey)
                    if (session.writeParamAwait(frame)) {
                        originalAdvIntervalFind = intervalFind
                    } else {
                        failedFields += "查找广播间隔"
                    }
                }
                if (gattPeriodChanged) {
                    val frame = CryptoUtils.buildFindMyPeriodConfigFrame(findmyPeriodIndexSafe, aesKey)
                    if (session.writeParamAwait(frame)) {
                        repository.updateFindMyPeriod(deviceUid,
                            YuiTagProtocol.FindMyPeriod.fromIndex(findmyPeriodIndexSafe).seconds)
                        originalFindmyPeriodIndex = findmyPeriodIndexSafe
                    } else {
                        failedFields += "时间窗口"
                    }
                }
                failedFields
            }

            // 连接失败
            if (writeResult.isFailure) {
                _uiState.update { it.copy(isSaving = false, saveError = "连接设备失败，请靠近后重试") }
                return@launch
            }
            val failedFields = writeResult.getOrThrow()

            // 同步本地数据库（以设备实际写入状态为准）
            if (gattNormalChanged || gattFindChanged) {
                val currentEntity = repository.getDeviceEntity(deviceUid)
                repository.updateBroadcastConfig(
                    deviceUid,
                    currentEntity?.broadcastName.orEmpty(),
                    originalAdvIntervalNormal ?: intervalNormal,
                    originalAdvIntervalFind ?: intervalFind
                )
            }

            if (failedFields.isNotEmpty()) {
                _uiState.update { it.copy(isSaving = false, saveError = "部分保存成功，以下项失败：${failedFields.joinToString("、")}") }
                return@launch
            }

            _uiState.update { it.copy(isSaving = false, saveError = null) }
            onSaved()
        }
    }

    fun dismissSaveError() {
        _uiState.update { it.copy(saveError = null) }
    }

    data class ConfigUiState(
        val advIntervalNormal: String = "",
        val advIntervalFind: String = "",
        val findmyEnabled: Boolean? = null,
        val findmySupported: Boolean? = null,  // Find My 支持状态，false=设备不支持该功能
        val findmyPeriodIndex: Int? = null,      // 轮换周期索引
        val isConnecting: Boolean = false,
        val connectError: String? = null,
        val isSaving: Boolean = false,
        val saveError: String? = null
    )
}
