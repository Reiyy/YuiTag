package com.reiyy.yuitag.viewmodel

import android.app.Application
import android.bluetooth.le.ScanResult
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.reiyy.yuitag.ble.BleScanner
import com.reiyy.yuitag.ble.FileLog
import com.reiyy.yuitag.ble.FindSession
import com.reiyy.yuitag.ble.YuiTagProtocol
import com.reiyy.yuitag.data.DeviceRepository
import com.reiyy.yuitag.data.theme.DeviceTheme
import com.reiyy.yuitag.model.RippleState
import com.reiyy.yuitag.util.vibrateShort
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 查找页状态机 */
enum class FindSearchState {
    SCANNING,       // 正在扫描广播寻找设备
    CONNECTING,     // GATT连接中
    CHECKING,       // 读取初始状态
    TRACKING,       // GATT连接RSSI追踪中
    EXITING         // 退出查找模式中
}

/** 接近/远离趋势 */
enum class FindTrend {
    STABLE,        // 稳定
    APPROACHING,   // 正在接近
    AWAY           // 正在远离
}

class FindViewModel(application: Application) : AndroidViewModel(application) {

    private val scanner = BleScanner(application)

    lateinit var repository: DeviceRepository
        private set

    fun initRepository(repo: DeviceRepository) {
        repository = repo
    }

    private val _uiState = MutableStateFlow(FindUiState())
    val uiState: StateFlow<FindUiState> = _uiState.asStateFlow()

    private var scanJob: Job? = null
    private var rssiJob: Job? = null
    private var targetUid: String? = null
    private var findSession: FindSession? = null
    private var isReusedSession: Boolean = false      // 标记是否复用了 MapMainVM 的会话
    private var findDisconnectListener: (() -> Unit)? = null

    // 三级 RSSI 滤波
    // Level 1: 中值预滤波（窗口=3，抑制单点野值）
    private val rssiMedianWindow = ArrayDeque<Int>(3)
    // Level 2: EMA 平滑（α=0.6，兼顾响应速度与平滑）
    private var filteredRssi: Float = Float.NaN
    private val rssiAlpha = 0.6f
    // Level 3: 滞回（防止距离文字在阈值边界来回闪烁）
    private var lastDistanceLevel: Int = -1  // -1=未初始化, 0=信号弱, 1=距离远, 2=距离近, 3=在附近, 4=在此处

    // 找到设备（进入"在附近/在此处"）时触发一次短震动
    private var lastVibrateLevel: Int = -1

    // 扫描 RSSI 追踪（GATT 连接后保持扫描）
    private var foundDeviceAddress: String? = null
    // 双源 RSSI 合并：广播与 GATT 谁新用谁
    private var lastScanRssiAtMs: Long = 0L     // 最近一次广播 RSSI 时间
    private var lastGattRssiAtMs: Long = 0L     // 最近一次 GATT RSSI 时间
    private var lastAppliedRssiAtMs: Long = 0L  // 已应用的最新样本时间（乱序/过期守卫）
    private val rssiLock = Any()                // updateRssi 线程安全锁（主线程扫描 + Binder GATT 回调）
    private var gattRssiFallback: Job? = null   // GATT readRemoteRssi 轮询协程

    // 趋势判断（接近/远离）
    private val trendWindow = ArrayDeque<Pair<Long, Float>>()  // (timeMs, smoothRssi)
    private var stableSinceMs = 0L                             // 稳定起始时间（0=未稳定）
    private var lastDirectionText = ""                         // 上一次方向文案（稳定<3s 时保留显示）

    fun initialize(deviceUid: String) {
        if (targetUid != null) {
            viewModelScope.launch {
                val entity = repository.getDeviceEntity(deviceUid)
                val themePath = entity?.themePath.orEmpty()
                _uiState.update { it.copy(
                    themePath = themePath,
                    theme = if (themePath.isNotEmpty()) repository.loadTheme(themePath) else null,
                    themeVersion = it.themeVersion + 1
                ) }
            }
            return
        }
        targetUid = deviceUid

        val device = repository.getDevice(deviceUid)
        _uiState.update { it.copy(
            deviceName = device?.customName?.ifEmpty { device.name } ?: "YuiTag",
            isBound = device?.isBound ?: false
        ) }
        viewModelScope.launch {
            val entity = repository.getDeviceEntity(deviceUid)
            val themePath = entity?.themePath.orEmpty()
            _uiState.update { it.copy(
                themePath = themePath,
                theme = if (themePath.isNotEmpty()) repository.loadTheme(themePath) else null
            ) }
        }

        // 已有活跃会话（如主页建立）则直接复用免扫描；否则走广播扫描发现
        if (!tryReuseExistingSession()) {
            startSearch()
        }
    }

    private fun startSearch() {
        // 重置滤波器状态
        filteredRssi = Float.NaN
        rssiMedianWindow.clear()
        lastDistanceLevel = -1
        lastVibrateLevel = -1
        foundDeviceAddress = null
        lastScanRssiAtMs = 0L
        lastGattRssiAtMs = 0L
        lastAppliedRssiAtMs = 0L
        trendWindow.clear()
        stableSinceMs = 0L
        lastDirectionText = ""

        if (!scanner.isBluetoothEnabled) {
            _uiState.update { it.copy(statusText = "蓝牙未开启", searchState = FindSearchState.SCANNING) }
            return
        }

        _uiState.update { it.copy(
            statusText = "搜索中...",
            searchState = FindSearchState.SCANNING,
            rippleState = RippleState.SEARCHING,
            trendText = ""
        ) }

        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            scanner.scanResults()
                .catch { e ->
                    _uiState.update { it.copy(statusText = "扫描出错", searchState = FindSearchState.SCANNING) }
                }
                .collect { result -> processFindResult(result) }
        }
    }

    private fun processFindResult(result: ScanResult) {
        val uid = targetUid ?: return
        val record = result.scanRecord ?: return

        val adData = YuiTagProtocol.parseManufacturerData(record)
        val foundUid = adData?.shortUid
            ?: result.device.name?.let { YuiTagProtocol.extractShortUidFromName(it) }
            ?: return

        if (!YuiTagProtocol.uidMatches(foundUid, uid)) return

        // 已经追踪到设备：提取扫描 RSSI（保持扫描）
        if (foundDeviceAddress == result.device.address) {
            lastScanRssiAtMs = System.currentTimeMillis()
            updateRssi(result.rssi, lastScanRssiAtMs)
            return
        }

        // 首次发现 → 保存设备 → 发起 GATT 连接（但不停止扫描）
        foundDeviceAddress = result.device.address
        repository.saveBluetoothDevice(uid, result.device)

        _uiState.update { it.copy(
            statusText = "连接中...",
            searchState = FindSearchState.CONNECTING
        ) }
        connectAndStartTracking()
    }

    /** 尝试复用已有活跃 GATT 会话（如主页建立）：跳过扫描直接进入追踪。
     *  与新建路径对齐补发查找模式与心跳；会话已处于断开中则不复用。 */
    private fun tryReuseExistingSession(): Boolean {
        val uid = targetUid ?: return false
        val existing = repository.getActiveFindSession(uid) ?: return false
        if (existing.isDisconnected) return false

        findSession = existing
        isReusedSession = true
        // 标记查找页持有该会话：主页后台断开需让位
        repository.bleConnectionManager.setFindPageActive(uid, true)
        registerSessionCallbacks(existing)
        FileLog.d(TAG, "复用已有 GATT 会话: uid=$uid")
        _uiState.update { it.copy(
            statusText = "已连接",
            searchState = FindSearchState.TRACKING,
            rippleState = RippleState.FOUND
        ) }
        existing.sendFindMode(true)
        // 复用会话可能已接近固件心跳超时，立即补发首包心跳再进入周期循环
        existing.startHeartbeat(viewModelScope, immediateFirst = true)
        startGattRssiPolling(existing)
        return true
    }

    private fun connectAndStartTracking() {
        val uid = targetUid ?: return
        val bleDevice = repository.getBluetoothDevice(uid) ?: return
        val device = repository.getDevice(uid)

        // Check if a GATT session already exists (established by MapMainViewModel)
        if (tryReuseExistingSession()) return

        viewModelScope.launch {
            _uiState.update { it.copy(searchState = FindSearchState.CONNECTING) }

            // 通过统一连接管理器建立连接（单连接约束）
            val result = repository.bleConnectionManager.connectForFind(
                uid = uid,
                device = bleDevice,
                aesKey = device?.aesKey
            )

            result.onSuccess { session ->
                findSession = session
                isReusedSession = false
                // 标记查找页持有该会话：主页后台断开需让位
                repository.bleConnectionManager.setFindPageActive(uid, true)
                registerSessionCallbacks(session)
                _uiState.update { it.copy(
                    statusText = "检查状态...",
                    searchState = FindSearchState.CHECKING
                ) }

                // 发送查找指令：触发 tag 广播加速
                session.sendFindMode(true)
                FileLog.i(TAG, "已发送查找模式开启指令 (0x4F,0x01)")

                // 启动心跳 (每20秒)
                session.startHeartbeat(viewModelScope)

                // 启动 GATT RSSI 轮询（双源合并）
                startGattRssiPolling(session)

                // CHECKING 阶段短暂等待后进入 TRACKING
                // （Tag 的初始响铃状态在断开时已自动关闭，此处 ringActive=false）
                _uiState.update { it.copy(
                    searchState = FindSearchState.TRACKING,
                    rippleState = RippleState.FOUND,
                    ringActive = false
                ) }
            }.onFailure { e ->
                FileLog.e("FindVM", "查找连接失败: ${e.message}")
                foundDeviceAddress = null
                _uiState.update { it.copy(
                    statusText = "连接失败，重新搜索...",
                    searchState = FindSearchState.SCANNING,
                    rippleState = RippleState.SEARCHING
                ) }
                // 重新开始搜索
                startSearch()
            }
        }
    }

    /** 注册会话回调：指令通知、GATT RSSI、断线监听 */
    private fun registerSessionCallbacks(session: FindSession) {
        // 处理指令通道通知（响铃/查找模式等命令的响应状态）
        session.onCommandNotify = { status ->
            FileLog.d(TAG, "指令通知: status=0x${status.toString(16)}")
        }

        // 设置 GATT RSSI 回调（双源合并：与广播 RSSI 优先使用最新样本）
        session.onRssiRead = { rssi ->
            lastGattRssiAtMs = System.currentTimeMillis()
            updateRssi(rssi, lastGattRssiAtMs)
        }

        // 注册断线回调
        val disconnectListener = {
            findSession = null
            rssiJob?.cancel()
            gattRssiFallback?.cancel()
            _uiState.update { it.copy(
                statusText = "设备已断开",
                searchState = FindSearchState.SCANNING,
                rippleState = RippleState.SEARCHING,
                ringActive = false,
                trendText = ""
            ) }
            // 重新开始扫描
            startSearch()
        }
        session.addDisconnectListener(disconnectListener)
        findDisconnectListener = disconnectListener
    }

    /** GATT readRemoteRssi 轮询（由 updateRssi 守卫决定最新样本） */
    private fun startGattRssiPolling(session: FindSession) {
        gattRssiFallback?.cancel()
        gattRssiFallback = viewModelScope.launch {
            while (isActive) {
                delay(GATT_RSSI_POLL_MS)
                session.readRssi()
            }
        }
    }

    /** 距离阈值辅助（供滞回使用） */
    private fun getThresholdForLevel(level: Int): Int = when (level) {
        4 -> -50   // "在此处"
        3 -> -65   // "在附近"
        2 -> -85   // "距离近"
        1 -> -95   // "距离远"
        else -> Int.MIN_VALUE
    }

    /** 三级 RSSI 滤波：中值(3) → EMA(α=0.6) → 滞回(±1dBm) */
    private fun updateRssi(rssi: Int, atMs: Long) {
        val out = synchronized(rssiLock) {
            // 双源合并守卫：只应用更新的样本（广播与 GATT 谁新用谁）
            if (atMs <= lastAppliedRssiAtMs) return@synchronized null
            lastAppliedRssiAtMs = atMs

            // Level 1: 中值预滤波（窗口=3，剔除偶发野值）
            rssiMedianWindow.addLast(rssi)
            if (rssiMedianWindow.size > 3) rssiMedianWindow.removeFirst()
            // 冷启动：窗口未满时用原始值直接 EMA，不等窗口填满
            val effectiveRssi = if (rssiMedianWindow.size >= 3) {
                rssiMedianWindow.sorted()[1]  // 3 个排序取中值
            } else {
                rssi  // 窗口未满，直接用原始值
            }

            // Level 2: EMA 平滑（自适应 α：大幅跳变时加速跟踪）
            filteredRssi = if (filteredRssi.isNaN()) {
                effectiveRssi.toFloat()
            } else {
                val diff = kotlin.math.abs(effectiveRssi - filteredRssi)
                val effectiveAlpha = if (diff > 20f) 0.8f else rssiAlpha  // 跳变>20dBm → 快速响应
                effectiveAlpha * effectiveRssi + (1f - effectiveAlpha) * filteredRssi
            }
            val smoothRssi = filteredRssi.roundToInt()

            // Level 3: 滞回判定距离文字（±1dBm）
            val rawLevel = when {
                smoothRssi > -50 -> 4  // "在此处"
                smoothRssi > -65 -> 3  // "在附近"
                smoothRssi >= -85 -> 2 // "距离近"
                smoothRssi >= -95 -> 1 // "距离远"
                else -> 0              // "信号弱"
            }
            val hysteresis = 1
            val finalLevel = when {
                lastDistanceLevel < 0 -> rawLevel  // 首次直接采用
                rawLevel > lastDistanceLevel && smoothRssi > getThresholdForLevel(rawLevel) + hysteresis -> rawLevel
                rawLevel < lastDistanceLevel && smoothRssi < getThresholdForLevel(lastDistanceLevel) - hysteresis -> rawLevel
                else -> lastDistanceLevel
            }
            lastDistanceLevel = finalLevel

            val statusText = when (finalLevel) {
                4 -> "在此处"
                3 -> "在附近"
                2 -> "距离近"
                1 -> "距离远"
                else -> "信号弱"
            }

            // 首次进入"在附近/在此处"（信号足够强）时触发短震动反馈
            val shouldVibrate = finalLevel >= 3 && lastVibrateLevel < 3
            lastVibrateLevel = finalLevel

            // 趋势判断：接近/远离；稳定 >3s 显示"无变化"，短暂稳定保留上一方向文案
            val trend = updateTrend(atMs, smoothRssi.toFloat())
            stableSinceMs = if (trend == FindTrend.STABLE) {
                if (stableSinceMs == 0L) atMs else stableSinceMs
            } else 0L
            val theme = _uiState.value.theme
            val trendText = when {
                finalLevel >= 4 -> theme?.trendHere?.ifEmpty { "在这呢！~\\(≧▽≦)/~" } ?: "在这呢！~\\(≧▽≦)/~"
                trend == FindTrend.APPROACHING -> (theme?.trendApproaching?.ifEmpty { "这个方向没错哦~罒ω罒" } ?: "这个方向没错哦~罒ω罒").also { lastDirectionText = it }
                trend == FindTrend.AWAY -> (theme?.trendAway?.ifEmpty { "好像走反了呢(ー_ー)!!" } ?: "好像走反了呢(ー_ー)!!").also { lastDirectionText = it }
                atMs - stableSinceMs > STABLE_TEXT_DELAY_MS -> theme?.trendStable?.ifEmpty { "愣着干嘛呢？(~￣△￣)~" } ?: "愣着干嘛呢？(~￣△￣)~"
                else -> lastDirectionText
            }

            RssiResult(
                smoothRssi = smoothRssi,
                statusText = statusText,
                rippleState = if (smoothRssi > -50) RippleState.HERE else RippleState.FOUND,
                trendText = trendText,
                shouldVibrate = shouldVibrate
            )
        } ?: return

        if (out.shouldVibrate) {
            try { vibrateShort(getApplication()) } catch (_: Exception) {}
        }
        _uiState.update { it.copy(
            rssi = out.smoothRssi,
            statusText = out.statusText,
            rippleState = out.rippleState,
            trendText = out.trendText
        ) }
    }

    /** 最小二乘斜率趋势判断（必须在 rssiLock 内调用） */
    private fun updateTrend(atMs: Long, rssi: Float): FindTrend {
        trendWindow.addLast(atMs to rssi)
        while (trendWindow.isNotEmpty() && atMs - trendWindow.first().first > TREND_WINDOW_MS) {
            trendWindow.removeFirst()
        }
        if (trendWindow.size < 3) return FindTrend.STABLE
        val t0 = trendWindow.first().first
        val spanMs = trendWindow.last().first - t0
        if (spanMs < TREND_MIN_SPAN_MS) return FindTrend.STABLE

        val n = trendWindow.size.toDouble()
        var sumT = 0.0
        var sumR = 0.0
        var sumTR = 0.0
        var sumT2 = 0.0
        for ((tMs, r) in trendWindow) {
            val tSec = (tMs - t0) / 1000.0   // 中心化：避免大时间戳浮点抵消
            sumT += tSec
            sumR += r.toDouble()
            sumTR += tSec * r
            sumT2 += tSec * tSec
        }
        val denom = n * sumT2 - sumT * sumT
        if (kotlin.math.abs(denom) < 1e-9) return FindTrend.STABLE
        val slope = (n * sumTR - sumT * sumR) / denom   // dBm/s
        return when {
            slope > TREND_SLOPE_DBM_PER_S -> FindTrend.APPROACHING
            slope < -TREND_SLOPE_DBM_PER_S -> FindTrend.AWAY
            else -> FindTrend.STABLE
        }
    }

    /** RSSI 滤波结果（震动与 UI 更新移到锁外执行，避免在锁内做 IPC） */
    private data class RssiResult(
        val smoothRssi: Int,
        val statusText: String,
        val rippleState: RippleState,
        val trendText: String,
        val shouldVibrate: Boolean
    )

    fun sendRing() {
        val session = findSession
        if (session != null) {
            // 切换 GATT 会话上的响铃状态
            val currentRing = _uiState.value.ringActive
            if (currentRing) {
                session.sendRing(false)
                _uiState.update { it.copy(ringActive = false) }
            } else {
                session.sendRing(true)
                _uiState.update { it.copy(ringActive = true) }
            }
        } else {
            // 回退：经由统一会话发送响铃（fire-and-forget，与已连接路径行为一致）
            val uid = targetUid ?: return
            val bleDevice = repository.getBluetoothDevice(uid) ?: return
            val device = repository.getDevice(uid)

            viewModelScope.launch {
                _uiState.update { it.copy(ringBusy = true) }
                val result = repository.bleConnectionManager.withSession(uid, bleDevice, device?.aesKey) { session ->
                    session.sendRing(true)
                    Unit
                }
                _uiState.update { it.copy(ringBusy = false, ringActive = result.isSuccess) }
            }
        }
    }

    fun exitFinder() {
        // 无条件清除查找页持有标记（会话可能已断开，须在 session 判空之外，防标记泄漏）
        val uid = targetUid
        if (::repository.isInitialized && uid != null) {
            repository.bleConnectionManager.setFindPageActive(uid, false)
        }
        val session = findSession
        if (session != null && uid != null) {
            _uiState.update { it.copy(searchState = FindSearchState.EXITING) }
            val wasRingActive = _uiState.value.ringActive
            val shouldDisconnect = !isReusedSession

            if (wasRingActive) {
                _uiState.update { it.copy(ringActive = false) }
            }
            session.stopHeartbeat()
            findDisconnectListener?.let { session.removeDisconnectListener(it) }
            findDisconnectListener = null
            findSession = null
            isReusedSession = false

            rssiJob?.cancel()
            rssiJob = null
            gattRssiFallback?.cancel()
            gattRssiFallback = null
            scanJob?.cancel()
            scanJob = null
            foundDeviceAddress = null

            // 委托 Repository 安全关闭查找模式并断开，保证 0x4F 0x00 在物理链路完成发送
            repository.safeExitFindMode(
                uid = uid,
                session = session,
                wasRingActive = wasRingActive,
                shouldDisconnect = shouldDisconnect
            )
        } else {
            rssiJob?.cancel()
            rssiJob = null
            gattRssiFallback?.cancel()
            gattRssiFallback = null
            scanJob?.cancel()
            scanJob = null
            foundDeviceAddress = null
            findSession = null
            isReusedSession = false
        }
    }

    fun cleanup() {
        scanJob?.cancel()
        rssiJob?.cancel()
        gattRssiFallback?.cancel()
        // 直接同步清理，不通过 viewModelScope.launch（onCleared 后 scope 已取消）
        exitFinder()
    }

    override fun onCleared() {
        super.onCleared()
        cleanup()
    }

    data class FindUiState(
        val deviceName: String = "YuiTag",
        val isBound: Boolean = false,
        val rssi: Int = 0,
        val txPower: Int = 0,
        val statusText: String = "搜索中...",
        val searchState: FindSearchState = FindSearchState.SCANNING,
        val rippleState: RippleState = RippleState.SEARCHING,
        val ringBusy: Boolean = false,
        val ringActive: Boolean = false,
        val trendText: String = "",
        val themePath: String = "",
        val theme: DeviceTheme? = null,
        val themeVersion: Int = 0
    )

    companion object {
        private const val TAG = "FindVM"
        private const val GATT_RSSI_POLL_MS = 500L     // GATT readRemoteRssi 轮询间隔
        private const val TREND_WINDOW_MS = 2_500L     // 趋势滑动窗口
        private const val TREND_MIN_SPAN_MS = 1_000L   // 趋势判定最小时间跨度
        private const val TREND_SLOPE_DBM_PER_S = 0.8f // 接近/远离斜率阈值 (dBm/s)
        private const val STABLE_TEXT_DELAY_MS = 3_000L // 稳定超过该时长才显示"无变化"
    }
}
