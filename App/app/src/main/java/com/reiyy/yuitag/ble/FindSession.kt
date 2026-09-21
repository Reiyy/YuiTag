package com.reiyy.yuitag.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.UUID

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
    private val aesKey: ByteArray?
) {
    // 串行操作队列（仅 await 写/读操作持有；fire-and-forget 发送保持同步入队）
    private val opMutex = Mutex()

    private var heartbeatJob: Job? = null
    private var heartbeatScope: CoroutineScope? = null
    private var disconnectRequested = false
    private var physicallyDisconnected = false

    /** 会话是否已请求断开或已物理断开（供会话复用方判定可用性） */
    val isDisconnected: Boolean get() = disconnectRequested || physicallyDisconnected

    var onRssiRead: ((Int) -> Unit)? = null
    var onCommandNotify: ((Int) -> Unit)? = null

    // 断线监听（可注册多个，由连接管理方在断线时统一通知）
    private val disconnectListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    // await 写入的挂起等待器（同一通道同一时刻仅允许一个挂起）
    private var pendingCmdAwait: CompletableDeferred<Boolean>? = null
    private var pendingParamAwait: CompletableDeferred<Boolean>? = null
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
        pendingParamAwait?.let { if (it.isActive) it.complete(false) }
        pendingParamAwait = null
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
                delay(20_000L)
                sendHeartbeat()
            }
        }
    }

    fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun sendHeartbeat() {
        val key = aesKey ?: return
        val cChar = cmdChar ?: return
        if (!disconnectRequested && !physicallyDisconnected) {
            writeInternal(CryptoUtils.buildHeartbeatFrame(key), cChar)
        }
    }

    @SuppressLint("MissingPermission")
    fun readRssi() {
        try { gatt.readRemoteRssi() } catch (_: Exception) {}
    }

    fun sendRing(enable: Boolean): Boolean {
        val cChar = cmdChar ?: return false
        val key = aesKey ?: return false
        if (!disconnectRequested && !physicallyDisconnected) {
            return writeInternal(
                CryptoUtils.buildCommandFrame(
                    YuiTagProtocol.CMD_ID_RING,
                    if (enable) YuiTagProtocol.CMD_ACTION_ON else YuiTagProtocol.CMD_ACTION_OFF,
                    key
                ),
                cChar
            )
        }
        return false
    }

    fun sendFindMode(enable: Boolean): Boolean {
        val cChar = cmdChar ?: return false
        val key = aesKey ?: return false
        if (!disconnectRequested && !physicallyDisconnected) {
            return writeInternal(
                CryptoUtils.buildCommandFrame(
                    YuiTagProtocol.CMD_ID_FIND_MODE,
                    if (enable) YuiTagProtocol.CMD_ACTION_ON else YuiTagProtocol.CMD_ACTION_OFF,
                    key
                ),
                cChar
            )
        }
        return false
    }

    /** 挂起发送响铃指令并等待固件通知确认 */
    suspend fun sendRingAwait(enable: Boolean, timeoutMs: Long = 1200L): Boolean {
        val key = aesKey ?: return false
        if (disconnectRequested || physicallyDisconnected) return false
        val frame = CryptoUtils.buildCommandFrame(
            YuiTagProtocol.CMD_ID_RING,
            if (enable) YuiTagProtocol.CMD_ACTION_ON else YuiTagProtocol.CMD_ACTION_OFF,
            key
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
            key
        )
        return writeCmdAwait(frame, timeoutMs)
    }

    /** CMD 通道通知：转发状态回调 + 完成挂起的 await 写入 */
    fun handleCommandNotify(value: ByteArray) {
        val status = YuiTagProtocol.extractStatus(value)
        onCommandNotify?.invoke(status)
        pendingCmdAwait?.let { d ->
            pendingCmdAwait = null
            if (d.isActive) d.complete(status == YuiTagProtocol.STATUS_SUCCESS)
        }
    }

    /** PARAM 通道通知：优先匹配数据型响应（如 FindMy 状态），其次完成挂起的 await 写入 */
    fun handleParamNotify(value: ByteArray) {
        val dataAwait = pendingParamDataAwait
        if (dataAwait != null && value.isNotEmpty() && value[0] == dataAwait.first) {
            pendingParamDataAwait = null
            if (dataAwait.second.isActive) dataAwait.second.complete(value)
            return
        }
        val status = YuiTagProtocol.extractStatus(value)
        pendingParamAwait?.let { d ->
            pendingParamAwait = null
            if (d.isActive) d.complete(status == YuiTagProtocol.STATUS_SUCCESS)
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

    /** 写入 CMD 特征并等待固件响应确认（失败/超时返回 false） */
    @SuppressLint("MissingPermission")
    suspend fun writeCmdAwait(data: ByteArray, timeoutMs: Long = 8000L): Boolean =
        opMutex.withLock { writeCmdAwaitLocked(data, timeoutMs) }

    private suspend fun writeCmdAwaitLocked(data: ByteArray, timeoutMs: Long): Boolean {
        val ch = cmdChar ?: return false
        if (pendingCmdAwait != null) return false
        // 写入前暂停心跳，避免心跳响应提前触发挂起的 await（假成功）
        val scope = heartbeatScope
        stopHeartbeat()
        val deferred = CompletableDeferred<Boolean>()
        pendingCmdAwait = deferred
        return try {
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ch.value = data
            if (!gatt.writeCharacteristic(ch)) {
                pendingCmdAwait = null
                return false
            }
            withTimeout(timeoutMs) { deferred.await() }
        } catch (e: CancellationException) {
            pendingCmdAwait = null
            throw e
        } catch (_: Exception) {
            pendingCmdAwait = null
            false
        } finally {
            // 写入完成后恢复心跳（若未断线且原本有心跳）
            if (!disconnectRequested && !physicallyDisconnected) scope?.let { startHeartbeat(it) }
        }
    }

    /** 写入 PARAM 特征并等待固件响应确认（失败/超时返回 false） */
    @SuppressLint("MissingPermission")
    suspend fun writeParamAwait(data: ByteArray, timeoutMs: Long = 8000L): Boolean =
        opMutex.withLock { writeParamAwaitLocked(data, timeoutMs) }

    private suspend fun writeParamAwaitLocked(data: ByteArray, timeoutMs: Long): Boolean {
        val ch = paramChar ?: return false
        if (pendingParamAwait != null) return false
        // 写入前暂停心跳，避免心跳响应提前触发挂起的 await（假成功）
        val scope = heartbeatScope
        stopHeartbeat()
        val deferred = CompletableDeferred<Boolean>()
        pendingParamAwait = deferred
        return try {
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ch.value = data
            if (!gatt.writeCharacteristic(ch)) {
                pendingParamAwait = null
                return false
            }
            withTimeout(timeoutMs) { deferred.await() }
        } catch (e: CancellationException) {
            pendingParamAwait = null
            throw e
        } catch (_: Exception) {
            pendingParamAwait = null
            false
        } finally {
            // 写入完成后恢复心跳（若未断线且原本有心跳）
            if (!disconnectRequested && !physicallyDisconnected) scope?.let { startHeartbeat(it) }
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
        return writeCmdAwait(CryptoUtils.buildUnbindFrame(key))
    }

    suspend fun writeName(name: String): Boolean {
        val key = aesKey ?: return false
        return writeParamAwait(CryptoUtils.buildNameWriteFrame(name, key))
    }

    suspend fun writeAdvInterval(intervalMs: Int, isFindMode: Boolean): Boolean {
        val key = aesKey ?: return false
        val frame = if (isFindMode) {
            CryptoUtils.buildFindIntervalWriteFrame(intervalMs, key)
        } else {
            CryptoUtils.buildIntervalWriteFrame(intervalMs, key)
        }
        return writeParamAwait(frame)
    }

    suspend fun writeFindMyEnable(enabled: Boolean): Boolean {
        val key = aesKey ?: return false
        return writeParamAwait(CryptoUtils.buildFindMyEnableFrame(enabled, key))
    }

    suspend fun timeSync(unixTime: Long): Boolean {
        val key = aesKey ?: return false
        return writeCmdAwait(CryptoUtils.buildFindMyTimeSyncFrame(unixTime, key))
    }

    /** 查询 Find My 状态：写 CMD 帧，等待 PARAM 通道数据型通知 */
    suspend fun getFindMyStatus(): YuiTagProtocol.FindMyStatus? = opMutex.withLock {
        val ch = cmdChar ?: return@withLock null
        val key = aesKey ?: return@withLock null
        val deferred = CompletableDeferred<ByteArray>()
        pendingParamDataAwait = YuiTagProtocol.CMD_FINDMY_STATUS.toByte() to deferred
        try {
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ch.value = CryptoUtils.buildFindMyStatusFrame(key)
            if (!gatt.writeCharacteristic(ch)) return@withLock null
            val resp = withTimeout(8000L) { deferred.await() }
            if (resp.isEmpty()) null else CryptoUtils.parseFindMyStatusResponse(resp)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } finally {
            pendingParamDataAwait = null
        }
    }

    suspend fun writePeriodConfig(periodIndex: Int): Boolean {
        val key = aesKey ?: return false
        return writeParamAwait(CryptoUtils.buildFindMyPeriodConfigFrame(periodIndex, key))
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

    /** 统一写入入口（fire-and-forget，断线判定统一由 GATT 断线回调处理） */
    @SuppressLint("MissingPermission")
    private fun writeInternal(data: ByteArray, ch: BluetoothGattCharacteristic): Boolean {
        return try {
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ch.value = data
            gatt.writeCharacteristic(ch)
        } catch (_: Exception) {
            false
        }
    }
}
