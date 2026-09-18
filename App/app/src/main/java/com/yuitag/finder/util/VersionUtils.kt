package com.yuitag.finder.util

/**
 * x.y.z 语义化版本解析与比较工具。
 * 兼容带 "v"/"V" 前缀的发布标签（如 v0.2.1）。
 */
object VersionUtils {

    private val VERSION_REGEX = Regex("""(\d+)(?:\.(\d+))?(?:\.(\d+))?""")

    /**
     * 解析版本字符串为数字段列表（major, minor, patch, ...）。
     * 无法解析时返回 null。
     */
    fun parseVersion(text: String): List<Int>? {
        val match = VERSION_REGEX.find(text.trimStart('v', 'V')) ?: return null
        val groups = match.groupValues
        val parts = mutableListOf<Int>()
        for (i in 1 until groups.size) {
            val group = groups[i]
            if (group.isEmpty()) break
            parts.add(group.toInt())
        }
        return if (parts.isEmpty()) null else parts
    }

    /** candidate 语义版本高于 current 时返回 true（缺位按 0 补齐）。 */
    fun isNewerVersion(candidate: String, current: String): Boolean {
        val c = parseVersion(candidate) ?: return false
        val cur = parseVersion(current) ?: return false
        return compareVersion(c, cur) > 0
    }

    private fun compareVersion(a: List<Int>, b: List<Int>): Int {
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val av = a.getOrElse(i) { 0 }
            val bv = b.getOrElse(i) { 0 }
            if (av != bv) return av.compareTo(bv)
        }
        return 0
    }
}
