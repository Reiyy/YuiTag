package com.reiyy.yuitag.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionUtilsTest {

    @Test
    fun testParseSemVer() {
        val v1 = VersionUtils.parseSemVer("1.0.0")
        assertNotNull(v1)
        assertEquals(listOf(1, 0, 0), v1!!.numbers)
        assertNull(v1.preRelease)

        val v2 = VersionUtils.parseSemVer("v1.0.0-beta.2")
        assertNotNull(v2)
        assertEquals(listOf(1, 0, 0), v2!!.numbers)
        assertEquals("beta.2", v2.preRelease)

        val v3 = VersionUtils.parseSemVer("V2.1.3-rc.1+build.2024")
        assertNotNull(v3)
        assertEquals(listOf(2, 1, 3), v3!!.numbers)
        assertEquals("rc.1", v3.preRelease)
        assertEquals("build.2024", v3.buildMetadata)

        // 兼容下划线命名（如 v1.0.0-beta_1, v1.0.0_beta.1）
        val v4 = VersionUtils.parseSemVer("v1.0.0-beta_1")
        assertNotNull(v4)
        assertEquals(listOf(1, 0, 0), v4!!.numbers)
        assertEquals("beta_1", v4.preRelease)

        val v5 = VersionUtils.parseSemVer("v1.0.0_beta.2")
        assertNotNull(v5)
        assertEquals(listOf(1, 0, 0), v5!!.numbers)
        assertEquals("beta.2", v5.preRelease)
    }

    @Test
    fun testIsPrerelease() {
        assertTrue(VersionUtils.isPrerelease("1.0.0-beta.2"))
        assertTrue(VersionUtils.isPrerelease("v1.0.0-beta.1"))
        assertTrue(VersionUtils.isPrerelease("1.0.0-alpha"))
        assertTrue(VersionUtils.isPrerelease("1.0.0-rc.1"))
        assertTrue(VersionUtils.isPrerelease("1.0.0-dev"))

        assertFalse(VersionUtils.isPrerelease("1.0.0"))
        assertFalse(VersionUtils.isPrerelease("v1.0.0"))
        assertFalse(VersionUtils.isPrerelease("V2.1.0"))
    }

    @Test
    fun testIsNewerVersion_prereleaseToPrerelease() {
        // beta.2 vs beta.1
        assertTrue(VersionUtils.isNewerVersion("v1.0.0-beta.2", "1.0.0-beta.1"))
        assertFalse(VersionUtils.isNewerVersion("v1.0.0-beta.1", "1.0.0-beta.2"))

        // beta.10 vs beta.9 (numeric precedence inside prerelease)
        assertTrue(VersionUtils.isNewerVersion("v1.0.0-beta.10", "1.0.0-beta.9"))
        assertFalse(VersionUtils.isNewerVersion("v1.0.0-beta.9", "1.0.0-beta.10"))

        // alpha vs beta
        assertTrue(VersionUtils.isNewerVersion("1.0.0-beta.1", "1.0.0-alpha.5"))

        // beta vs rc
        assertTrue(VersionUtils.isNewerVersion("1.0.0-rc.1", "1.0.0-beta.2"))

        // 下划线分隔预发布版本比较
        assertTrue(VersionUtils.isNewerVersion("v1.0.0-beta_2", "v1.0.0-beta_1"))
        assertTrue(VersionUtils.isNewerVersion("v1.0.0_beta_2", "v1.0.0_beta_1"))
        assertTrue(VersionUtils.isNewerVersion("v1.0.0-beta.2", "v1.0.0-beta_1"))
    }

    @Test
    fun testIsNewerVersion_prereleaseAndStable() {
        // 正式版发布后，应高于同数字版本的 beta 版
        assertTrue(VersionUtils.isNewerVersion("1.0.0", "1.0.0-beta.2"))
        assertTrue(VersionUtils.isNewerVersion("v1.0.0", "1.0.0-rc.2"))

        // 正式版用户不应“降级”提醒到同版本的 beta
        assertFalse(VersionUtils.isNewerVersion("1.0.0-beta.2", "1.0.0"))

        // 更高数字版本的 beta 应该高于较低数字版本的正式版
        assertTrue(VersionUtils.isNewerVersion("1.0.1-beta.1", "1.0.0"))
        assertTrue(VersionUtils.isNewerVersion("1.1.0-alpha.1", "1.0.0"))

        // 相同版本
        assertFalse(VersionUtils.isNewerVersion("1.0.0-beta.2", "1.0.0-beta.2"))
        assertFalse(VersionUtils.isNewerVersion("1.0.0", "1.0.0"))
    }
}
