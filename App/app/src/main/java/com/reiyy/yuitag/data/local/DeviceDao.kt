package com.reiyy.yuitag.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DeviceDao {
    /** 插入或替换设备 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(device: DeviceEntity)

    /** 备份恢复：设备不存在时插入（冲突忽略，不覆盖既有数据） */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(device: DeviceEntity)

    /** 备份恢复：设备已存在时按主键整体更新（不触发位置历史级联删除） */
    @Update
    suspend fun update(device: DeviceEntity)

    /** 根据 UID 查询设备 */
    @Query("SELECT * FROM devices WHERE uid = :uid")
    suspend fun getByUid(uid: String): DeviceEntity?

    /** 获取所有已绑定设备（Flow 实时观察）— 自定义排序优先，新增设备默认排最后 */
    @Query("SELECT * FROM devices ORDER BY sortOrder ASC, boundAt ASC")
    fun getAllFlow(): Flow<List<DeviceEntity>>

    /** 获取所有已绑定设备（同步） */
    @Query("SELECT * FROM devices ORDER BY sortOrder ASC, boundAt ASC")
    suspend fun getAll(): List<DeviceEntity>

    /** 获取当前最大排序值（新增设备默认排最后） */
    @Query("SELECT COALESCE(MAX(sortOrder), 0) FROM devices")
    suspend fun maxSortOrder(): Int

    /** 更新设备排序值（拖拽排序持久化） */
    @Query("UPDATE devices SET sortOrder = :order WHERE uid = :uid")
    suspend fun updateSortOrder(uid: String, order: Int)

    /** 更新设备自定义名称和类型 */
    @Query("UPDATE devices SET customName = :name, deviceType = :type, customIcon = :icon WHERE uid = :uid")
    suspend fun updateNameAndType(uid: String, name: String, type: String, icon: String)

    /** 更新设备广播配置（广播名称与广播间隔） */
    @Query("UPDATE devices SET broadcastName = :broadcastName, advIntervalNormal = :normalMs, advIntervalFind = :findMs WHERE uid = :uid")
    suspend fun updateBroadcastConfig(uid: String, broadcastName: String, normalMs: Int, findMs: Int)

    /** 更新 Find My 网络开关 */
    @Query("UPDATE devices SET findmyEnabled = :enabled WHERE uid = :uid")
    suspend fun updateFindMyEnabled(uid: String, enabled: Boolean)

    /** 更新 Find My 支持状态（false=设备不支持该功能） */
    @Query("UPDATE devices SET findmySupported = :supported WHERE uid = :uid")
    suspend fun updateFindMySupport(uid: String, supported: Boolean)

    /** 更新 Find My 种子密钥 */
    @Query("UPDATE devices SET findmySeed = :seed WHERE uid = :uid")
    suspend fun updateFindMySeed(uid: String, seed: ByteArray)

    /** 更新 Find My 轮换周期 */
    @Query("UPDATE devices SET findmyPeriodSec = :seconds WHERE uid = :uid")
    suspend fun updateFindMyPeriod(uid: String, seconds: Int)

    /** 更新 GATT 读取的设备信息（DIS）并持久化设备名 */
    @Query("UPDATE devices SET deviceName = :deviceName, serialNumber = :serialNumber, " +
        "firmwareVersion = :firmwareVersion, firmwarePlatform = :firmwarePlatform, " +
        "hardwareVersion = :hardwareVersion, manufacturer = :manufacturer, " +
        "productName = :productName, productModel = :productModel, coreVersion = :coreVersion WHERE uid = :uid")
    suspend fun updateDeviceReadInfo(
        uid: String,
        deviceName: String,
        serialNumber: String,
        firmwareVersion: String,
        firmwarePlatform: String,
        hardwareVersion: String,
        manufacturer: String,
        productName: String,
        productModel: String,
        coreVersion: String
    )

    /** 持久化设备 MAC 地址（供直接回连使用） */
    @Query("UPDATE devices SET macAddress = :mac WHERE uid = :uid")
    suspend fun updateMacAddress(uid: String, mac: String)

    /** 更新最近在线时间戳与最近广播 RSSI */
    @Query("UPDATE devices SET lastSeen = :lastSeen, lastRssi = :lastRssi WHERE uid = :uid")
    suspend fun updateLastSeen(uid: String, lastSeen: Long, lastRssi: Int)

    /** 更新最近一次广播电量（%，-1=未知） */
    @Query("UPDATE devices SET battery = :battery WHERE uid = :uid")
    suspend fun updateBattery(uid: String, battery: Int)

    /** 删除设备 */
    @Delete
    suspend fun delete(device: DeviceEntity)

    /** 根据 UID 删除 */
    @Query("DELETE FROM devices WHERE uid = :uid")
    suspend fun deleteByUid(uid: String)

    /** 设备数量 */
    @Query("SELECT COUNT(*) FROM devices")
    suspend fun count(): Int

    /** 更新主题包路径 */
    @Query("UPDATE devices SET themePath = :path WHERE uid = :uid")
    suspend fun updateThemePath(uid: String, path: String)
}
