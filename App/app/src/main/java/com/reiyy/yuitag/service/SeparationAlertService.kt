package com.reiyy.yuitag.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.reiyy.yuitag.MainActivity
import com.reiyy.yuitag.R
import com.reiyy.yuitag.ble.FileLog
import com.reiyy.yuitag.ble.YuiTagProtocol
import com.reiyy.yuitag.data.DeviceRepository
import com.reiyy.yuitag.data.NotificationRepository
import com.reiyy.yuitag.data.SeparationAlertLevel
import com.reiyy.yuitag.data.SeparationAlertPreferences
import com.reiyy.yuitag.data.YuiTagDevice
import com.reiyy.yuitag.data.local.YuiTagDatabase
import com.reiyy.yuitag.util.WifiNetworkUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 遗落提醒后台常驻服务：
 * - 常驻前台：CONNECTED_DEVICE 类型，保障后台存活
 * - 均衡扫描：日常保持 SCAN_MODE_BALANCED 节能扫描
 * - 探针复核：告警临界前临时切入 SCAN_MODE_LOW_LATENCY 短暂复核，压制误报
 * - 周期评估：专用 HandlerThread 独立评估设备信号与静默时长
 * - 息屏唤醒：高精度闹钟唤醒，对抗系统 Doze 深度休眠
 */
class SeparationAlertService : Service() {

    companion object {
        private const val TAG = "SepAlertService"

        const val CHANNEL_ID_ONGOING = "yuitag_separation_ongoing"
        const val CHANNEL_ID_ALERT = "yuitag_separation_alert"

        private const val NOTIFICATION_ID_ONGOING = 72201
        private const val NOTIFICATION_ID_ALERT_BASE = 72210

        /** 评估节拍周期 (ms) */
        private const val EVAL_INTERVAL_MS = 2500L

        /** RSSI 平滑滤波权重 */
        private const val RSSI_ALPHA = 0.25f

        /** 默认广播间隔 (ms) */
        private const val DEFAULT_ADV_INTERVAL_MS = 1500L

        /** 信号恢复稳定解警时长 (ms) */
        private const val RECOVERY_HOLD_TIME_MS = 7000L

        /** 扫描器全局静默超时时长 (ms) */
        private const val SCAN_STALL_TIMEOUT_MS = 45000L

        /** 扫描重启保护窗时长 (ms) */
        private const val SCAN_RESTART_GRACE_MS = 25000L

        /** 两次扫描重启最小间隔 (ms)，防系统节流 */
        private const val MIN_SCAN_RESTART_INTERVAL_MS = 15000L

        /** 全速探针持续时间 (ms) */
        private const val BOOST_SCAN_DURATION_MS = 6000L

        /** 全速探针触发最小间隔 (ms) */
        private const val MIN_BOOST_INTERVAL_MS = 5000L

        /** 探针提前触发缓冲时间 (ms)：在告警门限前开启全速探测 */
        private const val BOOST_PROBE_LEAD_TIME_MS = 8000L

        /** 状态巡检日志间隔 (ms) */
        private const val STATUS_LOG_INTERVAL_MS = 30 * 60 * 1000L

        private const val REQUEST_CODE_ALARM = 72202
        private const val ACTION_EVALUATE_ALARM = "com.reiyy.yuitag.action.EVALUATE_SEPARATION_ALARM"
        const val ACTION_REFRESH_WIFI = "com.reiyy.yuitag.action.REFRESH_WIFI"
        const val EXTRA_KNOWN_SSID = "com.reiyy.yuitag.extra.KNOWN_SSID"

        fun refreshWifiState(context: Context, knownSsid: String? = null) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, SeparationAlertService::class.java).apply {
                action = ACTION_REFRESH_WIFI
                if (!knownSsid.isNullOrBlank()) {
                    putExtra(EXTRA_KNOWN_SSID, knownSsid)
                }
            }
            try {
                appContext.startService(intent)
            } catch (e: Exception) {
                FileLog.w(TAG, "请求刷新 Wi-Fi 状态失败: ${e.message}")
            }
        }

        fun syncServiceState(context: Context) {
            val appContext = context.applicationContext
            val prefs = SeparationAlertPreferences(appContext)
            val db = YuiTagDatabase.getInstance(appContext)

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val monitored = db.deviceDao().getMonitoredDevices()
                    val shouldRun = prefs.isMasterEnabled() && monitored.isNotEmpty()
                    val intent = Intent(appContext, SeparationAlertService::class.java)
                    if (shouldRun) {
                        ContextCompat.startForegroundService(appContext, intent)
                    } else {
                        appContext.stopService(intent)
                    }
                } catch (e: Exception) {
                    FileLog.e(TAG, "同步服务状态失败: ${e.message}", e)
                }
            }
        }
    }

    private class TrackState(
        val uid: String,
        var displayName: String,
        var advIntervalMs: Long = DEFAULT_ADV_INTERVAL_MS,
        var lastSeenMs: Long = SystemClock.elapsedRealtime(),
        var smoothedRssi: Float = Float.NaN,
        var triggerStartTimeMs: Long? = null,
        var recoveryStartTimeMs: Long? = null,
        var isAlerted: Boolean = false
    )

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var repository: DeviceRepository
    private lateinit var preferences: SeparationAlertPreferences
    private lateinit var notificationManager: NotificationManager

    private val monitoredTags = ConcurrentHashMap<String, TrackState>()

    // BLE 扫描器状态
    private var scanCallback: ScanCallback? = null
    @Volatile
    private var isScanning = false
    private var currentScanMode = ScanSettings.SCAN_MODE_BALANCED
    private var lastScanRestartMs = 0L
    private var lastAnyScanResultMs = SystemClock.elapsedRealtime()
    private var scanRetryCount = 0

    // 全速探针状态
    private var isBoostingScan = false
    private var boostUntilMs = 0L
    private var lastBoostMs = 0L

    // 扫描重启保护期（在此单调时刻前不触发警报）
    private var scanRestartGraceUntilMs = 0L

    // 专用评估线程
    private var evalThread: HandlerThread? = null
    private var evalHandler: Handler? = null
    private val evalRunnable = object : Runnable {
        override fun run() {
            evaluateTrackedTags()
            evalHandler?.postDelayed(this, EVAL_INTERVAL_MS)
        }
    }

    // 闹钟状态
    private var lastScheduledAlarmRealtimeMs = 0L
    private var lastStatusLogMs = 0L

    // Wi-Fi 免打扰状态
    @Volatile
    private var currentWifiSsid: String? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastWifiSelfHealMs = 0L

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            when (state) {
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    FileLog.i(TAG, "系统蓝牙已关闭，停止扫描")
                    stopBleScanning()
                    monitoredTags.values.forEach { it.smoothedRssi = Float.NaN }
                }
                BluetoothAdapter.STATE_ON -> {
                    FileLog.i(TAG, "系统蓝牙已重新开启，恢复扫描")
                    val now = SystemClock.elapsedRealtime()
                    lastAnyScanResultMs = now
                    monitoredTags.values.forEach {
                        it.lastSeenMs = now
                        it.triggerStartTimeMs = null
                        it.recoveryStartTimeMs = null
                        it.smoothedRssi = Float.NaN
                    }
                    if (preferences.isMasterEnabled() && monitoredTags.isNotEmpty()) {
                        startBleScanning(ScanSettings.SCAN_MODE_BALANCED)
                    }
                }
            }
        }
    }

    private val bluetoothAdapter: BluetoothAdapter?
        get() = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    // --- 生命周期 ---

    override fun onCreate() {
        super.onCreate()
        preferences = SeparationAlertPreferences(this)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        repository = DeviceRepository.getInstance(this)

        createNotificationChannels()
        startServiceForeground(monitoredTags.size)

        // 注册蓝牙开关广播
        ContextCompat.registerReceiver(
            this,
            bluetoothStateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // 注册 Wi-Fi 连接状态监听
        registerWifiCallback()

        // 启动专用评估 HandlerThread
        evalThread = HandlerThread("YuiTag-SepEval", android.os.Process.THREAD_PRIORITY_BACKGROUND).apply {
            start()
            evalHandler = Handler(looper)
            evalHandler?.post(evalRunnable)
        }

        // 观察已启用监控的设备列表
        serviceScope.launch {
            repository.getMonitoredDevicesFlow().collect { devices ->
                updateMonitoredDevices(devices)
            }
        }

        // 观察 Wi-Fi 白名单开关与列表变更，即时触发告警重评估
        serviceScope.launch {
            preferences.safeWifiEnabledFlow().collect {
                evalHandler?.post { evaluateTrackedTags() }
            }
        }
        serviceScope.launch {
            preferences.safeWifiSetFlow().collect {
                evalHandler?.post { evaluateTrackedTags() }
            }
        }

        lastAnyScanResultMs = SystemClock.elapsedRealtime()
        FileLog.i(TAG, "遗落监测前台服务已启动")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!preferences.isMasterEnabled()) {
            cancelWakeAlarm()
            stopSelf()
            return START_NOT_STICKY
        }

        // 保持前台服务存活并同步最新的前台服务类型（如可能新获得的定位权限）
        startServiceForeground(monitoredTags.size)

        if (intent?.action == ACTION_EVALUATE_ALARM) {
            evalHandler?.post { evaluateTrackedTags() }
        } else if (intent?.action == ACTION_REFRESH_WIFI) {
            val knownSsid = intent.getStringExtra(EXTRA_KNOWN_SSID)
            evalHandler?.post {
                if (!knownSsid.isNullOrBlank()) {
                    val sanitized = WifiNetworkUtil.sanitizeSsid(knownSsid)
                    if (sanitized != null) {
                        val prevSsid = currentWifiSsid
                        currentWifiSsid = sanitized
                        if (prevSsid != sanitized) {
                            FileLog.i(TAG, "外部同步 Wi-Fi SSID: [${prevSsid ?: "无"}] -> [$sanitized]")
                        }
                    }
                } else if (currentWifiSsid == null) {
                    val freshSsid = WifiNetworkUtil.getCurrentlyConnectedWifiSsid(this)
                    if (freshSsid != null) {
                        currentWifiSsid = freshSsid
                        FileLog.i(TAG, "外部触发 Wi-Fi 补拉成功: [$freshSsid]")
                    }
                }
                evaluateTrackedTags()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(bluetoothStateReceiver)
        } catch (_: Exception) {}

        evalHandler?.removeCallbacks(evalRunnable)
        evalThread?.quitSafely()
        evalThread = null
        evalHandler = null

        cancelWakeAlarm()
        stopBleScanning()
        unregisterWifiCallback()
        serviceScope.cancel()
        FileLog.i(TAG, "遗落监测前台服务已停止")
    }

    // --- Wi-Fi 连接状态监听与安全区判定 ---

    private fun registerWifiCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                val wifiInfo = networkCapabilities.transportInfo as? WifiInfo
                val cleanSsid = WifiNetworkUtil.sanitizeSsid(wifiInfo?.ssid)
                val prevSsid = currentWifiSsid
                if (cleanSsid != null) {
                    currentWifiSsid = cleanSsid
                    if (prevSsid != cleanSsid) {
                        FileLog.i(TAG, "Wi-Fi 状态变更: [${prevSsid ?: "无"}] -> [$cleanSsid]")
                        evalHandler?.post { evaluateTrackedTags() }
                    }
                } else if (!networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    currentWifiSsid = null
                    if (prevSsid != null) {
                        FileLog.i(TAG, "当前网络非 Wi-Fi，清除 SSID: [$prevSsid]")
                        evalHandler?.post { evaluateTrackedTags() }
                    }
                }
            }

            override fun onLost(network: Network) {
                val prevSsid = currentWifiSsid
                currentWifiSsid = null
                if (prevSsid != null) {
                    FileLog.i(TAG, "Wi-Fi 已断开: [$prevSsid]")
                    evalHandler?.post { evaluateTrackedTags() }
                }
            }
        }

        try {
            cm.registerNetworkCallback(request, callback)
            networkCallback = callback
            val initialSsid = WifiNetworkUtil.getCurrentlyConnectedWifiSsid(this)
            if (initialSsid != null) {
                currentWifiSsid = initialSsid
            }
            FileLog.i(TAG, "Wi-Fi 状态监听已就绪，当前 SSID: [${currentWifiSsid ?: "等待回调解析"}]")
        } catch (e: Exception) {
            FileLog.e(TAG, "注册 Wi-Fi 监听失败: ${e.message}", e)
        }
    }

    private fun unregisterWifiCallback() {
        val cb = networkCallback ?: return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        try {
            cm?.unregisterNetworkCallback(cb)
        } catch (_: Exception) {}
        networkCallback = null
        currentWifiSsid = null
    }

    // --- BLE 扫描与探针复核 ---

    @Synchronized
    @SuppressLint("MissingPermission")
    private fun startBleScanning(mode: Int = ScanSettings.SCAN_MODE_BALANCED) {
        if (isScanning && currentScanMode == mode) return

        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            FileLog.w(TAG, "蓝牙未开启，跳过启动扫描")
            return
        }

        val scanner: BluetoothLeScanner = adapter.bluetoothLeScanner ?: return

        // 若当前正在扫描且需要切模式，先停止旧扫描
        if (isScanning) {
            val oldCb = scanCallback
            if (oldCb != null) {
                try {
                    scanner.stopScan(oldCb)
                } catch (_: Exception) {}
            }
            scanCallback = null
            isScanning = false
        }

        currentScanMode = mode
        val filters = listOf(YuiTagProtocol.createScanFilter())
        val settings = ScanSettings.Builder()
            .setScanMode(mode)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .setReportDelay(0L)
            .build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                handleScanResult(result)
            }

            override fun onScanFailed(errorCode: Int) {
                FileLog.e(TAG, "后台扫描失败: errorCode=$errorCode, mode=$currentScanMode")
                isScanning = false
                scanCallback = null
                scheduleScanRetry()
            }
        }

        try {
            scanner.startScan(filters, settings, callback)
            scanCallback = callback
            isScanning = true
            lastScanRestartMs = SystemClock.elapsedRealtime()
            val modeName = if (mode == ScanSettings.SCAN_MODE_LOW_LATENCY) "LOW_LATENCY" else "BALANCED"
            FileLog.i(TAG, "启动扫描 [$modeName]")
        } catch (e: Exception) {
            FileLog.e(TAG, "启动扫描异常: ${e.message}", e)
            scheduleScanRetry()
        }
    }

    @Synchronized
    @SuppressLint("MissingPermission")
    private fun stopBleScanning() {
        if (!isScanning) return
        val callback = scanCallback ?: return
        val adapter = bluetoothAdapter
        try {
            adapter?.bluetoothLeScanner?.stopScan(callback)
        } catch (_: Exception) {}
        scanCallback = null
        isScanning = false
        isBoostingScan = false
    }

    private fun restartBleScanning(reason: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastScanRestartMs < MIN_SCAN_RESTART_INTERVAL_MS) return
        FileLog.w(TAG, "重启扫描: $reason")
        stopBleScanning()
        scanRestartGraceUntilMs = now + SCAN_RESTART_GRACE_MS
        startBleScanning(ScanSettings.SCAN_MODE_BALANCED)
    }

    /** 触发全速探针复核扫描 */
    private fun triggerBoostScan(reason: String) {
        if (!preferences.isFinalVerificationEnabled()) return
        val now = SystemClock.elapsedRealtime()
        if (isBoostingScan && now < boostUntilMs) return
        if (now - lastBoostMs < MIN_BOOST_INTERVAL_MS) return

        FileLog.i(TAG, "启动探针复核扫描 (LOW_LATENCY, ${BOOST_SCAN_DURATION_MS / 1000}s): $reason")
        isBoostingScan = true
        boostUntilMs = now + BOOST_SCAN_DURATION_MS
        lastBoostMs = now

        startBleScanning(ScanSettings.SCAN_MODE_LOW_LATENCY)
    }

    private fun scheduleScanRetry() {
        val delays = longArrayOf(3000L, 10000L, 30000L, 60000L)
        val delayMs = delays[scanRetryCount.coerceAtMost(delays.size - 1)]
        scanRetryCount++
        FileLog.w(TAG, "扫描异常，${delayMs / 1000}s 后重试 (第 $scanRetryCount 次)")
        evalHandler?.postDelayed({
            if (preferences.isMasterEnabled() && monitoredTags.isNotEmpty() && !isScanning) {
                startBleScanning(ScanSettings.SCAN_MODE_BALANCED)
            }
        }, delayMs)
    }

    private fun handleScanResult(result: ScanResult) {
        val now = SystemClock.elapsedRealtime()
        lastAnyScanResultMs = now
        scanRetryCount = 0

        val record = result.scanRecord ?: return
        val parsed = YuiTagProtocol.parseManufacturerData(record)
        val shortUid = parsed?.shortUid?.uppercase()
            ?: result.device.name?.let { YuiTagProtocol.extractShortUidFromName(it) }?.uppercase()
            ?: return

        repository.saveBluetoothDevice(shortUid, result.device)

        val state = monitoredTags[shortUid] ?: return
        state.lastSeenMs = now

        val rssi = result.rssi
        state.smoothedRssi = if (state.smoothedRssi.isNaN()) {
            rssi.toFloat()
        } else {
            RSSI_ALPHA * rssi + (1f - RSSI_ALPHA) * state.smoothedRssi
        }

        // 仅在信号恢复至安全范围时拦截告警
        val currentLevel = SeparationAlertLevel.levelFromRssi(state.smoothedRssi)
        val configuredLevel = preferences.getTriggerLevel()
        val isSafe = currentLevel > configuredLevel.minFindLevel

        if (isSafe) {
            if (state.triggerStartTimeMs != null) {
                val remainingSec = (preferences.getDelaySeconds() * 1000L - (now - state.triggerStartTimeMs!!)) / 1000L
                if (remainingSec <= 10 || isBoostingScan) {
                    FileLog.i(TAG, "收到有效信号，拦截告警: ${state.displayName} (RSSI=$rssi, 剩余 ${maxOf(0L, remainingSec)}s)")
                }
                state.triggerStartTimeMs = null
            }
            if (isBoostingScan) {
                isBoostingScan = false
                FileLog.i(TAG, "探针复核捕获设备信号 (${state.displayName})，恢复 BALANCED 模式")
                startBleScanning(ScanSettings.SCAN_MODE_BALANCED)
            }
        }
    }

    // --- 设备列表同步 ---

    private fun updateMonitoredDevices(devices: List<YuiTagDevice>) {
        if (!preferences.isMasterEnabled() || devices.isEmpty()) {
            FileLog.i(TAG, "监控设备为空或总开关已关闭，停止服务")
            cancelWakeAlarm()
            stopSelf()
            return
        }

        val activeUids = devices.map { it.uid.uppercase() }.toSet()
        val iter = monitoredTags.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (!activeUids.contains(entry.key)) {
                notificationManager.cancel(getAlertNotificationId(entry.key))
                iter.remove()
            }
        }

        val now = SystemClock.elapsedRealtime()
        for (dev in devices) {
            val upperUid = dev.uid.uppercase()
            val displayName = dev.customName.ifEmpty { dev.name }
            val advInterval = dev.advIntervalNormal?.toLong() ?: DEFAULT_ADV_INTERVAL_MS
            val existing = monitoredTags[upperUid]
            if (existing != null) {
                existing.displayName = displayName
                existing.advIntervalMs = advInterval
            } else {
                monitoredTags[upperUid] = TrackState(
                    uid = upperUid,
                    displayName = displayName,
                    advIntervalMs = advInterval,
                    lastSeenMs = now
                )
            }
        }

        notificationManager.notify(NOTIFICATION_ID_ONGOING, buildOngoingNotification(monitoredTags.size))

        if (!isScanning) {
            startBleScanning(ScanSettings.SCAN_MODE_BALANCED)
        }
    }

    // --- 状态判定与告警评估 ---

    /** 基础静默门限：覆盖广播间隔与偶发丢包，下限 20 秒 */
    private fun computeSilenceThresholdMs(state: TrackState): Long {
        return maxOf(state.advIntervalMs * 8L + 8000L, 20_000L)
    }

    private fun evaluateTrackedTags() {
        if (!preferences.isMasterEnabled() || monitoredTags.isEmpty()) return

        val now = SystemClock.elapsedRealtime()
        val configuredLevel = preferences.getTriggerLevel()
        val userDelaySeconds = preferences.getDelaySeconds()
        val userDelayMs = userDelaySeconds * 1000L
        val finalVerificationEnabled = preferences.isFinalVerificationEnabled()

        // 全速探针到期复位：若探测时间已过或复核被关闭，退回 BALANCED 模式
        if (isBoostingScan && (now >= boostUntilMs || !finalVerificationEnabled)) {
            isBoostingScan = false
            FileLog.i(TAG, "探针复核结束，切回 BALANCED 模式")
            startBleScanning(ScanSettings.SCAN_MODE_BALANCED)
        }

        // 状态巡检日志（每30分钟一次）
        if (now - lastStatusLogMs >= STATUS_LOG_INTERVAL_MS) {
            lastStatusLogMs = now
            val summary = monitoredTags.values.joinToString {
                "${it.displayName}:静默${(now - it.lastSeenMs) / 1000}s"
            }
            val modeName = if (currentScanMode == ScanSettings.SCAN_MODE_LOW_LATENCY) "全速" else "均衡"
            FileLog.i(
                TAG,
                "状态巡检: 模式=$modeName, 扫描=$isScanning, 全局静默=${(now - lastAnyScanResultMs) / 1000}s, 设备=[$summary]"
            )
        }

        // 扫描器健康看门狗：监控设备全失联且全局持续静默超阈值时平滑自愈
        val scanSilentMs = now - lastAnyScanResultMs
        val scannerExpected = isScanning && bluetoothAdapter?.isEnabled == true
        if (scannerExpected && scanSilentMs > SCAN_STALL_TIMEOUT_MS) {
            val allSilent = monitoredTags.values.all { now - it.lastSeenMs > computeSilenceThresholdMs(it) }
            if (allSilent) {
                FileLog.w(TAG, "全局扫描静默超 ${SCAN_STALL_TIMEOUT_MS / 1000}s，重启扫描器自愈")
                restartBleScanning("看门狗超时")
            }
        }

        val inGracePeriod = now < scanRestartGraceUntilMs
        var needBoostScan = false
        var boostReason = ""

        val safeWifiEnabled = preferences.isSafeWifiEnabled()
        val safeWifiSet = if (safeWifiEnabled) preferences.getSafeWifiSet() else emptySet()

        // Wi-Fi 状态自愈与清理：断开时及时置空，连接但为空时尝试主动自愈补拉
        val isWifiActive = WifiNetworkUtil.isWifiConnected(this)
        if (!isWifiActive) {
            if (currentWifiSsid != null) {
                currentWifiSsid = null
            }
        } else if (safeWifiEnabled && safeWifiSet.isNotEmpty() && currentWifiSsid == null) {
            if (now - lastWifiSelfHealMs >= 5000L) {
                lastWifiSelfHealMs = now
                val healedSsid = WifiNetworkUtil.getCurrentlyConnectedWifiSsid(this)
                if (healedSsid != null) {
                    currentWifiSsid = healedSsid
                    FileLog.i(TAG, "Wi-Fi SSID 自愈获取成功: [$healedSsid]")
                }
            }
        }

        val isUnderSafeWifi = safeWifiEnabled && currentWifiSsid != null && safeWifiSet.contains(currentWifiSsid)

        for ((uid, state) in monitoredTags) {
            // GATT 连接豁免：已连接时必定在身边
            if (isGattConnected(uid)) {
                state.lastSeenMs = now
                state.triggerStartTimeMs = null
                state.recoveryStartTimeMs = null
                if (state.isAlerted) {
                    state.isAlerted = false
                    notificationManager.cancel(getAlertNotificationId(state.uid))
                    FileLog.i(TAG, "检测到 GATT 连接，解除警报: ${state.displayName}")
                }
                continue
            }

            // 安全 Wi-Fi 免打扰豁免：手机连接到特定 Wi-Fi 时不进行遗落监测并解除已有警报
            if (isUnderSafeWifi) {
                state.triggerStartTimeMs = null
                state.recoveryStartTimeMs = null
                if (state.isAlerted) {
                    state.isAlerted = false
                    notificationManager.cancel(getAlertNotificationId(state.uid))
                    FileLog.i(TAG, "处于安全 Wi-Fi [$currentWifiSsid]，解除警报: ${state.displayName}")
                }
                continue
            }

            val elapsedMs = now - state.lastSeenMs
            val silenceThreshold = computeSilenceThresholdMs(state)

            // 计算当前信号等级
            val currentLevel = if (elapsedMs > silenceThreshold) {
                -1
            } else if (state.smoothedRssi.isNaN()) {
                continue
            } else {
                SeparationAlertLevel.levelFromRssi(state.smoothedRssi)
            }
            val currentStatusText = SeparationAlertLevel.levelToStatusText(currentLevel)

            // 门限判断与告警倒计时
            val isTriggerCondition = currentLevel <= configuredLevel.minFindLevel
            if (isTriggerCondition) {
                state.recoveryStartTimeMs = null
                if (state.triggerStartTimeMs == null) {
                    state.triggerStartTimeMs = now
                    FileLog.d(TAG, "启动告警倒计时: ${state.displayName} (状态=$currentStatusText, 静默=${elapsedMs / 1000}s, 延时=${userDelaySeconds}s)")
                }
                val sustainedMs = now - (state.triggerStartTimeMs ?: now)
                val remainingMs = userDelayMs - sustainedMs

                // 临界告警前 8 秒触发全速探针复核
                if (finalVerificationEnabled && remainingMs in 1..BOOST_PROBE_LEAD_TIME_MS && !state.isAlerted) {
                    needBoostScan = true
                    boostReason = "${state.displayName} 距告警剩 ${remainingMs / 1000}s"
                }

                // 延时已满且不在重启保护期或探针中时触发警报
                if (sustainedMs >= userDelayMs && !state.isAlerted && !inGracePeriod && !isBoostingScan) {
                    state.isAlerted = true
                    triggerSeparationAlert(state, currentStatusText, elapsedMs)
                }
            } else {
                if (state.triggerStartTimeMs != null) {
                    FileLog.d(TAG, "信号恢复安全范围，终止倒计时: ${state.displayName} (状态=$currentStatusText)")
                    state.triggerStartTimeMs = null
                }
                handleRecovery(state, now)
            }
        }

        // 需要全速探针探测且当前未在探测中
        if (finalVerificationEnabled && needBoostScan && !isBoostingScan) {
            triggerBoostScan(boostReason)
        }

        scheduleNextDozeWakeAlarm(now, configuredLevel, userDelayMs, isUnderSafeWifi)
    }

    private fun handleRecovery(state: TrackState, now: Long) {
        if (!state.isAlerted) {
            state.recoveryStartTimeMs = null
            return
        }
        if (state.recoveryStartTimeMs == null) {
            state.recoveryStartTimeMs = now
        }
        val recoverySustainedMs = now - (state.recoveryStartTimeMs ?: now)
        if (recoverySustainedMs >= RECOVERY_HOLD_TIME_MS) {
            state.isAlerted = false
            state.recoveryStartTimeMs = null
            notificationManager.cancel(getAlertNotificationId(state.uid))
            FileLog.i(TAG, "信号恢复稳定，解除警报通知: ${state.displayName}")
            serviceScope.launch {
                try {
                    NotificationRepository.getInstance(this@SeparationAlertService)
                        .postSeparationRecovery(
                            uid = state.uid,
                            customName = state.displayName
                        )
                } catch (e: Exception) {
                    FileLog.w(TAG, "写入恢复通知异常: ${e.message}")
                }
            }
        }
    }

    // ============================================================
    // GATT 连接状态检查
    // ============================================================

    @SuppressLint("MissingPermission")
    private fun isGattConnected(uid: String): Boolean {
        if (repository.isDeviceConnected(uid)) return true

        val bm = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return false
        val adapter = bm.adapter ?: return false
        if (!adapter.isEnabled) return false

        try {
            val cachedDevice = repository.getBluetoothDevice(uid)
            if (cachedDevice != null &&
                bm.getConnectionState(cachedDevice, BluetoothProfile.GATT) == BluetoothProfile.STATE_CONNECTED
            ) {
                return true
            }

            val connectedDevices = bm.getConnectedDevices(BluetoothProfile.GATT)
            for (dev in connectedDevices) {
                val boundDevice = repository.getDevice(uid)
                val knownMac = boundDevice?.macAddress
                if (!knownMac.isNullOrEmpty() && dev.address.equals(knownMac, ignoreCase = true)) {
                    return true
                }
                val devName = dev.name
                if (devName != null) {
                    val shortUid = YuiTagProtocol.extractShortUidFromName(devName)
                    if (shortUid != null && YuiTagProtocol.uidMatches(shortUid, uid)) {
                        return true
                    }
                }
            }
        } catch (_: Exception) {
        }
        return false
    }

    // --- 闹钟调度 (Doze 模式哨兵) ---

    private fun scheduleNextDozeWakeAlarm(
        now: Long,
        configuredLevel: SeparationAlertLevel,
        userDelayMs: Long,
        isUnderSafeWifi: Boolean = false
    ) {
        if (isUnderSafeWifi) {
            cancelWakeAlarm()
            return
        }
        val tagsNeedingAlarm = monitoredTags.values.filter { !it.isAlerted || it.recoveryStartTimeMs != null }
        if (tagsNeedingAlarm.isEmpty() || !preferences.isMasterEnabled()) {
            cancelWakeAlarm()
            return
        }

        val finalVerificationEnabled = preferences.isFinalVerificationEnabled()
        var earliestTriggerRealtime = Long.MAX_VALUE
        for (state in tagsNeedingAlarm) {
            val baseDeadline: Long = when {
                state.isAlerted -> {
                    val timeInRecovery = now - (state.recoveryStartTimeMs ?: now)
                    now + (RECOVERY_HOLD_TIME_MS - timeInRecovery).coerceAtLeast(1000L)
                }
                state.triggerStartTimeMs != null -> {
                    (state.triggerStartTimeMs ?: now) + userDelayMs
                }
                else -> {
                    val silenceThreshold = computeSilenceThresholdMs(state)
                    state.lastSeenMs + silenceThreshold + userDelayMs
                }
            }

            // 启用复核时，闹钟提前在探针启动时刻（告警前 8 秒）唤醒系统
            val triggerRealtime = if (finalVerificationEnabled && !state.isAlerted) {
                (baseDeadline - BOOST_PROBE_LEAD_TIME_MS).coerceAtLeast(now + 1000L)
            } else {
                baseDeadline.coerceAtLeast(now + 1000L)
            }

            if (triggerRealtime < earliestTriggerRealtime) {
                earliestTriggerRealtime = triggerRealtime
            }
        }

        if (earliestTriggerRealtime != Long.MAX_VALUE) {
            if (abs(earliestTriggerRealtime - lastScheduledAlarmRealtimeMs) > 3000L) {
                scheduleWakeAlarm(earliestTriggerRealtime)
            }
        } else {
            cancelWakeAlarm()
        }
    }

    private fun scheduleWakeAlarm(targetRealtimeMs: Long) {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(this, SeparationAlertService::class.java).apply {
            action = ACTION_EVALUATE_ALARM
        }
        val pendingIntent = PendingIntent.getService(
            this,
            REQUEST_CODE_ALARM,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    targetRealtimeMs,
                    pendingIntent
                )
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    targetRealtimeMs,
                    pendingIntent
                )
            }
            lastScheduledAlarmRealtimeMs = targetRealtimeMs
        } catch (e: Exception) {
            FileLog.w(TAG, "设置唤醒闹钟失败: ${e.message}")
        }
    }

    private fun cancelWakeAlarm() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(this, SeparationAlertService::class.java).apply {
            action = ACTION_EVALUATE_ALARM
        }
        val pendingIntent = PendingIntent.getService(
            this,
            REQUEST_CODE_ALARM,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }
        lastScheduledAlarmRealtimeMs = 0L
    }

    // --- 告警通知发射 ---

    private fun triggerSeparationAlert(state: TrackState, statusText: String, silenceMs: Long) {
        val alertId = getAlertNotificationId(state.uid)
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("target_uid", state.uid)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            alertId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val message = "[${state.displayName}]已遗落，请及时查看"
        val notification = NotificationCompat.Builder(this, CHANNEL_ID_ALERT)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("物品遗落提醒")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()

        notificationManager.notify(alertId, notification)

        serviceScope.launch {
            try {
                NotificationRepository.getInstance(this@SeparationAlertService)
                    .postSeparationAlert(
                        uid = state.uid,
                        customName = state.displayName
                    )
            } catch (e: Exception) {
                FileLog.w(TAG, "写入遗落报警通知异常: ${e.message}")
            }
        }

        val nowRt = SystemClock.elapsedRealtime()
        val modeText = if (currentScanMode == ScanSettings.SCAN_MODE_LOW_LATENCY) "全速" else "均衡"
        val verifyText = if (preferences.isFinalVerificationEnabled()) "开启" else "关闭"
        FileLog.w(
            TAG,
            "触发遗落警报: Tag=${state.displayName} (UID=${state.uid}), 状态=$statusText, " +
                "静默=${silenceMs / 1000}s, 全局静默=${(nowRt - lastAnyScanResultMs) / 1000}s, " +
                "延时=${preferences.getDelaySeconds()}s, 模式=$modeText, 复核=$verifyText"
        )
    }

    private fun getAlertNotificationId(uid: String): Int {
        return NOTIFICATION_ID_ALERT_BASE + (uid.hashCode() and 0x7FFF)
    }

    private fun createNotificationChannels() {
        val ongoingChannel = NotificationChannel(
            CHANNEL_ID_ONGOING,
            "YuiTag遗落监测",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "保持后台扫描与遗落提醒功能正常运行"
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(ongoingChannel)

        val alertChannel = NotificationChannel(
            CHANNEL_ID_ALERT,
            "物品遗落提醒",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "当物品与手机分离时发出声音与震动警报"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 500, 250, 500)
        }
        notificationManager.createNotificationChannel(alertChannel)
    }

    private fun startServiceForeground(monitoredCount: Int) {
        val notification = buildOngoingNotification(monitoredCount)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val hasLocationPermission = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            var fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (hasLocationPermission) {
                fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }
            startForeground(
                NOTIFICATION_ID_ONGOING,
                notification,
                fgsType
            )
        } else {
            startForeground(NOTIFICATION_ID_ONGOING, notification)
        }
    }

    private fun buildOngoingNotification(monitoredCount: Int = 0): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID_ONGOING)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("YuiTag遗落监测")
            .setContentIntent(contentIntent)
            .setShowWhen(false)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
