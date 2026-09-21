package com.reiyy.yuitag.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID
import kotlin.coroutines.resume

/** 绑定流程结果：AES 密钥 + 设备信息 + FindMy 初始化是否成功 */
data class BindResult(
    val aesKey: ByteArray,
    val disInfo: YuiTagProtocol.DeviceDisInfo,
    val findMyOk: Boolean,
    val isSparks: Boolean = false
)

class BleGattClient {

    companion object {
        private const val TAG = "YuiTagGATT"
        private const val TIMEOUT_MS = 8000L
        private const val BIND_TIMEOUT_MS = 25000L
    }

    /**
     * 一次性事件等待器：仅当 await() 正在等待时 signal() 才生效，用于按序等待 GATT 回调。
     *
     * 注意：signal() 丢弃"无人等待时到达"的事件。调用方必须保证 await() 在事件回调之前置位
     * active —— 本类仅被 bindDevice 使用，而 bindDevice 只从 Main.immediate 调度上下文调用，
     * launch 会同步执行到首个挂起点（await），因此 BLE 回调线程不可能早于 active=true 触发。
     * 若未来改从其他调度器调用，需重构此机制。
     */
    private class OneShot<T>(private val timeoutMs: Long = BleGattClient.TIMEOUT_MS) {
        private var deferred = CompletableDeferred<T>()
        private var active = false
        private var buffered: T? = null

        suspend fun await(): T? {
            active = true
            buffered?.let {
                buffered = null
                active = false
                return it
            }
            val result = try {
                withTimeout(timeoutMs) { deferred.await() }
            } catch (_: TimeoutCancellationException) {
                null
            }
            active = false
            deferred = CompletableDeferred()
            return result
        }

        fun signal(value: T) {
            if (active && !deferred.isCompleted) {
                deferred.complete(value)
            } else {
                buffered = value
            }
        }
    }

    // --- 设备绑定流程：ECDH 密钥协商 → 验证 → 绑定完成 ---

    @SuppressLint("MissingPermission")
    suspend fun bindDevice(
        device: BluetoothDevice,
        onProgress: ((String) -> Unit)? = null
    ): Result<BindResult> {
        FileLog.d("GATT", "══════ 开始绑定 ${device.address} ══════")
        return try {
            withTimeout(BIND_TIMEOUT_MS) {
                val result = CompletableDeferred<Result<BindResult>>()
                var gatt: BluetoothGatt? = null
                var keyExChar: BluetoothGattCharacteristic? = null
                var cmdChar: BluetoothGattCharacteristic? = null
                var paramChar: BluetoothGattCharacteristic? = null
                var appKeyPair: CryptoUtils.EcdhKeyPair? = null
                var aesKey: ByteArray? = null
                var disInfo = YuiTagProtocol.DeviceDisInfo("", "", "", "", "", "")

                // 服务发现时序保护：MTU 协商后立即发现服务在部分 Android 设备上会返回空或旧缓存表。
                // 首次发现略微延后；若核心服务缺失，再通过 refresh() 清空 GATT 缓存重试。
                val mainHandler = Handler(Looper.getMainLooper())
                var discoveryRetried = false
                fun rediscoverServices(g: BluetoothGatt, useRefresh: Boolean) {
                    mainHandler.postDelayed({
                        if (useRefresh) {
                            try { g.javaClass.getMethod("refresh")?.invoke(g) } catch (_: Exception) {}
                        }
                        g.discoverServices()
                    }, if (useRefresh) 500L else 400L)
                }

                fun report(msg: String) {
                    FileLog.d(TAG, "[Progress] $msg")
                    onProgress?.invoke(msg)
                }

                val coreSvcUuid = UUID.fromString(YuiTagProtocol.CORE_SERVICE_UUID)
                val bindUuid = UUID.fromString(YuiTagProtocol.BIND_CHAR_UUID)
                val cmdUuid = UUID.fromString(YuiTagProtocol.CMD_CHAR_UUID)
                val paramUuid = UUID.fromString(YuiTagProtocol.PARAM_CHAR_UUID)

                fun fail(reason: String) {
                    FileLog.e("GATT", "绑定失败: $reason")
                    if (!result.isCompleted) result.complete(Result.failure(Exception(reason)))
                    try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
                }

                /** 绑定成功（FindMy 步骤失败不阻断绑定，findMyOk=false 由 UI 提供重试） */
                fun finishBind(findMyOk: Boolean, isSparks: Boolean = false) {
                    val key = aesKey
                    if (key == null) { fail("AES key lost"); return }
                    if (!result.isCompleted) result.complete(Result.success(BindResult(key, disInfo, findMyOk, isSparks)))
                    try { gatt?.disconnect() } catch (_: Exception) {}
                }

                // 一次性事件等待器（绑定流程各步骤串行，事件必然按序到达）
                val connectedEvt = OneShot<Int>()
                val mtuEvt = OneShot<Int>()
                val servicesEvt = OneShot<Int>()
                val keyExCccdEvt = OneShot<Int>()
                val pubkeyWriteEvt = OneShot<Int>()
                val tagPubkeyEvt = OneShot<ByteArray>()
                val cmdCccdEvt = OneShot<Int>()
                val paramCccdEvt = OneShot<Int>()
                val cmdNotifyEvt = OneShot<ByteArray>(timeoutMs = 6000L)
                val paramNotifyEvt = OneShot<ByteArray>(timeoutMs = 6000L)
                val readEvt = OneShot<Triple<UUID, ByteArray, Int>>()

                val callback = object : BluetoothGattCallback() {
                    override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                        FileLog.d(TAG, "连接状态变化: status=$status newState=$newState (2=CONNECTED, 0=DISCONNECTED)")
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            FileLog.e(TAG, "GATT 连接错误: status=$status")
                            g.close()
                            if (!result.isCompleted) result.complete(Result.failure(Exception("GATT error: status=$status")))
                            return
                        }
                        when (newState) {
                            BluetoothProfile.STATE_CONNECTED -> {
                                connectedEvt.signal(newState)
                                FileLog.i(TAG, "✓ GATT 已连接，协商 MTU (请求512)...")
                                g.requestMtu(512)
                            }
                            BluetoothProfile.STATE_DISCONNECTED -> {
                                FileLog.i(TAG, "GATT 断开")
                                g.close()
                                if (!result.isCompleted && aesKey == null)
                                    result.complete(Result.failure(Exception("Device disconnected during bind")))
                            }
                        }
                    }

                    override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                        FileLog.i(TAG, "MTU 协商完成: mtu=$mtu status=$status")
                        mtuEvt.signal(status)
                        // 延后触发服务发现，避免 Android 协议栈返回旧缓存表
                        rediscoverServices(g, useRefresh = false)
                    }

                    override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                        FileLog.d(TAG, "服务发现完成: status=$status")
                        servicesEvt.signal(status)
                        if (status != BluetoothGatt.GATT_SUCCESS) return
                        g.services.forEach { svc ->
                            FileLog.d(TAG, "  服务: ${svc.uuid.toString().takeLast(8)}")
                            svc.characteristics.forEach { ch ->
                                FileLog.d(TAG, "    特征: ${ch.uuid.toString().takeLast(8)} props=0x${ch.properties.toString(16)}")
                            }
                        }
                        val coreSvc = g.getService(coreSvcUuid)
                        keyExChar = coreSvc?.getCharacteristic(bindUuid)
                        cmdChar = coreSvc?.getCharacteristic(cmdUuid)
                        paramChar = coreSvc?.getCharacteristic(paramUuid)
                        if (keyExChar == null || cmdChar == null) {
                            if (!discoveryRetried) {
                                // 首次未命中时清缓存重试一次，覆盖动态服务暴露延迟
                                discoveryRetried = true
                                FileLog.w(TAG, "核心服务/特征未命中，refresh 后重试服务发现...")
                                rediscoverServices(g, useRefresh = true)
                                return
                            }
                            fail("Pairing characteristics not found"); return
                        }
                        FileLog.i(TAG, ">>> Step 1: 启用绑定通道通知 >>>")
                        g.setCharacteristicNotification(keyExChar, true)
                        val cccd = keyExChar!!.getDescriptor(YuiTagProtocol.CCCD_UUID)
                            ?: keyExChar!!.descriptors.firstOrNull { it.uuid.toString().contains("2902", true) }
                        if (cccd != null) {
                            FileLog.d(TAG, "找到 Bind CCCD，写入使能...")
                            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            g.writeDescriptor(cccd)
                        } else {
                            FileLog.w(TAG, "未找到 Bind CCCD，直接写入公钥")
                            keyExCccdEvt.signal(BluetoothGatt.GATT_SUCCESS)
                        }
                    }

                    override fun onDescriptorWrite(g: BluetoothGatt, desc: BluetoothGattDescriptor, status: Int) {
                        when (desc.characteristic.uuid) {
                            bindUuid -> keyExCccdEvt.signal(status)
                            cmdUuid -> cmdCccdEvt.signal(status)
                            paramUuid -> paramCccdEvt.signal(status)
                        }
                    }

                    override fun onCharacteristicWrite(
                        g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int
                    ) {
                        if (ch.uuid == bindUuid) pubkeyWriteEvt.signal(status)
                    }

                    override fun onCharacteristicChanged(
                        g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray
                    ) {
                        FileLog.d(TAG, "<<< 收到通知: char=${ch.uuid.toString().takeLast(4)} len=${value.size} data=${value.toHex()} <<<")
                        when (ch.uuid) {
                            bindUuid -> tagPubkeyEvt.signal(value)
                            cmdUuid -> cmdNotifyEvt.signal(value)
                            paramUuid -> paramNotifyEvt.signal(value)
                        }
                    }

                    @Suppress("DEPRECATION")
                    override fun onCharacteristicChanged(
                        g: BluetoothGatt, ch: BluetoothGattCharacteristic
                    ) {
                        // API 31-32 兼容：旧签名无 value 参数，从 characteristic.value 取
                        val value = ch.value ?: return
                        FileLog.d(TAG, "<<< 收到通知(legacy): char=${ch.uuid.toString().takeLast(4)} len=${value.size} data=${value.toHex()} <<<")
                        when (ch.uuid) {
                            bindUuid -> tagPubkeyEvt.signal(value)
                            cmdUuid -> cmdNotifyEvt.signal(value)
                            paramUuid -> paramNotifyEvt.signal(value)
                        }
                    }

                    override fun onCharacteristicRead(
                        g: BluetoothGatt, ch: BluetoothGattCharacteristic,
                        value: ByteArray, status: Int
                    ) {
                        readEvt.signal(Triple(ch.uuid, value, status))
                    }

                    @Suppress("DEPRECATION")
                    override fun onCharacteristicRead(
                        g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int
                    ) {
                        val value = if (status == BluetoothGatt.GATT_SUCCESS) ch.value ?: byteArrayOf() else byteArrayOf()
                        readEvt.signal(Triple(ch.uuid, value, status))
                    }
                }

                fun writeAppPubkey() {
                    FileLog.i(TAG, ">>> Step 2: 写入 App ECDH 公钥 >>>")
                    report("正在生成 ECDH 密钥...")
                    appKeyPair = CryptoUtils.generateEcdhKeyPair()
                    report("正在交换公钥...")
                    val ch = keyExChar ?: run { fail("KeyEx char lost"); return }
                    ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    ch.value = appKeyPair!!.publicKey
                    val ok = gatt?.writeCharacteristic(ch) ?: false
                    FileLog.i("GATT", "App公钥写入 bind通道 ok=$ok size=${appKeyPair!!.publicKey.size} 等待Tag响应...")
                    FileLog.d(TAG, "writeCharacteristic(KeyEx) → $ok")
                    if (!ok) fail("App public key write rejected")
                }

                gatt = device.connectGatt(null, false, callback, BluetoothDevice.TRANSPORT_LE)
                    ?: run {
                        val failure = Result.failure<BindResult>(Exception("connectGatt returned null"))
                        if (!result.isCompleted) result.complete(failure)
                        return@withTimeout failure
                    }

                val isSparks = YuiTagProtocol.isSparksDevice(device.name)

                // 在 launch 中执行顺序逻辑（享受协程上下文，可调用 suspend 函数）
                launch { try {
                    // 1. 等待连接
                    report("正在连接设备...")
                    if (connectedEvt.await() == null) { fail("连接超时"); return@launch }

                    // 2. 等待 MTU
                    mtuEvt.await()

                    // 3. 服务发现
                    val svcStatus = servicesEvt.await()
                    if (svcStatus == null || svcStatus != BluetoothGatt.GATT_SUCCESS) {
                        fail("Service discovery failed: $svcStatus"); return@launch
                    }

                    // 4. 启用绑定通道通知
                    val keyExCccd = keyExCccdEvt.await()
                    if (keyExCccd == null || keyExCccd != BluetoothGatt.GATT_SUCCESS) {
                        fail("Bind CCCD write failed: $keyExCccd"); return@launch
                    }

                    if (isSparks) {
                        // ── Sparks 低配版绑定: 写入 0x01 请求 AES 密钥 ──
                        report("正在请求密钥...")
                        FileLog.i(TAG, ">>> Step 2 (Sparks): 写入 0x01 请求 AES 密钥 >>>")
                        keyExChar!!.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        keyExChar!!.value = byteArrayOf(YuiTagProtocol.CMD_BIND_SPARKS_REQUEST)
                        val ok = gatt!!.writeCharacteristic(keyExChar)
                        FileLog.i("GATT", "Sparks 密钥请求写入 bind通道 ok=$ok 等待Tag返回密钥...")
                        if (!ok) { fail("Sparks key request write rejected"); return@launch }
                        val pubStatus = pubkeyWriteEvt.await()
                        if (pubStatus == null || pubStatus != BluetoothGatt.GATT_SUCCESS) {
                            fail("Sparks key request write failed: status=$pubStatus"); return@launch
                        }

                        report("正在接收密钥...")
                        val tagKeyData = tagPubkeyEvt.await()
                        if (tagKeyData == null || tagKeyData.size != 16) {
                            fail("Invalid Sparks key length: ${tagKeyData?.size}, expected 16"); return@launch
                        }
                        aesKey = tagKeyData
                        FileLog.hex("GATT", "Sparks AES密钥", aesKey)
                        FileLog.i(TAG, "══════ Sparks 密钥接收成功! ══════")
                    } else {
                        // 5. 写入 App 公钥（写入失败立即终止，避免空等超时）
                        writeAppPubkey()
                        val pubStatus = pubkeyWriteEvt.await()
                        if (pubStatus == null || pubStatus != BluetoothGatt.GATT_SUCCESS) {
                            fail("App public key write failed: status=$pubStatus"); return@launch
                        }

                        // 6. 等待 Tag 公钥并派生密钥（ECDH 计算切到后台线程）
                        report("正在派生密钥...")
                        val tagPub = tagPubkeyEvt.await()
                        if (tagPub == null || tagPub.size < 65) {
                            fail("Invalid Tag public key length: ${tagPub?.size}"); return@launch
                        }
                        val derived = withContext(Dispatchers.Default) {
                            val shared = CryptoUtils.computeSharedSecret(appKeyPair!!.privateKey, tagPub)
                            CryptoUtils.deriveAesKey(shared)
                        }
                        aesKey = derived
                    }

                    // 7. 启用 CMD 通知
                    FileLog.i(TAG, ">>> Step 4: 启用 CMD 通知 >>>")
                    gatt!!.setCharacteristicNotification(cmdChar, true)
                    val cmdCccd = cmdChar!!.getDescriptor(YuiTagProtocol.CCCD_UUID)
                        ?: cmdChar!!.descriptors.firstOrNull { it.uuid.toString().contains("2902", true) }
                    if (cmdCccd != null) {
                        cmdCccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt!!.writeDescriptor(cmdCccd)
                        val cmdCccdStatus = cmdCccdEvt.await()
                        if (cmdCccdStatus == null || cmdCccdStatus != BluetoothGatt.GATT_SUCCESS) {
                            fail("CMD CCCD write failed: $cmdCccdStatus"); return@launch
                        }
                    }

                    // 8. 发送绑定验证
                    report("正在完成...")
                    val verifyFrame = CryptoUtils.buildBindVerifyFrame(aesKey!!)
                    cmdChar!!.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    cmdChar!!.value = verifyFrame
                    if (gatt!!.writeCharacteristic(cmdChar) == false) { fail("Verify write rejected"); return@launch }
                    val verifyResp = cmdNotifyEvt.await()
                    if (verifyResp == null) { fail("绑定验证超时"); return@launch }
                    val verifyStatus = YuiTagProtocol.extractStatus(verifyResp)
                    if (verifyStatus != YuiTagProtocol.BIND_VERIFY_OK) {
                        fail("绑定验证失败: ${YuiTagProtocol.statusDescription(verifyStatus)}"); return@launch
                    }
                    FileLog.i(TAG, "══════ 绑定验证成功! ══════")
                    FileLog.i("GATT", "══════ 绑定验证成功! ══════")
                    FileLog.hex("GATT", "AES密钥", aesKey)
                    report("绑定成功")

                    // 9. 读取设备信息 (DIS)
                    report("正在读取设备信息...")
                    val disValues = mutableMapOf<UUID, String>()
                    val disChars = mutableListOf<BluetoothGattCharacteristic>()
                    gatt!!.getService(YuiTagProtocol.GAS_SERVICE_UUID.uuid)?.getCharacteristic(YuiTagProtocol.DEVICE_NAME_UUID)?.let { disChars.add(it) }
                    gatt!!.getService(YuiTagProtocol.DIS_SERVICE_UUID.uuid)?.let { dis ->
                        dis.getCharacteristic(YuiTagProtocol.MODEL_UUID)?.let { disChars.add(it) }
                        dis.getCharacteristic(YuiTagProtocol.SERIAL_UUID)?.let { disChars.add(it) }
                        dis.getCharacteristic(YuiTagProtocol.FW_REV_UUID)?.let { disChars.add(it) }
                        dis.getCharacteristic(YuiTagProtocol.HW_REV_UUID)?.let { disChars.add(it) }
                        dis.getCharacteristic(YuiTagProtocol.SW_REV_UUID)?.let { disChars.add(it) }
                        dis.getCharacteristic(YuiTagProtocol.MANUF_UUID)?.let { disChars.add(it) }
                    }
                    for (ch in disChars) {
                        gatt!!.readCharacteristic(ch)
                        val r = readEvt.await() ?: break
                        if (r.third == BluetoothGatt.GATT_SUCCESS) disValues[r.first] = String(r.second).trim()
                    }
                    disInfo = YuiTagProtocol.DeviceDisInfo(
                        deviceName = disValues[YuiTagProtocol.DEVICE_NAME_UUID] ?: device.name ?: "Unknown",
                        modelNumber = disValues[YuiTagProtocol.MODEL_UUID] ?: "",
                        serialNumber = disValues[YuiTagProtocol.SERIAL_UUID] ?: "",
                        firmwareVersion = disValues[YuiTagProtocol.FW_REV_UUID] ?: "",
                        hardwareVersion = disValues[YuiTagProtocol.HW_REV_UUID] ?: "",
                        manufacturer = disValues[YuiTagProtocol.MANUF_UUID] ?: "",
                        coreVersion = disValues[YuiTagProtocol.SW_REV_UUID] ?: ""
                    )

                    // 10. FindMy 校时 — Sparks 不支持，直接跳过
                    if (isSparks) {
                        FileLog.i("GATT", "Sparks 设备不支持 FindMy，跳过")
                        finishBind(findMyOk = false, isSparks = true); return@launch
                    }
                    report("正在配置FindMy...")
                    report("设备校时...")
                    val now = System.currentTimeMillis() / 1000
                    val tsFrame = CryptoUtils.buildFindMyTimeSyncFrame(now, aesKey!!)
                    cmdChar!!.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    cmdChar!!.value = tsFrame
                    if (gatt!!.writeCharacteristic(cmdChar) == false) {
                        FileLog.w("GATT", "FindMy 校时写入失败")
                        finishBind(findMyOk = false); return@launch
                    }
                    val tsResp = cmdNotifyEvt.await()
                    if (tsResp == null || YuiTagProtocol.extractStatus(tsResp) != YuiTagProtocol.STATUS_SUCCESS) {
                        FileLog.w("GATT", "FindMy 校时未确认")
                        finishBind(findMyOk = false); return@launch
                    }

                    // 11. 启用 FindMy（写 PARAM 特征，等待 PARAM 通知）
                    val paramC = paramChar ?: run { finishBind(findMyOk = false); return@launch }
                    gatt!!.setCharacteristicNotification(paramC, true)
                    val paramCccd = paramC.getDescriptor(YuiTagProtocol.CCCD_UUID)
                        ?: paramC.descriptors.firstOrNull { it.uuid.toString().contains("2902", true) }
                    if (paramCccd != null) {
                        paramCccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt!!.writeDescriptor(paramCccd)
                        paramCccdEvt.await()
                    }
                    val enFrame = CryptoUtils.buildFindMyEnableFrame(true, aesKey!!)
                    paramC.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    paramC.value = enFrame
                    if (gatt!!.writeCharacteristic(paramC) == false) {
                        FileLog.w("GATT", "FindMy 启用写入失败")
                        finishBind(findMyOk = false); return@launch
                    }
                    val enResp = paramNotifyEvt.await()
                    if (enResp == null || YuiTagProtocol.extractStatus(enResp) != YuiTagProtocol.STATUS_SUCCESS) {
                        FileLog.w("GATT", "FindMy 启用未确认")
                        finishBind(findMyOk = false); return@launch
                    }
                    report("FindMy启动成功")
                    finishBind(findMyOk = true)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        FileLog.e("GATT", "绑定流程异常: ${e.message}", e)
                        fail(e.message ?: "绑定流程异常")
                    }
                }

                try {
                    result.await()
                } finally {
                    try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
                }
            }
        } catch (e: TimeoutCancellationException) {
            Result.failure(Exception("绑定超时 (${BIND_TIMEOUT_MS / 1000}s)"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- 查找模式 GATT 长连接 ---

    @SuppressLint("MissingPermission")
    suspend fun connectForFind(
        device: BluetoothDevice,
        aesKey: ByteArray? = null
    ): Result<FindSession> {
        FileLog.d(TAG, "建立查找连接: ${device.address}")
        return try {
            withTimeout(TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    var gatt: BluetoothGatt? = null
                    var cmdChar: BluetoothGattCharacteristic? = null
                    var paramChar: BluetoothGattCharacteristic? = null
                    var cmdCccdDone = false
                    var paramCccdDone = false
                    var rssiCallback: ((Int) -> Unit)? = null   // 转发 RSSI 回调至 FindSession
                    var createdSession: FindSession? = null     // 追踪已成功创建的会话实例

                    val svcUuid = UUID.fromString(YuiTagProtocol.CORE_SERVICE_UUID)
                    val cmdUuid = UUID.fromString(YuiTagProtocol.CMD_CHAR_UUID)
                    val paramUuid = UUID.fromString(YuiTagProtocol.PARAM_CHAR_UUID)

                    /** 启用特征通知，返回 true 表示已同步完成（无 CCCD），false 表示等待 onDescriptorWrite */
                    fun enableNotify(g: BluetoothGatt, ch: BluetoothGattCharacteristic): Boolean {
                        g.setCharacteristicNotification(ch, true)
                        val cccd = ch.getDescriptor(YuiTagProtocol.CCCD_UUID)
                            ?: ch.descriptors.firstOrNull { it.uuid.toString().contains("2902", true) }
                        return if (cccd != null) {
                            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            g.writeDescriptor(cccd)
                            false
                        } else {
                            true
                        }
                    }

                    fun tryCreateSession(g: BluetoothGatt) {
                        if (!cmdCccdDone || !paramCccdDone) return
                        if (createdSession != null) return
                        val session = FindSession(g, cmdChar, paramChar, aesKey)
                        createdSession = session
                        rssiCallback = { rssi -> session.onRssiRead?.invoke(rssi) }
                        if (cont.isActive) cont.resume(Result.success(session))
                    }

                    val callback = object : BluetoothGattCallback() {
                        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                            if (status != BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_DISCONNECTED) {
                                FileLog.w(TAG, "[Find] GATT错误断开: status=0x${status.toString(16)} created=${createdSession != null}")
                                g.close()
                                if (cont.isActive) {
                                    cont.resume(Result.failure(Exception("GATT error: status=$status")))
                                } else {
                                    // 连接建立后意外断开：通知会话释放等待器
                                    createdSession?.notifyDisconnected()
                                }
                                return
                            }
                            when (newState) {
                                BluetoothProfile.STATE_CONNECTED -> {
                                    g.requestMtu(512)
                                    // 请求高连接优先级，加快 RSSI 上报频率
                                    try { g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }
                                    catch (_: Exception) {}
                                }
                                BluetoothProfile.STATE_DISCONNECTED -> {
                                    FileLog.i(TAG, "[Find] GATT已断开: status=0x${status.toString(16)} created=${createdSession != null}")
                                    g.close()
                                    if (cont.isActive) {
                                        cont.resume(Result.failure(Exception("Device disconnected")))
                                    } else {
                                        // 连接建立后意外断开：通知会话释放等待器
                                        createdSession?.notifyDisconnected()
                                    }
                                }
                            }
                        }

                        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                            FileLog.i(TAG, "[Find] MTU=$mtu")
                            g.discoverServices()
                        }

                        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                            if (status != BluetoothGatt.GATT_SUCCESS) {
                                if (cont.isActive) cont.resume(Result.failure(Exception("Service discovery failed")))
                                return
                            }
                            val svc = g.getService(svcUuid)
                            cmdChar = svc?.getCharacteristic(cmdUuid)
                            paramChar = svc?.getCharacteristic(paramUuid)
                            FileLog.i(TAG, "[Find] CMD char: ${if (cmdChar != null) "✓" else "✗"} PARAM char: ${if (paramChar != null) "✓" else "✗"}")

                            if (cmdChar == null) {
                                if (cont.isActive) cont.resume(Result.failure(Exception("CMD characteristic not found")))
                                return
                            }

                            // 先启用 CMD 通知（Android BLE 栈同一时刻只允许一个待处理 GATT 操作，
                            // PARAM CCCD 写入在 CMD CCCD 完成后再串行发起）
                            cmdCccdDone = enableNotify(g, cmdChar!!)
                            // 若 CMD 特征无需写入 CCCD（同步启用完成），需立即串行启用 PARAM，避免等待回调导致死锁
                            paramCccdDone = if (cmdCccdDone && paramChar != null) {
                                enableNotify(g, paramChar!!)
                            } else {
                                paramChar == null
                            }
                            tryCreateSession(g)
                        }

                        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
                            when (d.characteristic.uuid) {
                                cmdUuid -> {
                                    cmdCccdDone = true
                                    // CMD CCCD 写入完成 → 串行启用 PARAM 通知
                                    if (paramChar != null && !paramCccdDone) {
                                        paramCccdDone = enableNotify(g, paramChar!!)
                                    }
                                }
                                paramUuid -> paramCccdDone = true
                            }
                            tryCreateSession(g)
                        }

                        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
                            // 转发指令与参数通道的通知数据至当前会话
                            when (ch.uuid) {
                                cmdUuid -> createdSession?.handleCommandNotify(value)
                                paramUuid -> createdSession?.handleParamNotify(value)
                            }
                        }

                        @Suppress("DEPRECATION")
                        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
                            val value = ch.value ?: return
                            when (ch.uuid) {
                                cmdUuid -> createdSession?.handleCommandNotify(value)
                                paramUuid -> createdSession?.handleParamNotify(value)
                            }
                        }

                        override fun onCharacteristicRead(
                            g: BluetoothGatt, ch: BluetoothGattCharacteristic,
                            value: ByteArray, status: Int
                        ) {
                            // 转发读特征值响应至会话（复用连接读取 DIS/PARAM）
                            createdSession?.handleRead(value, status)
                        }

                        @Suppress("DEPRECATION")
                        override fun onCharacteristicRead(
                            g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int
                        ) {
                            val value = if (status == BluetoothGatt.GATT_SUCCESS) ch.value ?: byteArrayOf() else byteArrayOf()
                            createdSession?.handleRead(value, status)
                        }

                        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
                            if (status == BluetoothGatt.GATT_SUCCESS) {
                                rssiCallback?.invoke(rssi)
                            }
                        }
                    }

                    gatt = device.connectGatt(null, false, callback, BluetoothDevice.TRANSPORT_LE)
                        ?: run {
                            if (cont.isActive) cont.resume(Result.failure(Exception("connectGatt returned null")))
                            return@suspendCancellableCoroutine
                        }

                    cont.invokeOnCancellation {
                        try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            Result.failure(Exception("查找连接超时"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun connectForOta(
        device: BluetoothDevice,
        aesKey: ByteArray
    ): Result<OtaSession> {
        FileLog.d("GATT", "══════ OTA 授权 ${device.address} ══════")
        return try {
            withTimeout(TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    var gatt: BluetoothGatt? = null
                    var cmdChar: BluetoothGattCharacteristic? = null
                    var otaControlChar: BluetoothGattCharacteristic? = null
                    var cmdCccdDone = false
                    var authSent = false
                    var mtuValue = 23
                    var createdSession: OtaSession? = null

                    val coreSvcUuid = UUID.fromString(YuiTagProtocol.CORE_SERVICE_UUID)
                    val cmdUuid = UUID.fromString(YuiTagProtocol.CMD_CHAR_UUID)
                    val otaSvcUuid = UUID.fromString(YuiTagProtocol.OTA_SERVICE_UUID)
                    val otaControlUuid = UUID.fromString(YuiTagProtocol.OTA_CONTROL_CHAR_UUID)

                    fun fail(reason: String) {
                        FileLog.e(TAG, "OTA 授权失败: $reason")
                        try { gatt?.disconnect() } catch (_: Exception) {}
                        if (cont.isActive) cont.resume(Result.failure(Exception(reason)))
                    }

                    fun doSendAuth() {
                        if (!cmdCccdDone || authSent) return
                        authSent = true
                        FileLog.i(TAG, ">>> 发送 OTA 授权加密帧 >>>")
                        val encrypted = CryptoUtils.buildOtaAuthFrame(aesKey)
                        cmdChar?.let {
                            it.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                            it.value = encrypted
                            gatt?.writeCharacteristic(it)
                        } ?: fail("CMD characteristic lost")
                    }

                    val callback = object : BluetoothGattCallback() {
                        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                            if (status != BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_DISCONNECTED) {
                                g.close()
                                if (cont.isActive) {
                                    cont.resume(Result.failure(Exception("GATT error: status=$status")))
                                } else {
                                    createdSession?.onDisconnected?.invoke()
                                }
                                return
                            }
                            when (newState) {
                                BluetoothProfile.STATE_CONNECTED -> {
                                    g.requestMtu(512)
                                }
                                BluetoothProfile.STATE_DISCONNECTED -> {
                                    g.close()
                                    if (cont.isActive) {
                                        cont.resume(Result.failure(Exception("Device disconnected")))
                                    } else {
                                        createdSession?.onDisconnected?.invoke()
                                    }
                                }
                            }
                        }

                        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                            FileLog.i(TAG, "[OTA] MTU=$mtu")
                            mtuValue = mtu
                            g.discoverServices()
                        }

                        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                            if (status != BluetoothGatt.GATT_SUCCESS) {
                                fail("Service discovery failed"); return
                            }
                            val coreSvc = g.getService(coreSvcUuid)
                            cmdChar = coreSvc?.getCharacteristic(cmdUuid)
                            FileLog.i(TAG, "[OTA] CMD char: ${if (cmdChar != null) "✓" else "✗"}")

                            val otaSvc = g.getService(otaSvcUuid)
                            otaControlChar = otaSvc?.getCharacteristic(otaControlUuid)
                            FileLog.i(TAG, "[OTA] OTA Control char: ${if (otaControlChar != null) "✓" else "✗"}")

                            if (cmdChar == null) {
                                if (otaControlChar != null) {
                                    // Bootloader mode: no Core service → 跳过授权
                                    FileLog.i(TAG, "[OTA] Bootloader mode detected — skip auth")
                                    val session = OtaSession(g, otaControlChar, null, mtuValue, needAuth = false)
                                    createdSession = session
                                    if (cont.isActive) cont.resume(Result.success(session))
                                    return
                                }
                                fail("CMD characteristic not found"); return
                            }
                            if (otaControlChar == null) {
                                fail("OTA Control characteristic not found"); return
                            }

                            g.setCharacteristicNotification(cmdChar, true)
                            val cccd = cmdChar!!.getDescriptor(YuiTagProtocol.CCCD_UUID)
                                ?: cmdChar!!.descriptors.firstOrNull { it.uuid.toString().contains("2902", true) }
                            if (cccd != null) {
                                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                g.writeDescriptor(cccd)
                            } else {
                                cmdCccdDone = true
                                doSendAuth()
                            }
                        }

                        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
                            if (d.characteristic.uuid == cmdUuid) {
                                cmdCccdDone = true
                                doSendAuth()
                            }
                        }

                        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
                            if (ch.uuid == otaControlUuid) {
                                createdSession?.handleWriteComplete(status)
                            }
                        }

                        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
                            if (ch.uuid == cmdUuid) {
                                val statusByte = YuiTagProtocol.extractStatus(value)
                                FileLog.i(TAG, "<<< OTA 授权响应: 0x${statusByte.toString(16)} (${YuiTagProtocol.statusDescription(statusByte)})")
                                if (statusByte == YuiTagProtocol.STATUS_SUCCESS) {
                                    FileLog.i(TAG, "══════ OTA 授权成功! ══════")
                                    val session = OtaSession(g, otaControlChar, null, mtuValue)
                                    createdSession = session
                                    if (cont.isActive) cont.resume(Result.success(session))
                                } else {
                                    createdSession = OtaSession(g, otaControlChar, null, mtuValue)
                                    fail("OTA 授权被拒绝: ${YuiTagProtocol.statusDescription(statusByte)}")
                                }
                            }
                        }

                        @Suppress("DEPRECATION")
                        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
                            val value = ch.value ?: return
                            if (ch.uuid == cmdUuid) {
                                val statusByte = YuiTagProtocol.extractStatus(value)
                                FileLog.i(TAG, "<<< OTA 授权响应(legacy): 0x${statusByte.toString(16)} (${YuiTagProtocol.statusDescription(statusByte)})")
                                if (statusByte == YuiTagProtocol.STATUS_SUCCESS) {
                                    FileLog.i(TAG, "══════ OTA 授权成功! ══════")
                                    val session = OtaSession(g, otaControlChar, null, mtuValue)
                                    createdSession = session
                                    if (cont.isActive) cont.resume(Result.success(session))
                                } else {
                                    createdSession = OtaSession(g, otaControlChar, null, mtuValue)
                                    fail("OTA 授权被拒绝: ${YuiTagProtocol.statusDescription(statusByte)}")
                                }
                            }
                        }
                    }

                    gatt = device.connectGatt(null, false, callback, BluetoothDevice.TRANSPORT_LE)
                        ?: run {
                            if (cont.isActive) cont.resume(Result.failure(Exception("connectGatt returned null")))
                            return@suspendCancellableCoroutine
                        }

                    cont.invokeOnCancellation {
                        try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            Result.failure(Exception("OTA 授权超时 (${TIMEOUT_MS / 1000}s)"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- 连接 Bootloader 进行固件传输 ---

    @SuppressLint("MissingPermission")
    suspend fun connectForFirmwareTransfer(
        device: BluetoothDevice
    ): Result<OtaSession> {
        FileLog.d("GATT", "══════ 连接 Bootloader ${device.address} ══════")
        return try {
            withTimeout(TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    var gatt: BluetoothGatt? = null
                    var otaControlChar: BluetoothGattCharacteristic? = null
                    var otaDataChar: BluetoothGattCharacteristic? = null
                    var mtuValue = 23
                    var createdSession: OtaSession? = null

                    val otaSvcUuid = UUID.fromString(YuiTagProtocol.OTA_SERVICE_UUID)
                    val otaControlUuid = UUID.fromString(YuiTagProtocol.OTA_CONTROL_CHAR_UUID)
                    val otaDataUuid = UUID.fromString(YuiTagProtocol.OTA_DATA_CHAR_UUID)

                    fun fail(reason: String) {
                        FileLog.e(TAG, "Bootloader连接失败: $reason")
                        try { gatt?.disconnect() } catch (_: Exception) {}
                        if (cont.isActive) cont.resume(Result.failure(Exception(reason)))
                    }

                    val callback = object : BluetoothGattCallback() {
                        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                            FileLog.i("GATT", "[BL] onConnStateChange status=$status newState=$newState")
                            if (status != BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_DISCONNECTED) {
                                g.close()
                                if (cont.isActive) {
                                    cont.resume(Result.failure(Exception("Bootloader GATT error: status=$status")))
                                } else {
                                    createdSession?.onDisconnected?.invoke()
                                }
                                return
                            }
                            when (newState) {
                                BluetoothProfile.STATE_CONNECTED -> {
                                    FileLog.i("GATT", "[BL] ✓ Connected, requesting MTU")
                                    if (!g.requestMtu(247)) {
                                        g.discoverServices()
                                    }
                                    // onMtuChanged 内调用 discoverServices()
                                }
                                BluetoothProfile.STATE_DISCONNECTED -> {
                                    FileLog.i("GATT", "[BL] Disconnected")
                                    g.close()
                                    if (cont.isActive) {
                                        cont.resume(Result.failure(Exception("Bootloader disconnected")))
                                    } else {
                                        createdSession?.onDisconnected?.invoke()
                                    }
                                }
                            }
                        }

                        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                            FileLog.i("GATT", "[BL] MTU=$mtu (status=$status)")
                            if (status == BluetoothGatt.GATT_SUCCESS) mtuValue = mtu
                            // 清空 Android BLE GATT 缓存（反射调用，API 35+ 已移除该方法）
                            try {
                                g.javaClass.getMethod("refresh").invoke(g)
                            } catch (_: Exception) {}
                            g.discoverServices()
                        }

                        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                            FileLog.i("GATT", "[BL] onServicesDiscovered status=$status")
                            if (status != BluetoothGatt.GATT_SUCCESS) {
                                fail("Bootloader service discovery failed (status=$status)"); return
                            }
                            val otaSvc = g.getService(otaSvcUuid)
                            otaControlChar = otaSvc?.getCharacteristic(otaControlUuid)
                            otaDataChar = otaSvc?.getCharacteristic(otaDataUuid)
                            FileLog.i("GATT", "[BL] OTA Control=${otaControlChar != null} OTA Data=${otaDataChar != null}")
                            if (otaControlChar == null) {
                                fail("Bootloader OTA Control characteristic not found"); return
                            }
                            if (otaDataChar == null) {
                                fail("Bootloader OTA Data characteristic not found"); return
                            }
                            FileLog.i("GATT", "══════ Bootloader 连接成功! MTU=$mtuValue ══════")
                            val session = OtaSession(g, otaControlChar, otaDataChar, mtuValue)
                            createdSession = session
                            if (cont.isActive) cont.resume(Result.success(session))
                        }

                        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
                            // Control 与 Data 两种写都需要 await 完成
                            if (ch.uuid == otaControlUuid || ch.uuid == otaDataUuid) {
                                createdSession?.handleWriteComplete(status)
                            }
                        }
                    }

                    gatt = device.connectGatt(null, false, callback, BluetoothDevice.TRANSPORT_LE)
                        ?: run {
                            if (cont.isActive) cont.resume(Result.failure(Exception("connectGatt returned null")))
                            return@suspendCancellableCoroutine
                        }

                    cont.invokeOnCancellation {
                        try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            Result.failure(Exception("Bootloader连接超时 (${TIMEOUT_MS / 1000}s)"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // --- NCS OTA：连接、授权与发现 SMP 服务 ---

    @SuppressLint("MissingPermission")
    suspend fun connectForNcsOta(
        device: BluetoothDevice,
        aesKey: ByteArray,
        onStatusUpdate: ((String) -> Unit)? = null
    ): Result<NcsOtaSession> {
        FileLog.d("GATT", "══════ NCS OTA 授权 ${device.address} ══════")
        onStatusUpdate?.invoke("正在连接设备...")
        return try {
            withTimeout(30000L) {
                suspendCancellableCoroutine { cont ->
                    var gatt: BluetoothGatt? = null
                    var cmdChar: BluetoothGattCharacteristic? = null
                    var smpChar: BluetoothGattCharacteristic? = null
                    var cmdCccdDone = false
                    var authSent = false
                    var authDone = false
                    var mtuValue = 23
                    var createdSession: NcsOtaSession? = null

                    val coreSvcUuid = UUID.fromString(YuiTagProtocol.CORE_SERVICE_UUID)
                    val cmdUuid = UUID.fromString(YuiTagProtocol.CMD_CHAR_UUID)
                    val smpSvcUuid = UUID.fromString(McumgrProtocol.SMP_SERVICE_UUID)
                    val smpChrUuid = UUID.fromString(McumgrProtocol.SMP_CHAR_UUID)

                    val mainHandler = Handler(Looper.getMainLooper())

                    fun fail(reason: String) {
                        FileLog.e(TAG, "NCS OTA 失败: $reason")
                        try { gatt?.disconnect() } catch (_: Exception) {}
                        if (cont.isActive) cont.resume(Result.failure(Exception(reason)))
                    }

                    var authWriteRetries = 0
                    fun doSendAuth() {
                        if (!cmdCccdDone || authDone) return
                        if (authSent && authWriteRetries >= 3) return
                        authSent = true
                        FileLog.i(TAG, ">>> 发送 NCS OTA 授权加密帧 (0x6F) (尝试第${authWriteRetries + 1}次) >>>")
                        val encrypted = CryptoUtils.buildOtaAuthFrame(aesKey)
                        val ch = cmdChar ?: run { fail("CMD characteristic lost"); return }
                        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        ch.value = encrypted
                        val ok = gatt?.writeCharacteristic(ch) ?: false
                        if (!ok) {
                            authWriteRetries++
                            if (authWriteRetries < 4) {
                                FileLog.w(TAG, "[NCS] 写入 0x6F 授权帧返回 false (底层忙)，50ms后重试...")
                                authSent = false
                                mainHandler.postDelayed({ doSendAuth() }, 50L)
                            } else {
                                fail("CMD characteristic write rejected")
                            }
                        }
                    }

                    fun createSession(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
                        if (createdSession != null) return
                        val session = NcsOtaSession(g, ch, mtuValue)
                        createdSession = session
                        if (cont.isActive) cont.resume(Result.success(session))
                    }

                    fun subscribeSmpAndCreateSession(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
                        smpChar = ch
                        g.setCharacteristicNotification(ch, true)
                        val cccd = ch.getDescriptor(YuiTagProtocol.CCCD_UUID)
                            ?: ch.descriptors.firstOrNull { it.uuid.toString().contains("2902", true) }
                        if (cccd != null) {
                            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            val ok = g.writeDescriptor(cccd)
                            if (!ok) {
                                FileLog.w(TAG, "[NCS] SMP CCCD writeDescriptor 返回 false，50ms后重试...")
                                mainHandler.postDelayed({
                                    if (cont.isActive && createdSession == null) {
                                        g.writeDescriptor(cccd)
                                    }
                                }, 50L)
                            }
                        } else {
                            createSession(g, ch)
                        }
                    }

                    var smpRetryCount = 0
                    fun findSmpService(g: BluetoothGatt) {
                        val svc = g.getService(smpSvcUuid)
                        val ch = svc?.getCharacteristic(smpChrUuid)
                        FileLog.i(TAG, "[NCS] SMP service=${svc != null} char=${ch != null} (第${smpRetryCount + 1}次尝试)")
                        if (ch != null) {
                            subscribeSmpAndCreateSession(g, ch)
                            return
                        }
                        smpRetryCount++
                        if (smpRetryCount < 4) {
                            FileLog.w(TAG, "[NCS] SMP 服务尚未暴露，等待重试 ($smpRetryCount/3)...")
                            mainHandler.postDelayed({
                                try {
                                    g.javaClass.getMethod("refresh")?.invoke(g)
                                } catch (_: Exception) {}
                                mainHandler.postDelayed({
                                    val started = g.discoverServices()
                                    if (!started) {
                                        findSmpService(g)
                                    }
                                }, 150L)
                            }, 500L)
                            return
                        }
                        fail("SMP 服务未发现（设备可能未进入 OTA 授权状态）")
                    }

                    val callback = object : BluetoothGattCallback() {
                        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                            if (status != BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_DISCONNECTED) {
                                g.close()
                                if (cont.isActive) {
                                    cont.resume(Result.failure(Exception("GATT error: status=$status")))
                                } else {
                                    createdSession?.onDisconnected?.invoke()
                                }
                                return
                            }
                            when (newState) {
                                BluetoothProfile.STATE_CONNECTED -> {
                                    onStatusUpdate?.invoke("正在发现设备服务...")
                                    try {
                                        g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                                    } catch (_: Exception) {}
                                    if (!g.requestMtu(512)) {
                                        g.discoverServices()
                                    }
                                }
                                BluetoothProfile.STATE_DISCONNECTED -> {
                                    g.close()
                                    if (cont.isActive) {
                                        cont.resume(Result.failure(Exception("Device disconnected")))
                                    } else {
                                        createdSession?.onDisconnected?.invoke()
                                    }
                                }
                            }
                        }

                        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                            FileLog.i(TAG, "[NCS] MTU=$mtu")
                            mtuValue = mtu
                            g.discoverServices()
                        }

                        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                            if (status != BluetoothGatt.GATT_SUCCESS) {
                                fail("Service discovery failed (status=$status)"); return
                            }
                            // 探测是否已存在 SMP 服务（新固件静态模式或已有缓存）
                            val preSmp = g.getService(smpSvcUuid)?.getCharacteristic(smpChrUuid)
                            if (preSmp != null) {
                                smpChar = preSmp
                            }

                            if (!authDone) {
                                onStatusUpdate?.invoke("正在进行OTA授权...")
                                // Phase A：找 CMD 特征并发送 OTA 授权
                                val coreSvc = g.getService(coreSvcUuid)
                                cmdChar = coreSvc?.getCharacteristic(cmdUuid)
                                if (cmdChar == null) {
                                    fail("CMD characteristic not found"); return
                                }
                                FileLog.i(TAG, "[NCS] CMD char 已发现，SMP已预发现=${smpChar != null}，启用 CMD 通知")
                                g.setCharacteristicNotification(cmdChar, true)
                                val cccd = cmdChar!!.getDescriptor(YuiTagProtocol.CCCD_UUID)
                                    ?: cmdChar!!.descriptors.firstOrNull { it.uuid.toString().contains("2902", true) }
                                if (cccd != null) {
                                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                    g.writeDescriptor(cccd)
                                } else {
                                    cmdCccdDone = true
                                    mainHandler.post { doSendAuth() }
                                }
                            } else {
                                // Phase B：授权完成后发现 SMP 服务 (兼容动态 SMP 模式)
                                findSmpService(g)
                            }
                        }

                        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
                            when (d.characteristic.uuid) {
                                cmdUuid -> {
                                    cmdCccdDone = true
                                    mainHandler.post { doSendAuth() }
                                }
                                smpChrUuid -> {
                                    smpChar?.let { createSession(g, it) }
                                }
                            }
                        }

                        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
                            when (ch.uuid) {
                                cmdUuid -> {
                                    val statusByte = YuiTagProtocol.extractStatus(value)
                                    FileLog.i(TAG, "<<< NCS OTA 授权响应: 0x${statusByte.toString(16)} (${YuiTagProtocol.statusDescription(statusByte)})")
                                    if (statusByte == YuiTagProtocol.STATUS_SUCCESS) {
                                        authDone = true
                                        onStatusUpdate?.invoke("OTA授权成功，正在准备传输通道...")
                                        // 收到授权响应后立即锁定高速连接，防止任何潜在降速
                                        try {
                                            g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                                        } catch (_: Exception) {}

                                        if (smpChar != null) {
                                            // 快路径：SMP 服务在首次服务发现中已就绪（静态注册或已有缓存），直接开启 SMP 通知并建连
                                            FileLog.i(TAG, "══════ NCS OTA 授权成功，SMP 服务已就绪（静态模式） ══════")
                                            subscribeSmpAndCreateSession(g, smpChar!!)
                                        } else {
                                            // 回退路径：存量旧固件动态注册 SMP 服务，需清缓存重发现
                                            FileLog.i(TAG, "══════ NCS OTA 授权成功，等待动态 SMP 服务注册（兼容模式）... ══════")
                                            mainHandler.postDelayed({
                                                try {
                                                    gatt?.javaClass?.getMethod("refresh")?.invoke(gatt)
                                                } catch (_: Exception) {}
                                                mainHandler.postDelayed({
                                                    val started = gatt?.discoverServices() ?: false
                                                    if (!started) {
                                                        findSmpService(g)
                                                    }
                                                }, 150L)
                                            }, 200L)
                                        }
                                    } else {
                                        fail("OTA 授权被拒绝: ${YuiTagProtocol.statusDescription(statusByte)}")
                                    }
                                }
                                smpChrUuid -> {
                                    createdSession?.handleNotify(value)
                                }
                            }
                        }

                        @Suppress("DEPRECATION")
                        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
                            val value = ch.value ?: return
                            onCharacteristicChanged(g, ch, value)
                        }
                    }

                    gatt = device.connectGatt(null, false, callback, BluetoothDevice.TRANSPORT_LE)
                        ?: run {
                            if (cont.isActive) cont.resume(Result.failure(Exception("connectGatt returned null")))
                            return@suspendCancellableCoroutine
                        }

                    cont.invokeOnCancellation {
                        try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            Result.failure(Exception("NCS OTA 授权超时"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
