package com.reiyy.yuitag.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.reiyy.yuitag.ble.FileLog

/**
 * 供魅族 Flyme SystemUI 发现实况通知能力的 Service 组件。
 * SystemUI 在读取本组件关联的 live_notification_config meta-data 后，
 * 将 YuiTag 注册进系统实况通知列表。
 */
class LiveNotification : Service() {
    override fun onCreate() {
        super.onCreate()
        FileLog.d("LiveNotification", "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        FileLog.d("LiveNotification", "Service started")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        FileLog.d("LiveNotification", "Service bound")
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        FileLog.d("LiveNotification", "Service destroyed")
    }
}
