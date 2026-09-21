package com.reiyy.yuitag.ui.components

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.reiyy.yuitag.data.local.DeviceType

/**
 * 地图标记图标缓存 — 在 Compose 侧把设备类型图标栅格化为 Bitmap，
 * 供 osmdroid Marker 使用（避免每次标记重建时重复绘制）。
 */
class MapMarkerIcons(
    val myLocation: Bitmap,
    private val deviceByType: Map<DeviceType, Bitmap>,
    private val selectedDeviceByType: Map<DeviceType, Bitmap>,
    private val customBuilder: (String) -> Bitmap,
    private val selectedCustomBuilder: (String) -> Bitmap,
    private val markerSizePx: Int,
    private val selectedSizePx: Int,
    private val ringColorArgb: Int,
    private val ringWidthPx: Float,
    private val borderColorArgb: Int,
    private val borderWidthPx: Float,
    private val density: Density
) {
    private val customCache = mutableMapOf<String, Bitmap>()
    private val selectedCustomCache = mutableMapOf<String, Bitmap>()
    private val circleCache = mutableMapOf<Pair<Int, Int>, Bitmap>()
    private val selectedCache = mutableMapOf<Pair<Int, Int>, Bitmap>()
    private val startEndCache = mutableMapOf<String, Bitmap>()
    private val customImageCache = mutableMapOf<Bitmap, Bitmap>()
    private val selectedCustomImageCache = mutableMapOf<Bitmap, Bitmap>()

    fun deviceIcon(deviceType: DeviceType, customIcon: String): Bitmap {
        return if (deviceType == DeviceType.CUSTOM && customIcon.isNotBlank()) {
            customCache.getOrPut(customIcon) { customBuilder(customIcon) }
        } else {
            deviceByType[deviceType] ?: deviceByType.getValue(DeviceType.CUSTOM)
        }
    }

    fun selectedDeviceIcon(deviceType: DeviceType, customIcon: String): Bitmap {
        return if (deviceType == DeviceType.CUSTOM && customIcon.isNotBlank()) {
            selectedCustomCache.getOrPut(customIcon) { selectedCustomBuilder(customIcon) }
        } else {
            selectedDeviceByType[deviceType] ?: selectedDeviceByType.getValue(DeviceType.CUSTOM)
        }
    }

    fun customImageIcon(imageBitmap: Bitmap): Bitmap =
        customImageCache.getOrPut(imageBitmap) {
            buildCustomImageMarkerBitmap(imageBitmap, markerSizePx, borderColorArgb, borderWidthPx)
        }

    fun selectedCustomImageIcon(imageBitmap: Bitmap): Bitmap =
        selectedCustomImageCache.getOrPut(imageBitmap) {
            val normalIcon = buildCustomImageMarkerBitmap(imageBitmap, markerSizePx, borderColorArgb, borderWidthPx)
            buildSelectedDeviceIconBitmap(normalIcon, selectedSizePx, ringColorArgb, ringWidthPx)
        }

    /** 纯色圆形标记（轨迹点/起终点），按颜色与直径缓存 */
    fun circleMarker(color: Color, diameterPx: Int): Bitmap =
        circleCache.getOrPut(color.toArgb() to diameterPx) {
            buildCircleBitmap(diameterPx, color, density)
        }

    /** 选中态标记：白色外环（带黑边）+ 内部彩色圆，按颜色与直径缓存 */
    fun selectedMarker(color: Color, diameterPx: Int): Bitmap =
        selectedCache.getOrPut(color.toArgb() to diameterPx) {
            buildSelectedCircleBitmap(diameterPx, color, density)
        }

    /** 起点/终点象棋样式标记：圆形底色 + 白色圆环 + 居中文字 */
    fun startEndMarker(color: Color, text: String, diameterPx: Int): Bitmap =
        startEndCache.getOrPut("${color.toArgb()}_${diameterPx}_$text") {
            buildStartEndMarkerBitmap(diameterPx, color, text)
        }
}

@Composable
fun rememberMapMarkerIcons(): MapMarkerIcons {
    val density = LocalDensity.current
    val markerSizePx = with(density) { 44.dp.toPx().toInt() }
    val iconSizePx = with(density) { 28.dp.toPx().toInt() }
    val myLocSizePx = with(density) { 22.dp.toPx().toInt() }

    val tint = MaterialTheme.colorScheme.primary
    val background = MaterialTheme.colorScheme.primaryContainer
    val border = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)

    // rememberVectorPainter 是 Composable，必须在组合体内直接调用（内部按 imageVector 记忆化）
    val painters = remember { mutableMapOf<DeviceType, androidx.compose.ui.graphics.vector.VectorPainter>() }
    for (type in DeviceType.entries) {
        painters[type] = rememberVectorPainter(type.icon)
    }

    return remember(density, painters, tint, background, border) {
        val byType = DeviceType.entries.associateWith { type ->
            buildIconBitmap(painters[type], markerSizePx, iconSizePx, background, border, tint, density)
        }
        val customBuilder: (String) -> Bitmap = { text -> buildCustomIconBitmap(text, markerSizePx, background, border, tint) }

        val selectedSizePx = with(density) { 48.dp.toPx().toInt() }
        val ringWidthPx = with(density) { 3.dp.toPx() }
        val ringColorArgb = Color(0xFF66CCFF).toArgb()
        val selectedByType = DeviceType.entries.associateWith { type ->
            buildSelectedDeviceIconBitmap(byType[type]!!, selectedSizePx, ringColorArgb, ringWidthPx)
        }
        val selectedCustomBuilder: (String) -> Bitmap = { text ->
            buildSelectedDeviceIconBitmap(customBuilder(text), selectedSizePx, ringColorArgb, ringWidthPx)
        }

        MapMarkerIcons(
            myLocation = buildMyLocationBitmap(myLocSizePx, tint, density),
            deviceByType = byType,
            selectedDeviceByType = selectedByType,
            customBuilder = customBuilder,
            selectedCustomBuilder = selectedCustomBuilder,
            markerSizePx = markerSizePx,
            selectedSizePx = selectedSizePx,
            ringColorArgb = ringColorArgb,
            ringWidthPx = ringWidthPx,
            borderColorArgb = border.toArgb(),
            borderWidthPx = 2f,
            density = density
        )
    }
}

private fun buildCircleBitmap(sizePx: Int, color: Color, density: Density): Bitmap {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = androidx.compose.ui.graphics.Canvas(bitmap.asImageBitmap())
    val drawScope = CanvasDrawScope()
    drawScope.draw(density, LayoutDirection.Ltr, canvas, Size(sizePx.toFloat(), sizePx.toFloat())) {
        drawCircle(color = color, radius = sizePx / 2f)
    }
    return bitmap
}

private fun buildSelectedCircleBitmap(sizePx: Int, color: Color, density: Density): Bitmap {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = androidx.compose.ui.graphics.Canvas(bitmap.asImageBitmap())
    val drawScope = CanvasDrawScope()
    drawScope.draw(density, LayoutDirection.Ltr, canvas, Size(sizePx.toFloat(), sizePx.toFloat())) {
        val outerRadius = sizePx / 2f
        val ringWidth = sizePx * 0.14f
        val blackWidth = sizePx * 0.18f
        // 黑色外环（稍宽，形成细黑边）
        drawCircle(color = Color.Black, radius = outerRadius, style = Stroke(width = blackWidth))
        // 白色环（叠在黑环上，露出两侧黑边）
        drawCircle(color = Color.White, radius = outerRadius, style = Stroke(width = ringWidth))
        // 内部彩色圆
        val innerRadius = outerRadius - blackWidth - sizePx * 0.04f
        drawCircle(color = color, radius = innerRadius.coerceAtLeast(1f))
    }
    return bitmap
}

private fun buildStartEndMarkerBitmap(sizePx: Int, color: Color, text: String): Bitmap {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val center = sizePx / 2f
    val radius = sizePx / 2f

    // 圆形底色
    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color.toArgb()
        style = Paint.Style.FILL
    }
    canvas.drawCircle(center, center, radius, bgPaint)

    // 白色圆环
    val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = android.graphics.Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = sizePx * 0.12f
    }
    canvas.drawCircle(center, center, radius * 0.85f, ringPaint)

    // 居中文字
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = sizePx * 0.5f
        this.color = android.graphics.Color.WHITE
        isFakeBoldText = true
    }
    val fm = textPaint.fontMetrics
    val baseline = center - (fm.ascent + fm.descent) / 2f
    canvas.drawText(text, center, baseline, textPaint)

    return bitmap
}

private fun buildMyLocationBitmap(sizePx: Int, tint: Color, density: Density): Bitmap {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = androidx.compose.ui.graphics.Canvas(bitmap.asImageBitmap())
    val drawScope = CanvasDrawScope()
    drawScope.draw(density, LayoutDirection.Ltr, canvas, Size(sizePx.toFloat(), sizePx.toFloat())) {
        drawCircle(color = Color.White, radius = sizePx / 2f)
        drawCircle(color = tint, radius = sizePx / 2f * 0.62f)
    }
    return bitmap
}

private fun buildIconBitmap(
    painter: androidx.compose.ui.graphics.vector.VectorPainter?,
    sizePx: Int,
    iconSizePx: Int,
    background: Color,
    border: Color,
    tint: Color,
    density: Density
): Bitmap {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = androidx.compose.ui.graphics.Canvas(bitmap.asImageBitmap())
    val drawScope = CanvasDrawScope()
    drawScope.draw(density, LayoutDirection.Ltr, canvas, Size(sizePx.toFloat(), sizePx.toFloat())) {
        drawCircle(color = background, radius = sizePx / 2f)
        drawCircle(color = border, radius = sizePx / 2f, style = Stroke(width = 2f))
        painter?.let {
            translate(left = (sizePx - iconSizePx) / 2f, top = (sizePx - iconSizePx) / 2f) {
                with(it) {
                    draw(
                        Size(iconSizePx.toFloat(), iconSizePx.toFloat()),
                        colorFilter = ColorFilter.tint(tint)
                    )
                }
            }
        }
    }
    return bitmap
}

private fun buildCustomIconBitmap(
    text: String,
    sizePx: Int,
    background: Color,
    border: Color,
    tint: Color
): Bitmap {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val center = sizePx / 2f
    val radius = sizePx / 2f

    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = background.toArgb()
        style = Paint.Style.FILL
    }
    canvas.drawCircle(center, center, radius, bgPaint)

    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = border.toArgb()
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    canvas.drawCircle(center, center, radius, borderPaint)

    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = sizePx * 0.6f
        color = tint.toArgb()
    }
    val fm = textPaint.fontMetrics
    val baseline = center - (fm.ascent + fm.descent) / 2f
    canvas.drawText(text, center, baseline, textPaint)

    return bitmap
}

private fun buildSelectedDeviceIconBitmap(
    deviceIcon: Bitmap,
    selectedSizePx: Int,
    ringColorArgb: Int,
    ringWidthPx: Float
): Bitmap {
    val bitmap = Bitmap.createBitmap(selectedSizePx, selectedSizePx, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val center = selectedSizePx / 2f

    val iconLeft = (selectedSizePx - deviceIcon.width) / 2f
    val iconTop = (selectedSizePx - deviceIcon.height) / 2f
    canvas.drawBitmap(deviceIcon, iconLeft, iconTop, null)

    val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ringColorArgb
        style = Paint.Style.STROKE
        strokeWidth = ringWidthPx
    }
    val ringRadius = selectedSizePx / 2f - ringWidthPx / 2f
    canvas.drawCircle(center, center, ringRadius, ringPaint)

    return bitmap
}

private fun buildCustomImageMarkerBitmap(imageBitmap: Bitmap, sizePx: Int, borderColorArgb: Int, borderWidthPx: Float): Bitmap {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val center = sizePx / 2f
    val scaled = Bitmap.createScaledBitmap(imageBitmap, sizePx, sizePx, true)
    val path = android.graphics.Path().apply {
        addCircle(center, center, sizePx / 2f, android.graphics.Path.Direction.CW)
    }
    canvas.save()
    canvas.clipPath(path)
    canvas.drawBitmap(scaled, 0f, 0f, null)
    canvas.restore()

    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = borderColorArgb
        style = Paint.Style.STROKE
        strokeWidth = borderWidthPx
    }
    canvas.drawCircle(center, center, sizePx / 2f, borderPaint)
    return bitmap
}
