package com.reiyy.yuitag.notification.live

import android.content.Context
import android.graphics.Bitmap
import com.reiyy.yuitag.ble.FileLog
import com.reiyy.yuitag.data.LiveNotificationPreferences
import com.reiyy.yuitag.notification.live.flyme.FlymeLiveNotificationProvider

/**
 * 实况通知全局管理门面 (单例)
 * 负责选择适配器、调度状态分发以及与 App 设置联动。
 */
class LiveNotificationManager private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var instance: LiveNotificationManager? = null

        fun getInstance(context: Context): LiveNotificationManager {
            return instance ?: synchronized(this) {
                instance ?: LiveNotificationManager(context.applicationContext).also { instance = it }
            }
        }

        fun init(context: Context) {
            FlymeLiveNotificationProvider.initChannels(context)
        }
    }

    private val preferences = LiveNotificationPreferences(context)
    private val flymeProvider by lazy { FlymeLiveNotificationProvider() }
    private val defaultProvider by lazy { DefaultNotificationProvider() }

    /**
     * 动态获取当前最适合的通知适配器：魅族 Flyme -> 通用兜底
     */
    private fun getProvider(): ILiveNotificationProvider {
        return if (flymeProvider.isSupported(context)) {
            flymeProvider
        } else {
            defaultProvider
        }
    }

    private var lastDeviceUid: String? = null
    private var lastRinging: Boolean? = null
    private var lastConnected: Boolean? = null
    private var lastDistanceLevelText: String? = null
    private var lastStatusText: String? = null
    private var lastTrendText: String? = null
    private var lastIconBitmap: Bitmap? = null

    /**
     * 发送或更新寻物实况通知
     * 仅在关键状态或展示文本发生变化时才向系统推送更新，彻底避免无意义的底层信号数值频繁刷新
     * @param forceImmediate 强制立即推送（如响应响铃状态切换或测试触发）
     */
    fun postFindUpdate(payload: LiveFindPayload, forceImmediate: Boolean = false) {
        if (!preferences.isLiveNotificationEnabled()) {
            FileLog.d("LiveNotif", "Live notification is disabled in settings, skip post")
            return
        }

        val deviceChanged = (lastDeviceUid != payload.deviceUid)
        val ringingChanged = (lastRinging != payload.isRinging)
        val connectedChanged = (lastConnected != payload.isConnected)
        val distanceChanged = (lastDistanceLevelText != payload.distanceLevelText)
        val statusChanged = (lastStatusText != payload.statusText)
        val trendChanged = (lastTrendText != payload.trendText)
        val iconChanged = (lastIconBitmap != payload.customIconBitmap)

        val hasMeaningfulChange = forceImmediate || deviceChanged || ringingChanged ||
                connectedChanged || distanceChanged || statusChanged || trendChanged || iconChanged

        if (hasMeaningfulChange) {
            dispatchUpdate(payload)
        }
    }

    private fun dispatchUpdate(payload: LiveFindPayload) {
        lastDeviceUid = payload.deviceUid
        lastRinging = payload.isRinging
        lastConnected = payload.isConnected
        lastDistanceLevelText = payload.distanceLevelText
        lastStatusText = payload.statusText
        lastTrendText = payload.trendText
        lastIconBitmap = payload.customIconBitmap
        try {
            val currentProvider = getProvider()
            FileLog.i("LiveNotif", "Dispatching via ${currentProvider::class.java.simpleName}: uid=${payload.deviceUid}, name=${payload.deviceName}, dist=${payload.distanceLevelText}, trend=${payload.trendText}")
            currentProvider.showOrUpdateFind(context, payload)
        } catch (e: Exception) {
            FileLog.e("LiveNotif", "Error dispatching live notification: ${e.message}", e)
        }
    }

    /**
     * 取消指定设备的寻物实况通知
     */
    fun cancelFind(deviceUid: String) {
        lastDeviceUid = null
        lastRinging = null
        lastConnected = null
        lastDistanceLevelText = null
        lastStatusText = null
        lastTrendText = null
        lastIconBitmap = null
        try {
            val currentProvider = getProvider()
            FileLog.i("LiveNotif", "Cancelling find for $deviceUid via ${currentProvider::class.java.simpleName}")
            currentProvider.cancelFind(context, deviceUid)
        } catch (e: Exception) {
            FileLog.e("LiveNotif", "Error cancelling find: ${e.message}", e)
        }
    }

    /**
     * 取消所有实况通知
     */
    fun cancelAll() {
        lastDeviceUid = null
        lastRinging = null
        lastConnected = null
        lastDistanceLevelText = null
        lastStatusText = null
        lastTrendText = null
        lastIconBitmap = null
        try {
            val currentProvider = getProvider()
            FileLog.i("LiveNotif", "Cancelling all via ${currentProvider::class.java.simpleName}")
            currentProvider.cancelAll(context)
        } catch (e: Exception) {
            FileLog.e("LiveNotif", "Error cancelling all: ${e.message}", e)
        }
    }
}
