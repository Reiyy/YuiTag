package com.yuitag.finder.ui.components

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 轨迹折线 overlay：绘制连接线 + 沿轨迹的方向箭头（旧→新）。
 * 折线在屏幕坐标下绘制，箭头朝向由相邻段方位角决定，随地图平移/缩放自动重投影。
 */
class TrajectoryOverlay : Overlay() {

    var points: List<GeoPoint> = emptyList()
    var color: Int = 0xFF2196F3.toInt()
    var lineWidthPx: Float = 6f
    var showArrows: Boolean = true
    var arrowIntervalPx: Float = 80f
    var arrowSizePx: Float = 11f
    // 箭头填充色（默认白色缺口，压在彩色折线上清晰可见）
    var arrowColor: Int = 0xFFFFFFFF.toInt()

    override fun draw(c: Canvas, osmv: MapView, shadow: Boolean) {
        if (shadow) return
        if (points.size < 2) return

        val projection = osmv.projection
        val screenPts = ArrayList<Point>(points.size)
        for (p in points) {
            val pt = Point()
            projection.toPixels(p, pt)
            screenPts.add(pt)
        }

        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = lineWidthPx
            this.color = this@TrajectoryOverlay.color
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val path = Path()
        path.moveTo(screenPts[0].x.toFloat(), screenPts[0].y.toFloat())
        for (i in 1 until screenPts.size) {
            path.lineTo(screenPts[i].x.toFloat(), screenPts[i].y.toFloat())
        }
        c.drawPath(path, linePaint)

        if (showArrows) drawArrows(c, screenPts)
    }

    private fun drawArrows(c: Canvas, pts: List<Point>) {
        if (pts.size < 2 || arrowIntervalPx <= 0f) return

        val segLens = FloatArray(pts.size - 1)
        var total = 0f
        for (i in 0 until pts.size - 1) {
            val dx = pts[i + 1].x - pts[i].x
            val dy = pts[i + 1].y - pts[i].y
            val len = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
            segLens[i] = len
            total += len
        }
        if (total <= 0f) return

        // 短路径至少在中点画一个箭头；否则按固定像素间隔均匀放置
        if (total < arrowIntervalPx) {
            drawArrowAt(c, pts, segLens, total / 2f)
        } else {
            var d = arrowIntervalPx / 2f
            while (d < total) {
                drawArrowAt(c, pts, segLens, d)
                d += arrowIntervalPx
            }
        }
    }

    private fun drawArrowAt(c: Canvas, pts: List<Point>, segLens: FloatArray, dist: Float) {
        var acc = 0f
        var idx = 0
        while (idx < segLens.size - 1 && acc + segLens[idx] < dist) {
            acc += segLens[idx]
            idx++
        }
        if (idx >= segLens.size) return

        val local = dist - acc
        val len = segLens[idx]
        val t = if (len > 0f) (local / len).coerceIn(0f, 1f) else 0f
        val x = pts[idx].x + (pts[idx + 1].x - pts[idx].x) * t
        val y = pts[idx].y + (pts[idx + 1].y - pts[idx].y) * t
        val angle = Math.toDegrees(
            atan2(
                (pts[idx + 1].y - pts[idx].y).toDouble(),
                (pts[idx + 1].x - pts[idx].x).toDouble()
            )
        ).toFloat()
        drawArrowHead(c, x, y, angle)
    }

    private fun drawArrowHead(c: Canvas, x: Float, y: Float, angleDeg: Float) {
        val rad = Math.toRadians(angleDeg.toDouble())
        val cosv = cos(rad).toFloat()
        val sinv = sin(rad).toFloat()
        // 开口箭头（< / > 形）：两段白色描边构成 V 形，尖端朝前、开口朝后
        val s = minOf(arrowSizePx, lineWidthPx * 2.5f).coerceAtLeast(lineWidthPx * 1.8f)
        val tipX = x + cosv * s * 0.5f
        val tipY = y + sinv * s * 0.5f
        val backX = x - cosv * s * 0.5f
        val backY = y - sinv * s * 0.5f
        val px = -sinv * s * 0.5f
        val py = cosv * s * 0.5f

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (lineWidthPx * 0.45f).coerceAtLeast(2f)
            this.color = arrowColor
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        c.drawLine(tipX, tipY, backX + px, backY + py, paint)
        c.drawLine(tipX, tipY, backX - px, backY - py, paint)
    }
}
