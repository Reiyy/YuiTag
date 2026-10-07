package com.reiyy.yuitag.viewmodel

import android.app.Application
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.reiyy.yuitag.ble.BleGattClient
import com.reiyy.yuitag.ble.BleScanner
import com.reiyy.yuitag.ble.CryptoUtils
import com.reiyy.yuitag.ble.FileLog
import com.reiyy.yuitag.ble.FindSession
import com.reiyy.yuitag.ble.YuiTagProtocol
import com.reiyy.yuitag.data.DeviceRepository
import com.reiyy.yuitag.data.FindMyQueryService
import com.reiyy.yuitag.data.LocationQueryHelper
import com.reiyy.yuitag.data.NoEndpointException
import com.reiyy.yuitag.data.NotificationRepository
import com.reiyy.yuitag.data.SeparationAlertPreferences
import com.reiyy.yuitag.data.YuiTagDevice
import com.reiyy.yuitag.data.local.DeviceType
import com.reiyy.yuitag.data.local.LocationEntity
import com.reiyy.yuitag.data.local.toDomainModel
import com.reiyy.yuitag.service.SeparationAlertService
import com.reiyy.yuitag.util.LocationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

enum class BottomSheetMode { LIST, DETAIL }

enum class DeviceConnectionState {
    OFFLINE, ONLINE, CONNECTING, CONNECTED
}

/**
 * 设备健康度（一段时间内的活跃状态）：
 *  - NORMAL   ：7 天内收到过任一信号（BLE 广播或 FindMy 上报）
 *  - ABNORMAL ：>7 天无任何信号；或从未有信号且超过 5 分钟判断窗口
 *  - JUDGING  ：从未有信号且在 5 分钟判断窗口内（标签隐藏）
 */
enum class DeviceHealthState {
    NORMAL, ABNORMAL, JUDGING
}

class MapMainViewModel(application: Application) : AndroidViewModel(application) {

    private val scanner = BleScanner(application)
    private val gattClient = BleGattClient()
    private lateinit var repository: DeviceRepository
    private val findMyQueryService = FindMyQueryService(application)
    private val alertPrefs = SeparationAlertPreferences(application)
    // 延迟初始化，避免在 initRepository 之前访问未初始化的 repository
    private val locationQueryHelper by lazy { LocationQueryHelper(repository, findMyQueryService) }

    val locationProvider = LocationProvider(application)
    val locationState: StateFlow<LocationProvider.LocationState> = locationProvider.locationState

    private val _uiState = MutableStateFlow(
        MapMainUiState(isSeparationAlertMasterEnabled = alertPrefs.isMasterEnabled())
    )
    val uiState: StateFlow<MapMainUiState> = _uiState.asStateFlow()

    private val _boundDevices = MutableStateFlow<List<YuiTagDevice>>(emptyList())
    val boundDevices: StateFlow<List<YuiTagDevice>> = _boundDevices.asStateFlow()

    private val notificationRepository = NotificationRepository.getInstance(application)
    val unreadNotificationCount: StateFlow<Int> = notificationRepository.unreadCount
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0
        )

    // --- 扫描状态 ---
    private var scanJob: Job? = null
    private var scanTimeoutJob: Job? = null
    private var periodicScanJob: Job? = null
    private val foundDuringScan = mutableSetOf<String>()
    // 扫描结果节流：每个广播包只更新 BluetoothDevice 引用，设备数据按周期合并写入
    private val pendingScanned = mutableMapOf<String, YuiTagDevice>()
    private var lastScanFlushMs = 0L
    // 永久忽略的未绑定设备（SharedPreferences 持久化），通过弹窗确认后加入
    private val ignoredUnboundUids: MutableSet<String> = run {
        val prefs = getApplication<Application>().getSharedPreferences("yuitag_settings", Context.MODE_PRIVATE)
        prefs.getStringSet("ignored_unbound_uids", emptySet())?.toMutableSet() ?: mutableSetOf()
    }
    // 关闭弹窗后的短冷却（15s 内不重弹），键为 uid，值为冷却结束时间戳
    private val cooldownUntilByUid = mutableMapOf<String, Long>()
    private var boundDevicesLoaded = false
    // initRepository 仅在首次调用时启动收集器，避免反复重组导致协程累积
    private var repositoryInitialized = false

    // --- GATT 连接状态 ---
    private var connectJob: Job? = null
    private var backgroundDisconnectJob: Job? = null
    private var backToListDisconnectJob: Job? = null

    // --- 设备健康度状态 ---
    private var healthTickJob: Job? = null

    // --- Find My 查询状态 ---
    private var hasAutoQueriedOnce = false      // 主页全量查询仅冷启动时执行一次
    private var queryAllJob: Job? = null        // 全量查询重入保护

    // --- 精简模式 ---
    private var liteMode = false

    // --- 前后台跟踪 ---
    private var isInForeground = true

    companion object {
        private const val SCAN_MAX_DURATION_MS = 20_000L        // 单次扫描最长 20 秒
        private const val PERIODIC_SCAN_INTERVAL_MS = 120_000L  // 定期扫描间隔 2 分钟
        private const val PERIODIC_SCAN_DURATION_MS = 20_000L   // 定期扫描每次 20 秒
        private const val BACKGROUND_DISCONNECT_MS = 60_000L    // 后台 1 分钟后断开 GATT
        private const val IGNORE_COOLDOWN_MS = 15_000L          // 绑定弹窗关闭后 15s 内不重弹
        private const val SCAN_RESULT_FLUSH_MS = 1_000L         // 扫描结果合并写入周期（防每广告包重组）
        private const val NEW_DEVICE_GRACE_MS = 5 * 60_000L     // 新设备判断窗口 5 分钟（从未有信号时）
        private const val HEALTH_RECOMPUTE_INTERVAL_MS = 60_000L // 健康度定时重算周期 1 分钟
        private const val BACK_TO_LIST_DISCONNECT_MS = 7_000L   // 回列表后延迟断开（快速重进免重连）
        private const val AUTO_CONNECT_RECENT_MS = 5 * 60_000L  // 5 分钟内见过广播即自动建联
    }

    init {
        if (scanner.isBluetoothEnabled) {
            startInitialScan()
        } else {
            _uiState.update { it.copy(bluetoothEnabled = false) }
        }
        viewModelScope.launch {
            alertPrefs.masterEnabledFlow().collect { enabled ->
                _uiState.update { it.copy(isSeparationAlertMasterEnabled = enabled) }
            }
        }
    }

    fun initRepository(repo: DeviceRepository, lite: Boolean = false) {
        repository = repo
        liteMode = lite
        if (repositoryInitialized) return
        repositoryInitialized = true
        // 统一监听断线事件，同步设备连接状态（外部断开或他处主动断开）
        viewModelScope.launch {
            repository.bleConnectionManager.sessionClosed.collect { uid ->
                val current = _uiState.value.deviceConnectionStates[uid]
                if (current == DeviceConnectionState.CONNECTED || current == DeviceConnectionState.CONNECTING) {
                    updateConnectionState(uid, DeviceConnectionState.OFFLINE)
                }
                _uiState.update { it.copy(ringActive = false, ringTargetUid = null) }
            }
        }
        // 健康度定时重算：阈值是时间敏感的，需周期性跨线刷新（正常→异常）
        healthTickJob = viewModelScope.launch {
            while (isActive) {
                delay(HEALTH_RECOMPUTE_INTERVAL_MS)
                refreshHealthStates()
            }
        }
        viewModelScope.launch {
            // 列表数据源：Room 实体与实时扫描缓存合并。
            // 会话内用扫描缓存的最新 rssi/lastSeen；未扫描到（或扫描缓存更旧）时回退到 DB 持久化值，
            // 保证"上次在线"在重启后依然显示正确时间。
            combine(repository.boundDevicesFlow, repository.scannedDevices) { entities, scanned ->
                entities.map { entity ->
                    val domain = entity.toDomainModel()
                    val live = scanned[entity.uid]
                    if (live != null && live.lastSeen > domain.lastSeen) {
                        domain.copy(
                            rssi = live.rssi,
                            lastSeen = live.lastSeen,
                            battery = if (live.battery >= 0) live.battery else domain.battery
                        )
                    } else domain
                }
            }.distinctUntilChanged().collect { list ->
                _boundDevices.value = list
                refreshHealthStates()

                // 选中的设备被删除/解绑后自动回到列表模式，
                // 否则 bottomSheetMode 停留在 DETAIL 且 selectedDevice 为空，弹窗渲染一片空白
                val selectedUid = _uiState.value.selectedDeviceUid
                if (boundDevicesLoaded && selectedUid != null && list.none { it.uid == selectedUid }) {
                    handleSelectedDeviceRemoved(selectedUid)
                } else if (selectedUid != null) {
                    val currentDevice = list.find { it.uid == selectedUid }
                    if (currentDevice != null && isRecentlySeen(currentDevice)) {
                        connectToDevice(selectedUid)
                    }
                }

                if (!boundDevicesLoaded && list.isNotEmpty()) {
                    boundDevicesLoaded = true
                    repository.loadBoundKeysToCache()
                    initConnectionStates(list.map { it.uid }.toSet())
                    startPeriodicScan()
                    // 绑定设备加载完成前启动的初始扫描无法匹配任何设备，
                    // 载入完成后重启一次扫描，避免首屏在线状态短暂空白
                    if (scanner.isBluetoothEnabled) executeScan()
                    // 从 DB 预加载各设备最新位置，确保详情卡在 FindMy 网络查询返回前就能显示已知位置
                    if (!liteMode) loadLatestLocationsFromDb(list.map { it.uid })
                }
            }
        }
    }

    private fun loadLatestLocationsFromDb(uids: List<String>) {
        viewModelScope.launch {
            for (uid in uids) {
                val loc = repository.getLatestLocation(uid) ?: continue
                updateDeviceLatestLocation(uid, loc)
            }
        }
    }

    private fun initConnectionStates(uids: Set<String>) {
        val states = _uiState.value.deviceConnectionStates.toMutableMap()
        uids.forEach { uid ->
            if (uid !in states) states[uid] = DeviceConnectionState.OFFLINE
        }
        _uiState.update { it.copy(deviceConnectionStates = states) }
    }

    /** 选中的设备已从列表移除（删除/解绑）：回到列表模式并清理该设备的残留状态 */
    private fun handleSelectedDeviceRemoved(uid: String) {
        repository.bleConnectionManager.setHeld(uid, false)
        _uiState.update {
            it.copy(
                selectedDeviceUid = null,
                bottomSheetMode = BottomSheetMode.LIST,
                ringActive = false,
                ringTargetUid = null,
                deviceConnectionStates = it.deviceConnectionStates - uid,
                deviceBatteries = it.deviceBatteries - uid,
                deviceLatestLocations = it.deviceLatestLocations - uid,
                deviceQueryingUids = it.deviceQueryingUids - uid,
                deviceHealthStates = it.deviceHealthStates - uid
            )
        }
        // 断开残留 GATT 会话，避免已删除设备仍占用唯一连接
        repository.disconnectActiveFindSession(uid)
    }

    // --- 扫描操作 ---

    private fun startInitialScan() {
        executeScan()
    }

    private fun executeScan() {
        if (!scanner.isBleSupported || !scanner.isBluetoothEnabled) {
            FileLog.d("MapMainVM", "[Scan] 跳过：ble支持=${scanner.isBleSupported} 蓝牙开启=${scanner.isBluetoothEnabled}")
            return
        }
        FileLog.d("MapMainVM", "[Scan] 开始扫描")

        // 上一轮扫描残留的待写结果先落盘，避免丢失
        flushPendingScanned()

        scanJob?.cancel()
        scanTimeoutJob?.cancel()
        foundDuringScan.clear()
        _uiState.update { it.copy(isScanning = true, error = null) }

        val boundUids = _boundDevices.value.map { it.uid }.toSet()
        val initialStates = _uiState.value.deviceConnectionStates.toMutableMap()
        boundUids.forEach { uid ->
            if (initialStates[uid] != DeviceConnectionState.CONNECTED) {
                initialStates[uid] = DeviceConnectionState.OFFLINE
            }
        }
        _uiState.update { it.copy(deviceConnectionStates = initialStates) }

        scanTimeoutJob = viewModelScope.launch {
            delay(SCAN_MAX_DURATION_MS)
            finishScan(boundUids)
        }

        scanJob = viewModelScope.launch {
            scanner.scanResults()
                .catch { e ->
                    _uiState.update { it.copy(isScanning = false, error = e.message) }
                }
                .collect { result -> processScanResult(result) }
        }
    }

    private fun finishScan(boundUids: Set<String>) {
        FileLog.d("MapMainVM", "[Scan] 停止扫描 (found=$foundDuringScan)")
        scanJob?.cancel()
        scanJob = null
        scanTimeoutJob?.cancel()
        scanTimeoutJob = null
        // 落盘本轮最后一批扫描结果，保证列表状态完整
        flushPendingScanned()
        _uiState.update { it.copy(isScanning = false) }

        val states = _uiState.value.deviceConnectionStates.toMutableMap()
        boundUids.forEach { uid ->
            if (states[uid] != DeviceConnectionState.CONNECTED && uid !in foundDuringScan) {
                states[uid] = DeviceConnectionState.OFFLINE
            }
        }
        _uiState.update { it.copy(deviceConnectionStates = states) }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        scanTimeoutJob?.cancel()
        scanTimeoutJob = null
        flushPendingScanned()
        _uiState.update { it.copy(isScanning = false) }
    }

    /** 距上次写入达到节流周期时，合并写入待处理扫描结果 */
    private fun flushPendingScannedIfDue() {
        if (System.currentTimeMillis() - lastScanFlushMs >= SCAN_RESULT_FLUSH_MS) {
            flushPendingScanned()
        }
    }

    /** 将待写入的扫描结果批量合并进设备缓存（防每个广告包触发列表重组） */
    private fun flushPendingScanned() {
        if (pendingScanned.isEmpty()) return
        val pending = pendingScanned.toMap()
        pendingScanned.clear()
        lastScanFlushMs = System.currentTimeMillis()
        pending.values.forEach { repository.upsertScannedDevice(it) }
    }

    fun toggleScan() {
        if (_uiState.value.isScanning) {
            stopScan()
        } else {
            executeScan()
        }
    }

    /** 空状态引导用：始终发起一次新扫描（区别于 toggleScan 的开关语义） */
    fun triggerScan() {
        if (_uiState.value.isScanning) return
        executeScan()
    }

    // --- 周期扫描 ---

    private fun startPeriodicScan() {
        periodicScanJob?.cancel()
        periodicScanJob = viewModelScope.launch {
            while (isActive) {
                delay(PERIODIC_SCAN_INTERVAL_MS)
                if (!isInForeground) continue
                periodicScanOnce()
            }
        }
    }

    private suspend fun periodicScanOnce() {
        val boundUids = _boundDevices.value.map { it.uid }.toSet()
        if (boundUids.isEmpty()) return

        val found = mutableSetOf<String>()
        scanJob?.cancel()

        scanJob = viewModelScope.launch {
            // 周期扫描只需"在线/离线"状态。BALANCED(约3.3%占空比)对 2.3s 广播间隔
            // 的 Tag 在 20s 扫描窗内可靠捕获，功耗仍显著低于 LOW_LATENCY。
            scanner.scanResults(ScanSettings.SCAN_MODE_BALANCED).catch {}.collect { result ->
                processScanResultForPeriodic(result, found)
            }
        }
        delay(PERIODIC_SCAN_DURATION_MS)
        scanJob?.cancel()
        scanJob = null

        val states = _uiState.value.deviceConnectionStates.toMutableMap()
        boundUids.forEach { uid ->
            if (states[uid] != DeviceConnectionState.CONNECTED) {
                states[uid] = if (uid in found) DeviceConnectionState.ONLINE else DeviceConnectionState.OFFLINE
            }
        }
        _uiState.update { it.copy(deviceConnectionStates = states) }
    }

    private fun processScanResultForPeriodic(result: ScanResult, found: MutableSet<String>) {
        val record = result.scanRecord ?: return
        val adData = YuiTagProtocol.parseManufacturerData(record)
        val uid = adData?.shortUid ?: result.device.name?.let { YuiTagProtocol.extractShortUidFromName(it) } ?: return

        val boundUids = _boundDevices.value.map { it.uid }.toSet()
        if (uid in boundUids) {
            found.add(uid)
            val states = _uiState.value.deviceConnectionStates.toMutableMap()
            if (states[uid] != DeviceConnectionState.CONNECTED && states[uid] != DeviceConnectionState.ONLINE) {
                states[uid] = DeviceConnectionState.ONLINE
                _uiState.update { it.copy(deviceConnectionStates = states) }
            }

            // 周期扫描同样刷新"上次在线"（实时 rssi/lastSeen + 节流持久化）
            val existingBound = _boundDevices.value.find { it.uid == uid }
            val existingDevice = repository.getDevice(uid)
            val lastSeen = System.currentTimeMillis()
            repository.upsertScannedDevice(
                YuiTagDevice(
                    uid = uid,
                    name = existingBound?.name ?: result.device.name ?: "YuiTag_${uid.take(4)}",
                    rssi = result.rssi,
                    txPower = 0,
                    version = existingBound?.version ?: existingDevice?.version ?: 0,
                    battery = adData?.battery ?: existingDevice?.battery ?: -1,
                    isBound = true,
                    aesKey = existingBound?.aesKey ?: existingDevice?.aesKey,
                    customName = existingBound?.customName ?: "",
                    deviceType = existingBound?.deviceType ?: DeviceType.CUSTOM,
                    lastSeen = lastSeen
                ),
                result.device
            )
            viewModelScope.launch { repository.updateLastSeen(uid, lastSeen, result.rssi) }
            // 周期扫描同样刷新电量缓存
            val adBattery = adData?.battery
            if (adBattery != null) {
                _uiState.update { it.copy(deviceBatteries = it.deviceBatteries + (uid to adBattery)) }
                viewModelScope.launch { repository.updateBattery(uid, adBattery) }
            }
        }
    }

    // --- GATT 连接管理 ---

    /** 手动点击状态标签触发一次连接（已连接/连接中则忽略，避免重复点击） */
    fun retryConnect(uid: String) {
        val state = _uiState.value.deviceConnectionStates[uid] ?: DeviceConnectionState.OFFLINE
        if (state == DeviceConnectionState.CONNECTED || state == DeviceConnectionState.CONNECTING) return
        connectToDevice(uid)
    }

    private fun connectToDevice(uid: String, onConnected: ((FindSession) -> Unit)? = null) {
        val bleDevice = repository.getBluetoothDevice(uid) ?: return
        val device = repository.getDevice(uid) ?: return
        val aesKey = device.aesKey
        // 校时门槛：与设备页按钮同源，取自 Room 持久化实体（重启与会话内均正确），
        // 仅 Find My 已启用时连接后校时；已停用/不支持/未知状态均跳过
        val findmyEnabled = _boundDevices.value.find { it.uid == uid }?.findmyEnabled == true

        val existingSession = repository.bleConnectionManager.getSession(uid)
        if (existingSession != null) {
            updateConnectionState(uid, DeviceConnectionState.CONNECTED)
            onConnected?.invoke(existingSession)
            return
        }

        connectJob?.cancel()
        updateConnectionState(uid, DeviceConnectionState.CONNECTING)

        connectJob = viewModelScope.launch {
            val result = repository.bleConnectionManager.connectForFind(uid, bleDevice, aesKey)
            // 连接成功前若被新任务取消则不更新状态，避免残留 CONNECTING
            val session = result.getOrNull()
            if (session != null) {
                if (isActive) updateConnectionState(uid, DeviceConnectionState.CONNECTED)
                // 优先执行用户传入的就绪回调（例如响铃），杜绝被耗时校时阻塞
                if (isActive) onConnected?.invoke(session)
                if (isActive && findmyEnabled) {
                    launch {
                        val synced = session.timeSync(System.currentTimeMillis() / 1000)
                        if (synced && isActive) {
                            Toast.makeText(getApplication(), "Tag校时成功", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } else {
                // 连接失败：设备此刻不在附近，恢复为离线（此前误置 ONLINE 导致详情页标签错显"在线"）
                if (isActive) {
                    val isOnline = uid in foundDuringScan
                    updateConnectionState(uid, if (isOnline) DeviceConnectionState.ONLINE else DeviceConnectionState.OFFLINE)
                }
            }
        }
    }

    private fun disconnectFromDevice(uid: String) {
        connectJob?.cancel()
        connectJob = null
        repository.bleConnectionManager.setHeld(uid, false)

        val session = repository.bleConnectionManager.getSession(uid)
        if (session != null) {
            if (_uiState.value.ringTargetUid == uid && _uiState.value.ringActive) {
                try { session.sendRing(false) } catch (_: Exception) {}
            }
            repository.bleConnectionManager.disconnect(uid)
        }
        _uiState.update { it.copy(ringActive = false, ringTargetUid = null) }

        val states = _uiState.value.deviceConnectionStates.toMutableMap()
        val isOnline = uid in foundDuringScan
        states[uid] = if (isOnline) DeviceConnectionState.ONLINE else DeviceConnectionState.OFFLINE
        _uiState.update { it.copy(deviceConnectionStates = states) }
    }

    /** 回列表立即停止响铃，但保留 GATT 连接（供延迟断开窗口内快速重进复用） */
    private fun stopRingIfActive(uid: String) {
        val session = repository.bleConnectionManager.getSession(uid)
        if (session != null && _uiState.value.ringTargetUid == uid && _uiState.value.ringActive) {
            try { session.sendRing(false) } catch (_: Exception) {}
        }
        _uiState.update { it.copy(ringActive = false, ringTargetUid = null) }
    }

    /** 回列表后延迟 7 秒断开：期间重进同一设备保留连接，跨设备连接由 disconnectAllAndAwait 顶掉 */
    private fun scheduleDelayedDisconnect(uid: String) {
        backToListDisconnectJob?.cancel()
        backToListDisconnectJob = viewModelScope.launch {
            delay(BACK_TO_LIST_DISCONNECT_MS)
            // 期间用户已重新进入该设备详情 → 保留连接
            if (_uiState.value.selectedDeviceUid == uid) return@launch
            // 会话已被其他设备的连接顶掉 → 无需重复断开
            if (repository.bleConnectionManager.getSession(uid) == null) return@launch
            disconnectFromDevice(uid)
        }
    }

    private fun updateConnectionState(uid: String, state: DeviceConnectionState) {
        val states = _uiState.value.deviceConnectionStates.toMutableMap()
        states[uid] = state
        _uiState.update { it.copy(deviceConnectionStates = states) }
    }

    // --- 设备健康度计算 ---

    /** 依据 BLE 最近在线 + FindMy 最新上报，重算所有设备的健康度 */
    private fun refreshHealthStates() {
        val devices = _boundDevices.value
        val locations = _uiState.value.deviceLatestLocations
        val map = devices.associate { d -> d.uid to computeHealthState(d, locations[d.uid]) }
        if (map != _uiState.value.deviceHealthStates) {
            _uiState.update { it.copy(deviceHealthStates = map) }
            viewModelScope.launch {
                devices.forEach { device ->
                    val state = map[device.uid] ?: return@forEach
                    notificationRepository.checkDeviceHealthTransition(
                        uid = device.uid,
                        customName = device.customName.ifEmpty { device.name },
                        newHealthState = state.name
                    )
                }
            }
        }
    }

    private fun computeHealthState(device: YuiTagDevice, latestLocation: LocationEntity?): DeviceHealthState {
        val now = System.currentTimeMillis()
        val lastFindMyMs = latestLocation?.timestamp?.let { it * 1000L } ?: 0L
        val lastActivity = maxOf(device.lastSeen, lastFindMyMs)

        // 从未收到任何信号：进入 5 分钟判断窗口，窗口内隐藏，超时判异常
        if (lastActivity <= 0L) {
            return if (now - device.boundAt <= NEW_DEVICE_GRACE_MS) DeviceHealthState.JUDGING
                   else DeviceHealthState.ABNORMAL
        }
        val normalWindowMs = findMyQueryService.getHealthNormalDays() * 86_400_000L
        return if (now - lastActivity <= normalWindowMs) DeviceHealthState.NORMAL
               else DeviceHealthState.ABNORMAL
    }

    // --- 底部面板管理 ---

    fun selectDevice(device: YuiTagDevice) {
        val uid = device.uid
        val prevUid = _uiState.value.selectedDeviceUid
        if (prevUid != null && prevUid != uid) {
            repository.bleConnectionManager.setHeld(prevUid, false)
        }
        backToListDisconnectJob?.cancel()
        _uiState.update { it.copy(selectedDeviceUid = uid, bottomSheetMode = BottomSheetMode.DETAIL) }
        repository.bleConnectionManager.setHeld(uid, true)
        // 5 分钟内最近扫描到（"在你身边/在你附近"）即自动建联；否则不盲连
        if (isRecentlySeen(device)) connectToDevice(uid)
    }

    /** 地图标记点击 / 通知跳转：按 uid 选中设备并展开详情卡片 */
    fun selectDeviceByUid(uid: String) {
        if (boundDevicesLoaded && _boundDevices.value.isNotEmpty() && _boundDevices.value.none { it.uid == uid }) {
            Toast.makeText(getApplication(), "该设备已不在设备列表中", Toast.LENGTH_SHORT).show()
            return
        }
        val device = _boundDevices.value.find { it.uid == uid }
        val prevUid = _uiState.value.selectedDeviceUid
        if (prevUid != null && prevUid != uid) {
            repository.bleConnectionManager.setHeld(prevUid, false)
        }
        backToListDisconnectJob?.cancel()
        _uiState.update { it.copy(selectedDeviceUid = uid, bottomSheetMode = BottomSheetMode.DETAIL) }
        repository.bleConnectionManager.setHeld(uid, true)
        if (device != null && isRecentlySeen(device)) connectToDevice(uid)
    }

    /** 最近 5 分钟内见过广播（"在你身边/在你附近"）即可触发自动建联 */
    private fun isRecentlySeen(device: YuiTagDevice): Boolean =
        device.lastSeen > 0L && System.currentTimeMillis() - device.lastSeen <= AUTO_CONNECT_RECENT_MS

    fun backToList() {
        val uid = _uiState.value.selectedDeviceUid
        _uiState.update { it.copy(selectedDeviceUid = null, bottomSheetMode = BottomSheetMode.LIST) }
        if (uid != null) {
            stopRingIfActive(uid)
            scheduleDelayedDisconnect(uid)
        }
    }

    /** 拖拽排序结束后持久化自定义顺序 */
    fun updateDeviceOrder(uidOrder: List<String>) {
        if (uidOrder.isEmpty()) return
        viewModelScope.launch {
            repository.updateDeviceOrder(uidOrder)
        }
    }

    // --- 响铃控制 ---

    fun ringDevice(uid: String) {
        val session = repository.getActiveFindSession(uid)
        if (session == null) {
            // 未连接：自动建联，连上后响铃（显式用户动作，忽略健康度/在线门槛）
            connectToDevice(uid) { s ->
                val sent = s.sendRing(true)
                if (sent) {
                    _uiState.update { it.copy(ringActive = true, ringTargetUid = uid) }
                } else {
                    viewModelScope.launch {
                        val ok = s.sendRingAwait(true, 1500L)
                        if (ok) {
                            _uiState.update { it.copy(ringActive = true, ringTargetUid = uid) }
                        } else {
                            Toast.makeText(getApplication(), "发送响铃指令失败，请重试", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            return
        }

        val currentlyActive = _uiState.value.ringActive && _uiState.value.ringTargetUid == uid

        if (currentlyActive) {
            val sent = session.sendRing(false)
            _uiState.update { it.copy(ringActive = false, ringTargetUid = null) }
            if (!sent) {
                viewModelScope.launch {
                    session.sendRingAwait(false, 1500L)
                }
            }
        } else {
            val sent = session.sendRing(true)
            if (sent) {
                _uiState.update { it.copy(ringActive = true, ringTargetUid = uid) }
            } else {
                viewModelScope.launch {
                    val ok = session.sendRingAwait(true, 1500L)
                    if (ok) {
                        _uiState.update { it.copy(ringActive = true, ringTargetUid = uid) }
                    } else {
                        Toast.makeText(getApplication(), "发送响铃指令失败，请重试", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    /** 开启或停用指定设备的遗落提醒 */
    fun setSeparationAlertEnabled(uid: String, enabled: Boolean) {
        viewModelScope.launch {
            repository.updateSeparationAlertEnabled(uid, enabled)
            SeparationAlertService.syncServiceState(getApplication())
        }
    }

    // --- 设备绑定 ---

    fun bindDevice(uid: String) {
        val bleDevice = repository.getBluetoothDevice(uid) ?: return
        if (_uiState.value.bindingUid != null) return

        _uiState.update { it.copy(bindingUid = uid, bindError = null, bindProgress = "正在连接设备...") }

        viewModelScope.launch {
            val result = gattClient.bindDevice(bleDevice) { progress ->
                _uiState.update { it.copy(bindProgress = progress) }
            }
            result.onSuccess { bindResult ->
                val disInfo = bindResult.disInfo
                val device = repository.getDevice(uid)
                val name = device?.name ?: "YuiTag_${uid.take(4)}"
                val version = device?.version ?: 0

                val fixedKeysJson = if (bindResult.fixedKeys != null) {
                    CryptoUtils.serializeFixedKeys(bindResult.fixedKeys)
                } else ""
                val fixedKeyCount = bindResult.fixedKeys?.size

                repository.bindDevice(
                    uid = uid,
                    serialNumber = disInfo.serialNumber,
                    aesKey = bindResult.aesKey,
                    customName = name,
                    deviceType = DeviceType.CUSTOM,
                    protocolVersion = version,
                    firmwareVersion = disInfo.firmwareVer,
                    firmwarePlatform = disInfo.firmwarePlatform,
                    hardwareVersion = disInfo.hardwareVersion,
                    manufacturer = disInfo.manufacturer,
                    productName = disInfo.productName,
                    productModel = disInfo.productModel,
                    coreVersion = disInfo.coreVersion,
                    deviceName = disInfo.deviceName,
                    findmySupported = true,
                    findmyEnabled = if (bindResult.findMyOk) true else null,
                    fixedKeysJson = fixedKeysJson,
                    fixedKeyCount = fixedKeyCount
                )

                // 持久化 MAC 地址，供直接回连使用
                repository.saveMacAddress(uid, bleDevice.address)

                if (bindResult.isSparks) {
                    if (bindResult.findMyOk) {
                        _uiState.update { it.copy(bindingUid = null, bindSuccessUid = uid) }
                    } else {
                        _uiState.update { it.copy(
                            bindingUid = null,
                            bindFindMyFailedUid = uid,
                            bindError = "绑定成功，但 FindMy 密钥同步失败"
                        ) }
                    }
                } else {
                    // 派生并保存 Find My 种子（与 AES 密钥确定对应，与 FindMy 初始化成功与否无关）
                    val seed = CryptoUtils.deriveFindMySeed(bindResult.aesKey)
                    repository.updateFindMySeed(uid, seed)

                    if (bindResult.findMyOk) {
                        _uiState.update { it.copy(bindingUid = null, bindSuccessUid = uid) }
                    } else {
                        // 绑定成功但 FindMy 初始化失败 → 弹窗错误态，可单独重试 FindMy
                        _uiState.update { it.copy(
                            bindingUid = null,
                            bindFindMyFailedUid = uid,
                            bindError = "绑定成功，但 FindMy 初始化失败"
                        ) }
                    }
                }
            }.onFailure { e ->
                _uiState.update { it.copy(bindingUid = null, bindError = e.message ?: "绑定失败") }
            }
        }
    }

    /** 仅重试 FindMy 初始化（设备已绑定，无需重新绑定） */
    fun retryFindMy(uid: String) {
        val bleDevice = repository.getBluetoothDevice(uid) ?: return
        val device = repository.getDevice(uid)
        val aesKey = device?.aesKey ?: return
        if (_uiState.value.bindingUid != null) return

        val isSparks = YuiTagProtocol.isSparks(
            name = device.name,
            platform = device.firmwarePlatform,
            model = device.productModel,
            hasFixedKeys = device.fixedKeysJson.isNotEmpty()
        )

        _uiState.update { it.copy(bindingUid = uid, bindError = null, bindProgress = "正在配置FindMy...") }

        viewModelScope.launch {
            if (isSparks) {
                // Sparks: 生成固定密钥并同步
                val targetCount = device.fixedKeyCount ?: YuiTagProtocol.FINDMY_DEFAULT_KEYS
                val fixedKeys = CryptoUtils.generateFixedFindMyKeys(aesKey, count = targetCount)
                val result = repository.bleConnectionManager.withSession(uid, bleDevice, aesKey) { session ->
                    session.syncFixedKeys(fixedKeys)
                }
                result.onSuccess { ok ->
                    if (ok) {
                        val json = CryptoUtils.serializeFixedKeys(fixedKeys)
                        repository.updateFixedKeys(uid, json, fixedKeys.size)
                        repository.updateFindMyEnabled(uid, true)
                        _uiState.update { it.copy(bindingUid = null, bindFindMyFailedUid = null, bindSuccessUid = uid) }
                    } else {
                        _uiState.update { it.copy(bindingUid = null, bindFindMyFailedUid = uid, bindError = "FindMy 密钥同步失败，请重试") }
                    }
                }.onFailure { e ->
                    _uiState.update { it.copy(bindingUid = null, bindFindMyFailedUid = uid, bindError = e.message ?: "FindMy 密钥同步失败") }
                }
            } else {
                // Starlight: 校时并启用
                val result = repository.bleConnectionManager.withSession(uid, bleDevice, aesKey) { session ->
                    val now = System.currentTimeMillis() / 1000
                    session.timeSync(now) && session.writeFindMyEnable(true)
                }
                result.onSuccess { ok ->
                    if (ok) {
                        _uiState.update { it.copy(bindingUid = null, bindFindMyFailedUid = null, bindSuccessUid = uid) }
                    } else {
                        _uiState.update { it.copy(bindingUid = null, bindFindMyFailedUid = uid, bindError = "FindMy 初始化失败，请重试") }
                    }
                }.onFailure { e ->
                    _uiState.update { it.copy(bindingUid = null, bindFindMyFailedUid = uid, bindError = e.message ?: "FindMy 初始化失败") }
                }
            }
        }
    }

    fun dismissBindPopup() {
        // 关闭弹窗仅记录 15s 短冷却，不永久忽略
        val uid = _uiState.value.unboundDeviceUid
        if (uid != null) cooldownUntilByUid[uid] = System.currentTimeMillis() + IGNORE_COOLDOWN_MS
        _uiState.update { it.copy(showBindPopup = false, unboundDeviceUid = null,
            unboundDeviceName = null, bindSuccessUid = null, bindError = null,
            bindingUid = null, bindProgress = null, bindFindMyFailedUid = null) }
    }

    /** 用户确认"不是你的Tag"后，永久忽略该设备（持久化） */
    fun ignoreUnboundDevice(uid: String) {
        ignoredUnboundUids.add(uid)
        persistIgnoredUids()
        cooldownUntilByUid[uid] = System.currentTimeMillis() + IGNORE_COOLDOWN_MS
        _uiState.update { it.copy(showBindPopup = false, unboundDeviceUid = null,
            unboundDeviceName = null, bindSuccessUid = null, bindError = null,
            bindingUid = null, bindProgress = null, bindFindMyFailedUid = null) }
    }

    private fun persistIgnoredUids() {
        try {
            getApplication<Application>().getSharedPreferences("yuitag_settings", Context.MODE_PRIVATE)
                .edit().putStringSet("ignored_unbound_uids", ignoredUnboundUids).apply()
        } catch (_: Exception) {}
    }

    // --- Find My 位置查询 ---

    private val _locationQueryingState = MutableStateFlow(false)
    val locationQueryingState: StateFlow<Boolean> = _locationQueryingState.asStateFlow()

    fun locationHistoryFor(uid: String): Flow<List<LocationEntity>> {
        return repository.getLocationHistoryFlow(uid)
    }

    fun refreshLocation(uid: String) {
        if (_uiState.value.deviceQueryingUids.contains(uid)) return
        viewModelScope.launch {
            _locationQueryingState.value = true
            _uiState.update { it.copy(deviceQueryingUids = it.deviceQueryingUids + uid) }
            try {
                val results = locationQueryHelper.queryAndStore(uid)
                if (results.isNotEmpty()) {
                    val maxLoc = results.maxByOrNull { it.timestamp }
                    if (maxLoc != null) {
                        updateDeviceLatestLocation(uid, maxLoc)
                    }
                }
            } catch (e: Exception) {
                FileLog.w("MapMainVM", "FindMy query failed: ${e.message}")
            } finally {
                _locationQueryingState.value = false
                _uiState.update { it.copy(deviceQueryingUids = it.deviceQueryingUids - uid) }
            }
        }
    }

    private fun queryAllDeviceLocations() {
        val url = findMyQueryService.getEndpointUrl()
        if (url.isBlank()) return
        // 重入保护，进行中直接跳过
        if (queryAllJob?.isActive == true) return

        queryAllJob = viewModelScope.launch {
            try {
                val entities = repository.getAllBoundEntities().filter {
                    it.findmySeed != null || it.aesKey != null
                }
                if (entities.isEmpty()) return@launch

                _locationQueryingState.value = true

                for (entity in entities) {
                    try {
                        val results = locationQueryHelper.queryAndStore(entity.uid)
                        if (results.isNotEmpty()) {
                            val maxLoc = results.maxByOrNull { it.timestamp }
                            if (maxLoc != null) {
                                updateDeviceLatestLocation(entity.uid, maxLoc)
                            }
                        }
                    } catch (e: NoEndpointException) {
                        FileLog.w("MapMainVM", "Auto query: ${e.message}")
                        break
                    } catch (e: IOException) {
                        FileLog.w("MapMainVM", "Auto query ${entity.uid}: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                FileLog.w("MapMainVM", "Auto query failed: ${e.message}")
            } finally {
                _locationQueryingState.value = false
            }
        }
    }

    fun triggerFindMyQuery(uid: String) {
        if (_uiState.value.deviceQueryingUids.contains(uid)) return
        viewModelScope.launch {
            if (!locationQueryHelper.hasQueryableKey(uid)) {
                Toast.makeText(getApplication(), "设备未配置 FindMy 密钥", Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (findMyQueryService.getEndpointUrl().isBlank()) {
                Toast.makeText(getApplication(), "请先在设置中配置后端地址", Toast.LENGTH_SHORT).show()
                return@launch
            }

            _uiState.update { it.copy(deviceQueryingUids = it.deviceQueryingUids + uid) }

            try {
                val results = locationQueryHelper.queryAndStore(uid)

                if (results.isNotEmpty()) {
                    val maxLoc = results.maxByOrNull { it.timestamp }
                    if (maxLoc != null) {
                        updateDeviceLatestLocation(uid, maxLoc)
                    }
                    Toast.makeText(getApplication(), "找到 ${results.size} 条位置记录", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(getApplication(), "未找到位置信息", Toast.LENGTH_SHORT).show()
                }
            } catch (e: NoEndpointException) {
                Toast.makeText(getApplication(), e.message ?: "未配置后端地址", Toast.LENGTH_SHORT).show()
            } catch (e: IOException) {
                Toast.makeText(getApplication(), "网络请求失败: ${e.message}", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                FileLog.w("MapMainVM", "Manual query failed: ${e.message}", e)
                Toast.makeText(getApplication(), "查询失败: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                _uiState.update { it.copy(deviceQueryingUids = it.deviceQueryingUids - uid) }
            }
        }
    }

    private fun updateDeviceLatestLocation(uid: String, location: LocationEntity) {
        val current = _uiState.value.deviceLatestLocations[uid]
        if (current != null && current.timestamp >= location.timestamp) {
            return
        }
        _uiState.update {
            it.copy(deviceLatestLocations = it.deviceLatestLocations + (uid to location))
        }
        refreshHealthStates()
    }

    // --- 生命周期响应 ---

    fun onPause() {
        isInForeground = false
        stopScan()
        if (!liteMode) locationProvider.stopLocationUpdates()
        scheduleBackgroundDisconnect()
    }

    fun onResume() {
        isInForeground = true
        backgroundDisconnectJob?.cancel()
        backgroundDisconnectJob = null

        val masterEnabled = alertPrefs.isMasterEnabled()
        if (_uiState.value.isSeparationAlertMasterEnabled != masterEnabled) {
            _uiState.update { it.copy(isSeparationAlertMasterEnabled = masterEnabled) }
        }

        refreshHealthStates()

        if (!_uiState.value.isScanning && scanner.isBluetoothEnabled) {
            executeScan()
        }
        if (!liteMode) locationProvider.requestLocation()

        // 主页全量查询仅在冷启动（首次 onResume）执行一次；
        // 此后从其他页面/后台返回不再自动全量查询，进入设备页时单独查询该设备
        if (!liteMode && !hasAutoQueriedOnce) {
            hasAutoQueriedOnce = true
            queryAllDeviceLocations()
        }
    }

    private fun scheduleBackgroundDisconnect() {
        backgroundDisconnectJob?.cancel()
        backgroundDisconnectJob = viewModelScope.launch {
            delay(BACKGROUND_DISCONNECT_MS)
            val uid = _uiState.value.selectedDeviceUid
            if (uid != null) {
                val session = repository.bleConnectionManager.getSession(uid)
                if (session != null) {
                    // 查找页正在使用该会话：跳过后台断开，避免杀死查找页连接
                    if (repository.bleConnectionManager.isFindPageActive(uid)) return@launch
                    if (_uiState.value.ringTargetUid == uid && _uiState.value.ringActive) {
                        try { session.sendRing(false) } catch (_: Exception) {}
                    }
                    repository.bleConnectionManager.disconnect(uid)
                    updateConnectionState(uid, DeviceConnectionState.ONLINE)
                    _uiState.update { it.copy(ringActive = false, ringTargetUid = null) }
                }
            }
        }
    }

    fun onBluetoothEnabled() {
        _uiState.update { it.copy(bluetoothEnabled = true, error = null) }
        executeScan()
    }

    override fun onCleared() {
        super.onCleared()
        scanJob?.cancel()
        periodicScanJob?.cancel()
        connectJob?.cancel()
        healthTickJob?.cancel()
        backToListDisconnectJob?.cancel()
        val uid = _uiState.value.selectedDeviceUid
        if (uid != null && ::repository.isInitialized) {
            repository.bleConnectionManager.setHeld(uid, false)
        }
        if (::repository.isInitialized) repository.bleConnectionManager.disconnectAll()
        if (!liteMode) locationProvider.stopLocationUpdates()
        if (::repository.isInitialized) repository.clearScannedDevices()
    }

    // --- 扫描结果处理 ---

    private fun processScanResult(result: ScanResult) {
        val device = result.device
        val record = result.scanRecord ?: return
        val rssi = result.rssi

        val adData = YuiTagProtocol.parseManufacturerData(record)

        val uid: String
        val version: Int
        val isBound: Boolean
        val battery: Int

        if (adData != null) {
            uid = adData.shortUid
            version = adData.version
            isBound = adData.isBound
            battery = adData.battery ?: (repository.getDevice(uid)?.battery ?: -1)
        } else {
            val name = device.name ?: return
            val nameUid = YuiTagProtocol.extractShortUidFromName(name) ?: return
            uid = nameUid
            version = 0
            isBound = false
            battery = -1
        }

        // 诊断日志：扫描到的 YuiTag 设备
        FileLog.d("MapMainVM", "[Scan] name=${device.name} addr=${device.address} rssi=$rssi adOk=${adData != null} uid=$uid bound=$isBound")

        val displayName = device.name?.takeIf { it.isNotBlank() } ?: "YuiTag_${uid.take(4)}"
        val existingBound = _boundDevices.value.find { it.uid == uid }
        val isAlreadyBound = existingBound != null
        val existingDevice = repository.getDevice(uid)

        val dev = YuiTagDevice(
            uid = uid, name = displayName, rssi = rssi, txPower = 0,
            version = version, battery = battery,
            isBound = isAlreadyBound || isBound,
            aesKey = existingBound?.aesKey ?: existingDevice?.aesKey,
            customName = existingBound?.customName ?: "",
            deviceType = existingBound?.deviceType ?: DeviceType.CUSTOM,
            firmwareVersion = existingBound?.firmwareVersion ?: "",
            firmwarePlatform = existingBound?.firmwarePlatform ?: "",
            hardwareVersion = existingBound?.hardwareVersion ?: "",
            manufacturer = existingBound?.manufacturer ?: "",
            productName = existingBound?.productName ?: "",
            productModel = existingBound?.productModel ?: "",
            lastSeen = System.currentTimeMillis()
        )

        // 扫描结果节流：BluetoothDevice 引用立即缓存（连接可用），
        // 设备数据按 SCAN_RESULT_FLUSH_MS 合并写入，避免每个广告包触发列表重组
        repository.saveBluetoothDevice(uid, device)
        pendingScanned[uid] = dev
        flushPendingScannedIfDue()

        // 持久化已绑定设备的 MAC（仅地址变化时写库）
        if (isAlreadyBound || isBound) {
            val cachedMac = existingBound?.macAddress.orEmpty()
            if (cachedMac != device.address) {
                viewModelScope.launch { repository.saveMacAddress(uid, device.address) }
            }
            // 节流持久化最近在线时间/广播 RSSI
            viewModelScope.launch { repository.updateLastSeen(uid, dev.lastSeen, rssi) }
        }

        // 电量直接从广播包读取（0x180F 电池服务，含 0% 合法值）
        val adBattery = adData?.battery
        if (adBattery != null) {
            _uiState.update { it.copy(deviceBatteries = it.deviceBatteries + (uid to adBattery)) }
            // 已绑定设备持久化电量，离线/重启后用于展示缓存电量
            if (isAlreadyBound || isBound) {
                viewModelScope.launch { repository.updateBattery(uid, adBattery) }
            }
        }

        // Track found devices
        val boundUids = _boundDevices.value.map { it.uid }.toSet()
        if (uid in boundUids) {
            foundDuringScan.add(uid)
            val states = _uiState.value.deviceConnectionStates.toMutableMap()
            if (states[uid] != DeviceConnectionState.CONNECTED && states[uid] != DeviceConnectionState.CONNECTING) {
                states[uid] = DeviceConnectionState.ONLINE
                _uiState.update { it.copy(deviceConnectionStates = states) }
            }

            // When all bound devices found, stop immediately
            val allFound = boundUids.all { it in foundDuringScan }
            if (allFound && _uiState.value.isScanning) {
                viewModelScope.launch { finishScan(boundUids) }
            }
        }

        // 诊断日志：弹窗判定
        val alreadyShown = _uiState.value.showBindPopup
        val ignored = uid in ignoredUnboundUids
        val cooldownRemaining = (cooldownUntilByUid[uid] ?: 0L) - System.currentTimeMillis()
        FileLog.d("MapMainVM",
            "[Bind] uid=$uid bound=$isBound alreadyBound=$isAlreadyBound showing=$alreadyShown ignored=$ignored cooldown=${cooldownRemaining}ms")

        if (!isAlreadyBound && !isBound && !alreadyShown && !ignored && cooldownRemaining < 0L) {
            FileLog.d("MapMainVM", "[Bind] uid=$uid → SHOW popup")
            _uiState.update {
                it.copy(showBindPopup = true, unboundDeviceUid = uid, unboundDeviceName = displayName)
            }
        }
    }

    // --- 界面状态定义 ---

    data class MapMainUiState(
        val isScanning: Boolean = false,
        val bluetoothEnabled: Boolean = true,
        val error: String? = null,
        val bottomSheetMode: BottomSheetMode = BottomSheetMode.LIST,
        val selectedDeviceUid: String? = null,
        val isSeparationAlertMasterEnabled: Boolean = true,
        // Bind popup
        val showBindPopup: Boolean = false,
        val unboundDeviceUid: String? = null,
        val unboundDeviceName: String? = null,
        val bindingUid: String? = null,
        val bindProgress: String? = null,
        val bindSuccessUid: String? = null,
        val bindError: String? = null,
        // 绑定成功但 FindMy 初始化失败 → 弹窗提供单独重试
        val bindFindMyFailedUid: String? = null,
        // Ring (toggle)
        val ringActive: Boolean = false,
        val ringTargetUid: String? = null,
        // Connection states per device
        val deviceConnectionStates: Map<String, DeviceConnectionState> = emptyMap(),
        // Device health states per device (正常/异常/判断中)
        val deviceHealthStates: Map<String, DeviceHealthState> = emptyMap(),
        // Device batteries cache
        val deviceBatteries: Map<String, Int> = emptyMap(),
        // FindMy latest locations per device (for map markers)
        val deviceLatestLocations: Map<String, com.reiyy.yuitag.data.local.LocationEntity> = emptyMap(),
        // Device UIDs currently querying FindMy
        val deviceQueryingUids: Set<String> = emptySet()
    )
}
