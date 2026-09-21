package com.yuitag.finder.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 数据库自动化迁移测试套件：
 * 验证旧版本数据库升级到新版本时，关键业务凭据（UID、AES 密钥、配置项）无损，
 * 且新增字段与表结构的 Schema 校验完全通过。
 */
@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {

    private val testDb = "migration-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        YuiTagDatabase::class.java,
        listOf(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun testMigration1To2() {
        // 1. 创建 v1 数据库并写入真实测试设备数据
        var db = helper.createDatabase(testDb, 1).apply {
            execSQL(
                """
                INSERT INTO devices (
                    uid, serialNumber, customName, deviceType, customIcon, themePath,
                    protocolVersion, firmwareVersion, firmwarePlatform, hardwareVersion,
                    manufacturer, productName, productModel, aesKey, boundAt,
                    broadcastName, macAddress, deviceName, sortOrder, lastSeen, lastRssi, battery
                ) VALUES (
                    'TEST', '112233445566778899AABBCC', '我的钥匙', '钥匙', '🔑', '',
                    1, '1.0.0', 'EFR32BG22', 'REV_A',
                    'YuiTag', 'YuiTag_P', 'YT-01', X'0102030405060708090A0B0C0D0E0F10', 1700000000000,
                    'YuiTag_TEST', 'AA:BB:CC:DD:EE:FF', 'YuiTag_TEST', 0, 1700000000000, -65, 95
                )
                """.trimIndent()
            )
            close()
        }

        // 2. 运行迁移并校验 Schema 与数据完整性
        db = helper.runMigrationsAndValidate(testDb, 2, true, YuiTagDatabase.MIGRATION_1_2)

        // 3. 校验旧数据未丢失，新字段 coreVersion 默认填充为空字符串
        val cursor = db.query("SELECT uid, customName, coreVersion, aesKey FROM devices WHERE uid = 'TEST'")
        assertNotNull(cursor)
        cursor.use {
            assertEquals(true, it.moveToFirst())
            assertEquals("TEST", it.getString(0))
            assertEquals("我的钥匙", it.getString(1))
            assertEquals("", it.getString(2)) // 校验 coreVersion 默认值正确
            assertNotNull(it.getBlob(3))      // 校验 aesKey 二进制密钥未丢失
        }
    }
}
