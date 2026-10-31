package com.reiyy.yuitag.ui.components

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.BitmapDrawable
import com.reiyy.yuitag.ui.theme.LocalDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.reiyy.yuitag.util.TiandituTileSource
import kotlinx.coroutines.delay
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.TileSystem
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.TilesOverlay
import org.osmdroid.views.overlay.compass.CompassOverlay
import java.io.File
import kotlin.math.ln

// 居中时目标点落在屏幕高度比例（避开顶栏与底部卡片）
private const val CENTER_OFFSET_FRACTION = 0.30f
private const val SELF_MARKER_KEY = "__self__"
// 指南针空闲休眠：无地图交互超过该时长后停止传感器监听与持续重绘
private const val COMPASS_IDLE_MS = 10_000L

/** 就地更新 marker 的位置/标题/图标（图标位图未变时跳过赋值，避免重复创建 Drawable） */
private fun applyMarker(marker: Marker, data: MapMarkerData, mapView: MapView) {
    marker.position = GeoPoint(data.latitude, data.longitude)
    marker.title = data.title
    data.icon?.let { bmp ->
        val current = (marker.icon as? BitmapDrawable)?.bitmap
        if (current !== bmp) {
            marker.icon = BitmapDrawable(mapView.context.resources, bmp)
        }
    }
}

// App 支持的全局地图缩放范围（允许超出瓦片服务端原生层级进行数字超分放大 overzoom）
private const val APP_MIN_ZOOM = 3.0
private const val APP_MAX_ZOOM = 22.0

// 天地图样式 → 底图图层 / 注记图层 / 服务端瓦片最大缩放级别。
// ter(地形晕渲)服务端瓦片仅提供到 14 级，其余为 18 级。超过后由 MapView 自动数字放大显示。
private data class TiandituLayerConfig(
    val base: String,
    val label: String,
    val tileMaxZoom: Int
)

private fun tiandituLayerFor(style: String): TiandituLayerConfig = when (style) {
    TiandituTileSource.STYLE_TERRAIN ->
        TiandituLayerConfig("ter", "cta", TiandituTileSource.MAX_ZOOM_TERRAIN)
    TiandituTileSource.STYLE_SATELLITE ->
        TiandituLayerConfig("img", "cia", TiandituTileSource.MAX_ZOOM)
    else ->
        TiandituLayerConfig("vec", "cva", TiandituTileSource.MAX_ZOOM)
}

/**
 * 暗夜地图调优滤镜：
 * 1. 去饱和度 (0.18f)：大幅压低日间瓦片原色彩度（如蓝水、绿草），避免直接反色后产生刺眼的土黄水体或紫红植被；
 * 2. 反色与冷调映射：白色路网与陆地映射至柔和低对比的暗夜冷灰(#10111A)，黑色地名注记映射至高可读性的浅白(#E2E2EC)；
 * 3. 保持 Alpha 通道不受影响，透明背景注记层完美保留。
 */
private val MapDarkColorFilter: ColorMatrixColorFilter by lazy {
    val satMatrix = ColorMatrix().apply {
        setSaturation(0.18f)
    }
    val invertMatrix = ColorMatrix(floatArrayOf(
        -0.85f, 0.00f, 0.00f, 0f, 226f,
        0.00f, -0.85f, 0.00f, 0f, 226f,
        0.00f, 0.00f, -0.80f, 0f, 236f,
        0.00f, 0.00f, 0.00f, 1f, 0f
    ))
    invertMatrix.preConcat(satMatrix)
    ColorMatrixColorFilter(invertMatrix)
}

private fun applyMapTheme(
    mapView: MapView,
    annotationOverlay: TilesOverlay?,
    isDark: Boolean,
    tiandituStyle: String
) {
    val shouldDarken = isDark && tiandituStyle != TiandituTileSource.STYLE_SATELLITE
    val filter = if (shouldDarken) MapDarkColorFilter else null

    val bgColor = if (shouldDarken) {
        android.graphics.Color.parseColor("#1B1C1E")
    } else {
        android.graphics.Color.parseColor("#F5F5F5")
    }
    val lineCol = if (shouldDarken) {
        android.graphics.Color.parseColor("#2C2D30")
    } else {
        android.graphics.Color.parseColor("#CCCCCC")
    }

    mapView.setBackgroundColor(bgColor)
    mapView.overlayManager.tilesOverlay?.let { tilesOverlay ->
        tilesOverlay.setColorFilter(filter)
        tilesOverlay.loadingBackgroundColor = bgColor
        tilesOverlay.loadingLineColor = lineCol
    }
    annotationOverlay?.let { labelOverlay ->
        labelOverlay.setColorFilter(filter)
        labelOverlay.loadingBackgroundColor = android.graphics.Color.TRANSPARENT
        labelOverlay.loadingLineColor = android.graphics.Color.TRANSPARENT
    }
}

@Composable
fun OsmMapView(
    initialLatitude: Double = 39.9042,
    initialLongitude: Double = 116.4074,
    zoomLevel: Double = 15.0,
    targetCenter: Pair<Double, Double>? = null,
    animateToTarget: Boolean = true,
    onMapReady: ((MapView) -> Unit)? = null,
    onMapTouched: (() -> Unit)? = null,
    onMarkerClick: ((String) -> Unit)? = null,
    zoomToTargetLevel: Double? = null,
    preserveZoomOnCenter: Boolean = false,
    centerOffsetFraction: Float = CENTER_OFFSET_FRACTION,
    recenterToken: Int = 0,
    modifiers: Modifier = Modifier,
    markers: List<MapMarkerData> = emptyList(),
    selectedUid: String? = null,
    selfMarkerToFrontToken: Int = 0,
    tiandituKey: String = "",
    tiandituStyle: String = TiandituTileSource.STYLE_VECTOR,
    paused: Boolean = false,
    polylines: List<MapPolylineData> = emptyList(),
    fitBoundsPoints: List<Pair<Double, Double>> = emptyList(),
    fitBoundsToken: Int = 0,
    fitBoundsTopPaddingDp: Float = 64f,
    fitBoundsBottomPaddingDp: Float = 64f,
    fitBoundsHorizontalPaddingDp: Float = 48f,
    fitBoundsAnimated: Boolean = true,
    isDark: Boolean = LocalDarkTheme.current
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val currentOnMapTouched by rememberUpdatedState(onMapTouched)

    // 最后一次地图交互时间（指南针空闲休眠依据），先于 mapView 定义供内部回调引用
    val lastMapInteractionMs = remember { mutableLongStateOf(System.currentTimeMillis()) }

    // 地图空白处触摸监听（置于底层，仅当上层 Marker 与 TrajectoryOverlay 均未消费点击时触发）
    val touchOverlay = remember {
        MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                lastMapInteractionMs.longValue = System.currentTimeMillis()
                currentOnMapTouched?.invoke()
                return false
            }
            override fun longPressHelper(p: GeoPoint?): Boolean {
                lastMapInteractionMs.longValue = System.currentTimeMillis()
                return false
            }
        })
    }

    val mapView = remember {
        val cacheDir = File(context.cacheDir, "osmdroid")
        if (!cacheDir.exists()) cacheDir.mkdirs()

        val versionName = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (_: PackageManager.NameNotFoundException) {
            "1.0"
        }

        Configuration.getInstance().apply {
            osmdroidTileCache = cacheDir
            osmdroidBasePath = context.cacheDir.resolve("osmdroid")
            userAgentValue = "${context.packageName}/$versionName"
        }

        MapView(context).apply {
            setMultiTouchControls(true)
            setUseDataConnection(true)
            val initBg = if (isDark && tiandituStyle != TiandituTileSource.STYLE_SATELLITE) {
                android.graphics.Color.parseColor("#1B1C1E")
            } else {
                android.graphics.Color.parseColor("#F5F5F5")
            }
            setBackgroundColor(initBg)
            controller.setZoom(zoomLevel.coerceIn(APP_MIN_ZOOM, APP_MAX_ZOOM))
            controller.setCenter(GeoPoint(initialLatitude, initialLongitude))
            minZoomLevel = APP_MIN_ZOOM
            maxZoomLevel = APP_MAX_ZOOM
            overlays.add(touchOverlay)
        }
    }

    // 指南针独立创建并启用（后续由空闲休眠循环控制 enable/disable）
    val compassOverlay = remember(mapView) {
        CompassOverlay(context, mapView).also {
            it.enableCompass()
            mapView.overlays.add(it)
        }
    }

    // pan/zoom 手势同样视为交互（唤醒指南针）
    DisposableEffect(mapView) {
        val listener = object : MapListener {
            override fun onScroll(event: ScrollEvent): Boolean {
                lastMapInteractionMs.longValue = System.currentTimeMillis()
                return false
            }
            override fun onZoom(event: ZoomEvent): Boolean {
                lastMapInteractionMs.longValue = System.currentTimeMillis()
                return false
            }
        }
        mapView.addMapListener(listener)
        onDispose { mapView.removeMapListener(listener) }
    }

    DisposableEffect(Unit) {
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onDetach()
        }
    }

    // 底部面板几乎全屏盖住地图时暂停渲染（省 GPU/网络），恢复时继续
    LaunchedEffect(paused) {
        if (paused) mapView.onPause() else mapView.onResume()
    }

    // 指南针空闲休眠：paused 或超过 COMPASS_IDLE_MS 无交互 → disableCompass；
    // 恢复可见且有交互 → enableCompass（避免传感器持续采样 + 地图持续重绘）
    val currentPaused by rememberUpdatedState(paused)
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000L)
            val shouldSleep = currentPaused ||
                (System.currentTimeMillis() - lastMapInteractionMs.longValue > COMPASS_IDLE_MS)
            if (shouldSleep) {
                if (compassOverlay.isCompassEnabled) {
                    compassOverlay.disableCompass()
                    mapView.invalidate()
                }
            } else {
                if (!compassOverlay.isCompassEnabled) {
                    compassOverlay.enableCompass()
                    mapView.invalidate()
                }
            }
        }
    }

    // marker 按 key 增量更新，避免 markers 变化时全量重建
    val markerCache = remember { mutableMapOf<String, Marker>() }

    // 天地图瓦片源（可选）：填了 key 用天地图，否则回退 OSM
    // 重要：注记层固定插入在 overlays[0]（最底层），确保轨迹折线与标记点始终绘制在地名/路网文字之上
    var annotationOverlay by remember { mutableStateOf<TilesOverlay?>(null) }
    LaunchedEffect(tiandituKey, tiandituStyle) {
        annotationOverlay?.let { mapView.overlays.remove(it) }
        annotationOverlay = null

        if (tiandituKey.isBlank()) {
            mapView.setTileProvider(MapTileProviderBasic(context, TileSourceFactory.MAPNIK))
            mapView.maxZoomLevel = APP_MAX_ZOOM
        } else {
            val layer = tiandituLayerFor(tiandituStyle)
            val baseSource = TiandituTileSource(
                "TIANDITU_${layer.base}", layer.base, tiandituKey, maxZoom = layer.tileMaxZoom
            )
            val labelSource = TiandituTileSource(
                "TIANDITU_${layer.label}", layer.label, tiandituKey, maxZoom = layer.tileMaxZoom
            )

            mapView.setTileProvider(MapTileProviderBasic(context, baseSource))
            val labelOverlay = TilesOverlay(MapTileProviderBasic(context, labelSource), context).apply {
                loadingBackgroundColor = android.graphics.Color.TRANSPARENT
                loadingLineColor = android.graphics.Color.TRANSPARENT
            }
            mapView.overlays.add(0, labelOverlay)
            annotationOverlay = labelOverlay
            mapView.maxZoomLevel = APP_MAX_ZOOM
        }
        applyMapTheme(mapView, annotationOverlay, isDark, tiandituStyle)
        mapView.invalidate()
    }

    // 主题深浅模式或底图样式切换：动态更新瓦片滤镜与背景色，无需重新拉取瓦片
    LaunchedEffect(isDark, annotationOverlay, tiandituStyle) {
        applyMapTheme(mapView, annotationOverlay, isDark, tiandituStyle)
        mapView.invalidate()
    }

    // 位置变更时平滑移动至目标中心
    LaunchedEffect(targetCenter, animateToTarget, recenterToken) {
        if (targetCenter != null && animateToTarget) {
            val (lat, lng) = targetCenter
            if (lat != 0.0 && lng != 0.0) {
                val zoom = when {
                    preserveZoomOnCenter && zoomToTargetLevel == null ->
                        mapView.zoomLevelDouble.coerceIn(APP_MIN_ZOOM, APP_MAX_ZOOM)
                    else -> {
                        val targetZoom = zoomToTargetLevel ?: 15.0
                        mapView.zoomLevelDouble.coerceAtLeast(targetZoom).coerceAtMost(APP_MAX_ZOOM)
                    }
                }
                mapView.post {
                    if (!mapView.isLaidOut) return@post
                    val h = mapView.height
                    val offY = (h * centerOffsetFraction).toInt()
                    val ts = MapView.getTileSystem()
                    val mapSize = TileSystem.MapSize(zoom)
                    val x01 = ts.getX01FromLongitude(lng)
                    val y01 = ts.getY01FromLatitude(lat)
                    // 地图中心放在点下方 (h/2 - offY) 像素处 → 点落在屏幕 offY 高度，避开底部卡片
                    val centerY01 = (y01 + (h / 2.0 - offY) / mapSize).coerceIn(0.0, 1.0)
                    val centerLat = ts.getLatitudeFromY01(centerY01)
                    val centerLng = ts.getLongitudeFromX01(x01.coerceIn(0.0, 1.0))
                    mapView.controller.animateTo(GeoPoint(centerLat, centerLng), zoom, 500L)
                }
            }
        }
    }

    // 增量更新标记点（按 key 新增/更新/移除，避免每次全量重建）
    DisposableEffect(markers, onMarkerClick) {
        val retained = mutableSetOf<String>()
        markers.forEach { data ->
            val key = if (data.uid.isEmpty()) SELF_MARKER_KEY else data.uid
            retained.add(key)
            val existing = markerCache[key]
            if (existing == null) {
                val marker = Marker(mapView)
                marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                if (data.uid.isNotEmpty()) {
                    marker.setOnMarkerClickListener { _, _ ->
                        lastMapInteractionMs.longValue = System.currentTimeMillis()
                        onMarkerClick?.invoke(data.uid)
                        true
                    }
                }
                mapView.overlays.add(marker)
                markerCache[key] = marker
                applyMarker(marker, data, mapView)
            } else {
                applyMarker(existing, data, mapView)
            }
        }
        markerCache.keys.toList().forEach { key ->
            if (key !in retained) {
                mapView.overlays.remove(markerCache[key])
                markerCache.remove(key)
            }
        }
        // 初期或未选中时，确保本机标记排在所有 marker 最上方；若已有选中则保持选中标记在最顶层
        if (selectedUid != null) {
            markerCache[selectedUid]?.let { marker ->
                mapView.overlays.remove(marker)
                mapView.overlays.add(marker)
            }
        } else {
            markerCache[SELF_MARKER_KEY]?.let { selfMarker ->
                mapView.overlays.remove(selfMarker)
                mapView.overlays.add(selfMarker)
            }
        }
        mapView.invalidate()
        onDispose {}
    }

    // 将选中标记移至顶层，取消选中时恢复本机标记至顶层
    LaunchedEffect(selectedUid) {
        if (selectedUid != null) {
            markerCache[selectedUid]?.let { marker ->
                mapView.overlays.remove(marker)
                mapView.overlays.add(marker)
                mapView.invalidate()
            }
        } else {
            markerCache[SELF_MARKER_KEY]?.let { selfMarker ->
                mapView.overlays.remove(selfMarker)
                mapView.overlays.add(selfMarker)
                mapView.invalidate()
            }
        }
    }

    // 手动刷新定位时将本机标记移至顶层
    LaunchedEffect(selfMarkerToFrontToken) {
        if (selfMarkerToFrontToken > 0) {
            markerCache[SELF_MARKER_KEY]?.let { selfMarker ->
                mapView.overlays.remove(selfMarker)
                mapView.overlays.add(selfMarker)
                mapView.invalidate()
            }
        }
    }

    // 轨迹折线（含双层护边、方向箭头、动态抽稀节点与点击命中）：按 id 增量更新。
    // 插入在天地图注记层 annotationOverlay 之后、Marker 之前，确保轨迹压在路名之上且在起终图标之下。
    val polylineCache = remember { mutableMapOf<String, TrajectoryOverlay>() }
    DisposableEffect(polylines, isDark, tiandituStyle, annotationOverlay) {
        val retained = mutableSetOf<String>()
        val isDarkMapSurface = isDark || tiandituStyle == TiandituTileSource.STYLE_SATELLITE

        fun applyPolylineData(overlay: TrajectoryOverlay, data: MapPolylineData) {
            overlay.points = data.points.map { GeoPoint(it.first, it.second) }
            overlay.nodes = data.nodes
            overlay.displayMode = data.displayMode
            overlay.color = data.color
            overlay.useGradient = data.useGradient
            overlay.startColor = data.startColor
            overlay.midColor = data.midColor
            overlay.endColor = data.endColor
            overlay.isDarkMap = isDarkMapSurface
            overlay.lineWidthPx = with(density) { data.widthDp.dp.toPx() }
            overlay.casingWidthPx = with(density) { data.casingWidthDp.dp.toPx() }
            overlay.maxArcOffsetPx = with(density) { data.maxArcOffsetDp.dp.toPx() }
            overlay.showArrows = data.showArrows
            overlay.arrowIntervalPx = with(density) { data.arrowIntervalDp.dp.toPx() }
            overlay.arrowSizePx = with(density) { data.arrowSizeDp.dp.toPx() }
            overlay.showIntermediateNodes = data.showIntermediateNodes
            overlay.minNodeSpacingPx = with(density) { data.minNodeSpacingDp.dp.toPx() }
            overlay.nodeRadiusPx = with(density) { data.nodeRadiusDp.dp.toPx() }
            overlay.hitRadiusPx = with(density) { 28.dp.toPx() }
            overlay.selectedNodeId = data.selectedNodeId
            overlay.onNodeClick = data.onNodeClick?.let { cb ->
                { nodeId ->
                    lastMapInteractionMs.longValue = System.currentTimeMillis()
                    cb(nodeId)
                }
            }
        }

        polylines.forEach { data ->
            retained.add(data.id)
            val existing = polylineCache[data.id]
            val labelIdx = annotationOverlay?.let { mapView.overlays.indexOf(it) } ?: -1
            val touchIdx = mapView.overlays.indexOf(touchOverlay)
            val minTargetIdx = (maxOf(labelIdx, touchIdx) + 1).coerceAtLeast(0)

            if (existing == null) {
                val overlay = TrajectoryOverlay()
                applyPolylineData(overlay, data)
                mapView.overlays.add(minTargetIdx.coerceAtMost(mapView.overlays.size), overlay)
                polylineCache[data.id] = overlay
            } else {
                applyPolylineData(existing, data)
                // 确保 TrajectoryOverlay 始终位于 annotationOverlay 与 touchOverlay 之上
                val polyIdx = mapView.overlays.indexOf(existing)
                if (polyIdx in 0 until minTargetIdx) {
                    mapView.overlays.remove(existing)
                    val targetIdx = (maxOf(
                        annotationOverlay?.let { mapView.overlays.indexOf(it) } ?: -1,
                        mapView.overlays.indexOf(touchOverlay)
                    ) + 1).coerceIn(0, mapView.overlays.size)
                    mapView.overlays.add(targetIdx, existing)
                }
            }
        }
        polylineCache.keys.toList().forEach { id ->
            if (id !in retained) {
                mapView.overlays.remove(polylineCache[id])
                polylineCache.remove(id)
            }
        }
        mapView.invalidate()
        onDispose {}
    }

    // 自动缩放至覆盖全部轨迹点（考虑顶部筛选栏与底部时间轴面板的非对称安全边距，并支持平滑过渡）
    var hasInitialFitDone by remember { mutableStateOf(false) }
    LaunchedEffect(fitBoundsPoints, fitBoundsToken) {
        if (fitBoundsPoints.isEmpty()) return@LaunchedEffect
        val pts = fitBoundsPoints.map { GeoPoint(it.first, it.second) }
        mapView.post {
            if (!mapView.isLaidOut || mapView.width <= 0 || mapView.height <= 0) return@post
            val padTopPx = with(density) { fitBoundsTopPaddingDp.dp.toPx() }
            val padBottomPx = with(density) { fitBoundsBottomPaddingDp.dp.toPx() }
            val padHorizPx = with(density) { fitBoundsHorizontalPaddingDp.dp.toPx() }
            val ts = MapView.getTileSystem()

            var minLat = 90.0
            var maxLat = -90.0
            var minLng = 180.0
            var maxLng = -180.0
            for (p in pts) {
                if (p.latitude < minLat) minLat = p.latitude
                if (p.latitude > maxLat) maxLat = p.latitude
                if (p.longitude < minLng) minLng = p.longitude
                if (p.longitude > maxLng) maxLng = p.longitude
            }

            val isSingleOrTightCluster = pts.size == 1 ||
                (maxLat - minLat < 1e-4 && maxLng - minLng < 1e-4)

            val targetZoom: Double
            val midX01: Double
            val midY01: Double

            if (isSingleOrTightCluster) {
                targetZoom = if (pts.size == 1) 16.5 else 17.5
                midX01 = ts.getX01FromLongitude((minLng + maxLng) / 2.0)
                midY01 = ts.getY01FromLatitude((minLat + maxLat) / 2.0)
            } else {
                val availW = (mapView.width - padHorizPx * 2f).coerceAtLeast(100f).toDouble()
                val availH = (mapView.height - padTopPx - padBottomPx).coerceAtLeast(100f).toDouble()
                val xMin = ts.getX01FromLongitude(minLng)
                val xMax = ts.getX01FromLongitude(maxLng)
                val yMin = ts.getY01FromLatitude(maxLat)
                val yMax = ts.getY01FromLatitude(minLat)
                val spanX = (xMax - xMin).coerceAtLeast(1e-7)
                val spanY = (yMax - yMin).coerceAtLeast(1e-7)

                val reqMapSize = minOf(availW / spanX, availH / spanY)
                val tileSize = TileSystem.getTileSize().toDouble().coerceAtLeast(256.0)
                targetZoom = (ln(reqMapSize / tileSize) / ln(2.0)).coerceIn(APP_MIN_ZOOM, 18.0)
                midX01 = (xMin + xMax) / 2.0
                midY01 = (yMin + yMax) / 2.0
            }

            val mapSize = TileSystem.MapSize(targetZoom)
            // 补偿上下非对称内边距，使轨迹外包框中心落在可视安全区域正中央
            val shiftY01 = (padBottomPx - padTopPx) / 2.0 / mapSize
            val centerLat = ts.getLatitudeFromY01((midY01 + shiftY01).coerceIn(0.0, 1.0))
            val centerLng = ts.getLongitudeFromX01(midX01.coerceIn(0.0, 1.0))
            val centerGeo = GeoPoint(centerLat, centerLng)

            if (hasInitialFitDone && fitBoundsAnimated) {
                mapView.controller.animateTo(centerGeo, targetZoom, 550L)
            } else {
                mapView.controller.setZoom(targetZoom)
                mapView.controller.setCenter(centerGeo)
                hasInitialFitDone = true
            }
            mapView.invalidate()
        }
    }

    AndroidView(
        factory = {
            onMapReady?.invoke(mapView)
            mapView
        },
        modifier = modifiers,
        update = { view ->
            view.invalidate()
        }
    )
}

data class MapMarkerData(
    val latitude: Double,
    val longitude: Double,
    val title: String,
    val uid: String = "",
    val icon: Bitmap? = null
)

data class MapPolylineData(
    val id: String,
    val points: List<Pair<Double, Double>>,
    val color: Int,
    val widthDp: Float = 5.2f,
    val casingWidthDp: Float = 8.4f,
    val maxArcOffsetDp: Float = 36f,
    val showArrows: Boolean = false,
    val arrowIntervalDp: Float = 68f,
    val arrowSizeDp: Float = 13f,
    val useGradient: Boolean = true,
    val startColor: Int = 0xFF10B981.toInt(),
    val midColor: Int = 0xFF0EA5E9.toInt(),
    val endColor: Int = 0xFF2563EB.toInt(),
    val nodes: List<TrajectoryNode> = emptyList(),
    val displayMode: TrajectoryDisplayMode = TrajectoryDisplayMode.SMART_ARCS,
    val showIntermediateNodes: Boolean = true,
    val minNodeSpacingDp: Float = 24f,
    val nodeRadiusDp: Float = 4.2f,
    val selectedNodeId: Long? = null,
    val onNodeClick: ((Long) -> Unit)? = null
)
