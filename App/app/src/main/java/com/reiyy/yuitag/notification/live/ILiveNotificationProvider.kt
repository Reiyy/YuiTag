package com.reiyy.yuitag.notification.live

import android.content.Context

/**
 * 实况通知厂商适配器接口
 */
interface ILiveNotificationProvider {

    /**
     * 检查当前系统环境是否支持此 Provider
     */
    fun isSupported(context: Context): Boolean

    /**
     * 发送或更新寻物实况通知
     */
    fun showOrUpdateFind(context: Context, payload: LiveFindPayload)

    /**
     * 取消指定设备的寻物通知
     */
    fun cancelFind(context: Context, deviceUid: String)

    /**
     * 取消所有实况通知
     */
    fun cancelAll(context: Context)
}
