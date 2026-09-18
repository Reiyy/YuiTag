package com.yuitag.finder.data.local

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

    @Query("SELECT * FROM location_history WHERE deviceUid = :deviceUid ORDER BY timestamp DESC")
    fun getByDeviceUid(deviceUid: String): Flow<List<LocationEntity>>

    @Query("SELECT * FROM location_history WHERE deviceUid = :deviceUid ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestByDeviceUid(deviceUid: String): LocationEntity?

    @Query("SELECT COUNT(*) FROM location_history WHERE deviceUid = :deviceUid AND timestamp = :timestamp")
    suspend fun existsByTimestamp(deviceUid: String, timestamp: Long): Int

    @Query("DELETE FROM location_history WHERE deviceUid = :deviceUid")
    suspend fun deleteByDeviceUid(deviceUid: String)
}
