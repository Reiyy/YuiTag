package com.yuitag.finder.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.yuitag.finder.data.YuiTagDevice

/**
 * Room 实体：已绑定的 YuiTag 设备
 */
@Entity(tableName = "devices")
@TypeConverters(Converters::class)
data class DeviceEntity(
    @PrimaryKey
    val uid: String,                    // 短ID (4 hex chars), 扫描匹配用
    val serialNumber: String = "",      // 完整序列号 12 bytes = 24 hex chars，蓝牙 DIS 读取
    val customName: String,             // 用户自定义名称
    val deviceType: String = "自定义",  // 设备类型标签
    val customIcon: String = "",        // 自定义类型时的 unicode 图标（emoji/符号）
    val themePath: String = "",         // 主题包根目录路径，空=使用默认样式
    val protocolVersion: Int = 0,       // 协议版本
    val firmwareVersion: String = "",   // 固件版本（原始含|分隔的平台信息）
    val firmwarePlatform: String = "",  // 平台信息（从 FW_VERSION 拆分）
    val hardwareVersion: String = "",   // 硬件版本
    val manufacturer: String = "",      // 厂商信息
    val productName: String = "",       // 产品名称（从 MODEL_NUMBER 拆分）
    val productModel: String = "",      // 产品型号（从 MODEL_NUMBER 拆分）
    val aesKey: ByteArray? = null,      // AES-128密钥 (绑定后存储)
    val boundAt: Long = System.currentTimeMillis(),  // 绑定时间戳
    // 广播与网络配置
    val broadcastName: String = "",           // 设备广播名称
    val advIntervalNormal: Int? = null,        // 正常模式广播间隔 (ms)，null=未读取
    val advIntervalFind: Int? = null,          // 查找模式广播间隔 (ms)，null=未读取
    val findmyEnabled: Boolean? = null,        // Find My 网络开关，null=未读取
    val findmySupported: Boolean? = null,       // Find My 支持状态，null=未知（默认支持）
    val findmySeed: ByteArray? = null,        // Find My 种子密钥 (32 字节)
    val findmyPeriodSec: Int? = null,          // Find My 轮换周期 (秒)，null=未读取
    val macAddress: String = "",               // BLE MAC 地址 (手动导入用)
    val deviceName: String = "",               // 设备名称 (DIS GATT)
    val sortOrder: Int = 0,                    // 用户自定义排序值
    val lastSeen: Long = 0,                    // 最近在线时间戳
    val lastRssi: Int = 0,                     // 最近一次广播 RSSI
    val battery: Int = -1                      // 最近一次广播电量（%，-1=未知）
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DeviceEntity) return false
        return uid == other.uid &&
            serialNumber == other.serialNumber &&
            customName == other.customName &&
            deviceType == other.deviceType &&
            customIcon == other.customIcon &&
            themePath == other.themePath &&
            protocolVersion == other.protocolVersion &&
            firmwareVersion == other.firmwareVersion &&
            firmwarePlatform == other.firmwarePlatform &&
            hardwareVersion == other.hardwareVersion &&
            manufacturer == other.manufacturer &&
            productName == other.productName &&
            productModel == other.productModel &&
            boundAt == other.boundAt &&
            broadcastName == other.broadcastName &&
            advIntervalNormal == other.advIntervalNormal &&
            advIntervalFind == other.advIntervalFind &&
            findmyEnabled == other.findmyEnabled &&
            findmySupported == other.findmySupported &&
            findmyPeriodSec == other.findmyPeriodSec &&
            macAddress == other.macAddress &&
            deviceName == other.deviceName &&
            sortOrder == other.sortOrder &&
            lastSeen == other.lastSeen &&
            lastRssi == other.lastRssi &&
            battery == other.battery &&
            bytesEq(aesKey, other.aesKey) &&
            bytesEq(findmySeed, other.findmySeed)
    }

    override fun hashCode(): Int {
        var result = uid.hashCode()
        result = 31 * result + serialNumber.hashCode()
        result = 31 * result + customName.hashCode()
        result = 31 * result + deviceType.hashCode()
        result = 31 * result + customIcon.hashCode()
        result = 31 * result + themePath.hashCode()
        result = 31 * result + protocolVersion
        result = 31 * result + firmwareVersion.hashCode()
        result = 31 * result + firmwarePlatform.hashCode()
        result = 31 * result + hardwareVersion.hashCode()
        result = 31 * result + manufacturer.hashCode()
        result = 31 * result + productName.hashCode()
        result = 31 * result + productModel.hashCode()
        result = 31 * result + boundAt.hashCode()
        result = 31 * result + broadcastName.hashCode()
        result = 31 * result + (advIntervalNormal ?: 0)
        result = 31 * result + (advIntervalFind ?: 0)
        result = 31 * result + (findmyEnabled?.hashCode() ?: 0)
        result = 31 * result + (findmySupported?.hashCode() ?: 0)
        result = 31 * result + (findmyPeriodSec ?: 0)
        result = 31 * result + macAddress.hashCode()
        result = 31 * result + deviceName.hashCode()
        result = 31 * result + sortOrder
        result = 31 * result + lastSeen.hashCode()
        result = 31 * result + lastRssi
        result = 31 * result + battery
        result = 31 * result + (aesKey?.contentHashCode() ?: 0)
        result = 31 * result + (findmySeed?.contentHashCode() ?: 0)
        return result
    }
}

fun DeviceEntity.toDomainModel(): YuiTagDevice = YuiTagDevice(
    uid = uid,
    // name 恒为设备名（GATT/DIS），customName 仅为用户自定义展示名
    name = deviceName.ifEmpty { "YuiTag_${uid.take(4)}" },
    rssi = lastRssi,
    txPower = 0,
    version = protocolVersion,
    battery = battery,
    isBound = true,
    aesKey = aesKey,
    customName = customName,
    deviceType = DeviceType.fromLabel(deviceType),
    customIcon = customIcon,
    themePath = themePath,
    firmwareVersion = firmwareVersion,
    firmwarePlatform = firmwarePlatform,
    hardwareVersion = hardwareVersion,
    manufacturer = manufacturer,
    productName = productName,
    productModel = productModel,
    serialNumber = serialNumber,
    broadcastName = broadcastName,
    advIntervalNormal = advIntervalNormal,
    advIntervalFind = advIntervalFind,
    findmyEnabled = findmyEnabled,
    findmySupported = findmySupported,
    findmySeed = findmySeed,
    findmyPeriodSec = findmyPeriodSec,
    macAddress = macAddress,
    deviceName = deviceName,
    lastSeen = lastSeen,
    boundAt = boundAt
)

/** ByteArray 内容比较（null 安全） */
private fun bytesEq(a: ByteArray?, b: ByteArray?): Boolean =
    (a == null && b == null) || (a != null && b != null && a.contentEquals(b))
