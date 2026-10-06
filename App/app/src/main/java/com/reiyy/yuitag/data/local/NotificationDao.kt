package com.reiyy.yuitag.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface NotificationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(notification: NotificationEntity): Long

    @Query("SELECT * FROM notifications ORDER BY timestamp DESC, id DESC")
    fun getAllFlow(): Flow<List<NotificationEntity>>

    @Query("SELECT * FROM notifications ORDER BY timestamp DESC, id DESC")
    suspend fun getAll(): List<NotificationEntity>

    @Query("SELECT COUNT(*) FROM notifications WHERE isRead = 0")
    fun getUnreadCountFlow(): Flow<Int>

    @Query("UPDATE notifications SET isRead = 1 WHERE id = :id")
    suspend fun markAsRead(id: Long)

    @Query("UPDATE notifications SET isRead = 1 WHERE isRead = 0")
    suspend fun markAllAsRead()

    @Query("DELETE FROM notifications WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM notifications")
    suspend fun deleteAll()

    @Query("DELETE FROM notifications WHERE isRead = 1")
    suspend fun deleteAllRead()

    @Query("""
        DELETE FROM notifications 
        WHERE id NOT IN (
            SELECT id FROM notifications 
            ORDER BY timestamp DESC, id DESC 
            LIMIT :maxCount
        )
    """)
    suspend fun trimToMaxCount(maxCount: Int)
}
