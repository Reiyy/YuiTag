package com.yuitag.finder.ble

import android.content.Context
import android.content.SharedPreferences
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文件日志工具 — 按天分文件写入。
 * 文件路径: /sdcard/Android/data/com.yuitag.finder/files/yuitag_yyyy-MM-dd.log
 * 获取方式: adb pull /sdcard/Android/data/com.yuitag.finder/files/
 *
 * 分级: V / D / I / W / E。
 * V、D 级日志（含密钥等敏感调试信息）仅在"调试模式"开启时写入；
 * I、W、E 级始终写入。调试模式在 App 设置中运行时切换，实时生效。
 *
 * 自动清理: 超过 7 天的日志文件在初始化与每日轮转时删除。
 */
object FileLog {
    private const val PREF_NAME = "yuitag_settings"
    private const val KEY_DEBUG_MODE = "debug_mode_enabled"
    private const val FILE_PREFIX = "yuitag_"
    private const val FILE_EXT = ".log"
    private const val RETENTION_DAYS = 7L

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private var logDir: File? = null
    private var prefs: SharedPreferences? = null
    private var currentDate: String? = null
    private var writer: BufferedWriter? = null

    /** 必须在 Application 或 Activity 中调用一次以初始化路径 */
    fun init(context: Context) {
        val dir = context.getExternalFilesDir(null) ?: context.cacheDir
        logDir = dir
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        // 清理旧版单文件日志
        try { File(dir, "yuitag_debug.log").delete() } catch (_: Exception) {}
        rotateIfNeeded()
    }

    fun v(tag: String, msg: String) = write(LogLevel.VERBOSE, tag, msg)

    fun d(tag: String, msg: String) = write(LogLevel.DEBUG, tag, msg)

    fun i(tag: String, msg: String) = write(LogLevel.INFO, tag, msg)

    fun w(tag: String, msg: String, tr: Throwable? = null) = write(LogLevel.WARN, tag, msg, tr)

    fun e(tag: String, msg: String, tr: Throwable? = null) = write(LogLevel.ERROR, tag, msg, tr)

    fun hex(tag: String, label: String, data: ByteArray?) {
        if (data == null) { d(tag, "$label: null"); return }
        d(tag, "$label (${data.size}B): ${data.joinToString(" ") { "%02X".format(it) }}")
    }

    private fun debugEnabled(): Boolean =
        prefs?.getBoolean(KEY_DEBUG_MODE, false) ?: false

    @Synchronized
    private fun write(level: LogLevel, tag: String, msg: String, tr: Throwable? = null) {
        try {
            if (level.code < LogLevel.INFO.code && !debugEnabled()) return
            rotateIfNeeded()
            val w = writer ?: return
            val ts = timeFormat.format(Date())
            w.write("$ts $level/$tag: $msg\n")
            if (tr != null) {
                val sw = java.io.StringWriter()
                tr.printStackTrace(java.io.PrintWriter(sw))
                w.write(sw.toString() + "\n")
            }
            w.flush()
        } catch (_: Exception) {}
    }

    /** 跨天时关闭旧文件并打开当天新文件，同时触发过期日志清理 */
    private fun rotateIfNeeded() {
        try {
            val dir = logDir ?: return
            val today = dateFormat.format(Date())
            if (today == currentDate && writer != null) return
            try { writer?.close() } catch (_: Exception) {}
            currentDate = today
            val file = File(dir, "$FILE_PREFIX$today$FILE_EXT")
            writer = BufferedWriter(
                OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8),
                8192
            )
            cleanup()
        } catch (_: Exception) {
            writer = null
        }
    }

    /** 删除超过保留期限的日志文件 */
    private fun cleanup() {
        try {
            val dir = logDir ?: return
            val cutoff = System.currentTimeMillis() - RETENTION_DAYS * 24L * 3600 * 1000
            dir.listFiles { f -> f.isFile && f.name.startsWith(FILE_PREFIX) && f.name.endsWith(FILE_EXT) }
                ?.forEach { f ->
                    if (f.lastModified() < cutoff) {
                        try { f.delete() } catch (_: Exception) {}
                    }
                }
        } catch (_: Exception) {}
    }
}

private enum class LogLevel(val code: Int, val label: String) {
    VERBOSE(2, "V"),
    DEBUG(3, "D"),
    INFO(4, "I"),
    WARN(5, "W"),
    ERROR(6, "E");

    override fun toString(): String = label
}
