package com.reiyy.yuitag.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import androidx.core.content.ContextCompat
import com.reiyy.yuitag.R
import com.reiyy.yuitag.data.local.DeviceType
import java.io.File
import kotlin.math.min

object TagIconHelper {

    private const val DEFAULT_ICON_SIZE_PX = 288 // 对应 72dp @ 4x (xxxhdpi)

    /**
     * 为目标 Tag 生成实况通知展示的高清圆形头像 Bitmap
     *
     * 优先级规则（与 App 内 DeviceTypeIcon 保持严格一致）：
     * 1. 自定义主题照片（themePath/mini_icon.png 或 themePath/icon.png）-> 圆形裁剪
     * 2. 自定义类型 Emoji（customIcon 不为空）-> 柔和背景圆底 + 居中 Emoji 绘制
     * 3. 预设分类（KEY/BACKPACK/WALLET/LUGGAGE/CUSTOM）-> 品牌色圆底 + 对应预设图标
     */
    fun createTagIconBitmap(
        context: Context,
        themePath: String? = null,
        deviceType: DeviceType = DeviceType.CUSTOM,
        customIcon: String? = null,
        sizePx: Int = DEFAULT_ICON_SIZE_PX
    ): Bitmap {
        // 1. 检查主题自定义图片
        if (!themePath.isNullOrBlank()) {
            val miniIcon = File(themePath, "mini_icon.png")
            val icon = File(themePath, "icon.png")
            val targetFile = if (miniIcon.exists()) miniIcon else if (icon.exists()) icon else null
            if (targetFile != null) {
                runCatching {
                    BitmapFactory.decodeFile(targetFile.absolutePath)?.let { raw ->
                        return cropToCircle(raw, sizePx)
                    }
                }
            }
        }

        // 2. 检查自定义 Emoji 字符
        val cleanEmoji = customIcon?.trim().orEmpty()
        if (deviceType == DeviceType.CUSTOM && cleanEmoji.isNotEmpty()) {
            return buildEmojiBitmap(cleanEmoji, sizePx)
        }

        // 3. 预设类型图标
        val presetDrawableRes = when (deviceType) {
            DeviceType.KEY -> R.drawable.ic_device_key
            DeviceType.BACKPACK -> R.drawable.ic_device_backpack
            DeviceType.WALLET -> R.drawable.ic_device_wallet
            DeviceType.LUGGAGE -> R.drawable.ic_device_luggage
            DeviceType.CUSTOM -> R.drawable.ic_device_category
        }
        return renderPresetIconToBitmap(context, presetDrawableRes, sizePx)
    }

    private fun cropToCircle(source: Bitmap, targetSize: Int): Bitmap {
        val output = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val radius = targetSize / 2f

        canvas.drawCircle(radius, radius, radius, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)

        val minDim = min(source.width, source.height)
        val srcLeft = (source.width - minDim) / 2
        val srcTop = (source.height - minDim) / 2
        val srcRect = Rect(srcLeft, srcTop, srcLeft + minDim, srcTop + minDim)
        val dstRect = Rect(0, 0, targetSize, targetSize)

        canvas.drawBitmap(source, srcRect, dstRect, paint)
        return output
    }

    private fun buildEmojiBitmap(emoji: String, sizePx: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val center = sizePx / 2f

        // 圆形柔和底色 (#E8F0FE 浅蓝) 与边框
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#E8F0FE")
            style = Paint.Style.FILL
        }
        canvas.drawCircle(center, center, center, bgPaint)

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#C2D7FA")
            style = Paint.Style.STROKE
            strokeWidth = sizePx * 0.03f
        }
        canvas.drawCircle(center, center, center - borderPaint.strokeWidth / 2f, borderPaint)

        // 绘制 Emoji
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = sizePx * 0.52f
        }
        val fm = textPaint.fontMetrics
        val baseline = center - (fm.ascent + fm.descent) / 2f
        canvas.drawText(emoji, center, baseline, textPaint)

        return bitmap
    }

    private fun renderPresetIconToBitmap(context: Context, iconRes: Int, sizePx: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val center = sizePx / 2f

        // 圆形底色 (#1976D2 蓝色)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#1976D2")
            style = Paint.Style.FILL
        }
        canvas.drawCircle(center, center, center, bgPaint)

        // 矢量图标（白色，占圆直径的约 56%）
        val iconSizePx = (sizePx * 0.56f).toInt()
        val offset = (sizePx - iconSizePx) / 2
        ContextCompat.getDrawable(context, iconRes)?.mutate()?.let { drawable ->
            drawable.setTint(android.graphics.Color.WHITE)
            drawable.setBounds(offset, offset, offset + iconSizePx, offset + iconSizePx)
            drawable.draw(canvas)
        }

        return bitmap
    }
}
