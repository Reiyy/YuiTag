package com.yuitag.finder.ble

import android.bluetooth.BluetoothDevice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * 蓝牙连接管理器 — 统一管理 FindSession 生命周期。
 *
 * BG22 固件仅支持单条 BLE 连接，因此：
 *  - 任何新连接建立前会先断开既有会话，并等待 BLE 协议栈确认物理断开（替代固定 delay）；
 *  - 提供两类访问方式：
 *      · withSession  —— 临时操作（读配置/写配置/解绑/响铃兜底等），块结束后自动释放；
 *      · acquire/release —— 常驻持有（查找页、主页详情），支持引用计数。
 *  - 断线（外部断开或主动断开）通过 [sessionClosed] 统一广播，供各页面同步 UI 状态。
 */
class BleConnectionManager(private val gattClient: BleGattClient) {

    private val sessions = ConcurrentHashMap<String, FindSession>()
    private val heldCounts = ConcurrentHashMap<String, Int>()

    // 查找页正在使用的会话（uid 集合）：后台延迟断开需让位于查找页
    private val findPageActiveUids: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** 标记查找页是否正在使用该设备会话（进入查找页置位，退出查找页清除） */
    fun setFindPageActive(uid: String, active: Boolean) {
        if (active) findPageActiveUids.add(uid) else findPageActiveUids.remove(uid)
    }

    /** 查找页是否正在使用该设备会话 */
    fun isFindPageActive(uid: String): Boolean = findPageActiveUids.contains(uid)

    private val _sessionClosed = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val sessionClosed: SharedFlow<String> = _sessionClosed.asSharedFlow()

    fun getSession(uid: String): FindSession? = sessions[uid]

    // --- 建连与会话持有 ---

    /**
     * 建立查找连接（单连接约束：先断开既有会话并等待物理断开确认）。
     * 已存在同 uid 会话时不会重复建连。
     */
    suspend fun connectForFind(
        uid: String,
        device: BluetoothDevice,
        aesKey: ByteArray?
    ): Result<FindSession> {
        sessions[uid]?.let { return Result.success(it) }
        disconnectAllAndAwait()
        val result = gattClient.connectForFind(device, aesKey)
        result.onSuccess { session ->
            session.addDisconnectListener {
                // 身份校验：避免旧会话迟到的断线回调误删新会话
                if (sessions[uid] === session) {
                    sessions.remove(uid)
                    _sessionClosed.tryEmit(uid)
                }
            }
            sessions[uid] = session
        }
        return result
    }

    /**
     * 临时操作：同 uid 有活会话则复用；否则独占建连。
     * 块结束后，若调用前无会话且未被 acquire 持有，则释放断开。
     */
    suspend fun <T> withSession(
        uid: String,
        device: BluetoothDevice,
        aesKey: ByteArray?,
        block: suspend (FindSession) -> T
    ): Result<T> {
        val existing = sessions[uid]
        val wasActive = existing != null
        val session: FindSession = if (existing != null) {
            existing
        } else {
            val r = connectForFind(uid, device, aesKey)
            if (r.isFailure) return Result.failure(r.exceptionOrNull() ?: Exception("连接失败"))
            r.getOrThrow()
        }
        return try {
            Result.success(block(session))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            // 临时操作自行建连的会话：等待物理断开后再返回，避免残留"断开中"会话被后续复用
            if (!wasActive && !isHeld(uid)) {
                withContext(NonCancellable) { disconnectAndAwait(uid) }
            }
        }
    }

    /** 常驻持有：引用计数 +1。已存在同 uid 会话则直接复用。 */
    suspend fun acquire(uid: String, device: BluetoothDevice, aesKey: ByteArray?): Result<FindSession> {
        val existing = sessions[uid]
        if (existing != null) {
            heldCounts.merge(uid, 1, Int::plus)
            return Result.success(existing)
        }
        val r = connectForFind(uid, device, aesKey)
        if (r.isSuccess) heldCounts.merge(uid, 1, Int::plus)
        return r
    }

    /** 常驻释放：引用计数 -1，归零时断开。 */
    fun release(uid: String) {
        val count = heldCounts.computeIfPresent(uid) { _, v -> v - 1 }
        if (count == null || count <= 0) {
            heldCounts.remove(uid)
            disconnect(uid)
        }
    }

    private fun isHeld(uid: String): Boolean = (heldCounts[uid] ?: 0) > 0

    // --- 断开管理 ---

    /** 断开指定设备会话（异步：物理断开由回调确认后自动清理）。 */
    fun disconnect(uid: String) {
        val session = sessions[uid] ?: return
        heldCounts.remove(uid)
        try { session.disconnect() } catch (_: Exception) {}
    }

    /** 断开所有会话（异步，fire-and-forget）。 */
    fun disconnectAll() {
        sessions.keys.toList().forEach { disconnect(it) }
    }

    /**
     * 断开指定会话并等待物理断开确认（替代固定 delay）。
     * 超时未确认则强制关闭 gatt 兜底。
     */
    private suspend fun disconnectAndAwait(uid: String) {
        val session = sessions.remove(uid) ?: return
        heldCounts.remove(uid)
        try { session.disconnect() } catch (_: Exception) {}
        if (!session.awaitDisconnected(1500)) {
            session.forceClose()
        }
        _sessionClosed.tryEmit(uid)
    }

    /**
     * 断开所有会话并等待物理断开确认（供建连前调用）。
     */
    private suspend fun disconnectAllAndAwait() {
        val targets = sessions.keys.toList()
        for (uid in targets) {
            disconnectAndAwait(uid)
        }
    }
}
