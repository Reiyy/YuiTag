package com.reiyy.yuitag.notification.live

import android.graphics.Bitmap

/**
 * 近场寻物实况通知的数据载荷
 */
data class LiveFindPayload(
    val deviceUid: String,
    val deviceName: String,
    val statusText: String,       // 简明状态，如 "正在接近"、"在附近"、"搜索中..."
    val distanceLevelText: String,// 距离等级大字，如 "在附近"、"距离近"、"信号弱"
    val rssi: Int,                // 平滑滤波后的 RSSI (dBm)
    val trendText: String,        // 趋势说明，如 "这个方向没错哦~"
    val isRinging: Boolean,       // 是否正在蜂鸣响铃
    val isConnected: Boolean,     // 是否已建立 GATT 追踪连接
    val themeColorHex: String? = null, // 设备个性化强调色 (#RRGGBB)
    val customIconBitmap: Bitmap? = null // 设备自定义头像/图标
)
