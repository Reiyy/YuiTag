package com.reiyy.yuitag.ui.components

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
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
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.TileSystem
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.TilesOverlay
import org.osmdroid.views.overlay.compass.CompassOverlay
import java.io.File

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
    fitBoundsToken: Int = 0
) {
    val context = LocalContext.current
    val density = LocalDensity.current

    // 最后一次地图交互时间（指南针空闲休眠依据），先于 mapView 定义供内部回调引用
    val lastMapInteractionMs = remember { mutableLongStateOf(System.currentTimeMillis()) }

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
            controller.setZoom(zoomLevel.coerceIn(APP_MIN_ZOOM, APP_MAX_ZOOM))
            controller.setCenter(GeoPoint(initialLatitude, initialLongitude))
            minZoomLevel = APP_MIN_ZOOM
            maxZoomLevel = APP_MAX_ZOOM

            // 地图触摸监听
            val touchOverlay = MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                    lastMapInteractionMs.longValue = System.currentTimeMillis()
                    onMapTouched?.invoke()
                    return false
                }
                override fun longPressHelper(p: GeoPoint?): Boolean {
                    lastMapInteractionMs.longValue = System.currentTimeMillis()
                    return false
                }
            })
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
            val labelOverlay = TilesOverlay(MapTileProviderBasic(context, labelSource), context)
            mapView.overlays.add(labelOverlay)
            annotationOverlay = labelOverlay
            mapView.maxZoomLevel = APP_MAX_ZOOM
        }
        mapView.invalidate()
    }

    // 位置变更时平滑移动至目标中心
    LaunchedEffect(targetCenter, animateToTarget, recenterToken) {
        if (targetCenter != null && animateToTarget) {
            val (lat, lng) = targetCenter
            if (lat != 0.0 && lng != 0.0) {
                val targetZoom = zoomToTargetLevel ?: 15.0
                val zoom = mapView.zoomLevelDouble.coerceAtLeast(targetZoom).coerceAtMost(APP_MAX_ZOOM)
                mapView.post {
                    if (!mapView.isLaidOut) return@post
                    val h = mapView.height
                    val offY = (h * CENTER_OFFSET_FRACTION).toInt()
                    val ts = MapView.getTileSystem()
                    val mapSize = TileSystem.MapSize(zoom)
                    val x01 = ts.getX01FromLongitude(lng)
                    val y01 = ts.getY01FromLatitude(lat)
                    // 地图中心放在点下方 (h/2 - offY) 像素处 → 点落在屏幕 offY 高度，避开底部卡片
                    val centerY01 = y01 + (h / 2.0 - offY) / mapSize
                    val centerLat = ts.getLatitudeFromY01(centerY01)
                    val centerLng = ts.getLongitudeFromX01(x01)
                    mapView.controller.animateTo(GeoPoint(centerLat, centerLng), zoom, 800L)
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

    // 轨迹折线（含方向箭头）：按 id 增量更新，避免全量重建。
    // 插到 overlays 头部，确保始终绘制在 marker 下方。
    val polylineCache = remember { mutableMapOf<String, TrajectoryOverlay>() }
    DisposableEffect(polylines) {
        val retained = mutableSetOf<String>()
        polylines.forEach { data ->
            retained.add(data.id)
            val geoPoints = data.points.map { GeoPoint(it.first, it.second) }
            val existing = polylineCache[data.id]
            if (existing == null) {
                val overlay = TrajectoryOverlay().apply {
                    points = geoPoints
                    color = data.color
                    lineWidthPx = with(density) { data.widthDp.dp.toPx() }
                    showArrows = data.showArrows
                    arrowIntervalPx = with(density) { data.arrowIntervalDp.dp.toPx() }
                    arrowSizePx = with(density) { data.arrowSizeDp.dp.toPx() }
                }
                mapView.overlays.add(0, overlay)
                polylineCache[data.id] = overlay
            } else {
                existing.points = geoPoints
                existing.color = data.color
                existing.showArrows = data.showArrows
                existing.lineWidthPx = with(density) { data.widthDp.dp.toPx() }
                existing.arrowIntervalPx = with(density) { data.arrowIntervalDp.dp.toPx() }
                existing.arrowSizePx = with(density) { data.arrowSizeDp.dp.toPx() }
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

    // 自动缩放至覆盖全部轨迹点（单点回退为中心 + 固定缩放）
    LaunchedEffect(fitBoundsPoints, fitBoundsToken) {
        if (fitBoundsPoints.isEmpty()) return@LaunchedEffect
        val pts = fitBoundsPoints.map { GeoPoint(it.first, it.second) }
        mapView.post {
            if (!mapView.isLaidOut) return@post
            if (pts.size == 1) {
                mapView.controller.setCenter(pts[0])
                mapView.controller.setZoom(
                    mapView.zoomLevelDouble.coerceAtLeast(16.0).coerceAtMost(APP_MAX_ZOOM)
                )
            } else {
                val box = BoundingBox.fromGeoPoints(pts)
                val padding = with(density) { 64.dp.toPx().toInt() }
                mapView.zoomToBoundingBox(box, false, padding)
                if (mapView.zoomLevelDouble > APP_MAX_ZOOM) {
                    mapView.controller.setZoom(APP_MAX_ZOOM)
                }
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
    val widthDp: Float = 4f,
    val showArrows: Boolean = false,
    val arrowIntervalDp: Float = 64f,
    val arrowSizeDp: Float = 11f
)
