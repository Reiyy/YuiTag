package com.reiyy.yuitag.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

import com.reiyy.yuitag.ble.FileLog
import java.io.File
import java.io.RandomAccessFile

@Database(
    entities = [DeviceEntity::class, LocationEntity::class, NotificationEntity::class],
    // ⚠️ 数据库升级规范：实体字段变动时必须递增此版本号，严禁在当前版本硬改！
    // 后续升级示例（v4 -> v5）：version = 5, autoMigrations = [AutoMigration(from = 4, to = 5)]
    version = 5,
    autoMigrations = [
        androidx.room.AutoMigration(from = 2, to = 3),
        androidx.room.AutoMigration(from = 3, to = 4),
        androidx.room.AutoMigration(from = 4, to = 5)
    ],
    exportSchema = true
)
@androidx.room.TypeConverters(Converters::class)
abstract class YuiTagDatabase : RoomDatabase() {

    abstract fun deviceDao(): DeviceDao
    abstract fun locationDao(): LocationDao
    abstract fun notificationDao(): NotificationDao

    companion object {
        const val DATABASE_NAME = "yuitag.db"
        const val DATABASE_VERSION = 5

        @Volatile
        private var INSTANCE: YuiTagDatabase? = null

        /**
         * 手动迁移实例（作为 AutoMigration 的参考/显式后备）：
         * 为 devices 表补充 coreVersion 字段
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                FileLog.i("DB", "执行数据库手动迁移: v1 -> v2 (ADD COLUMN coreVersion)")
                db.execSQL("ALTER TABLE devices ADD COLUMN coreVersion TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * 手动迁移实例：为 devices 表补充 separationAlertEnabled 字段
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                FileLog.i("DB", "执行数据库手动迁移: v2 -> v3 (ADD COLUMN separationAlertEnabled)")
                db.execSQL("ALTER TABLE devices ADD COLUMN separationAlertEnabled INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * 手动迁移实例：为 devices 表补充 txPower 与 findmyTxPower 字段
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                FileLog.i("DB", "执行数据库手动迁移: v3 -> v4 (ADD COLUMN txPower, findmyTxPower)")
                db.execSQL("ALTER TABLE devices ADD COLUMN txPower INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE devices ADD COLUMN findmyTxPower INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * 手动迁移实例：新增 notifications 表与索引
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                FileLog.i("DB", "执行数据库手动迁移: v4 -> v5 (CREATE TABLE notifications)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `notifications` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `type` TEXT NOT NULL,
                        `level` TEXT NOT NULL,
                        `sender` TEXT NOT NULL,
                        `deviceUid` TEXT,
                        `title` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `isRead` INTEGER NOT NULL DEFAULT 0,
                        `actionRoute` TEXT,
                        `extraData` TEXT
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_notifications_timestamp` ON `notifications` (`timestamp`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_notifications_deviceUid` ON `notifications` (`deviceUid`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_notifications_isRead` ON `notifications` (`isRead`)")
            }
        }

        fun getInstance(context: Context): YuiTagDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context.applicationContext).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context): YuiTagDatabase {
            // 升级前安全检查：若检测到数据库文件版本落后，先制作文件级快照
            backupDatabaseIfUpgradeNeeded(context)

            val builder = Room.databaseBuilder(
                context,
                YuiTagDatabase::class.java,
                DATABASE_NAME
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .fallbackToDestructiveMigration()
                .addCallback(object : Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        super.onOpen(db)
                        FileLog.i("DB", "数据库已就绪 (v${db.version})")
                        cleanUpgradeBackup(context)
                    }
                })

            return builder.build()
        }

        /**
         * 升级前轻量快照：在 SQLite 打开/升级前，若检测到版本即将升级，
         * 复制一份 yuitag.db 到 yuitag.db.upgrade_bak。
         * 升级成功后在 onOpen 中清理；若升级异常崩溃，保留备份文件供数据抢救。
         */
        private fun backupDatabaseIfUpgradeNeeded(context: Context) {
            try {
                val dbFile = context.getDatabasePath(DATABASE_NAME)
                if (!dbFile.exists() || dbFile.length() < 64) return

                val currentVersion = readUserVersion(dbFile)
                if (currentVersion in 1 until DATABASE_VERSION) {
                    FileLog.i("DB", "检测到数据库升级 (v$currentVersion -> v$DATABASE_VERSION)，创建安全快照...")
                    val parent = dbFile.parentFile ?: return
                    dbFile.copyTo(File(parent, "$DATABASE_NAME.upgrade_bak"), overwrite = true)

                    val walFile = File(parent, "$DATABASE_NAME-wal")
                    if (walFile.exists() && walFile.length() > 0) {
                        walFile.copyTo(File(parent, "$DATABASE_NAME-wal.upgrade_bak"), overwrite = true)
                    }
                    val shmFile = File(parent, "$DATABASE_NAME-shm")
                    if (shmFile.exists() && shmFile.length() > 0) {
                        shmFile.copyTo(File(parent, "$DATABASE_NAME-shm.upgrade_bak"), overwrite = true)
                    }
                    FileLog.i("DB", "升级前快照创建成功")
                }
            } catch (e: Exception) {
                FileLog.w("DB", "创建升级前快照失败 (不阻断升级流程): ${e.message}")
            }
        }

        /** 读取 SQLite Header (byte 60..63) 记录的 user_version */
        private fun readUserVersion(dbFile: File): Int {
            return try {
                RandomAccessFile(dbFile, "r").use { raf ->
                    raf.seek(60)
                    raf.readInt()
                }
            } catch (_: Exception) {
                0
            }
        }

        private fun cleanUpgradeBackup(context: Context) {
            try {
                val parent = context.getDatabasePath(DATABASE_NAME).parentFile ?: return
                File(parent, "$DATABASE_NAME.upgrade_bak").delete()
                File(parent, "$DATABASE_NAME-wal.upgrade_bak").delete()
                File(parent, "$DATABASE_NAME-shm.upgrade_bak").delete()
            } catch (_: Exception) {}
        }
    }
}
