package com.reiyy.yuitag.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 通知类型
 */
enum class NotificationType(val code: String, val displayName: String) {
    BATTERY("BATTERY", "电池"),
    DEVICE_STATUS("DEVICE_STATUS", "状态"),
    UPDATE("UPDATE", "更新"),
    SYSTEM("SYSTEM", "系统");

    companion object {
        fun fromCode(code: String): NotificationType =
            entries.find { it.code.equals(code, ignoreCase = true) } ?: SYSTEM
    }
}

/**
 * 通知级别
 * - LOW: 低提醒 (电量 < 50%，恢复正常，常规提示)
 * - MEDIUM: 中提醒 (电量 < 30%，需注意状态)
 * - HIGH: 高提醒 (电量 < 10%，紧急即将耗尽，遗落报警)
 */
enum class NotificationLevel(val code: String, val displayName: String) {
    LOW("LOW", "低提醒"),
    MEDIUM("MEDIUM", "中提醒"),
    HIGH("HIGH", "高提醒");

    companion object {
        fun fromCode(code: String): NotificationLevel =
            entries.find { it.code.equals(code, ignoreCase = true) } ?: LOW
    }
}

/**
 * Room 实体：应用内通知消息
 */
@Entity(
    tableName = "notifications",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["deviceUid"]),
        Index(value = ["isRead"])
    ]
)
data class NotificationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val type: String, // 对应 NotificationType.code
    val level: String, // 对应 NotificationLevel.code
    val sender: String, // 通知发送方 (例如 "钥匙扣 Tag (A1B2)"、"YuiTag 系统")
    val deviceUid: String? = null, // 关联的设备短 UID (为空代表系统或全局通知)
    val title: String, // 通知标题
    val content: String, // 通知详细正文
    val timestamp: Long = System.currentTimeMillis(), // 产生时间戳
    val isRead: Boolean = false, // 已读/未读状态
    val actionRoute: String? = null, // 点击跳转路由 (如 detail/{uid}, otaFirmwareUpdate/{uid}, about)
    val extraData: String? = null // 预留扩展元数据
)
