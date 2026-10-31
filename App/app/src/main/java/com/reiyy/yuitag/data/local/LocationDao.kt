package com.reiyy.yuitag.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LocationDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(locations: List<LocationEntity>)

    /** 补全已存在记录的密钥信息，仅更新缺失字段 */
    @Query("""
        UPDATE location_history
        SET periodIndex = :periodIndex, hashedKey = :hashedKey
        WHERE deviceUid = :deviceUid AND timestamp = :timestamp
          AND (periodIndex IS NULL OR hashedKey IS NULL)
    """)
    suspend fun backfillKeyInfo(
        deviceUid: String,
        timestamp: Long,
        periodIndex: Long?,
        hashedKey: String?
    )

    /** 补全已存在记录的地址信息，仅更新缺失字段 */
    @Query("""
        UPDATE location_history
        SET address = :address, addressDetail = :addressDetail
        WHERE deviceUid = :deviceUid AND timestamp = :timestamp
          AND (address IS NULL OR address = '')
    """)
    suspend fun backfillAddress(
        deviceUid: String,
        timestamp: Long,
        address: String,
        addressDetail: AddressComponents?
    )

    /** 查询某设备所有地址未解析（address 为 null 或空）的记录，按时间倒序排列 */
    @Query("""
        SELECT * FROM location_history
        WHERE deviceUid = :deviceUid AND (address IS NULL OR address = '')
        ORDER BY timestamp DESC
    """)
    suspend fun getUnresolvedLocations(deviceUid: String): List<LocationEntity>

    /** 统计某设备未解析地址的记录数 */
    @Query("""
        SELECT COUNT(*) FROM location_history
        WHERE deviceUid = :deviceUid AND (address IS NULL OR address = '')
    """)
    suspend fun getUnresolvedCount(deviceUid: String): Int

    /** 更新单条记录的地址信息 */
    @Query("""
        UPDATE location_history
        SET address = :address, addressDetail = :addressDetail
        WHERE id = :id
    """)
    suspend fun updateAddress(id: Long, address: String, addressDetail: AddressComponents?)

    /** 批量更新指定 ID 列表的地址信息 */
    @Query("""
        UPDATE location_history
        SET address = :address, addressDetail = :addressDetail
        WHERE id IN (:ids)
    """)
    suspend fun updateAddressByIds(ids: List<Long>, address: String, addressDetail: AddressComponents?)

    @Query("SELECT * FROM location_history WHERE deviceUid = :deviceUid ORDER BY timestamp DESC")
    fun getByDeviceUid(deviceUid: String): Flow<List<LocationEntity>>

    @Query("SELECT * FROM location_history WHERE deviceUid = :deviceUid ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestByDeviceUid(deviceUid: String): LocationEntity?

    @Query("SELECT COUNT(*) FROM location_history WHERE deviceUid = :deviceUid AND timestamp = :timestamp")
    suspend fun existsByTimestamp(deviceUid: String, timestamp: Long): Int

    @Query("DELETE FROM location_history WHERE deviceUid = :deviceUid")
    suspend fun deleteByDeviceUid(deviceUid: String)
}
