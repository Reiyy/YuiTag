package com.reiyy.yuitag.viewmodel

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.reiyy.yuitag.ble.CryptoUtils
import com.reiyy.yuitag.ble.FileLog
import com.reiyy.yuitag.ble.FindMyBroadcastScanner
import com.reiyy.yuitag.ble.YuiTagProtocol
import com.reiyy.yuitag.data.DeviceRepository
import com.reiyy.yuitag.data.YuiTagDevice
import com.reiyy.yuitag.data.local.DeviceEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class DevOptionsViewModel(application: Application) : AndroidViewModel(application) {

    private var uid: String? = null

    lateinit var repository: DeviceRepository
        private set

    fun initRepository(repo: DeviceRepository) {
        repository = repo
    }

    private val _uiState = MutableStateFlow(DevOptionsUiState())
    val uiState: StateFlow<DevOptionsUiState> = _uiState.asStateFlow()

    /** 当前值（用于对比变更） */
    private var originalBroadcastName = ""

    private val scanner = FindMyBroadcastScanner(application)
    private var scanJob: Job? = null
    private val macToPubX = mutableMapOf<String, ByteArray>()
    private val macToSlot = mutableMapOf<String, Int>()

    fun initialize(deviceUid: String) {
        if (uid != null) return
        uid = deviceUid

        viewModelScope.launch {
            val device = repository.getDevice(deviceUid)
            val entity = repository.getDeviceEntity(deviceUid)

            val name = entity?.broadcastName?.takeIf { it.isNotEmpty() }
                ?: device?.broadcastName?.takeIf { it.isNotEmpty() }
                ?: device?.name.orEmpty()
            originalBroadcastName = name

            val isSparks = checkIsSparks(entity, device)

            _uiState.update {
                it.copy(
                    broadcastName = name,
                    findmySupported = entity?.findmySupported ?: device?.findmySupported,
                    isSparks = isSparks
                )
            }
        }
    }

    fun updateBroadcastName(name: String) {
        if (name.toByteArray(Charsets.UTF_8).size > 24) return
        _uiState.update { it.copy(broadcastName = name) }
    }

    fun saveName(onSaved: () -> Unit) {
        val deviceUid = uid ?: return
        // 防止重复提交
        if (_uiState.value.isSaving) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, saveError = null) }

            val name = _uiState.value.broadcastName
            if (name.toByteArray(Charsets.UTF_8).size > 24) {
                _uiState.update { it.copy(isSaving = false, saveError = "广播名称不能超过 24 字节") }
                return@launch
            }
            if (name == originalBroadcastName) {
                _uiState.update { it.copy(isSaving = false) }
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

            // 复用统一会话写入广播名称（等待固件确认）
            val result = repository.bleConnectionManager.withSession(deviceUid, bleDevice, aesKey) { session ->
                session.writeName(name)
            }
            if (result.isFailure || result.getOrDefault(false) == false) {
                _uiState.update { it.copy(isSaving = false, saveError = "写入广播名称失败，请靠近设备后重试") }
                return@launch
            }

            // 广播名称写入成功 → 用当前间隔值同步本地 DB
            val entity = repository.getDeviceEntity(deviceUid)
            val intervalNormal = entity?.advIntervalNormal ?: device.advIntervalNormal ?: 1500
            val intervalFind = entity?.advIntervalFind ?: device.advIntervalFind ?: 500
            repository.updateBroadcastConfig(deviceUid, name, intervalNormal, intervalFind)
            originalBroadcastName = name

            _uiState.update { it.copy(isSaving = false, saveError = null) }
            onSaved()
        }
    }

    fun dismissSaveError() {
        _uiState.update { it.copy(saveError = null) }
    }

    // --- Find My 状态查询 (CMD 0x63) ---

    fun fetchFindMyStatus() {
        val deviceUid = uid ?: return
        if (_uiState.value.isFetchingFindMyStatus) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(isFetchingFindMyStatus = true, findMyStatusError = null,
                    findMyStatus = null, showFindMyStatusDialog = true)
            }

            val bleDevice = repository.getBluetoothDevice(deviceUid)
            val device = repository.getDevice(deviceUid)
            val aesKey = device?.aesKey

            if (bleDevice == null || aesKey == null) {
                _uiState.update {
                    it.copy(isFetchingFindMyStatus = false,
                        findMyStatusError = "设备不在范围内或未绑定")
                }
                return@launch
            }

            // 读取本地缓存的时间窗口时长，用于弹窗展示窗口标签（无缓存时仅显示原始周期号）
            val periodSec = repository.getDeviceEntity(deviceUid)?.findmyPeriodSec
                ?: device.findmyPeriodSec

            val result = repository.bleConnectionManager.withSession(deviceUid, bleDevice, aesKey) { session ->
                session.getFindMyStatus()
            }
            val status = result.getOrNull()

            _uiState.update {
                it.copy(
                    isFetchingFindMyStatus = false,
                    findMyStatus = status,
                    findMyPeriodSec = periodSec,
                    findMyStatusError = if (status == null)
                        "获取FindMy状态失败，请靠近设备后重试" else null
                )
            }
        }
    }

    fun dismissFindMyStatusDialog() {
        _uiState.update {
            it.copy(showFindMyStatusDialog = false, findMyStatus = null,
                findMyPeriodSec = null, findMyStatusError = null)
        }
    }

    // --- Find My 派生当前密钥（调试） ---

    /** 派生当前密钥并写入文件日志（由主页详情面板迁移而来） */
    fun deriveCurrentKey() {
        val deviceUid = uid ?: return
        viewModelScope.launch {
            val entity = repository.getDeviceEntity(deviceUid)
            val device = repository.getDevice(deviceUid)
            val isSparks = checkIsSparks(entity, device)

            if (isSparks) {
                val fixedKeys = getStoredFixedKeys(entity, device)
                if (fixedKeys.isEmpty()) {
                    Toast.makeText(getApplication(), "设备尚未同步固定密钥组，请先在配置页同步", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val sb = StringBuilder()
                val devDisplayName = entity?.customName?.ifEmpty { entity.deviceName.ifEmpty { "YuiTag" } } ?: "YuiTag"
                sb.appendLine("══════ Sparks 固定密钥组: $devDisplayName (共 ${fixedKeys.size} 个) ══════")
                for ((idx, k) in fixedKeys.withIndex()) {
                    val privHex = k.privateKey.joinToString("") { "%02x".format(it) }
                    val pubXHex = k.publicKeyX.joinToString("") { "%02x".format(it) }
                    val mac = CryptoUtils.deriveFindMyMac(k.publicKeyX)
                    val hashedAdvKey = CryptoUtils.computeHashedAdvKey(k.publicKeyX)
                    val hashedAdvKeyHex = hashedAdvKey.joinToString("") { "%02x".format(it) }
                    sb.appendLine("Slot #$idx:")
                    sb.appendLine("  MAC:                    $mac")
                    sb.appendLine("  Public key X (hex):     $pubXHex")
                    sb.appendLine("  Private key (hex):      $privHex")
                    sb.appendLine("  Hashed adv key (hex):   $hashedAdvKeyHex")
                }
                FileLog.d("FindMyDebug", sb.toString())
                Toast.makeText(getApplication(), "固定密钥组（共 ${fixedKeys.size} 个）已写入日志", Toast.LENGTH_LONG).show()
                return@launch
            }

            val seed = entity?.findmySeed
            val aesKey = entity?.aesKey
            if (seed == null || aesKey == null) {
                Toast.makeText(getApplication(), "设备密钥未配置", Toast.LENGTH_SHORT).show()
                return@launch
            }

            try {
                val periodSec = entity.findmyPeriodSec ?: 3600
                val periodLabel = when (periodSec) {
                    900 -> "15分钟"
                    1800 -> "30分钟"
                    3600 -> "1小时"
                    7200 -> "2小时"
                    else -> "${periodSec}秒"
                }
                val nowSec = System.currentTimeMillis() / 1000
                val periodIndex = nowSec / periodSec
                val periodStartSec = periodIndex * periodSec

                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                sdf.timeZone = TimeZone.getTimeZone("UTC")
                val periodTimeStr = sdf.format(Date(periodStartSec * 1000))

                val privateKey = CryptoUtils.deriveFindMyPrivateKey(seed, periodIndex)
                val pubX = CryptoUtils.deriveFindMyPublicKeyX(privateKey)
                val hashedAdvKey = CryptoUtils.computeHashedAdvKey(pubX)

                val privateKeyHex = privateKey.joinToString("") { "%02x".format(it) }
                val pubXHex = pubX.joinToString("") { "%02x".format(it) }
                val hashedAdvKeyHex = hashedAdvKey.joinToString("") { "%02x".format(it) }
                val privateKeyB64 = android.util.Base64.encodeToString(privateKey, android.util.Base64.NO_WRAP)
                val pubXB64 = android.util.Base64.encodeToString(pubX, android.util.Base64.NO_WRAP)
                val hashedAdvKeyB64 = android.util.Base64.encodeToString(hashedAdvKey, android.util.Base64.NO_WRAP)
                val aesKeyHex = aesKey.joinToString("") { "%02x".format(it) }
                val seedHex = seed.joinToString("") { "%02x".format(it) }

                val sb = StringBuilder()
                sb.appendLine("══════ 派生当前密钥: ${entity.customName.ifEmpty { "YuiTag" }} ══════")
                sb.appendLine("时间窗口：$periodLabel")
                sb.appendLine("AES Key: $aesKeyHex")
                sb.appendLine("Seed: $seedHex")
                sb.appendLine()
                sb.appendLine("Period #$periodIndex ($periodTimeStr UTC):")
                sb.appendLine("  Private key (b64):      $privateKeyB64")
                sb.appendLine("  Public key X (b64):     $pubXB64")
                sb.appendLine("  Hashed adv key (b64):   $hashedAdvKeyB64")
                sb.appendLine("  Private key (hex):      $privateKeyHex")
                sb.appendLine("  Public key X (hex):     $pubXHex")
                sb.appendLine("  Hashed adv key (hex):   $hashedAdvKeyHex")

                FileLog.d("FindMyDebug", sb.toString())

                Toast.makeText(getApplication(),
                    "密钥已写入日志\nPeriod #$periodIndex ($periodTimeStr UTC)",
                    Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                FileLog.w("DevOptionsVM", "Debug key derivation failed: ${e.message}", e)
                Toast.makeText(getApplication(), "派生失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // --- Find My 广播扫描 ---

    fun startFindMyBroadcastScan() {
        val deviceUid = uid ?: return
        if (_uiState.value.findMyBroadcast.isScanning || _uiState.value.findMyBroadcast.isDeriving) return

        viewModelScope.launch {
            _uiState.update { it.copy(findMyBroadcast = FindMyBroadcastState(isDeriving = true)) }

            try {
                val device = repository.getDevice(deviceUid)
                val entity = repository.getDeviceEntity(deviceUid)

                val aesKey = device?.aesKey ?: entity?.aesKey
                if (aesKey == null) {
                    _uiState.update {
                        it.copy(findMyBroadcast = FindMyBroadcastState(error = "设备未绑定，缺少密钥"))
                    }
                    return@launch
                }

                val isSparks = checkIsSparks(entity, device)

                val macs = withContext(Dispatchers.IO) {
                    val result = mutableSetOf<String>()
                    macToPubX.clear()
                    macToSlot.clear()

                    if (isSparks) {
                        val fixedKeys = getStoredFixedKeys(entity, device).ifEmpty {
                            val count = entity?.fixedKeyCount ?: device?.fixedKeyCount ?: YuiTagProtocol.FINDMY_DEFAULT_KEYS
                            CryptoUtils.generateFixedFindMyKeys(aesKey, count)
                        }

                        for ((idx, k) in fixedKeys.withIndex()) {
                            val mac = CryptoUtils.deriveFindMyMac(k.publicKeyX)
                            result.add(mac)
                            macToPubX[mac] = k.publicKeyX
                            macToSlot[mac] = idx
                        }
                    } else {
                        // Starlight 分支：基于当前时间与周期动态派生窗口密钥 (±5 时间窗口)
                        val seed = entity?.findmySeed ?: CryptoUtils.deriveFindMySeed(aesKey)
                        val periodSec = (entity?.findmyPeriodSec ?: device?.findmyPeriodSec) ?: 3600
                        val currentTime = System.currentTimeMillis() / 1000
                        val currentPeriodIndex = currentTime / periodSec

                        for (i in -WINDOW_RANGE..WINDOW_RANGE) {
                            val periodIndex = currentPeriodIndex + i
                            val privateKey = CryptoUtils.deriveFindMyPrivateKey(seed, periodIndex)
                            val pubX = CryptoUtils.deriveFindMyPublicKeyX(privateKey)
                            val mac = CryptoUtils.deriveFindMyMac(pubX)
                            result.add(mac)
                            macToPubX[mac] = pubX
                        }
                    }
                    result
                }

                if (macs.isEmpty()) {
                    _uiState.update {
                        it.copy(findMyBroadcast = FindMyBroadcastState(error = "无可用的 FindMy 候选 MAC 地址"))
                    }
                    return@launch
                }

                _uiState.update {
                    it.copy(findMyBroadcast = FindMyBroadcastState(
                        isScanning = true,
                        derivedMacCount = macs.size
                    ))
                }

                scanJob = launch {
                    scanner.scan(macs)
                        .catch { e ->
                            _uiState.update {
                                it.copy(findMyBroadcast = FindMyBroadcastState(
                                    error = "扫描异常: ${e.message}"
                                ))
                            }
                        }
                        .collect { result ->
                            if (!isActive) return@collect
                            val percent = signalPercent(result.rssi)
                            val pubX = macToPubX[result.macAddress.uppercase()]
                            val slot = macToSlot[result.macAddress.uppercase()]
                            _uiState.update {
                                it.copy(findMyBroadcast = it.findMyBroadcast.copy(
                                    isScanning = true,
                                    isDeriving = false,
                                    rssi = result.rssi,
                                    signalPercent = percent,
                                    batteryStatus = result.batteryStatus,
                                    lastSeenTime = result.timestamp,
                                    detectedMac = result.macAddress,
                                    pubXHex = pubX?.joinToString("") { "%02X".format(it) },
                                    matchedSlot = slot,
                                    derivedMacCount = macs.size
                                ))
                            }
                        }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(findMyBroadcast = FindMyBroadcastState(
                        error = if (_uiState.value.isSparks) "加载固定密钥失败: ${e.message}" else "密钥派生失败: ${e.message}"
                    ))
                }
            }
        }
    }

    fun stopFindMyBroadcastScan() {
        scanJob?.cancel()
        scanJob = null
        macToPubX.clear()
        macToSlot.clear()
        _uiState.update { it.copy(findMyBroadcast = FindMyBroadcastState()) }
    }

    fun dismissFindMyBroadcastError() {
        _uiState.update {
            it.copy(findMyBroadcast = it.findMyBroadcast.copy(error = null))
        }
    }

    override fun onCleared() {
        super.onCleared()
        scanJob?.cancel()
    }

    private fun checkIsSparks(entity: DeviceEntity?, device: YuiTagDevice?): Boolean {
        return YuiTagProtocol.isSparks(
            name = entity?.deviceName ?: device?.name,
            platform = entity?.firmwarePlatform ?: device?.firmwarePlatform,
            model = entity?.productModel ?: device?.productModel,
            hasFixedKeys = !entity?.fixedKeysJson.isNullOrEmpty() || !device?.fixedKeysJson.isNullOrEmpty()
        )
    }

    private fun getStoredFixedKeys(
        entity: DeviceEntity?,
        device: YuiTagDevice?
    ): List<CryptoUtils.FixedFindMyKey> {
        val json = entity?.fixedKeysJson?.takeIf { it.isNotEmpty() }
            ?: device?.fixedKeysJson?.takeIf { it.isNotEmpty() }
        return json?.let { CryptoUtils.deserializeFixedKeys(it) }.orEmpty()
    }

    companion object {
        private const val WINDOW_RANGE = 5

        /** RSSI -100..-30 映射到 0..100% */
        fun signalPercent(rssi: Int): Float {
            return ((rssi + 100) / 70.0f * 100f).coerceIn(0f, 100f)
        }
    }

    data class FindMyBroadcastState(
        val isScanning: Boolean = false,
        val isDeriving: Boolean = false,
        val rssi: Int? = null,
        val signalPercent: Float? = null,
        val batteryStatus: Int? = null,
        val lastSeenTime: Long? = null,
        val detectedMac: String? = null,
        val pubXHex: String? = null,
        val matchedSlot: Int? = null,
        val derivedMacCount: Int = 0,
        val error: String? = null
    )

    data class DevOptionsUiState(
        val broadcastName: String = "",
        val isSaving: Boolean = false,
        val saveError: String? = null,
        val findmySupported: Boolean? = null,   // Find My 支持状态，false=设备不支持该功能
        val isSparks: Boolean = false,
        val findMyStatus: YuiTagProtocol.FindMyStatus? = null,
        val findMyPeriodSec: Int? = null,
        val isFetchingFindMyStatus: Boolean = false,
        val findMyStatusError: String? = null,
        val showFindMyStatusDialog: Boolean = false,
        val findMyBroadcast: FindMyBroadcastState = FindMyBroadcastState()
    )
}
