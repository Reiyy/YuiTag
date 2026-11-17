package com.reiyy.yuitag.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * YuiTag 单设备 GATT 长连接会话。
 *
 * 职责：
 *  1. 持有 gatt / cmdChar / paramChar / aesKey（连接建立后由 BleConnectionManager 统一分配）；
 *  2. 内置串行操作队列（opMutex）：同一时刻仅一个 GATT 写/读未决，杜绝命令应答串扰；
 *  3. 读支持：DIS 设备信息、PARAM 参数配置均在既有连接上完成，不再每次单独建连；
 *  4. 命令支持：解绑/广播名/广播间隔/FindMy 系列，全部复用缓存特征值；
 *  5. 断开确认：awaitDisconnected 供连接管理器等待 BLE 协议栈真实断开后再建新连接。
 */
class FindSession(
    val gatt: BluetoothGatt,
    private val cmdChar: BluetoothGattCharacteristic?,
    private val paramChar: BluetoothGattCharacteristic?,
    private val aesKey: ByteArray?,
    val negotiatedMtu: Int = 23
) {
    companion object {
        private const val TAG = "FindSession"
        private const val HEARTBEAT_CHECK_INTERVAL_MS = 15_000L
        private const val HEARTBEAT_IDLE_THRESHOLD_MS = 45_000L
    }

    private val opMutex = Mutex()
    private val seqCounter = AtomicInteger(1)

    /** 生成 1..255 循环请求序号（用于匹配 V2 通知回显） */
    fun nextSeq(): Byte {
        while (true) {
            val s = seqCounter.getAndIncrement() and 0xFF
            if (s != 0) return s.toByte()
        }
    }

    private var heartbeatJob: Job? = null
    private var heartbeatScope: CoroutineScope? = null
    private var disconnectRequested = false
    private var physicallyDisconnected = false

    // 最近一次向设备发送任何指令/参数帧的时间戳，用于空闲保活判定
    @Volatile
    private var lastTxTimeMs: Long = System.currentTimeMillis()

    /** 会话是否已请求断开或已物理断开（供会话复用方判定可用性） */
    val isDisconnected: Boolean get() = disconnectRequested || physicallyDisconnected

    var onRssiRead: ((Int) -> Unit)? = null
    var onCommandNotify: ((Int) -> Unit)? = null

    // 断线监听（可注册多个，由连接管理方在断线时统一通知）
    private val disconnectListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    // await 写入的挂起等待器（同一通道同一时刻仅允许一个挂起）
    private var pendingCmdAwait: CompletableDeferred<Boolean>? = null
    private var pendingCmdWriteAwait: CompletableDeferred<Int>? = null
    @Volatile
    private var expectedCmdSeq: Int? = null

    private var pendingParamAwait: CompletableDeferred<Boolean>? = null
    private var pendingParamWriteAwait: CompletableDeferred<Int>? = null
    @Volatile
    private var expectedParamSeq: Int? = null

    // PARAM 通道的数据型响应（如 FindMy 状态查询：[0x63][...]），按首字节匹配
    private var pendingParamDataAwait: Pair<Byte, CompletableDeferred<ByteArray>>? = null
    // 读特征值等待器（串行队列保证同时仅一个读）
    private var readAwait: CompletableDeferred<ByteArray?>? = null
    // 物理断开回调等待器（供 awaitDisconnected 使用）
    private var disconnectDeferred: CompletableDeferred<Unit>? = null

    fun addDisconnectListener(listener: () -> Unit) {
        disconnectListeners.add(listener)
    }

    fun removeDisconnectListener(listener: () -> Unit) {
        disconnectListeners.remove(listener)
    }

    /** BLE 协议栈报告断开（外部断开或主动断开后的回调）时调用 */
    fun notifyDisconnected() {
        physicallyDisconnected = true
        disconnectListeners.forEach { it() }
        disconnectListeners.clear()
        completePendingAwaits()
        disconnectDeferred?.let { if (it.isActive) it.complete(Unit) }
        disconnectDeferred = null
    }

    private fun completePendingAwaits() {
        pendingCmdAwait?.let { if (it.isActive) it.complete(false) }
        pendingCmdAwait = null
        pendingCmdWriteAwait?.let { if (it.isActive) it.complete(BluetoothGatt.GATT_FAILURE) }
        pendingCmdWriteAwait = null
        expectedCmdSeq = null
        pendingParamAwait?.let { if (it.isActive) it.complete(false) }
        pendingParamAwait = null
        pendingParamWriteAwait?.let { if (it.isActive) it.complete(BluetoothGatt.GATT_FAILURE) }
        pendingParamWriteAwait = null
        expectedParamSeq = null
        pendingParamDataAwait?.second?.let { if (it.isActive) it.complete(ByteArray(0)) }
        pendingParamDataAwait = null
        readAwait?.let { if (it.isActive) it.complete(null) }
        readAwait = null
    }

    fun startHeartbeat(scope: CoroutineScope, immediateFirst: Boolean = false) {
        heartbeatScope = scope
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            // 复用会话场景：会话可能已接近固件心跳超时，首包立即发出再进入周期循环
            if (immediateFirst) sendHeartbeat()
            while (isActive) {
                delay(HEARTBEAT_CHECK_INTERVAL_MS)
                // 仅当距最近一次业务写操作超过 45s 时才补发保活包，减少空口唤醒与功耗
                if (System.currentTimeMillis() - lastTxTimeMs >= HEARTBEAT_IDLE_THRESHOLD_MS) {
                    sendHeartbeat()
                }
            }
        }
    }

    fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private suspend fun sendHeartbeat() {
        val key = aesKey ?: return
        if (disconnectRequested || physicallyDisconnected) return
        // 当前正有 GATT 读写占用通道时跳过本次心跳（活跃写操作同样刷新固件心跳与 lastTxTimeMs）
        if (!opMutex.tryLock()) return
        try {
            writeCmdNoReplyAwaitLocked(CryptoUtils.buildHeartbeatFrame(key, nextSeq()))
        } finally {
            opMutex.unlock()
        }
    }

    @SuppressLint("MissingPermission")
    fun readRssi() {
        try { gatt.readRemoteRssi() } catch (_: Exception) {}
    }

    /** 挂起发送响铃指令并等待固件通知确认 */
    suspend fun sendRingAwait(enable: Boolean, timeoutMs: Long = 1200L): Boolean {
        val key = aesKey ?: return false
        if (disconnectRequested || physicallyDisconnected) return false
        val frame = CryptoUtils.buildCommandFrame(
            YuiTagProtocol.CMD_ID_RING,
            if (enable) YuiTagProtocol.CMD_ACTION_ON else YuiTagProtocol.CMD_ACTION_OFF,
            key,
            nextSeq()
        )
        return writeCmdAwait(frame, timeoutMs)
    }

    /** 挂起发送查找模式开关指令并等待固件通知确认 */
    suspend fun sendFindModeAwait(enable: Boolean, timeoutMs: Long = 1200L): Boolean {
        val key = aesKey ?: return false
        if (disconnectRequested || physicallyDisconnected) return false
        val frame = CryptoUtils.buildCommandFrame(
            YuiTagProtocol.CMD_ID_FIND_MODE,
            if (enable) YuiTagProtocol.CMD_ACTION_ON else YuiTagProtocol.CMD_ACTION_OFF,
            key,
            nextSeq()
        )
        return writeCmdAwait(frame, timeoutMs)
    }

    /** CMD 通知：校验 2B [Seq][Status] 并完成挂起请求 */
    fun handleCommandNotify(value: ByteArray) {
        val status = YuiTagProtocol.extractStatus(value)
        val respSeq = YuiTagProtocol.extractSeq(value)
        val expSeq = expectedCmdSeq
        if (expSeq != null) {
            if (value.size != 2 || respSeq != expSeq) {
                FileLog.w(TAG, "忽略不匹配 CMD 通知: 期望Seq=$expSeq actualLen=${value.size} 实际Seq=$respSeq status=0x${status.toString(16)}")
                return
            }
            onCommandNotify?.invoke(status)
            pendingCmdAwait?.let { d ->
                pendingCmdAwait = null
                expectedCmdSeq = null
                if (d.isActive) d.complete(status == YuiTagProtocol.STATUS_SUCCESS)
            }
            return
        }
        if (value.size == 2) {
            onCommandNotify?.invoke(status)
        }
    }

    /** PARAM 通知：优先匹配数据帧（如 FindMy 状态），其次校验 2B [Seq][Status] 完成挂起写入 */
    fun handleParamNotify(value: ByteArray) {
        val dataAwait = pendingParamDataAwait
        if (dataAwait != null && value.isNotEmpty() && value[0] == dataAwait.first) {
            pendingParamDataAwait = null
            if (dataAwait.second.isActive) dataAwait.second.complete(value)
            return
        }
        val status = YuiTagProtocol.extractStatus(value)
        val respSeq = YuiTagProtocol.extractSeq(value)
        val expSeq = expectedParamSeq
        if (expSeq != null) {
            if (value.size != 2 || respSeq != expSeq) {
                FileLog.w(TAG, "忽略不匹配 PARAM 通知: 期望Seq=$expSeq actualLen=${value.size} 实际Seq=$respSeq status=0x${status.toString(16)}")
                return
            }
            pendingParamAwait?.let { d ->
                pendingParamAwait = null
                expectedParamSeq = null
                if (d.isActive) d.complete(status == YuiTagProtocol.STATUS_SUCCESS)
            }
        }
    }

    /** CMD / PARAM 特征值写入回调路由（由 gatt 回调转发，用于确认底层 ATT 事务完成以释放 mDeviceBusy） */
    fun handleCharacteristicWrite(ch: BluetoothGattCharacteristic, status: Int) {
        when (ch.uuid) {
            cmdChar?.uuid -> {
                pendingCmdWriteAwait?.let { d ->
                    pendingCmdWriteAwait = null
                    if (d.isActive) d.complete(status)
                }
            }
            paramChar?.uuid -> {
                pendingParamWriteAwait?.let { d ->
                    pendingParamWriteAwait = null
                    if (d.isActive) d.complete(status)
                }
            }
        }
    }

    /** 读特征值响应路由（由 gatt 回调转发） */
    fun handleRead(value: ByteArray?, status: Int) {
        val d = readAwait
        readAwait = null
        if (d != null && d.isActive) {
            d.complete(if (status == BluetoothGatt.GATT_SUCCESS) value else null)
        }
    }

    /** 无应答 CMD 写入（仅 opMutex 持锁期间调用，用于空闲保活） */
    @SuppressLint("MissingPermission")
    private suspend fun writeCmdNoReplyAwaitLocked(data: ByteArray, timeoutMs: Long = 2000L): Boolean {
        val ch = cmdChar ?: return false
        if (disconnectRequested || physicallyDisconnected) return false
        if (pendingCmdAwait != null || pendingCmdWriteAwait != null) return false
        val writeDeferred = CompletableDeferred<Int>()
        pendingCmdWriteAwait = writeDeferred
        expectedCmdSeq = null
        return try {
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ch.value = data
            if (!gatt.writeCharacteristic(ch)) {
                pendingCmdWriteAwait = null
                return false
            }
            val writeStatus = withTimeout(timeoutMs) { writeDeferred.await() }
            val ok = writeStatus == BluetoothGatt.GATT_SUCCESS
            if (ok) lastTxTimeMs = System.currentTimeMillis()
            ok
        } catch (_: TimeoutCancellationException) {
            pendingCmdWriteAwait = null
            false
        } catch (e: CancellationException) {
            pendingCmdWriteAwait = null
            throw e
        } catch (_: Exception) {
            pendingCmdWriteAwait = null
            false
        }
    }

    /** 写入 CMD 特征并等待固件响应确认（失败/超时返回 false） */
    @SuppressLint("MissingPermission")
    suspend fun writeCmdAwait(data: ByteArray, timeoutMs: Long = 8000L): Boolean =
        opMutex.withLock { writeCmdAwaitLocked(data, timeoutMs) }

    private suspend fun writeCmdAwaitLocked(data: ByteArray, timeoutMs: Long): Boolean {
        val ch = cmdChar ?: return false
        if (disconnectRequested || physicallyDisconnected) return false
        if (data.size < YuiTagProtocol.V2_HEADER_LEN + 2 ||
            data.size > YuiTagProtocol.V2_HEADER_LEN + YuiTagProtocol.CMD_DECRYPT_BUF_MAX_LEN
        ) {
            FileLog.w(TAG, "writeCmdAwait rejected: invalid V2 len=${data.size}")
            return false
        }
        if (pendingCmdAwait != null || pendingCmdWriteAwait != null) return false
        val notifyDeferred = CompletableDeferred<Boolean>()
        val writeDeferred = CompletableDeferred<Int>()
        pendingCmdAwait = notifyDeferred
        pendingCmdWriteAwait = writeDeferred
        expectedCmdSeq = data[0].toInt() and 0xFF
        return try {
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ch.value = data
            if (!gatt.writeCharacteristic(ch)) {
                pendingCmdAwait = null
                pendingCmdWriteAwait = null
                expectedCmdSeq = null
                return false
            }
            withTimeout(timeoutMs) {
                val writeStatus = writeDeferred.await()
                if (writeStatus == BluetoothGatt.GATT_SUCCESS) {
                    lastTxTimeMs = System.currentTimeMillis()
                }
                val notifyOk = notifyDeferred.await()
                writeStatus == BluetoothGatt.GATT_SUCCESS && notifyOk
            }
        } catch (_: TimeoutCancellationException) {
            pendingCmdAwait = null
            pendingCmdWriteAwait = null
            expectedCmdSeq = null
            false
        } catch (e: CancellationException) {
            pendingCmdAwait = null
            pendingCmdWriteAwait = null
            expectedCmdSeq = null
            throw e
        } catch (_: Exception) {
            pendingCmdAwait = null
            pendingCmdWriteAwait = null
            expectedCmdSeq = null
            false
        }
    }

    /** 写入 PARAM 特征并等待固件响应确认（失败/超时返回 false） */
    @SuppressLint("MissingPermission")
    suspend fun writeParamAwait(data: ByteArray, timeoutMs: Long = 8000L): Boolean =
        opMutex.withLock { writeParamAwaitLocked(data, timeoutMs) }

    private suspend fun writeParamAwaitLocked(data: ByteArray, timeoutMs: Long): Boolean {
        val ch = paramChar ?: return false
        if (disconnectRequested || physicallyDisconnected) return false
        if (data.size < YuiTagProtocol.V2_HEADER_LEN + 2 ||
            data.size > YuiTagProtocol.V2_HEADER_LEN + YuiTagProtocol.PARAM_DECRYPT_BUF_MAX_LEN
        ) {
            FileLog.w(TAG, "writeParamAwait rejected: invalid V2 len=${data.size}")
            return false
        }
        if (pendingParamAwait != null || pendingParamWriteAwait != null) return false
        val notifyDeferred = CompletableDeferred<Boolean>()
        val writeDeferred = CompletableDeferred<Int>()
        pendingParamAwait = notifyDeferred
        pendingParamWriteAwait = writeDeferred
        expectedParamSeq = data[0].toInt() and 0xFF
        return try {
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ch.value = data
            if (!gatt.writeCharacteristic(ch)) {
                pendingParamAwait = null
                pendingParamWriteAwait = null
                expectedParamSeq = null
                return false
            }
            withTimeout(timeoutMs) {
                val writeStatus = writeDeferred.await()
                if (writeStatus == BluetoothGatt.GATT_SUCCESS) {
                    lastTxTimeMs = System.currentTimeMillis()
                }
                val notifyOk = notifyDeferred.await()
                writeStatus == BluetoothGatt.GATT_SUCCESS && notifyOk
            }
        } catch (_: TimeoutCancellationException) {
            pendingParamAwait = null
            pendingParamWriteAwait = null
            expectedParamSeq = null
            false
        } catch (e: CancellationException) {
            pendingParamAwait = null
            pendingParamWriteAwait = null
            expectedParamSeq = null
            throw e
        } catch (_: Exception) {
            pendingParamAwait = null
            pendingParamWriteAwait = null
            expectedParamSeq = null
            false
        }
    }

    // --- 读取操作（复用既有连接） ---

    /** 读取单特征值（串行队列保护，失败/超时返回 null） */
    @SuppressLint("MissingPermission")
    private suspend fun readCharLocked(ch: BluetoothGattCharacteristic): ByteArray? {
        val deferred = CompletableDeferred<ByteArray?>()
        readAwait = deferred
        return try {
            if (!gatt.readCharacteristic(ch)) {
                readAwait = null
                return null
            }
            withTimeout(8000L) { deferred.await() }
        } catch (e: CancellationException) {
            readAwait = null
            throw e
        } catch (_: Exception) {
            readAwait = null
            null
        }
    }

    /** 读取设备信息（DIS 0x180A + GAS 0x1800），复用既有连接，整体 10s 超时 */
    suspend fun readDisInfo(deviceNameFallback: String = ""): Result<YuiTagProtocol.DeviceDisInfo> =
        opMutex.withLock {
            val values = mutableMapOf<UUID, String>()
            val disChars = mutableListOf<BluetoothGattCharacteristic>()
            gatt.getService(YuiTagProtocol.GAS_SERVICE_UUID.uuid)?.getCharacteristic(YuiTagProtocol.DEVICE_NAME_UUID)?.let { disChars.add(it) }
            gatt.getService(YuiTagProtocol.DIS_SERVICE_UUID.uuid)?.let { dis ->
                dis.getCharacteristic(YuiTagProtocol.MODEL_UUID)?.let { disChars.add(it) }
                dis.getCharacteristic(YuiTagProtocol.SERIAL_UUID)?.let { disChars.add(it) }
                dis.getCharacteristic(YuiTagProtocol.FW_REV_UUID)?.let { disChars.add(it) }
                dis.getCharacteristic(YuiTagProtocol.HW_REV_UUID)?.let { disChars.add(it) }
                dis.getCharacteristic(YuiTagProtocol.SW_REV_UUID)?.let { disChars.add(it) }
                dis.getCharacteristic(YuiTagProtocol.MANUF_UUID)?.let { disChars.add(it) }
            }
            try {
                withTimeout(10_000L) {
                    for (ch in disChars) {
                        readCharLocked(ch)?.let { values[ch.uuid] = String(it).trim() }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 整体超时则返回已读取的部分数据
            }
            Result.success(YuiTagProtocol.DeviceDisInfo(
                deviceName = values[YuiTagProtocol.DEVICE_NAME_UUID] ?: deviceNameFallback,
                modelNumber = values[YuiTagProtocol.MODEL_UUID] ?: "",
                serialNumber = values[YuiTagProtocol.SERIAL_UUID] ?: "",
                firmwareVersion = values[YuiTagProtocol.FW_REV_UUID] ?: "",
                hardwareVersion = values[YuiTagProtocol.HW_REV_UUID] ?: "",
                manufacturer = values[YuiTagProtocol.MANUF_UUID] ?: "",
                coreVersion = values[YuiTagProtocol.SW_REV_UUID] ?: ""
            ))
        }

    /** 读取参数配置（PARAM 0xAA46 + GAS 设备名），复用既有连接，整体 8s 超时 */
    suspend fun readParamConfig(): Result<YuiTagProtocol.DeviceParamInfo> = opMutex.withLock {
        try {
            withTimeout(8000L) {
                var broadcastName = ""
                gatt.getService(YuiTagProtocol.GAS_SERVICE_UUID.uuid)?.getCharacteristic(YuiTagProtocol.DEVICE_NAME_UUID)?.let { gas ->
                    readCharLocked(gas)?.let { broadcastName = String(it).trim() }
                }
                val ch = paramChar
                if (ch == null) {
                    Result.success(YuiTagProtocol.DeviceParamInfo(broadcastName = broadcastName))
                } else {
                    val raw = readCharLocked(ch)
                    if (raw == null) {
                        Result.failure(Exception("PARAM 读取失败"))
                    } else {
                        val parsed = YuiTagProtocol.parseParamConfig(raw)
                        Result.success(parsed.copy(broadcastName = parsed.broadcastName.ifEmpty { broadcastName }))
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Result.failure(Exception("PARAM 读取超时"))
        }
    }

    // --- 命令写入操作（复用既有连接） ---

    suspend fun sendUnbind(): Boolean {
        val key = aesKey ?: return false
        return writeCmdAwait(CryptoUtils.buildUnbindFrame(key, nextSeq()))
    }

    suspend fun writeName(name: String): Boolean {
        val key = aesKey ?: return false
        return writeParamAwait(CryptoUtils.buildNameWriteFrame(name, key, nextSeq()))
    }

    suspend fun writeAdvInterval(intervalMs: Int, isFindMode: Boolean): Boolean {
        val key = aesKey ?: return false
        val frame = if (isFindMode) {
            CryptoUtils.buildFindIntervalWriteFrame(intervalMs, key, nextSeq())
        } else {
            CryptoUtils.buildIntervalWriteFrame(intervalMs, key, nextSeq())
        }
        return writeParamAwait(frame)
    }

    suspend fun writeFindMyEnable(enabled: Boolean): Boolean {
        val key = aesKey ?: return false
        return writeParamAwait(CryptoUtils.buildFindMyEnableFrame(enabled, key, nextSeq()))
    }

    suspend fun timeSync(unixTime: Long): Boolean {
        val key = aesKey ?: return false
        return writeCmdAwait(CryptoUtils.buildFindMyTimeSyncFrame(unixTime, key, nextSeq()))
    }

    /** 查询 Find My 状态：写 CMD 帧，等待 PARAM 通道数据型通知 */
    @SuppressLint("MissingPermission")
    suspend fun getFindMyStatus(): YuiTagProtocol.FindMyStatus? = opMutex.withLock {
        val ch = cmdChar ?: return@withLock null
        val key = aesKey ?: return@withLock null
        if (disconnectRequested || physicallyDisconnected) return@withLock null
        val deferred = CompletableDeferred<ByteArray>()
        val writeDeferred = CompletableDeferred<Int>()
        pendingParamDataAwait = YuiTagProtocol.CMD_FINDMY_STATUS to deferred
        pendingCmdWriteAwait = writeDeferred
        try {
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ch.value = CryptoUtils.buildFindMyStatusFrame(key, nextSeq())
            if (!gatt.writeCharacteristic(ch)) return@withLock null
            val resp = withTimeout(8000L) {
                val writeStatus = writeDeferred.await()
                if (writeStatus != BluetoothGatt.GATT_SUCCESS) return@withTimeout ByteArray(0)
                lastTxTimeMs = System.currentTimeMillis()
                deferred.await()
            }
            if (resp.isEmpty()) null else CryptoUtils.parseFindMyStatusResponse(resp)
        } catch (_: TimeoutCancellationException) {
            null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } finally {
            pendingParamDataAwait = null
            pendingCmdWriteAwait = null
        }
    }

    suspend fun writePeriodConfig(periodIndex: Int): Boolean {
        val key = aesKey ?: return false
        return writeParamAwait(CryptoUtils.buildFindMyPeriodConfigFrame(periodIndex, key, nextSeq()))
    }

    suspend fun writeTxPower(txPowerDbm: Int): Boolean {
        val key = aesKey ?: return false
        return writeParamAwait(CryptoUtils.buildTxPowerConfigFrame(txPowerDbm, key, nextSeq()))
    }

    suspend fun writeFindMyTxPower(txPowerDbm: Int): Boolean {
        val key = aesKey ?: return false
        return writeParamAwait(CryptoUtils.buildFindMyTxPowerConfigFrame(txPowerDbm, key, nextSeq()))
    }

    suspend fun writeFindMyAdvInterval(intervalMs: Int): Boolean {
        val key = aesKey ?: return false
        return writeParamAwait(CryptoUtils.buildFindMyIntervalConfigFrame(intervalMs, key, nextSeq()))
    }

    /** 批量写入 PARAM (0x70 TLV)，超出 MTU/缓冲上限时自动分片串行下发。 */
    suspend fun writeBatchParams(items: List<Pair<Byte, ByteArray>>): Boolean {
        if (items.isEmpty()) return true
        val key = aesKey ?: return false
        val maxFrameBytes = minOf(
            (negotiatedMtu - 3).coerceAtLeast(20),
            YuiTagProtocol.V2_HEADER_LEN + YuiTagProtocol.PARAM_DECRYPT_BUF_MAX_LEN
        )
        // V2 固定开销 6B (Header 4B + CMD 1B + CRC 1B)，单条 TLV 需 2 + valLen
        val maxBodyBytes = maxFrameBytes - 6
        if (items.any { 2 + it.second.size > maxBodyBytes }) {
            FileLog.w(TAG, "writeBatchParams rejected: single item exceeds MTU/buffer limit (maxFrame=$maxFrameBytes)")
            return false
        }
        val chunks = mutableListOf<List<Pair<Byte, ByteArray>>>()
        val currentChunk = mutableListOf<Pair<Byte, ByteArray>>()
        var currentBodyLen = 0
        for (item in items) {
            val itemLen = 2 + item.second.size
            if (currentChunk.isNotEmpty() && currentBodyLen + itemLen > maxBodyBytes) {
                chunks.add(currentChunk.toList())
                currentChunk.clear()
                currentBodyLen = 0
            }
            currentChunk.add(item)
            currentBodyLen += itemLen
        }
        if (currentChunk.isNotEmpty()) {
            chunks.add(currentChunk)
        }
        for (chunk in chunks) {
            val frame = CryptoUtils.buildBatchParamWriteFrame(chunk, key, nextSeq())
            if (!writeParamAwait(frame)) return false
        }
        return true
    }

    /** 同步固定密钥池至 Sparks (START -> 批量 DATA 1..4 把 -> FINISH)。 */
    suspend fun syncFixedKeys(
        keys: List<CryptoUtils.FixedFindMyKey>,
        onProgress: ((current: Int, total: Int) -> Unit)? = null
    ): Boolean {
        val key = aesKey ?: return false
        val total = keys.size
        if (total !in YuiTagProtocol.FINDMY_MIN_KEYS..YuiTagProtocol.FINDMY_MAX_KEYS) {
            FileLog.w(TAG, "syncFixedKeys rejected: total $total out of [${YuiTagProtocol.FINDMY_MIN_KEYS}, ${YuiTagProtocol.FINDMY_MAX_KEYS}]")
            return false
        }

        val maxAttPayload = minOf(
            negotiatedMtu - 3,
            YuiTagProtocol.V2_HEADER_LEN + YuiTagProtocol.PARAM_DECRYPT_BUF_MAX_LEN
        ).coerceAtMost(200)
        // 批量 DATA 帧长 = Header(4B) + SubCmd(4B) + Count*28B + CRC(1B) = 9 + Count*28
        if (maxAttPayload < 37) {
            FileLog.w(TAG, "syncFixedKeys rejected: MTU 过小 (mtu=$negotiatedMtu, maxAttPayload=$maxAttPayload < 37)")
            return false
        }
        val keysPerBatch = ((maxAttPayload - 9) / 28).coerceIn(1, 4)

        FileLog.i(TAG, "开始同步固定密钥池: 共 $total 个密钥 (mtu=$negotiatedMtu, batchSize=$keysPerBatch)")
        try { gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) } catch (_: Exception) {}

        try {
            // 1. START
            val startFrame = CryptoUtils.buildKeysSyncStartFrame(total, key, nextSeq())
            if (!writeParamAwait(startFrame, timeoutMs = 5000L)) {
                FileLog.w(TAG, "FixedKeys sync START 失败")
                return false
            }

            // 2. 批量 DATA (1..4 把/包)
            var idx = 0
            while (idx < total) {
                val end = minOf(idx + keysPerBatch, total)
                val batch = keys.subList(idx, end)
                onProgress?.invoke(end, total)
                val dataFrame = CryptoUtils.buildKeysSyncBatchDataFrame(
                    startIndex = idx,
                    pubKeysX = batch.map { it.publicKeyX },
                    aesKey = key,
                    seq = nextSeq()
                )
                if (!writeParamAwait(dataFrame, timeoutMs = 5000L)) {
                    FileLog.w(TAG, "FixedKeys sync 批量 DATA 失败: startIndex=$idx count=${batch.size}")
                    return false
                }
                idx = end
            }

            // 3. FINISH
            val finishFrame = CryptoUtils.buildKeysSyncFinishFrame(total, key, nextSeq())
            if (!writeParamAwait(finishFrame, timeoutMs = 8000L)) {
                FileLog.w(TAG, "FixedKeys sync FINISH 失败")
                return false
            }

            FileLog.i(TAG, "固定密钥池同步成功: 共 $total 个密钥")
            return true
        } finally {
            try { gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED) } catch (_: Exception) {}
        }
    }

    // --- 断开与资源清理 ---

    /**
     * 等待 BLE 协议栈确认物理断开。
     * 由连接管理器在“断开旧会话后建新连接”前调用，替代固定 delay。
     * 返回 true 表示回调已到达；超时返回 false（由调用方决定是否 forceClose 兜底）。
     */
    suspend fun awaitDisconnected(timeoutMs: Long = 1500): Boolean {
        if (physicallyDisconnected) return true
        val d = CompletableDeferred<Unit>()
        disconnectDeferred = d
        return try {
            withTimeout(timeoutMs) { d.await() }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** 强制关闭底层 gatt（断开确认超时后的兜底） */
    @SuppressLint("MissingPermission")
    fun forceClose() {
        try { gatt.disconnect() } catch (_: Exception) {}
        try { gatt.close() } catch (_: Exception) {}
    }

    /**
     * 主动断开：仅发起 gatt.disconnect()，不立即 close。
     * 物理断开回调到达后由 BleGattClient 回调执行 close 并触发 notifyDisconnected；
     * 若回调超时未达，由连接管理器的 forceClose() 兜底关闭。
     */
    fun disconnect() {
        disconnectRequested = true
        stopHeartbeat()
        completePendingAwaits()
        try { gatt.disconnect() } catch (_: Exception) {}
    }
}
