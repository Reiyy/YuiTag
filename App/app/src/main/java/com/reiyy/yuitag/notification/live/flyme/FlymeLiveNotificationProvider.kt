/*
 * Implementation references Meizu Flyme live notification mechanisms from
 * starSchedule (https://github.com/lightStarrr/starSchedule) by lightStarrr.
 */

package com.reiyy.yuitag.notification.live.flyme

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Icon
import android.os.Bundle
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.reiyy.yuitag.MainActivity
import com.reiyy.yuitag.R
import com.reiyy.yuitag.ble.FileLog
import com.reiyy.yuitag.data.LiveNotificationPreferences
import com.reiyy.yuitag.notification.live.ILiveNotificationProvider
import com.reiyy.yuitag.notification.live.LiveFindPayload
import com.reiyy.yuitag.notification.live.LiveNotificationActionReceiver
import com.reiyy.yuitag.ui.navigation.Routes

/**
 * 魅族 Flyme 11/12 实况通知适配器
 */
class FlymeLiveNotificationProvider : ILiveNotificationProvider {

    companion object {
        const val LIVE_CHANNEL_ID = "live_notification_channel"
        const val LIVE_CHANNEL_NAME = "实况通知"
        private const val NOTIFICATION_ID_BASE = 1001

        private val activeDeviceUids = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        fun initChannels(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(LIVE_CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    LIVE_CHANNEL_ID,
                    LIVE_CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "魅族 Flyme 实况通知频道"
                    enableLights(true)
                    enableVibration(true)
                    setBypassDnd(true)
                    setShowBadge(true)
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    override fun isSupported(context: Context): Boolean {
        return LiveNotificationPreferences.isFlymeSupported(context)
    }

    private fun ensureChannel(context: Context) {
        initChannels(context)
    }

    private fun getNotificationId(deviceUid: String): Int {
        if (deviceUid == "test_live_notif_device") return NOTIFICATION_ID_BASE
        val hash = deviceUid.hashCode().let { if (it == Int.MIN_VALUE) 0 else kotlin.math.abs(it) }
        return NOTIFICATION_ID_BASE + (hash % 900)
    }

    override fun showOrUpdateFind(context: Context, payload: LiveFindPayload) {
        ensureChannel(context)
        val notificationId = getNotificationId(payload.deviceUid)

        // 首次推送使用 operation = 0 (创建实况窗)，后续信号增量更新使用 operation = 1 (静默更新)，避免持续打断胶囊收起
        val isFirstPost = activeDeviceUids.add(payload.deviceUid)
        val operation = if (isFirstPost) 0 else 1

        // 1. 确定胶囊配色及前景色
        val bgColorInt = if (payload.isRinging) {
            android.graphics.Color.parseColor("#FF9800")
        } else {
            payload.themeColorHex?.let { runCatching { android.graphics.Color.parseColor(it) }.getOrNull() }
                ?: android.graphics.Color.parseColor("#4CAF50")
        }
        val contentColorInt = calculateContentColor(bgColorInt)

        // 2. 组装胶囊态文案与图标（按需显示雷达图标表示查找状态，文案仅展示“在附近/距离近/距离远”等状态）
        val capsuleContent = if (payload.isRinging) {
            "响铃中"
        } else if (payload.isConnected) {
            payload.distanceLevelText
        } else {
            payload.statusText
        }

        val capsuleIconBitmap = if (payload.isRinging) {
            getTintedBitmap(context, R.drawable.ic_notification_ringing, contentColorInt)
        } else {
            getTintedBitmap(context, R.drawable.ic_notification_radar, contentColorInt)
        }

        val capsuleBundle = Bundle().apply {
            putInt("notification.live.capsuleStatus", 1)
            putInt("notification.live.capsuleType", 1)
            putString("notification.live.capsuleTitle", capsuleContent)
            putString("notification.live.capsuleContent", capsuleContent)
            capsuleIconBitmap?.let {
                putParcelable("notification.live.capsuleIcon", Icon.createWithBitmap(it))
            }
            putInt("notification.live.capsuleBgColor", bgColorInt)
            putInt("notification.live.capsuleContentColor", contentColorInt)
        }

        // 3. 组装实况主 Bundle (operation: 0 创建, 1 静默更新)
        val liveBundle = Bundle().apply {
            putBoolean("is_live", true)
            putInt("notification.live.operation", operation)
            putInt("notification.live.type", 10)
            putBundle("notification.live.capsule", capsuleBundle)
            putInt("notification.live.contentColor", contentColorInt)
        }

        // 4. 构建展开态 RemoteViews
        val remoteViews = RemoteViews(context.packageName, R.layout.live_notification_find_card).apply {
            // (1) 左侧 Tag 图标
            if (payload.customIconBitmap != null) {
                setImageViewBitmap(R.id.live_device_icon, payload.customIconBitmap)
            } else {
                setImageViewResource(R.id.live_device_icon, R.drawable.ic_notification_tag_card)
            }

            // (2) 中心区域文本：
            // 第 1 行：Tag 名称
            setTextViewText(R.id.live_device_name, payload.deviceName)

            // 第 2 行：距离提示文本（如“在附近”，响铃时不改变此文本与颜色）
            val (distanceText, distanceColor) = if (payload.isConnected) {
                val color = when (payload.distanceLevelText) {
                    "在此处", "在附近" -> android.graphics.Color.parseColor("#2E7D32")
                    "距离近" -> android.graphics.Color.parseColor("#388E3C")
                    "距离远" -> android.graphics.Color.parseColor("#F57C00")
                    "信号弱" -> android.graphics.Color.parseColor("#757575")
                    else -> android.graphics.Color.parseColor("#1976D2")
                }
                payload.distanceLevelText to color
            } else {
                payload.statusText to android.graphics.Color.parseColor("#757575")
            }
            setTextViewText(R.id.live_distance_text, distanceText)
            setTextColor(R.id.live_distance_text, distanceColor)

            // 第 3 行：状态与趋势文本（直接展示用户在个性化中配置的提示文本，响铃时不被覆盖）
            val trendDisplay = if (payload.trendText.isNotEmpty()) {
                payload.trendText
            } else {
                payload.statusText
            }
            setTextViewText(R.id.live_trend_text, trendDisplay)

            // (3) 右侧区域：竖向圆形图标按钮（响铃在上，退出在下）
            if (payload.isRinging) {
                setInt(R.id.live_btn_ring, "setBackgroundResource", R.drawable.live_notification_circle_btn_ring_active_bg)
                setImageViewResource(R.id.live_btn_ring, R.drawable.ic_notification_btn_ring_active)
                setContentDescription(R.id.live_btn_ring, "停止响铃")
            } else {
                setInt(R.id.live_btn_ring, "setBackgroundResource", R.drawable.live_notification_circle_btn_bg)
                setImageViewResource(R.id.live_btn_ring, R.drawable.ic_notification_btn_ring)
                setContentDescription(R.id.live_btn_ring, "开始响铃")
            }

            setInt(R.id.live_btn_exit, "setBackgroundResource", R.drawable.live_notification_circle_btn_bg)
            setImageViewResource(R.id.live_btn_exit, R.drawable.ic_notification_btn_close)
            setContentDescription(R.id.live_btn_exit, "结束查找")

            // 按钮点击 PendingIntent
            setOnClickPendingIntent(
                R.id.live_btn_ring,
                LiveNotificationActionReceiver.createToggleRingPendingIntent(context, payload.deviceUid)
            )
            setOnClickPendingIntent(
                R.id.live_btn_exit,
                LiveNotificationActionReceiver.createExitFindPendingIntent(context, payload.deviceUid)
            )
        }

        // 5. 点击卡片主体跳回 App 查找页
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(MainActivity.EXTRA_NAV_ROUTE, Routes.findRoute(payload.deviceUid))
            putExtra(MainActivity.EXTRA_DEVICE_UID, payload.deviceUid)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingContentIntent = PendingIntent.getActivity(
            context,
            notificationId,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 6. 构造并提交通知 (setOnlyAlertOnce(true) 保证仅首报发出提醒与展开，后续更新静默留在胶囊)
        val notification = Notification.Builder(context, LIVE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_radar)
            .setContentTitle(payload.deviceName)
            .setContentText(capsuleContent)
            .addExtras(liveBundle)
            .setCustomContentView(remoteViews)
            .setContentIntent(pendingContentIntent)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        FileLog.i("LiveNotif", "Posting Flyme live notification #$notificationId (op=$operation): name=${payload.deviceName}, text=$capsuleContent")
        nm.notify(notificationId, notification)
    }

    override fun cancelFind(context: Context, deviceUid: String) {
        activeDeviceUids.remove(deviceUid)
        val notificationId = getNotificationId(deviceUid)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        FileLog.i("LiveNotif", "Cancelling Flyme live notification #$notificationId for $deviceUid")
        nm.cancel(notificationId)
    }

    override fun cancelAll(context: Context) {
        activeDeviceUids.clear()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        FileLog.i("LiveNotif", "Cancelling all notifications from FlymeLiveNotificationProvider")
        nm.cancelAll()
    }

    private fun calculateContentColor(bgColorInt: Int): Int {
        val r = (bgColorInt shr 16 and 0xFF) / 255f
        val g = (bgColorInt shr 8 and 0xFF) / 255f
        val b = (bgColorInt and 0xFF) / 255f
        val luminance = 0.2126f * r + 0.7152f * g + 0.0722f * b
        return if (luminance > 0.65f) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }

    private fun getTintedBitmap(context: Context, resId: Int, tintColor: Int): Bitmap {
        val drawable = ContextCompat.getDrawable(context, resId)!!.mutate()
        drawable.setTint(tintColor)
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }
}
