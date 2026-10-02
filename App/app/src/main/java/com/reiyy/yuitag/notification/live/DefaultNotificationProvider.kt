package com.reiyy.yuitag.notification.live

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.reiyy.yuitag.MainActivity
import com.reiyy.yuitag.R
import com.reiyy.yuitag.ble.FileLog
import com.reiyy.yuitag.ui.navigation.Routes

/**
 * 通用 Android 平台兜底通知适配器（标准常驻通知）
 */
class DefaultNotificationProvider : ILiveNotificationProvider {

    companion object {
        const val NORMAL_CHANNEL_ID = "yuitag_find_channel"
        const val NORMAL_CHANNEL_NAME = "寻物通知"
        private const val NOTIFICATION_ID_BASE = 88000
    }

    override fun isSupported(context: Context): Boolean = true

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(NORMAL_CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                NORMAL_CHANNEL_ID,
                NORMAL_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "蓝牙寻物时在通知栏显示当前距离与控制操作"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun getNotificationId(deviceUid: String): Int {
        return NOTIFICATION_ID_BASE + (deviceUid.hashCode() and 0x7FFF)
    }

    override fun showOrUpdateFind(context: Context, payload: LiveFindPayload) {
        ensureChannel(context)
        val notificationId = getNotificationId(payload.deviceUid)

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(MainActivity.EXTRA_NAV_ROUTE, Routes.findRoute(payload.deviceUid))
            putExtra(MainActivity.EXTRA_DEVICE_UID, payload.deviceUid)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentText = if (payload.isConnected) {
            "${payload.distanceLevelText} · ${payload.trendText}"
        } else {
            payload.statusText
        }

        val ringActionTitle = if (payload.isRinging) "停止响铃" else "开始响铃"
        val ringPendingIntent = LiveNotificationActionReceiver.createToggleRingPendingIntent(context, payload.deviceUid)
        val exitPendingIntent = LiveNotificationActionReceiver.createExitFindPendingIntent(context, payload.deviceUid)

        val builder = NotificationCompat.Builder(context, NORMAL_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_tag)
            .setContentTitle(payload.deviceName)
            .setContentText(contentText)
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(R.drawable.ic_notification_ringing, ringActionTitle, ringPendingIntent)
            .addAction(0, "结束查找", exitPendingIntent)

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        FileLog.i("LiveNotif", "Posting default notification #$notificationId: name=${payload.deviceName}, text=$contentText")
        nm.notify(notificationId, builder.build())
    }

    override fun cancelFind(context: Context, deviceUid: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        FileLog.i("LiveNotif", "Cancelling default notification for $deviceUid")
        nm.cancel(getNotificationId(deviceUid))
    }

    override fun cancelAll(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        FileLog.i("LiveNotif", "Cancelling all default notifications")
        nm.cancelAll()
    }
}
