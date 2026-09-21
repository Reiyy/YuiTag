package com.reiyy.yuitag.data

import com.reiyy.yuitag.data.local.DeviceType

data class YuiTagDevice(
    val uid: String,
    val name: String,
    val rssi: Int,
    val txPower: Int,
    val version: Int,
    val battery: Int,
    val advInterval: Int = 0,
    val isBound: Boolean = false,
    val aesKey: ByteArray? = null,
    val customName: String = "",
    val deviceType: DeviceType = DeviceType.CUSTOM,
    val customIcon: String = "",
    val themePath: String = "",
    val firmwareVersion: String = "",
    val firmwarePlatform: String = "",  // 平台信息
    val hardwareVersion: String = "",
    val manufacturer: String = "",
    val productName: String = "",       // 产品名称
    val productModel: String = "",      // 产品型号
    val coreVersion: String = "",       // 核心版本 (0x2A28)
    val serialNumber: String = "",      // 完整序列号 (24 位十六进制字符)
    val lastSeen: Long = System.currentTimeMillis(),
    // 广播与网络配置
    val broadcastName: String = "",           // 设备广播名称
    val advIntervalNormal: Int? = null,        // 正常模式广播间隔 (ms)，null=未读取
    val advIntervalFind: Int? = null,          // 查找模式广播间隔 (ms)，null=未读取
    val findmyEnabled: Boolean? = null,        // Find My 网络开关，null=未读取
    val findmySupported: Boolean? = null,       // Find My 支持状态，null=未知（默认支持）
    val findmySeed: ByteArray? = null,        // Find My 种子密钥
    val findmyPeriodSec: Int? = null,          // Find My 轮换周期 (秒)，null=未读取
    val macAddress: String = "",               // BLE MAC 地址
    val deviceName: String = "",               // 设备名称 (DIS GATT)
    val boundAt: Long = 0                      // 绑定时间戳
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is YuiTagDevice) return false
        return uid == other.uid &&
            name == other.name &&
            rssi == other.rssi &&
            txPower == other.txPower &&
            version == other.version &&
            battery == other.battery &&
            advInterval == other.advInterval &&
            isBound == other.isBound &&
            customName == other.customName &&
            deviceType == other.deviceType &&
            customIcon == other.customIcon &&
            themePath == other.themePath &&
            firmwareVersion == other.firmwareVersion &&
            firmwarePlatform == other.firmwarePlatform &&
            hardwareVersion == other.hardwareVersion &&
            manufacturer == other.manufacturer &&
            productName == other.productName &&
            productModel == other.productModel &&
            coreVersion == other.coreVersion &&
            serialNumber == other.serialNumber &&
            lastSeen == other.lastSeen &&
            broadcastName == other.broadcastName &&
            advIntervalNormal == other.advIntervalNormal &&
            advIntervalFind == other.advIntervalFind &&
            findmyEnabled == other.findmyEnabled &&
            findmySupported == other.findmySupported &&
            findmyPeriodSec == other.findmyPeriodSec &&
            macAddress == other.macAddress &&
            deviceName == other.deviceName &&
            boundAt == other.boundAt &&
            bytesEq(aesKey, other.aesKey) &&
            bytesEq(findmySeed, other.findmySeed)
    }

    override fun hashCode(): Int {
        var result = uid.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + rssi
        result = 31 * result + txPower
        result = 31 * result + version
        result = 31 * result + battery
        result = 31 * result + advInterval
        result = 31 * result + isBound.hashCode()
        result = 31 * result + customName.hashCode()
        result = 31 * result + deviceType.hashCode()
        result = 31 * result + customIcon.hashCode()
        result = 31 * result + themePath.hashCode()
        result = 31 * result + firmwareVersion.hashCode()
        result = 31 * result + firmwarePlatform.hashCode()
        result = 31 * result + hardwareVersion.hashCode()
        result = 31 * result + manufacturer.hashCode()
        result = 31 * result + productName.hashCode()
        result = 31 * result + productModel.hashCode()
        result = 31 * result + coreVersion.hashCode()
        result = 31 * result + serialNumber.hashCode()
        result = 31 * result + lastSeen.hashCode()
        result = 31 * result + broadcastName.hashCode()
        result = 31 * result + (advIntervalNormal ?: 0)
        result = 31 * result + (advIntervalFind ?: 0)
        result = 31 * result + (findmyEnabled?.hashCode() ?: 0)
        result = 31 * result + (findmySupported?.hashCode() ?: 0)
        result = 31 * result + (findmyPeriodSec ?: 0)
        result = 31 * result + macAddress.hashCode()
        result = 31 * result + deviceName.hashCode()
        result = 31 * result + boundAt.hashCode()
        result = 31 * result + (aesKey?.contentHashCode() ?: 0)
        result = 31 * result + (findmySeed?.contentHashCode() ?: 0)
        return result
    }
}

fun formatUid(uid: String): String = uid.uppercase()

/** ByteArray 内容比较（null 安全） */
private fun bytesEq(a: ByteArray?, b: ByteArray?): Boolean =
    (a == null && b == null) || (a != null && b != null && a.contentEquals(b))
