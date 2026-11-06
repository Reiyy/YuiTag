package com.reiyy.yuitag.util

/**
 * 语义化版本（SemVer）解析与比较工具。
 * 兼容带 "v"/"V" 前缀，支持预发布后缀（如 -beta, -beta.1, -rc.1, -alpha 等）及构建元数据（+build）。
 *
 * 规范参考：https://semver.org/lang/zh-CN/
 * 比较规则：
 * 1. 优先比较 major.minor.patch 数字段；
 * 2. 数字段相同的情况下，正式发布版（无预发布后缀）优先于任何预发布版（例如 1.0.0 > 1.0.0-beta.2）；
 * 3. 预发布段按照点号/标识符比较，纯数字按数值大小比较，字母按 ASCII 字典序比较。
 */
object VersionUtils {

    data class SemVer(
        val numbers: List<Int>,
        val preRelease: String? = null,
        val buildMetadata: String? = null
    ) : Comparable<SemVer> {

        val isPrerelease: Boolean get() = !preRelease.isNullOrEmpty()

        override fun compareTo(other: SemVer): Int {
            val maxLen = maxOf(numbers.size, other.numbers.size)
            for (i in 0 until maxLen) {
                val a = numbers.getOrElse(i) { 0 }
                val b = other.numbers.getOrElse(i) { 0 }
                if (a != b) return a.compareTo(b)
            }

            // 正式版优先级高于同数字版本的预发布版（例如 1.0.0 > 1.0.0-beta.2）
            if (preRelease == null && other.preRelease != null) return 1
            if (preRelease != null && other.preRelease == null) return -1
            if (preRelease == null && other.preRelease == null) return 0

            // 均含有预发布后缀
            val aTokens = tokenizePreRelease(preRelease)
            val bTokens = tokenizePreRelease(other.preRelease)
            val minLen = minOf(aTokens.size, bTokens.size)
            for (i in 0 until minLen) {
                val at = aTokens[i]
                val bt = bTokens[i]
                if (at != bt) {
                    val aNum = at.toLongOrNull()
                    val bNum = bt.toLongOrNull()
                    return when {
                        aNum != null && bNum != null -> aNum.compareTo(bNum)
                        aNum != null && bNum == null -> -1 // 数字标识符低于非数字标识符优先级
                        aNum == null && bNum != null -> 1
                        else -> at.compareTo(bt)
                    }
                }
            }
            return aTokens.size.compareTo(bTokens.size)
        }
    }

    private val SEMVER_REGEX = Regex(
        """^[vV]?(\d+(?:\.\d+)*)(?:[-_]([0-9A-Za-z._-]+))?(?:\+([0-9A-Za-z._-]+))?$"""
    )

    private val TOKEN_SPLIT_REGEX = Regex("""[._-]|(?<=[a-zA-Z])(?=\d)|(?<=\d)(?=[a-zA-Z])""")

    private fun tokenizePreRelease(pre: String?): List<String> {
        if (pre.isNullOrEmpty()) return emptyList()
        return pre.split(TOKEN_SPLIT_REGEX).filter { it.isNotEmpty() }
    }

    /**
     * 解析版本字符串为 SemVer 对象。
     */
    fun parseSemVer(text: String): SemVer? {
        val trimmed = text.trim()
        val match = SEMVER_REGEX.matchEntire(trimmed) ?: return null
        val numbers = match.groupValues[1].split(".").mapNotNull { it.toIntOrNull() }
        if (numbers.isEmpty()) return null
        val preRelease = match.groupValues.getOrNull(2)?.ifEmpty { null }
        val buildMetadata = match.groupValues.getOrNull(3)?.ifEmpty { null }
        return SemVer(numbers, preRelease, buildMetadata)
    }

    /**
     * 判断指定版本字符串是否为预发布/开发版本（带 beta, alpha, rc 等后缀）。
     */
    fun isPrerelease(text: String): Boolean {
        val semVer = parseSemVer(text)
        if (semVer != null) return semVer.isPrerelease
        val lower = text.lowercase()
        return lower.contains("-") || lower.contains("beta") || lower.contains("alpha") || lower.contains("rc") || lower.contains("dev")
    }

    /**
     * 保持与历史接口兼容：解析纯数字段。
     */
    fun parseVersion(text: String): List<Int>? {
        return parseSemVer(text)?.numbers
    }

    /**
     * candidate 语义版本高于 current 时返回 true。
     */
    fun isNewerVersion(candidate: String, current: String): Boolean {
        val c = parseSemVer(candidate) ?: return false
        val cur = parseSemVer(current) ?: return false
        return c > cur
    }

    /**
     * 比较两个版本字符串大小，用于排序（a > b 返回 >0，a < b 返回 <0，相等返回 0）。
     */
    fun compare(a: String, b: String): Int {
        val sa = parseSemVer(a)
        val sb = parseSemVer(b)
        if (sa == null && sb == null) return a.compareTo(b)
        if (sa == null) return -1
        if (sb == null) return 1
        return sa.compareTo(sb)
    }
}
