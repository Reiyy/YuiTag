package com.reiyy.yuitag.notification.live

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.reiyy.yuitag.data.DeviceRepository

/**
 * 接收来自实况通知卡片上操作按钮点击的广播接收器
 */
class LiveNotificationActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_TOGGLE_RING = "com.reiyy.yuitag.action.TOGGLE_RING"
        const val ACTION_EXIT_FIND = "com.reiyy.yuitag.action.EXIT_FIND"
        const val EXTRA_DEVICE_UID = "extra_device_uid"

        @Volatile
        var activeFindListener: LiveFindActionListener? = null

        fun createToggleRingPendingIntent(context: Context, deviceUid: String): PendingIntent {
            val intent = Intent(context, LiveNotificationActionReceiver::class.java).apply {
                action = ACTION_TOGGLE_RING
                putExtra(EXTRA_DEVICE_UID, deviceUid)
            }
            val requestCode = (deviceUid + "_ring").hashCode()
            return PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        fun createExitFindPendingIntent(context: Context, deviceUid: String): PendingIntent {
            val intent = Intent(context, LiveNotificationActionReceiver::class.java).apply {
                action = ACTION_EXIT_FIND
                putExtra(EXTRA_DEVICE_UID, deviceUid)
            }
            val requestCode = (deviceUid + "_exit").hashCode()
            return PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }

    interface LiveFindActionListener {
        fun onToggleRing(deviceUid: String)
        fun onExitFind(deviceUid: String)
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val uid = intent?.getStringExtra(EXTRA_DEVICE_UID) ?: return
        when (intent.action) {
            ACTION_TOGGLE_RING -> {
                activeFindListener?.onToggleRing(uid)
            }
            ACTION_EXIT_FIND -> {
                val listener = activeFindListener
                if (listener != null) {
                    listener.onExitFind(uid)
                } else {
                    runCatching {
                        val repo = DeviceRepository.getInstance(context)
                        repo.bleConnectionManager.setFindPageActive(uid, false)
                        repo.bleConnectionManager.disconnect(uid)
                    }
                }
                LiveNotificationManager.getInstance(context).cancelFind(uid)
            }
        }
    }
}
