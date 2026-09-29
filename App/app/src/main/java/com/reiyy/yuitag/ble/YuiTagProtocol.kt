package com.reiyy.yuitag.ble

import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanRecord
import android.os.ParcelUuid
import java.util.UUID

/**
 * YuiTag BLE 协议常量定义、广播与特征值解析工具。
 *
 * 基础 UUID: 722Axxxx-8a74-432f-9055-7ed8a5584a11
 */
object YuiTagProtocol {
    // --- 核心业务服务 (0xAA00) ---
    const val CORE_SERVICE_UUID      = "722AAA00-8a74-432f-9055-7ed8a5584a11"
    const val BIND_CHAR_UUID         = "722AAA42-8a74-432f-9055-7ed8a5584a11"   // 安全绑定通道（ECDH 协商 / 密钥下发）
    const val CMD_CHAR_UUID          = "722AAA43-8a74-432f-9055-7ed8a5584a11"   // 指令与状态通道（响铃 / 心跳 / 解绑等）
    const val PARAM_CHAR_UUID        = "722AAA46-8a74-432f-9055-7ed8a5584a11"   // 设备参数配置通道（广播间隔 / 名称 / FindMy 开关等）

    // --- Silicon Labs OTA 服务 ---
    const val OTA_SERVICE_UUID       = "1D14D6EE-FD63-4FA1-BFA4-8F47B42119F0"
    const val OTA_CONTROL_CHAR_UUID  = "F7BF3564-FB6D-4E53-88A4-5E37E0326063"
    const val OTA_DATA_CHAR_UUID     = "984227F3-34FC-4045-A5D0-2C581F81A153"

    // Silicon Labs Gecko Bootloader AppLoader OTA 操作码
    const val OTA_OPCODE_BEGIN       = 0x00.toByte()   // 写入 OTA Control 启动 DFU 接收
    const val OTA_OPCODE_END         = 0x03.toByte()   // 写入 OTA Control 完成 DFU 并校验安装

    // --- NCS (nRF Connect SDK) OTA — MCUmgr SMP over BLE ---
    const val SMP_SERVICE_UUID       = "8D53DC1D-1DB7-4CD3-868B-8A527460AA84"
    const val SMP_CHAR_UUID          = "DA2E7828-FBCE-4E01-AE9E-261174997C48"

    // --- 标准 BLE 规范服务与特征 (16-bit) ---
    val GAS_SERVICE_UUID: ParcelUuid = ParcelUuid(UUID.fromString("00001800-0000-1000-8000-00805F9B34FB"))
    val DIS_SERVICE_UUID: ParcelUuid = ParcelUuid(UUID.fromString("0000180A-0000-1000-8000-00805F9B34FB"))
    val BATTERY_SERVICE_UUID: ParcelUuid = ParcelUuid(UUID.fromString("0000180F-0000-1000-8000-00805F9B34FB"))

    // 设备信息服务（DIS）特征值
    val DEVICE_NAME_UUID: UUID   = UUID.fromString("00002A00-0000-1000-8000-00805F9B34FB")
    val MODEL_UUID: UUID         = UUID.fromString("00002A24-0000-1000-8000-00805F9B34FB")
    val SERIAL_UUID: UUID        = UUID.fromString("00002A25-0000-1000-8000-00805F9B34FB")
    val FW_REV_UUID: UUID        = UUID.fromString("00002A26-0000-1000-8000-00805F9B34FB")
    val HW_REV_UUID: UUID        = UUID.fromString("00002A27-0000-1000-8000-00805F9B34FB")
    val SW_REV_UUID: UUID        = UUID.fromString("00002A28-0000-1000-8000-00805F9B34FB")
    val MANUF_UUID: UUID         = UUID.fromString("00002A29-0000-1000-8000-00805F9B34FB")

    // CCCD 通知启用标准描述符
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    // 厂商识别码 (Company ID)
    const val COMPANY_ID = 0x7722

    // 厂商数据协议版本（广播厂商数据第一字节）
    const val ADV_VERSION_V1 = 0x01.toByte()

    /**
     * 构造标准硬件级扫描过滤器：
     * 显式匹配 Company ID (0x7722) 及 V1 协议版本首字节 (0x01)，掩码 0xFF。
     * 避免部分蓝牙芯片在锁屏/休眠时由于空字节掩码导致硬件过滤被拒绝或静默丢包。
     */
    fun createScanFilter(): ScanFilter {
        return ScanFilter.Builder()
            .setManufacturerData(
                COMPANY_ID,
                byteArrayOf(ADV_VERSION_V1),
                byteArrayOf(0xFF.toByte())
            )
            .build()
    }

    // 设备名称前缀
    const val DEVICE_NAME_PREFIX = "YuiTag Starlight"
    const val DEVICE_NAME_PREFIX_SPARKS = "YuiTag Sparks"

    // --- 指令与动作定义 ---
    const val CMD_ID_RING             = 0x07.toByte()   // 响铃指令
    const val CMD_ID_UNBIND           = 0x7F.toByte()   // 解绑指令
    const val CMD_ID_HEARTBEAT        = 0x06.toByte()   // 心跳指令
    const val CMD_ACTION_ON           = 0x01.toByte()
    const val CMD_ACTION_OFF          = 0x00.toByte()
    const val CMD_ACTION_EXEC         = 0xFF.toByte()   // 心跳/解绑等确认动作

    // Parameter Config commands (0xAA46)
    const val CMD_NAME_WRITE          = 0x4E.toByte()   // 设备名称写入
    const val CMD_INTERVAL_CONFIG     = 0x54.toByte()   // 广播间隔配置
    const val FINDMY_ENABLE           = 0x60.toByte()   // Find My 网络开关

    // Find My 支持状态（PARAM 配置字节）
    const val FINDMY_STATUS_DISABLED    = 0x00  // 支持 Find My，但未启用
    const val FINDMY_STATUS_ENABLED     = 0x01  // 支持 Find My，已启用
    const val FINDMY_STATUS_UNSUPPORTED = 0xFF  // 设备不支持 Find My

    // Find My 时间与状态指令
    const val CMD_FINDMY_TIME_SYNC      = 0x62.toByte()   // 校时
    const val CMD_FINDMY_STATUS         = 0x63.toByte()   // 查询 Find My 状态
    const val CMD_FINDMY_PERIOD_CONFIG  = 0x64.toByte()   // 密钥轮换时间窗口配置

    // 绑定验证
    const val CMD_BIND_VERIFY         = 0x56.toByte()   // 绑定验证
    const val CMD_BATTERY             = 0x50.toByte()   // 电池指令 (写=触发检测, 读=直接读取)

    // 查找模式
    const val CMD_FIND_INTERVAL_CONFIG = 0x46.toByte()   // 查找模式广播间隔配置
    const val CMD_ID_FIND_MODE        = 0x4F.toByte()   // 查找指令（开启/关闭加速广播）

    // OTA
    const val CMD_OTA_AUTH            = 0x6F.toByte()   // OTA 授权指令

    // 低配版 Sparks 绑定
    const val CMD_BIND_SPARKS_REQUEST = 0x01.toByte()   // 低配版：请求直接下发 AES 密钥

    // --- 通知返回状态码 ---
    const val STATUS_SUCCESS           = 0x0A
    const val STATUS_FAILED            = 0x0B
    const val STATUS_AES_DECRYPT_ERROR = 0x0C
    const val STATUS_CRC_ERROR         = 0x0D
    const val STATUS_UNKNOWN           = 0xFF

    // 绑定验证状态
    const val BIND_VERIFY_OK   = 0x01
    const val BIND_VERIFY_FAIL = 0x00

    // GATT 帧格式说明：
    // 发送帧：[CMD(1B)][Action/Data(nB)][CRC(1B)] → AES-128-CTR 加密
    // 通知帧：[Seq(1B)][Status(1B)] = 2 字节

    // --- 数据模型 ---

    data class ParsedAdData(
        val version: Int,
        val shortUid: String,    // 2 字节短 UID（4 位十六进制字符）
        val isBound: Boolean,    // true=正常模式, false=未绑定模式
        val battery: Int?        // 电量百分比（正常模式下电量 <20% 时携带）
    )

    data class DeviceDisInfo(
        val deviceName: String,
        val modelNumber: String,     // 原始值: "产品名|型号" (| 分隔)
        val serialNumber: String,    // 8-byte full UID as hex string
        val firmwareVersion: String, // 原始值: "固件版本|平台" (| 分隔)
        val hardwareVersion: String,
        val manufacturer: String,
        val coreVersion: String = "" // 核心版本 (0x2A28)
    ) {
        /** 从 MODEL_NUMBER 拆分的产品名称（| 前） */
        val productName: String get() = modelNumber.split("|").getOrElse(0) { modelNumber }.trim()
        /** 从 MODEL_NUMBER 拆分的产品型号（| 后） */
        val productModel: String get() = modelNumber.split("|").getOrElse(1) { "" }.trim()
        /** 从 FW_VERSION 拆分的固件版本（| 前） */
        val firmwarePlatform: String get() = firmwareVersion.split("|").getOrElse(1) { "" }.trim()
        /** 纯固件版本号（去掉平台后缀） */
        val firmwareVer: String get() = firmwareVersion.split("|").getOrElse(0) { firmwareVersion }.trim()
    }

    /** 设备参数配置信息（从 PARAM 特征值 0xAA46 读取） */
    data class DeviceParamInfo(
        val broadcastName: String = "",
        val advIntervalNormal: Int = 1500,  // 正常模式广播间隔 (ms)
        val advIntervalFind: Int = 500,     // 查找模式广播间隔 (ms)
        val findmyEnabled: Boolean = true,  // Find My 网络开关
        val findmySupported: Boolean = true, // Find My 支持状态（false=设备不支持该功能）
        val findmyPeriodIndex: Int = 2      // 时间窗口索引: 0=15min, 1=30min, 2=1h, 3=2h
    )

    /** Find My 状态查询响应 */
    data class FindMyStatus(
        val timeSynced: Boolean = false,
        val currentTime: Long = 0,       // 设备当前 Unix 时间
        val currentPeriod: Long = 0       // 当前时间窗口索引
    )

    /**
     * 解析 PARAM 特征值 (0xAA46) 原始字节。
     * 格式: [nameLen(1)][name(n)][advInt(2 LE)][findInt(2 LE)][findmy(1)][period(1)]
     * 缺失字段使用默认值。
     */
    fun parseParamConfig(value: ByteArray): DeviceParamInfo {
        if (value.isEmpty()) return DeviceParamInfo()
        val nameLen = value[0].toInt() and 0xFF
        val nameEnd = 1 + nameLen
        var broadcastName = ""
        if (nameLen > 0 && value.size >= nameEnd) {
            broadcastName = String(value, 1, nameLen).trim()
        }
        var advIntervalNormal: Int? = null
        if (value.size >= nameEnd + 2) {
            advIntervalNormal = ((value[nameEnd + 1].toInt() and 0xFF) shl 8) or
                (value[nameEnd].toInt() and 0xFF)
        }
        var advIntervalFind: Int? = null
        if (value.size >= nameEnd + 4) {
            advIntervalFind = ((value[nameEnd + 3].toInt() and 0xFF) shl 8) or
                (value[nameEnd + 2].toInt() and 0xFF)
        }
        var findmyEnabled: Boolean? = null
        var findmySupported: Boolean? = null
        if (value.size >= nameEnd + 5) {
            when (value[nameEnd + 4].toInt() and 0xFF) {
                FINDMY_STATUS_ENABLED -> {
                    findmyEnabled = true
                    findmySupported = true
                }
                FINDMY_STATUS_DISABLED -> {
                    findmyEnabled = false
                    findmySupported = true
                }
                FINDMY_STATUS_UNSUPPORTED -> {
                    findmyEnabled = false
                    findmySupported = false
                }
                else -> {
                    // 未知字节：按不支持处理（避免误判为已启用）
                    findmyEnabled = false
                    findmySupported = false
                }
            }
        }
        var findmyPeriodIndex: Int? = null
        if (value.size >= nameEnd + 6) {
            findmyPeriodIndex = value[nameEnd + 5].toInt() and 0xFF
        }
        return DeviceParamInfo(
            broadcastName = broadcastName,
            advIntervalNormal = advIntervalNormal ?: 1500,
            advIntervalFind = advIntervalFind ?: 500,
            findmyEnabled = findmyEnabled ?: true,
            findmySupported = findmySupported ?: true,
            findmyPeriodIndex = findmyPeriodIndex ?: 2
        )
    }

    /** Find My 时间窗口配置 */
    enum class FindMyPeriod(val index: Int, val seconds: Int, val label: String) {
        MIN_15(0, 900, "15 分钟"),
        MIN_30(1, 1800, "30 分钟"),
        HOUR_1(2, 3600, "1 小时"),
        HOUR_2(3, 7200, "2 小时");

        companion object {
            fun fromIndex(index: Int): FindMyPeriod =
                entries.firstOrNull { it.index == index } ?: HOUR_1

            fun fromSeconds(seconds: Int): FindMyPeriod =
                entries.firstOrNull { it.seconds == seconds } ?: HOUR_1
        }
    }

    // --- 广播数据解析 ---

    /**
     * 解析自定义厂商数据（Manufacturer Specific Data）。
     * Android 协议栈已自动剥离 2 字节 Company ID。
     *
     * 绑定模式：  3 字节 [版本(1)][短UID(2)]
     * 未绑定模式：4 字节 [版本(1)][短UID(2)][绑定状态=0x00]
     */
    fun parseManufacturerData(record: ScanRecord): ParsedAdData? {
        val data = record.getManufacturerSpecificData(COMPANY_ID) ?: return null
        if (data.size < 3) return null

        val version = data[0].toInt() and 0xFF

        // 协议版本兼容性检查
        if (!isVersionSupported(version)) {
            FileLog.w("YuiTagProtocol", "不支持的协议版本: $version, 支持: $supportedProtocolVersions")
            return null
        }

        val shortUid = buildString {
            for (i in 1..2) append("%02X".format(data[i]))
        }

        val isBound = data.size < 4 || data[3].toInt() != 0x00

        val batteryData = record.getServiceData(BATTERY_SERVICE_UUID)
        val battery = if (batteryData != null && batteryData.isNotEmpty()) {
            batteryData[0].toInt() and 0xFF
        } else null

        return ParsedAdData(version, shortUid, isBound, battery)
    }

    /** 从广播名称提取短 UID（匹配末尾 4 位十六进制字符） */
    fun extractShortUidFromName(name: String): String? {
        val regex = Regex("YuiTag.*[-_]([0-9A-Fa-f]{4})")
        return regex.find(name)?.groupValues?.get(1)?.uppercase()
    }

    /** 判断短 UID 是否匹配目标 UID（目标 UID 可以是 8 字节完整 UID 或 2 字节短 UID） */
    fun uidMatches(shortUid: String, targetUid: String): Boolean {
        if (targetUid.length == 4) {
            return shortUid.equals(targetUid, ignoreCase = true)
        }
        return targetUid.take(4).equals(shortUid, ignoreCase = true)
    }

    // --- 辅助工具方法 ---

    fun formatUidDisplay(uid: String): String =
        uid.chunked(2).joinToString(":") { it.uppercase() }

    /** 校验 2 字节通知响应 [Seq][Status] 是否为成功 */
    fun isNotificationSuccess(data: ByteArray): Boolean {
        val status = when (data.size) {
            2 -> data[1].toInt() and 0xFF
            else -> data.firstOrNull()?.toInt()?.and(0xFF) ?: 0xFF
        }
        return status == STATUS_SUCCESS
    }

    /** 从通知帧提取状态字节 */
    fun extractStatus(data: ByteArray): Int = when (data.size) {
        2 -> data[1].toInt() and 0xFF
        else -> data.firstOrNull()?.toInt()?.and(0xFF) ?: 0xFF
    }

    /** 从通知帧提取序号字节 */
    fun extractSeq(data: ByteArray): Int = when (data.size) {
        2 -> data[0].toInt() and 0xFF
        else -> 0
    }

    fun statusDescription(status: Int): String = when (status) {
        STATUS_SUCCESS -> "成功"
        STATUS_FAILED -> "失败"
        STATUS_AES_DECRYPT_ERROR -> "AES解密错误"
        STATUS_CRC_ERROR -> "CRC校验错误"
        STATUS_UNKNOWN -> "未知指令"
        BIND_VERIFY_OK -> "绑定验证成功"
        BIND_VERIFY_FAIL -> "绑定验证失败"
        else -> "未知状态(0x%02X)".format(status)
    }

    // --- 协议版本兼容扩展 ---

    /** 已知协议版本 */
    enum class ProtocolVersion(val code: Int) {
        V1(1);  // 当前唯一版本，后续可扩展 V2(2) 等

        companion object {
            fun fromCode(code: Int): ProtocolVersion =
                entries.firstOrNull { it.code == code } ?: V1
        }
    }

    /** 协议版本对应的行为差异接口 */
    interface ProtocolHandler {
        val version: ProtocolVersion

        /** 解析制造商数据（不同版本字段布局可能不同） */
        fun parseManufacturerData(record: ScanRecord): ParsedAdData?

        /** 构建指定指令帧 */
        fun buildCommandFrame(cmdId: Byte, action: Byte, aesKey: ByteArray): ByteArray

        /** 构建心跳帧 */
        fun buildHeartbeatFrame(aesKey: ByteArray): ByteArray

        /** 构建解绑帧 */
        fun buildUnbindFrame(aesKey: ByteArray): ByteArray
    }

    /** 默认 V1 协议处理器 */
    private object V1Handler : ProtocolHandler {
        override val version = ProtocolVersion.V1

        override fun parseManufacturerData(record: ScanRecord): ParsedAdData? =
            YuiTagProtocol.parseManufacturerData(record)

        override fun buildCommandFrame(cmdId: Byte, action: Byte, aesKey: ByteArray): ByteArray =
            CryptoUtils.buildCommandFrame(cmdId, action, aesKey)

        override fun buildHeartbeatFrame(aesKey: ByteArray): ByteArray =
            CryptoUtils.buildHeartbeatFrame(aesKey)

        override fun buildUnbindFrame(aesKey: ByteArray): ByteArray =
            CryptoUtils.buildUnbindFrame(aesKey)
    }

    /** 根据协议版本获取对应的处理器 */
    fun getHandler(versionCode: Int): ProtocolHandler = when (ProtocolVersion.fromCode(versionCode)) {
        ProtocolVersion.V1 -> V1Handler
    }

    /** 当前 APP 支持的协议版本列表 */
    val supportedProtocolVersions: Set<Int> = setOf(ProtocolVersion.V1.code)

    /** 检查协议版本是否兼容 */
    fun isVersionSupported(versionCode: Int): Boolean =
        versionCode in supportedProtocolVersions

    /** 判断是否为低配版 Sparks 设备 */
    fun isSparksDevice(name: String?): Boolean =
        name?.startsWith(DEVICE_NAME_PREFIX_SPARKS) == true

    // --- OTA 平台与支持能力识别 ---

    enum class OtaPlatform { SSV5, NCS, UNKNOWN }

    /** 根据固件平台标识判定 OTA 方案：SSv5 为 Bg22 本地 OTA，NCS 为 nRF MCUmgr SMP */
    fun otaPlatformOf(firmwarePlatform: String): OtaPlatform =
        when (firmwarePlatform.trim().uppercase()) {
            "SSV5" -> OtaPlatform.SSV5
            "NCS" -> OtaPlatform.NCS
            else -> OtaPlatform.UNKNOWN
        }

    /** 不支持 OTA 的产品型号（Sparks 低配版，型号 YLS05N） */
    private val OTA_UNSUPPORTED_MODELS = setOf("YLS05N")

    /** 是否支持 OTA：仅按产品型号判定；型号未知时默认支持（兼容存量设备） */
    fun supportsOta(productModel: String): Boolean =
        productModel.trim().uppercase() !in OTA_UNSUPPORTED_MODELS
}

