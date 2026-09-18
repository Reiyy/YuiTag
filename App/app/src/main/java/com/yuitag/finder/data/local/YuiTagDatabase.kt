package com.yuitag.finder.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [DeviceEntity::class, LocationEntity::class],
    version = 1,
    exportSchema = true
)
@androidx.room.TypeConverters(Converters::class)
abstract class YuiTagDatabase : RoomDatabase() {

    abstract fun deviceDao(): DeviceDao
    abstract fun locationDao(): LocationDao

    companion object {
        @Volatile
        private var INSTANCE: YuiTagDatabase? = null

        fun getInstance(context: Context): YuiTagDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    YuiTagDatabase::class.java,
                    "yuitag.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
