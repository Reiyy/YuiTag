package com.reiyy.yuitag.ui.components

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.view.MotionEvent
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.TileSystem
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** 轨迹连线显示模式 */
enum class TrajectoryDisplayMode {
    /** 智能弧线：去程/回程微弧线分离 + 点选时自动聚焦前后段并淡化背景线 */
    SMART_ARCS,
    /** 仅位置点：隐藏所有连接线，仅展示按时序渐变的轨迹节点 */
    POINTS_ONLY
}

/**
 * 单个轨迹节点渲染数据：包含坐标、时间戳、定位精度、与下一节点的真实距离及是否构成时间断层。
 */
data class TrajectoryNode(
    val id: Long,
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long = 0L,
    val accuracy: Int = 0,
    val distanceToNextMeters: Double = 0.0,
    val isGapToNext: Boolean = false
)

/**
 * 高可读性轨迹 Overlay：
 * 1. 单向微偏二次贝塞尔弧线（Directional Quadratic Bézier Arc）：往返路径（A→B 与 B→A）自动向两侧微拱分离，消除直线重叠与蜘蛛网感；
 * 2. 距离自适应衰减（Distance-Adaptive Attenuation）：近距连续移动保持实线，远距/跨城跳变自动收缩为细浅虚线飞弧；
 * 3. 焦点尾迹模式（Focus & Ghost Context）：选中节点或拖动时间轴时，仅高亮当前点前后移动段，其余历史线段退化为 18% 透明度极细幽灵底线；
 * 4. 屏幕像素级动态节点抽稀（LOD）+ 选中点精度圈（Accuracy Circle）+ 全量节点触摸命中检测。
 */
class TrajectoryOverlay : Overlay() {

    var points: List<GeoPoint> = emptyList()
    var nodes: List<TrajectoryNode> = emptyList()
    var displayMode: TrajectoryDisplayMode = TrajectoryDisplayMode.SMART_ARCS

    var color: Int = 0xFF2563EB.toInt()
    var startColor: Int = 0xFF10B981.toInt()
    var midColor: Int = 0xFF0EA5E9.toInt()
    var endColor: Int = 0xFF2563EB.toInt()
    var useGradient: Boolean = true
    var isDarkMap: Boolean = false

    var lineWidthPx: Float = 14f
    var casingWidthPx: Float = 22f
    var maxArcOffsetPx: Float = 96f
    var showArrows: Boolean = true
    var arrowIntervalPx: Float = 180f
    var arrowSizePx: Float = 15f
    var arrowColor: Int = 0xFFFFFFFF.toInt()

    var showIntermediateNodes: Boolean = true
    var minNodeSpacingPx: Float = 68f
    var nodeRadiusPx: Float = 11f
    var selectedNodeId: Long? = null
    var hitRadiusPx: Float = 72f
    var onNodeClick: ((Long) -> Unit)? = null

    // 缓存最近一次绘制时的可见抽稀节点索引，供点击命中优先匹配可见点
    private val lastVisibleIndices = HashSet<Int>()

    private val accuracyFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val accuracyStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val outerRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val casingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val arrowOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val arrowFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val nodeShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x55000000
    }
    private val nodeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val nodeCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val nodeClusterRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private data class SegmentGeometry(
        val index: Int,
        val x0: Float,
        val y0: Float,
        val cx: Float,
        val cy: Float,
        val x1: Float,
        val y1: Float,
        val chordLenPx: Float,
        val isCurved: Boolean,
        val path: Path
    )

    override fun draw(c: Canvas, osmv: MapView, shadow: Boolean) {
        if (shadow) return
        val hasNodes = nodes.isNotEmpty()
        val count = if (hasNodes) nodes.size else points.size
        if (count == 0) return

        val projection = osmv.projection
        val screenPts = ArrayList<Point>(count)
        if (hasNodes) {
            for (n in nodes) {
                val pt = Point()
                projection.toPixels(GeoPoint(n.latitude, n.longitude), pt)
                screenPts.add(pt)
            }
        } else {
            for (p in points) {
                val pt = Point()
                projection.toPixels(p, pt)
                screenPts.add(pt)
            }
        }

        val selectedIdx = if (hasNodes && selectedNodeId != null) {
            nodes.indexOfFirst { it.id == selectedNodeId }
        } else -1

        // 0. 绘制当前选中节点的定位精度圈（Accuracy Circle）
        if (selectedIdx >= 0) {
            val selNode = nodes[selectedIdx]
            if (selNode.accuracy > 0) {
                val metersPerPx = TileSystem.GroundResolution(selNode.latitude, osmv.zoomLevelDouble)
                if (metersPerPx > 0.0) {
                    val radiusPx = (selNode.accuracy / metersPerPx).toFloat()
                    if (radiusPx in 10f..2400f) {
                        val center = screenPts[selectedIdx]
                        accuracyFillPaint.color = if (isDarkMap) 0x2860A5FA else 0x222563EB
                        accuracyStrokePaint.color = if (isDarkMap) 0x9060A5FA.toInt() else 0x802563EB.toInt()
                        accuracyStrokePaint.strokeWidth = (lineWidthPx * 0.22f).coerceAtLeast(2f)
                        c.drawCircle(center.x.toFloat(), center.y.toFloat(), radiusPx, accuracyFillPaint)
                        c.drawCircle(center.x.toFloat(), center.y.toFloat(), radiusPx, accuracyStrokePaint)
                    }
                }
            }
        }

        val zoomFactor = ((osmv.zoomLevelDouble - 10.0) / 8.0).coerceIn(0.78, 1.18).toFloat()

        if (count >= 2 && displayMode != TrajectoryDisplayMode.POINTS_ONLY) {
            drawTrajectorySegments(c, screenPts, count, hasNodes, selectedIdx, zoomFactor)
        }

        // 4. 绘制屏幕像素级动态抽稀中间节点（LOD Waypoints）
        lastVisibleIndices.clear()
        lastVisibleIndices.add(0)
        if (count > 1) lastVisibleIndices.add(count - 1)
        if (showIntermediateNodes && count > 2) {
            drawIntermediateNodesLod(c, screenPts, selectedIdx, zoomFactor)
        }
    }

    private fun drawTrajectorySegments(
        c: Canvas,
        screenPts: List<Point>,
        count: Int,
        hasNodes: Boolean,
        selectedIdx: Int,
        zoomFactor: Float
    ) {
        val corePx = lineWidthPx * zoomFactor
        val casingPx = casingWidthPx.coerceAtLeast(corePx + 6f) * zoomFactor
        val outerRimPx = casingPx + (corePx * 0.30f).coerceAtLeast(2f)

        // 焦点窗口判定：
        // - 当用户选中了某节点（selectedIdx >= 0）时启用焦点高亮
        // - 焦点段为当前点前 2 段（来路尾迹）与后 1 段（去向），其余段降级为幽灵底线
        val isFocusActive = selectedIdx >= 0
        val focusCenter = if (selectedIdx >= 0) selectedIdx else (count - 1)
        val focusStartSeg = (focusCenter - 2).coerceAtLeast(0)
        val focusEndSeg = focusCenter.coerceAtMost(count - 2)

        val geometries = ArrayList<SegmentGeometry>(count - 1)
        for (i in 0 until count - 1) {
            val p0 = screenPts[i]
            val p1 = screenPts[i + 1]
            val dx = (p1.x - p0.x).toFloat()
            val dy = (p1.y - p0.y).toFloat()
            val len = hypot(dx.toDouble(), dy.toDouble()).toFloat()
            if (len < 1.5f) continue

            val distMeters = if (hasNodes) nodes[i].distanceToNextMeters else 0.0
            // 屏幕长度 >= 36px 或物理距离 >= 180m 时采用方向微弧线，使 A→B 与 B→A 往返线段自然向两侧分离
            val useArc = len >= 36f && (distMeters >= 180.0 || len >= 64f)
            val bowFactor = when {
                distMeters >= 2500.0 -> 0.16f
                distMeters >= 600.0 -> 0.13f
                else -> 0.10f
            }
            val bowPx = if (useArc) (len * bowFactor).coerceAtMost(maxArcOffsetPx) else 0f
            val nx = -dy / len
            val ny = dx / len
            val cx = (p0.x + p1.x) * 0.5f + nx * bowPx
            val cy = (p0.y + p1.y) * 0.5f + ny * bowPx

            val path = Path().apply {
                moveTo(p0.x.toFloat(), p0.y.toFloat())
                if (useArc) {
                    quadTo(cx, cy, p1.x.toFloat(), p1.y.toFloat())
                } else {
                    lineTo(p1.x.toFloat(), p1.y.toFloat())
                }
            }
            geometries.add(
                SegmentGeometry(
                    index = i,
                    x0 = p0.x.toFloat(),
                    y0 = p0.y.toFloat(),
                    cx = cx,
                    cy = cy,
                    x1 = p1.x.toFloat(),
                    y1 = p1.y.toFloat(),
                    chordLenPx = len,
                    isCurved = useArc,
                    path = path
                )
            )
        }

        // Pass 1: 若处于焦点尾迹模式（SMART_ARCS + 选中节点），先在底层绘制极细半透明「幽灵背景轨迹」
        if (isFocusActive && displayMode == TrajectoryDisplayMode.SMART_ARCS) {
            corePaint.pathEffect = null
            corePaint.strokeWidth = (corePx * 0.38f).coerceAtLeast(3f)
            for (geom in geometries) {
                val i = geom.index
                if (i in focusStartSeg..focusEndSeg) continue
                val progress = if (count <= 2) 1f else i.toFloat() / (count - 2).toFloat()
                val baseColor = if (useGradient) interpolateColor(progress) else color
                corePaint.color = applyAlpha(baseColor, if (isDarkMap) 0.24f else 0.20f)
                c.drawPath(geom.path, corePaint)
            }
        }

        // Pass 2: 绘制前景可见线段（焦点段 或 全览段）的外护边 Casing（全部保持正常清晰实线）
        outerRimPaint.pathEffect = null
        casingPaint.pathEffect = null
        for (geom in geometries) {
            val i = geom.index
            val inFocus = i in focusStartSeg..focusEndSeg
            if (isFocusActive && !inFocus) continue

            val widthScale = if (inFocus && isFocusActive) 1.05f else 1.0f
            val casingAlpha = if (inFocus && isFocusActive) 0.96f else 0.92f

            outerRimPaint.strokeWidth = outerRimPx * widthScale
            outerRimPaint.color = applyAlpha(if (isDarkMap) 0x80000000.toInt() else 0x450F172A, casingAlpha)
            c.drawPath(geom.path, outerRimPaint)

            casingPaint.strokeWidth = casingPx * widthScale
            val baseCasingCol = if (isDarkMap) 0xEB1E293B.toInt() else 0xF5FFFFFF.toInt()
            casingPaint.color = applyAlpha(baseCasingCol, casingAlpha)
            c.drawPath(geom.path, casingPaint)
        }

        // Pass 3: 绘制前景可见线段的时序渐变实线内芯与方向箭头
        corePaint.pathEffect = null
        for (geom in geometries) {
            val i = geom.index
            val inFocus = i in focusStartSeg..focusEndSeg
            if (isFocusActive && !inFocus) continue

            val progress = if (count <= 2) 1f else i.toFloat() / (count - 2).toFloat()
            val baseColor = if (useGradient) interpolateColor(progress) else color

            val widthScale: Float
            val segAlpha: Float
            if (isFocusActive && inFocus) {
                widthScale = if (i == focusCenter - 1 || i == focusCenter) 1.08f else 0.92f
                segAlpha = when (i) {
                    focusCenter - 1 -> 1.0f // 当前直接来路最亮
                    focusCenter -> 0.94f    // 下一步去向次之
                    else -> 0.76f           // 前序尾迹渐隐
                }
            } else {
                widthScale = 1.0f
                segAlpha = 0.78f + 0.22f * progress
            }

            corePaint.strokeWidth = corePx * widthScale
            corePaint.color = applyAlpha(baseColor, segAlpha)
            c.drawPath(geom.path, corePaint)

            // 绘制弧线上的方向箭头
            if (showArrows && geom.chordLenPx >= corePx * 2.6f) {
                val arrowScale = widthScale.coerceIn(0.85f, 1.05f)
                val arrowAlpha = (segAlpha + 0.12f).coerceIn(0.75f, 1.0f)
                if (geom.chordLenPx < arrowIntervalPx * 1.4f) {
                    drawArrowOnGeometry(c, geom, 0.52f, corePx * arrowScale, arrowAlpha)
                } else {
                    val stepCount = (geom.chordLenPx / arrowIntervalPx).toInt().coerceIn(1, 3)
                    for (k in 1..stepCount) {
                        val t = k.toFloat() / (stepCount + 1).toFloat()
                        drawArrowOnGeometry(c, geom, t, corePx * arrowScale, arrowAlpha)
                    }
                }
            }
        }
    }

    private fun drawArrowOnGeometry(
        c: Canvas,
        geom: SegmentGeometry,
        t: Float,
        effectiveCorePx: Float,
        alphaFactor: Float
    ) {
        val u = 1f - t
        val x: Float
        val y: Float
        val tx: Float
        val ty: Float
        if (geom.isCurved) {
            x = u * u * geom.x0 + 2f * u * t * geom.cx + t * t * geom.x1
            y = u * u * geom.y0 + 2f * u * t * geom.cy + t * t * geom.y1
            tx = 2f * u * (geom.cx - geom.x0) + 2f * t * (geom.x1 - geom.cx)
            ty = 2f * u * (geom.cy - geom.y0) + 2f * t * (geom.y1 - geom.cy)
        } else {
            x = geom.x0 + (geom.x1 - geom.x0) * t
            y = geom.y0 + (geom.y1 - geom.y0) * t
            tx = geom.x1 - geom.x0
            ty = geom.y1 - geom.y0
        }
        val angle = Math.toDegrees(atan2(ty.toDouble(), tx.toDouble())).toFloat()
        drawArrowHead(c, x, y, angle, effectiveCorePx, alphaFactor)
    }

    private fun drawIntermediateNodesLod(
        c: Canvas,
        screenPts: List<Point>,
        selectedIdx: Int,
        zoomFactor: Float
    ) {
        val count = screenPts.size
        val firstPt = screenPts.first()
        val lastPt = screenPts.last()
        var lastDrawnPt = firstPt
        var foldedSinceLast = 0

        val baseR = (nodeRadiusPx * zoomFactor).coerceAtLeast(7f)
        val coreR = baseR * 0.64f
        val shadowR = baseR + 1.8f
        val isFocusActive = selectedIdx >= 0
        val focusCenter = if (selectedIdx >= 0) selectedIdx else (count - 1)
        val focusNodeRange = (focusCenter - 2)..(focusCenter + 1)

        for (i in 1 until count - 1) {
            val pt = screenPts[i]
            if (i == selectedIdx) {
                lastVisibleIndices.add(i)
                lastDrawnPt = pt
                foldedSinceLast = 0
                continue
            }

            val inFocus = i in focusNodeRange

            val distPrev = hypot((pt.x - lastDrawnPt.x).toDouble(), (pt.y - lastDrawnPt.y).toDouble()).toFloat()
            val distEnd = hypot((pt.x - lastPt.x).toDouble(), (pt.y - lastPt.y).toDouble()).toFloat()
            val distStart = hypot((pt.x - firstPt.x).toDouble(), (pt.y - firstPt.y).toDouble()).toFloat()

            val spacingThreshold = if (inFocus && isFocusActive) minNodeSpacingPx * 0.55f else minNodeSpacingPx
            if (distPrev >= spacingThreshold &&
                distEnd >= spacingThreshold * 0.75f &&
                distStart >= spacingThreshold * 0.75f
            ) {
                val progress = i.toFloat() / (count - 1).toFloat()
                val ptColor = if (useGradient) interpolateColor(progress) else color
                val cx = pt.x.toFloat()
                val cy = pt.y.toFloat()

                val nodeScale = if (isFocusActive && !inFocus) 0.78f else 1.0f
                val nodeAlpha = if (isFocusActive && !inFocus) 0.52f else 1.0f

                nodeShadowPaint.color = applyAlpha(0x55000000, nodeAlpha)
                nodeBorderPaint.color = applyAlpha(Color.WHITE, nodeAlpha)

                if (foldedSinceLast >= 2 && (!isFocusActive || inFocus)) {
                    val clusterR = baseR * 1.22f * nodeScale
                    c.drawCircle(cx, cy, clusterR + 1.6f, nodeShadowPaint)
                    c.drawCircle(cx, cy, clusterR, nodeBorderPaint)
                    nodeClusterRingPaint.color = applyAlpha(ptColor, 0.45f * nodeAlpha)
                    nodeClusterRingPaint.strokeWidth = (baseR * 0.26f).coerceAtLeast(2f)
                    c.drawCircle(cx, cy, clusterR * 0.82f, nodeClusterRingPaint)
                    nodeCorePaint.color = applyAlpha(ptColor, nodeAlpha)
                    c.drawCircle(cx, cy, coreR * 0.88f * nodeScale, nodeCorePaint)
                } else {
                    c.drawCircle(cx, cy, shadowR * nodeScale, nodeShadowPaint)
                    c.drawCircle(cx, cy, baseR * nodeScale, nodeBorderPaint)
                    nodeCorePaint.color = applyAlpha(ptColor, nodeAlpha)
                    c.drawCircle(cx, cy, coreR * nodeScale, nodeCorePaint)
                }

                lastVisibleIndices.add(i)
                lastDrawnPt = pt
                foldedSinceLast = 0
            } else {
                foldedSinceLast++
            }
        }
    }

    private fun drawArrowHead(
        c: Canvas,
        x: Float,
        y: Float,
        angleDeg: Float,
        corePx: Float,
        alphaFactor: Float
    ) {
        val rad = Math.toRadians(angleDeg.toDouble())
        val cosv = cos(rad).toFloat()
        val sinv = sin(rad).toFloat()

        val s = minOf(arrowSizePx, corePx * 1.45f).coerceAtLeast(corePx * 1.0f)
        val tipX = x + cosv * s * 0.48f
        val tipY = y + sinv * s * 0.48f
        val backX = x - cosv * s * 0.42f
        val backY = y - sinv * s * 0.42f
        val wingX = -sinv * s * 0.46f
        val wingY = cosv * s * 0.46f

        val arrowPath = Path().apply {
            moveTo(backX + wingX, backY + wingY)
            lineTo(tipX, tipY)
            lineTo(backX - wingX, backY - wingY)
        }

        arrowOutlinePaint.strokeWidth = (corePx * 0.54f).coerceAtLeast(4.0f)
        arrowOutlinePaint.color = applyAlpha(0x990F172A.toInt(), alphaFactor)
        c.drawPath(arrowPath, arrowOutlinePaint)

        arrowFillPaint.strokeWidth = (corePx * 0.34f).coerceAtLeast(2.2f)
        arrowFillPaint.color = applyAlpha(arrowColor, alphaFactor)
        c.drawPath(arrowPath, arrowFillPaint)
    }

    override fun onSingleTapConfirmed(e: MotionEvent, mapView: MapView): Boolean {
        val callback = onNodeClick ?: return false
        if (nodes.isEmpty()) return false

        val projection = mapView.projection
        val pt = Point()
        val tapX = e.x
        val tapY = e.y

        var bestVisibleId: Long? = null
        var bestVisibleDist = hitRadiusPx

        var bestAnyId: Long? = null
        var bestAnyDist = hitRadiusPx * 0.85f

        for (i in nodes.indices) {
            val n = nodes[i]
            projection.toPixels(GeoPoint(n.latitude, n.longitude), pt)
            val d = hypot((pt.x - tapX).toDouble(), (pt.y - tapY).toDouble()).toFloat()
            if (i in lastVisibleIndices) {
                if (d < bestVisibleDist) {
                    bestVisibleDist = d
                    bestVisibleId = n.id
                }
            }
            if (d < bestAnyDist) {
                bestAnyDist = d
                bestAnyId = n.id
            }
        }

        val hitId = bestVisibleId ?: bestAnyId
        if (hitId != null) {
            callback(hitId)
            return true
        }
        return false
    }

    /** 三段式色彩插值：0.0(起点翠绿) -> 0.5(中段青蓝) -> 1.0(终点科技蓝) */
    private fun interpolateColor(fraction: Float): Int {
        val f = fraction.coerceIn(0f, 1f)
        return if (f <= 0.5f) {
            blendArgb(startColor, midColor, f * 2f)
        } else {
            blendArgb(midColor, endColor, (f - 0.5f) * 2f)
        }
    }

    private fun blendArgb(c1: Int, c2: Int, ratio: Float): Int {
        val r = ratio.coerceIn(0f, 1f)
        val a = (Color.alpha(c1) + (Color.alpha(c2) - Color.alpha(c1)) * r).toInt()
        val red = (Color.red(c1) + (Color.red(c2) - Color.red(c1)) * r).toInt()
        val g = (Color.green(c1) + (Color.green(c2) - Color.green(c1)) * r).toInt()
        val b = (Color.blue(c1) + (Color.blue(c2) - Color.blue(c1)) * r).toInt()
        return Color.argb(a, red, g, b)
    }

    private fun applyAlpha(color: Int, factor: Float): Int {
        val a = (Color.alpha(color) * factor).toInt().coerceIn(0, 255)
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }
}
