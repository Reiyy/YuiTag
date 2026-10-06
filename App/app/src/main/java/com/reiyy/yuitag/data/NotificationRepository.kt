package com.reiyy.yuitag.data

import android.content.Context
import android.content.SharedPreferences
import com.reiyy.yuitag.ble.FileLog
import com.reiyy.yuitag.data.local.NotificationDao
import com.reiyy.yuitag.data.local.NotificationEntity
import com.reiyy.yuitag.data.local.NotificationLevel
import com.reiyy.yuitag.data.local.NotificationType
import com.reiyy.yuitag.data.local.YuiTagDatabase
import com.reiyy.yuitag.ui.navigation.Routes
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.ConcurrentHashMap

/**
 * 应用内通知仓库
 * - 统筹管理通知的持久化、未读统计与生命周期
 * - 默认保留 200 条最新通知
 * - 提供电量提醒、设备状态、版本更新等防抖入库逻辑
 */
class NotificationRepository private constructor(
    private val notificationDao: NotificationDao,
    private val prefs: SharedPreferences
) {

    val notifications: Flow<List<NotificationEntity>> = notificationDao.getAllFlow()
    val unreadCount: Flow<Int> = notificationDao.getUnreadCountFlow()

    // 内存中设备状态事件防抖缓存 (避免极短时间内因信号跳动重复入库)
    private val lastStatusAlertTime = ConcurrentHashMap<String, Long>()

    /**
     * 发送通知并自动控制最大保留条数 (200条)
     */
    suspend fun postNotification(notification: NotificationEntity): Long {
        val id = notificationDao.insert(notification)
        try {
            notificationDao.trimToMaxCount(MAX_NOTIFICATION_COUNT)
        } catch (e: Exception) {
            FileLog.w(TAG, "修剪通知最大保留数量失败: ${e.message}")
        }
        FileLog.i(TAG, "已生成通知 [id=$id, type=${notification.type}, level=${notification.level}]: ${notification.title}")
        return id
    }

    suspend fun markAsRead(id: Long) {
        notificationDao.markAsRead(id)
    }

    suspend fun markAllAsRead() {
        notificationDao.markAllAsRead()
    }

    suspend fun deleteById(id: Long) {
        notificationDao.deleteById(id)
    }

    suspend fun deleteAll() {
        notificationDao.deleteAll()
    }

    suspend fun deleteAllRead() {
        notificationDao.deleteAllRead()
    }

    /**
     * 电量检测与提醒分级入库：
     * - < 10%  : 高提醒 (HIGH, 紧急换电)
     * - < 30%  : 中提醒 (MEDIUM, 需注意更换)
     * - < 50%  : 低提醒 (LOW, 留意电量)
     * - >= 50% : 清除该设备的告警状态（更换电池后复位）
     *
     * 防抖机制：
     * 1. 跨级别加重（如低->中，中->高）时立即触发新通知。
     * 2. 同一级别内 24 小时内不重复发送，避免广播密集打扰。
     */
    suspend fun checkBatteryAlert(uid: String, customName: String, battery: Int) {
        if (battery < 0) return

        val prefKey = "battery_alert_state_$uid"

        if (battery >= 50) {
            // 电量充足或已换电池，清除告警记录
            if (prefs.contains(prefKey)) {
                prefs.edit().remove(prefKey).apply()
                FileLog.d(TAG, "设备 $uid 电量恢复正常 ($battery%)，已重置电量告警记录")
            }
            return
        }

        // 计算电量档位与级别
        val (level, tierRank) = when {
            battery < 10 -> NotificationLevel.HIGH to 3
            battery < 30 -> NotificationLevel.MEDIUM to 2
            else -> NotificationLevel.LOW to 1
        }

        val now = System.currentTimeMillis()
        val savedRecord = prefs.getString(prefKey, null)
        var shouldAlert = false

        if (savedRecord == null) {
            shouldAlert = true
        } else {
            val parts = savedRecord.split(":")
            val lastTierRank = parts.getOrNull(0)?.toIntOrNull() ?: 0
            val lastAlertTime = parts.getOrNull(1)?.toLongOrNull() ?: 0L

            if (tierRank > lastTierRank) {
                // 电量跌入更严重的区间，立即告警
                shouldAlert = true
            } else if (tierRank == lastTierRank) {
                // 同级别，超过 24 小时冷却周期才提醒
                if (now - lastAlertTime > BATTERY_COOLDOWN_MS) {
                    shouldAlert = true
                }
            }
        }

        if (shouldAlert) {
            prefs.edit().putString(prefKey, "$tierRank:$now:$battery").apply()

            val displayName = customName.ifEmpty { "Tag ($uid)" }
            val (title, content) = when (level) {
                NotificationLevel.HIGH -> {
                    "要没电啦！" to "马上就要没电啦！响铃和信号发射都没力气了~ 赶快换上新电池吧！"
                }
                NotificationLevel.MEDIUM -> {
                    "电池快用完了哦~" to "电池快耗尽啦，最近记得换新电池哦~"
                }
                NotificationLevel.LOW -> {
                    "别忘了电量哦~" to "电量过半啦，记得备好新电池~"
                }
            }

            postNotification(
                NotificationEntity(
                    type = NotificationType.BATTERY.code,
                    level = level.code,
                    sender = displayName,
                    deviceUid = uid,
                    title = title,
                    content = content,
                    timestamp = now,
                    actionRoute = Routes.mainDeviceRoute(uid),
                    extraData = "battery=$battery"
                )
            )
        }
    }

    /**
     * 发送遗落报警通知
     */
    suspend fun postSeparationAlert(uid: String, customName: String) {
        val now = System.currentTimeMillis()
        val key = "${uid}_sep_alert"
        val lastTime = lastStatusAlertTime[key] ?: 0L
        if (now - lastTime < STATUS_COOLDOWN_MS) return
        lastStatusAlertTime[key] = now

        val displayName = customName.ifEmpty { "Tag ($uid)" }
        postNotification(
            NotificationEntity(
                type = NotificationType.DEVICE_STATUS.code,
                level = NotificationLevel.HIGH.code,
                sender = displayName,
                deviceUid = uid,
                title = "别丢下我！",
                content = "居然把我忘记了，赶紧回来接我！",
                timestamp = now,
                actionRoute = Routes.findRoute(uid)
            )
        )
    }

    /**
     * 发送遗落恢复通知
     */
    suspend fun postSeparationRecovery(uid: String, customName: String) {
        val now = System.currentTimeMillis()
        val key = "${uid}_sep_recovery"
        val lastTime = lastStatusAlertTime[key] ?: 0L
        if (now - lastTime < STATUS_COOLDOWN_MS) return
        lastStatusAlertTime[key] = now

        val displayName = customName.ifEmpty { "Tag ($uid)" }
        postNotification(
            NotificationEntity(
                type = NotificationType.DEVICE_STATUS.code,
                level = NotificationLevel.LOW.code,
                sender = displayName,
                deviceUid = uid,
                title = "我回来啦~",
                content = "真是的，下次别忘了我哦~",
                timestamp = now,
                actionRoute = Routes.mainDeviceRoute(uid)
            )
        )
    }

    /**
     * 兼容旧版设备状态调用，委托至 postSeparationAlert / postSeparationRecovery
     */
    suspend fun postDeviceStatusAlert(
        uid: String,
        customName: String,
        isAbnormal: Boolean,
        reason: String = ""
    ) {
        if (isAbnormal) {
            postSeparationAlert(uid, customName)
        } else {
            postSeparationRecovery(uid, customName)
        }
    }

    /**
     * 设备健康状态迁移检测：
     * - 设备状态变为 ABNORMAL 时触发“设备异常”通知
     * - 设备状态从 ABNORMAL 恢复为 NORMAL 时触发“设备上线”通知
     */
    suspend fun checkDeviceHealthTransition(
        uid: String,
        customName: String,
        newHealthState: String
    ) {
        val prefKey = "health_state_record_$uid"
        val lastState = prefs.getString(prefKey, null)
        val displayName = customName.ifEmpty { "Tag ($uid)" }

        if (lastState == null) {
            // 首次记录设备健康状态，写入初始状态，避免新绑定或冷启动误触发
            prefs.edit().putString(prefKey, newHealthState).apply()
            return
        }

        if (lastState == newHealthState) {
            return
        }

        // 状态发生迁移，更新记录
        prefs.edit().putString(prefKey, newHealthState).apply()

        if (newHealthState == "ABNORMAL") {
            postNotification(
                NotificationEntity(
                    type = NotificationType.DEVICE_STATUS.code,
                    level = NotificationLevel.HIGH.code,
                    sender = displayName,
                    deviceUid = uid,
                    title = "设备异常！",
                    content = "很久没收到该设备的活动了，记得检查一下哦！",
                    timestamp = System.currentTimeMillis(),
                    actionRoute = Routes.mainDeviceRoute(uid)
                )
            )
        } else if (lastState == "ABNORMAL" && newHealthState == "NORMAL") {
            postNotification(
                NotificationEntity(
                    type = NotificationType.DEVICE_STATUS.code,
                    level = NotificationLevel.LOW.code,
                    sender = displayName,
                    deviceUid = uid,
                    title = "设备上线",
                    content = "设备活动已恢复。",
                    timestamp = System.currentTimeMillis(),
                    actionRoute = Routes.mainDeviceRoute(uid)
                )
            )
        }
    }

    /**
     * 清理设备的通知状态记录（解绑/删除设备时调用）
     */
    fun clearDeviceState(uid: String) {
        prefs.edit()
            .remove("health_state_record_$uid")
            .remove("battery_alert_state_$uid")
            .apply()
    }

    /**
     * App 新版本可用提醒
     */
    suspend fun postAppUpdateNotification(versionName: String, releaseNotes: String = "") {
        val prefKey = "app_update_notified_$versionName"
        if (prefs.getBoolean(prefKey, false)) return
        prefs.edit().putBoolean(prefKey, true).apply()

        postNotification(
            NotificationEntity(
                type = NotificationType.UPDATE.code,
                level = NotificationLevel.LOW.code,
                sender = "YuiTag App",
                deviceUid = null,
                title = "发现新版本！",
                content = "App有新版本啦！",
                timestamp = System.currentTimeMillis(),
                actionRoute = Routes.ABOUT,
                extraData = "version=$versionName"
            )
        )
    }

    /**
     * 设备固件新版本提醒
     */
    suspend fun postFirmwareUpdateNotification(uid: String, customName: String, newVersion: String) {
        val prefKey = "fw_update_notified_${uid}_$newVersion"
        if (prefs.getBoolean(prefKey, false)) return
        prefs.edit().putBoolean(prefKey, true).apply()

        val displayName = customName.ifEmpty { "Tag ($uid)" }
        postNotification(
            NotificationEntity(
                type = NotificationType.UPDATE.code,
                level = NotificationLevel.LOW.code,
                sender = displayName,
                deviceUid = uid,
                title = "发现新固件！",
                content = "Tag固件有新版本啦！",
                timestamp = System.currentTimeMillis(),
                actionRoute = Routes.otaFirmwareUpdateRoute(uid),
                extraData = "version=$newVersion"
            )
        )
    }

    companion object {
        private const val TAG = "NotificationRepo"
        const val MAX_NOTIFICATION_COUNT = 200
        private const val BATTERY_COOLDOWN_MS = 24 * 60 * 60 * 1000L // 24小时
        private const val STATUS_COOLDOWN_MS = 30 * 1000L // 30秒短防抖

        @Volatile
        private var INSTANCE: NotificationRepository? = null

        fun getInstance(context: Context): NotificationRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: run {
                    val appContext = context.applicationContext
                    val db = YuiTagDatabase.getInstance(appContext)
                    val prefs = appContext.getSharedPreferences("yuitag_notifications", Context.MODE_PRIVATE)
                    NotificationRepository(db.notificationDao(), prefs).also { INSTANCE = it }
                }
            }
        }
    }
}
