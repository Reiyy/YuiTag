package com.yuitag.finder.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [DeviceEntity::class, LocationEntity::class],
    version = 2,
    exportSchema = true
)
@androidx.room.TypeConverters(Converters::class)
abstract class YuiTagDatabase : RoomDatabase() {

    abstract fun deviceDao(): DeviceDao
    abstract fun locationDao(): LocationDao

    companion object {
        @Volatile
        private var INSTANCE: YuiTagDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE devices ADD COLUMN coreVersion TEXT NOT NULL DEFAULT ''")
            }
        }

        fun getInstance(context: Context): YuiTagDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    YuiTagDatabase::class.java,
                    "yuitag.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
