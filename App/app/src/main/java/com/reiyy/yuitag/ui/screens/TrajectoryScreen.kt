package com.reiyy.yuitag.ui.screens

import android.graphics.Color as AndroidColor
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.ScatterPlot
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.reiyy.yuitag.data.FindMyQueryService
import com.reiyy.yuitag.data.local.LocationEntity
import com.reiyy.yuitag.ui.components.MapMarkerData
import com.reiyy.yuitag.ui.components.MapMarkerIcons
import com.reiyy.yuitag.ui.components.MapPolylineData
import com.reiyy.yuitag.ui.components.OsmMapView
import com.reiyy.yuitag.ui.components.TrajectoryDisplayMode
import com.reiyy.yuitag.ui.components.TrajectoryNode
import com.reiyy.yuitag.ui.components.rememberMapMarkerIcons
import com.reiyy.yuitag.viewmodel.TimeWindow
import com.reiyy.yuitag.viewmodel.TrajectoryRange
import com.reiyy.yuitag.viewmodel.TrajectorySummary
import com.reiyy.yuitag.viewmodel.TrajectoryViewModel
import org.osmdroid.views.MapView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

// 轨迹时序渐变色（起点翠绿 → 中段青蓝 → 终点科技蓝）
private val TrajectoryStartColor = Color(0xFF10B981)
private val TrajectoryMidColor = Color(0xFF0EA5E9)
private val TrajectoryEndColor = Color(0xFF2563EB)
private val ManualStartColor = Color(0xFFFF9800)
private val ManualEndColor = Color(0xFFE91E63)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrajectoryScreen(
    uid: String,
    onBack: () -> Unit,
    viewModel: TrajectoryViewModel = viewModel()
) {
    LaunchedEffect(uid) { viewModel.initialize(uid) }

    val points by viewModel.trajectoryPoints.collectAsState()
    val range by viewModel.range.collectAsState()
    val summary by viewModel.summary.collectAsState()
    val isDeduplicateEnabled by viewModel.isDeduplicateEnabled.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val deviceName by viewModel.deviceName.collectAsState()
    val resolvingAddressIds by viewModel.resolvingAddressIds.collectAsState()
    val failedAddressIds by viewModel.failedAddressIds.collectAsState()

    val context = LocalContext.current
    val density = LocalDensity.current
    val markerIcons = rememberMapMarkerIcons()

    val mapConfig = remember(context) {
        val service = FindMyQueryService(context)
        service.getTiandituKey() to service.getTiandituStyle()
    }

    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var selectedPointId by remember { mutableStateOf<Long?>(null) }
    var showCustomRange by remember { mutableStateOf(false) }
    var displayMode by remember { mutableStateOf(TrajectoryDisplayMode.SMART_ARCS) }

    fun cycleDisplayMode() {
        displayMode = when (displayMode) {
            TrajectoryDisplayMode.SMART_ARCS -> TrajectoryDisplayMode.POINTS_ONLY
            TrajectoryDisplayMode.POINTS_ONLY -> TrajectoryDisplayMode.SMART_ARCS
        }
    }

    val selectedIndex = remember(points, selectedPointId) {
        if (selectedPointId == null) -1 else points.indexOfFirst { it.id == selectedPointId }
    }
    val selectedPoint = remember(points, selectedIndex) {
        if (selectedIndex in points.indices) points[selectedIndex] else null
    }

    // 选中节点若尚未解析地址，立即触发单点优先逆地理编码解析
    LaunchedEffect(selectedPoint?.id, selectedPoint?.address) {
        selectedPoint?.let { pt ->
            if (pt.address.isNullOrBlank()) {
                viewModel.ensureAddressResolved(pt)
            }
        }
    }

    // 当点集变化导致原选中点不在当前列表时，自动清除选中状态
    LaunchedEffect(points) {
        if (selectedPointId != null && points.none { it.id == selectedPointId }) {
            selectedPointId = null
        }
    }

    // 视角控制状态：区分「全览缩放 (fitBounds)」与「单点聚焦跟随 (recenter)」
    var fitBoundsPoints by remember { mutableStateOf<List<Pair<Double, Double>>>(emptyList()) }
    var fitBoundsToken by remember { mutableIntStateOf(0) }
    var pendingAutoFit by remember { mutableStateOf(true) }

    var recenterTarget by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var recenterToken by remember { mutableIntStateOf(0) }
    var zoomToTargetLevel by remember { mutableStateOf<Double?>(null) }
    var preserveZoomOnCenter by remember { mutableStateOf(true) }

    fun triggerFitAll() {
        if (points.isNotEmpty()) {
            fitBoundsPoints = points.map { it.latitude to it.longitude }
            fitBoundsToken++
        }
    }

    fun focusOnPoint(point: LocationEntity, minZoom: Double? = null) {
        selectedPointId = point.id
        recenterTarget = point.latitude to point.longitude
        zoomToTargetLevel = minZoom
        preserveZoomOnCenter = (minZoom == null)
        recenterToken++
    }

    // 初次加载或切换预设/自定义范围/去重开关后自动全览框选；手动调整起终点时保持用户当前缩放视角
    LaunchedEffect(points) {
        if (points.isNotEmpty() && (pendingAutoFit || fitBoundsPoints.isEmpty())) {
            fitBoundsPoints = points.map { it.latitude to it.longitude }
            fitBoundsToken++
            pendingAutoFit = false
        }
    }

    val manualStartTs = (range as? TrajectoryRange.ManualPoints)?.startTs
    val manualEndTs = (range as? TrajectoryRange.ManualPoints)?.endTs

    val startPx = with(density) { 26.dp.toPx().toInt() }
    val selectedPx = with(density) { 28.dp.toPx().toInt() }

    val markers = remember(
        points,
        manualStartTs,
        manualEndTs,
        markerIcons,
        startPx,
        selectedPx,
        selectedPoint?.id
    ) {
        buildTrajectoryMarkers(
            points = points,
            manualStartTs = manualStartTs,
            manualEndTs = manualEndTs,
            icons = markerIcons,
            startPx = startPx,
            selectedPx = selectedPx,
            selectedId = selectedPoint?.id
        )
    }

    val selectedMarkerUid = remember(points, selectedPoint?.id) {
        val selId = selectedPoint?.id ?: return@remember null
        when {
            points.size == 1 && points.first().id == selId -> "pt_$selId"
            points.isNotEmpty() && points.first().id == selId -> "start_$selId"
            points.size >= 2 && points.last().id == selId -> "end_$selId"
            else -> "sel_$selId"
        }
    }

    val trajectoryNodes = remember(points) {
        points.mapIndexed { idx, p ->
            var dist = 0.0
            var isGap = false
            if (idx < points.lastIndex) {
                val next = points[idx + 1]
                val dt = next.timestamp - p.timestamp
                dist = TrajectoryViewModel.haversineMeters(
                    p.latitude, p.longitude, next.latitude, next.longitude
                )
                isGap = dt >= TrajectoryViewModel.TIME_GAP_THRESHOLD_SEC &&
                    dist >= TrajectoryViewModel.TIME_GAP_MIN_DISTANCE_M
            }
            TrajectoryNode(
                id = p.id,
                latitude = p.latitude,
                longitude = p.longitude,
                timestamp = p.timestamp,
                accuracy = p.accuracy,
                distanceToNextMeters = dist,
                isGapToNext = isGap
            )
        }
    }

    val polylines = remember(points, trajectoryNodes, selectedPoint?.id, displayMode) {
        if (points.isEmpty()) emptyList() else listOf(
            MapPolylineData(
                id = "trajectory",
                points = points.map { it.latitude to it.longitude },
                color = TrajectoryEndColor.toArgb(),
                widthDp = 5.0f,
                casingWidthDp = 8.2f,
                maxArcOffsetDp = 36f,
                showArrows = true,
                arrowIntervalDp = 76f,
                arrowSizeDp = 12.5f,
                useGradient = true,
                startColor = TrajectoryStartColor.toArgb(),
                midColor = TrajectoryMidColor.toArgb(),
                endColor = TrajectoryEndColor.toArgb(),
                nodes = trajectoryNodes,
                displayMode = displayMode,
                showIntermediateNodes = true,
                minNodeSpacingDp = 24f,
                nodeRadiusDp = 4.2f,
                selectedNodeId = selectedPoint?.id,
                onNodeClick = { clickedId ->
                    points.firstOrNull { it.id == clickedId }?.let { pt ->
                        focusOnPoint(pt, minZoom = null)
                    }
                }
            )
        )
    }

    val bottomPaddingDp = if (selectedPoint != null) 250f else 128f

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = deviceName,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (points.isNotEmpty()) {
                            val hiddenCount = (summary.rawCount - summary.displayCount).coerceAtLeast(0)
                            val dedupeSuffix = if (isDeduplicateEnabled && hiddenCount > 0) {
                                " · 已折叠 $hiddenCount 个停留点"
                            } else ""
                            Text(
                                text = "${points.size} 个轨迹点 · 约 ${formatDistance(summary.totalDistanceMeters)}$dedupeSuffix",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            pendingAutoFit = false
                            val enabled = viewModel.toggleDeduplicate()
                            val msg = if (enabled) {
                                "已开启连续停留折叠（保留往返路径）"
                            } else {
                                "已显示全部原始位置点"
                            }
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.FilterList,
                            contentDescription = if (isDeduplicateEnabled) "关闭停留折叠" else "开启停留折叠",
                            tint = if (isDeduplicateEnabled) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                    IconButton(onClick = { viewModel.refresh(uid) }) {
                        if (isRefreshing) {
                            val infiniteTransition = rememberInfiniteTransition(label = "refresh_spin")
                            val rotation by infiniteTransition.animateFloat(
                                initialValue = 0f,
                                targetValue = 360f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(1000, easing = LinearEasing),
                                    repeatMode = RepeatMode.Restart
                                ),
                                label = "refresh_rotation"
                            )
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "正在刷新",
                                modifier = Modifier.rotate(rotation),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // ① 背景地图（最先组合，确保顶部控件与底部面板绘制在其上）
            OsmMapView(
                initialLatitude = points.firstOrNull()?.latitude ?: 39.9042,
                initialLongitude = points.firstOrNull()?.longitude ?: 116.4074,
                zoomLevel = 16.0,
                targetCenter = recenterTarget,
                animateToTarget = true,
                zoomToTargetLevel = zoomToTargetLevel,
                preserveZoomOnCenter = preserveZoomOnCenter,
                centerOffsetFraction = if (selectedPoint != null) 0.36f else 0.45f,
                recenterToken = recenterToken,
                onMapReady = { mapViewRef = it },
                onMapTouched = { selectedPointId = null },
                markers = markers,
                selectedUid = selectedMarkerUid,
                polylines = polylines,
                fitBoundsPoints = fitBoundsPoints,
                fitBoundsToken = fitBoundsToken,
                fitBoundsTopPaddingDp = 104f,
                fitBoundsBottomPaddingDp = bottomPaddingDp,
                fitBoundsHorizontalPaddingDp = 52f,
                fitBoundsAnimated = true,
                onMarkerClick = { key ->
                    resolvePoint(key, points)?.let { pt ->
                        focusOnPoint(pt, minZoom = null)
                    }
                },
                tiandituKey = mapConfig.first,
                tiandituStyle = mapConfig.second,
                modifiers = Modifier.fillMaxSize()
            )

            // ② 顶部范围筛选与图例条
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                shadowElevation = 3.dp
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    RangeSelectorRow(
                        range = range,
                        onSelectPreset = { window ->
                            val alreadySelected = (range as? TrajectoryRange.Preset)?.window == window
                            pendingAutoFit = true
                            viewModel.selectPreset(window)
                            if (alreadySelected) {
                                triggerFitAll()
                            }
                        },
                        onSelectCustom = { showCustomRange = true }
                    )
                    ManualRangeBar(
                        range = range,
                        onClear = {
                            pendingAutoFit = true
                            viewModel.clearManual()
                        }
                    )
                    if (points.isNotEmpty()) {
                        TrajectoryLegendBar(summary = summary)
                    }
                }
            }

            // ③ 空状态提示
            if (points.isEmpty()) {
                EmptyState(Modifier.align(Alignment.Center))
            }

            // ④ 右侧悬浮地图快捷控制栏（模式切换 / 全览 / 起点 / 终点 / 缩放 + -）
            if (points.isNotEmpty()) {
                FloatingMapControls(
                    hasMultiplePoints = points.size >= 2,
                    displayMode = displayMode,
                    onCycleDisplayMode = { cycleDisplayMode() },
                    onFitAll = { triggerFitAll() },
                    onJumpStart = {
                        points.firstOrNull()?.let { focusOnPoint(it, minZoom = 16.5) }
                    },
                    onJumpEnd = {
                        points.lastOrNull()?.let { focusOnPoint(it, minZoom = 16.5) }
                    },
                    onZoomIn = { mapViewRef?.controller?.zoomIn() },
                    onZoomOut = { mapViewRef?.controller?.zoomOut() },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 12.dp, bottom = 72.dp)
                )
            }

            // ⑤ 底部时间轴滑块与节点详情巡检面板
            if (points.isNotEmpty()) {
                val isSelectedResolveFailed = selectedPoint != null &&
                    selectedPoint.address.isNullOrBlank() &&
                    (selectedPoint.id in failedAddressIds && selectedPoint.id !in resolvingAddressIds)
                TrajectoryBottomPanel(
                    points = points,
                    selectedIndex = selectedIndex,
                    selectedPoint = selectedPoint,
                    isAddressResolveFailed = isSelectedResolveFailed,
                    manualStartTs = manualStartTs,
                    manualEndTs = manualEndTs,
                    onSelectIndex = { idx ->
                        val target = points.getOrNull(idx) ?: return@TrajectoryBottomPanel
                        focusOnPoint(target, minZoom = null)
                    },
                    onZoomToPoint = { pt ->
                        focusOnPoint(pt, minZoom = 17.5)
                    },
                    onSelectStart = { pt ->
                        pendingAutoFit = false
                        viewModel.setManualStart(pt.timestamp)
                    },
                    onSelectEnd = { pt ->
                        pendingAutoFit = false
                        viewModel.setManualEnd(pt.timestamp)
                    },
                    onDismissDetail = { selectedPointId = null },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }
    }

    if (showCustomRange) {
        CustomRangeDialog(
            range = range,
            onConfirm = { s, e ->
                pendingAutoFit = true
                viewModel.selectCustomDates(s, e)
                showCustomRange = false
            },
            onDismiss = { showCustomRange = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RangeSelectorRow(
    range: TrajectoryRange,
    onSelectPreset: (TimeWindow) -> Unit,
    onSelectCustom: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TimeWindow.entries.forEach { w ->
            val selected = (range as? TrajectoryRange.Preset)?.window == w
            FilterChip(
                selected = selected,
                onClick = { onSelectPreset(w) },
                label = { Text(w.label()) }
            )
        }
        val isCustom = range is TrajectoryRange.CustomDates
        val customLabel = if (range is TrajectoryRange.CustomDates) {
            val fmt = remember { SimpleDateFormat("MM/dd", Locale.getDefault()) }
            "${fmt.format(Date(range.startTs * 1000))}-${fmt.format(Date(range.endTs * 1000))}"
        } else {
            "自定义"
        }
        FilterChip(
            selected = isCustom,
            onClick = onSelectCustom,
            label = { Text(customLabel) }
        )
    }
}

@Composable
private fun ManualRangeBar(range: TrajectoryRange, onClear: () -> Unit) {
    if (range !is TrajectoryRange.ManualPoints) return
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    val startText = range.startTs?.let { fmt.format(Date(it * 1000)) }
    val endText = range.endTs?.let { fmt.format(Date(it * 1000)) }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "手动截取 · 起点: ${startText ?: "不限"}   终点: ${endText ?: "不限"}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onClear, modifier = Modifier.size(26.dp)) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "清除手动选择",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }
}

/** 顶部轨迹图例与时间跨度栏：直观解释时序渐变方向与时间范围 */
@Composable
private fun TrajectoryLegendBar(summary: TrajectorySummary) {
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    val spanText = if (summary.startTimestamp != null && summary.endTimestamp != null) {
        if (summary.startTimestamp == summary.endTimestamp) {
            fmt.format(Date(summary.startTimestamp * 1000))
        } else {
            "${fmt.format(Date(summary.startTimestamp * 1000))} ~ ${fmt.format(Date(summary.endTimestamp * 1000))}"
        }
    } else ""

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                text = "起",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = TrajectoryStartColor
            )
            Box(
                modifier = Modifier
                    .width(44.dp)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(TrajectoryStartColor, TrajectoryMidColor, TrajectoryEndColor)
                        )
                    )
            )
            Text(
                text = "终",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = TrajectoryEndColor
            )
        }

        if (spanText.isNotEmpty()) {
            Text(
                text = spanText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

/** 右侧地图快捷操作浮钮组：连线模式切换、全览复位、跳至起/终点、单手缩放 */
@Composable
private fun FloatingMapControls(
    hasMultiplePoints: Boolean,
    displayMode: TrajectoryDisplayMode,
    onCycleDisplayMode: () -> Unit,
    onFitAll: () -> Unit,
    onJumpStart: () -> Unit,
    onJumpEnd: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 连线模式切换按钮（线 / 仅点 图标切换）
        if (hasMultiplePoints) {
            val isArcMode = displayMode == TrajectoryDisplayMode.SMART_ARCS
            Surface(
                onClick = onCycleDisplayMode,
                shape = RoundedCornerShape(12.dp),
                color = if (isArcMode) {
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
                } else {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.96f)
                },
                shadowElevation = 4.dp,
                tonalElevation = 2.dp,
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isArcMode) Icons.Default.Timeline else Icons.Default.ScatterPlot,
                        contentDescription = if (isArcMode) "切换为仅显示位置点" else "切换为显示轨迹连线",
                        modifier = Modifier.size(20.dp),
                        tint = if (isArcMode) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        }
                    )
                }
            }
        }

        // 全览按钮
        Surface(
            onClick = onFitAll,
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            shadowElevation = 4.dp,
            tonalElevation = 2.dp,
            modifier = Modifier.size(40.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.CropFree,
                    contentDescription = "全览轨迹",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        // 起 / 终 快捷跳转胶囊
        if (hasMultiplePoints) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                shadowElevation = 4.dp,
                tonalElevation = 2.dp,
                modifier = Modifier.width(40.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp)
                            .clickable(onClick = onJumpStart),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "起",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = TrajectoryStartColor
                        )
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 6.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp)
                            .clickable(onClick = onJumpEnd),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "终",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = TrajectoryEndColor
                        )
                    }
                }
            }
        }

        // + / - 缩放控制组
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            shadowElevation = 4.dp,
            tonalElevation = 2.dp,
            modifier = Modifier.width(40.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(onClick = onZoomIn, modifier = Modifier.size(38.dp)) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "放大地图",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 6.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
                IconButton(onClick = onZoomOut, modifier = Modifier.size(38.dp)) {
                    Icon(
                        Icons.Default.Remove,
                        contentDescription = "缩小地图",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

/**
 * 底部一体化时间轴巡检与节点详情面板：
 * - 常驻精简时间轴滑块与 `<` `>` 步进按钮，无需在地图上盲戳小点即可按时间顺序巡检；
 * - 选中某点时平滑展开地址、精度、与上一点距离/时间差、定焦放大及设为起终点操作。
 */
@Composable
private fun TrajectoryBottomPanel(
    points: List<LocationEntity>,
    selectedIndex: Int,
    selectedPoint: LocationEntity?,
    isAddressResolveFailed: Boolean,
    manualStartTs: Long?,
    manualEndTs: Long?,
    onSelectIndex: (Int) -> Unit,
    onZoomToPoint: (LocationEntity) -> Unit,
    onSelectStart: (LocationEntity) -> Unit,
    onSelectEnd: (LocationEntity) -> Unit,
    onDismissDetail: () -> Unit,
    modifier: Modifier = Modifier
) {
    val fullFmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    val shortFmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
        shadowElevation = 8.dp,
        tonalElevation = 3.dp
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            // 1. 顶部步进控制条（< 上一点 | 当前节点状态 | 下一点 >）
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val canPrev = points.isNotEmpty() && (selectedIndex == -1 || selectedIndex > 0)
                val canNext = points.isNotEmpty() && (selectedIndex == -1 || selectedIndex < points.lastIndex)

                IconButton(
                    onClick = {
                        val targetIdx = if (selectedIndex <= 0) points.lastIndex else selectedIndex - 1
                        onSelectIndex(targetIdx)
                    },
                    enabled = canPrev,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = "上一轨迹点"
                    )
                }

                Row(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    if (selectedPoint != null && selectedIndex >= 0) {
                        val badgeText = when (selectedIndex) {
                            0 -> "起点 · 1/${points.size}"
                            points.lastIndex -> "终点 · ${points.size}/${points.size}"
                            else -> "节点 ${selectedIndex + 1}/${points.size}"
                        }
                        val badgeColor = when (selectedIndex) {
                            0 -> TrajectoryStartColor
                            points.lastIndex -> TrajectoryEndColor
                            else -> MaterialTheme.colorScheme.primary
                        }
                        Surface(
                            shape = CircleShape,
                            color = badgeColor.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = badgeText,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = badgeColor,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = fullFmt.format(Date(selectedPoint.timestamp * 1000)),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    } else {
                        Text(
                            text = "拖动滑块或点击左右箭头回溯轨迹 (${points.size} 个点)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                IconButton(
                    onClick = {
                        val targetIdx = if (selectedIndex < 0 || selectedIndex >= points.lastIndex) 0 else selectedIndex + 1
                        onSelectIndex(targetIdx)
                    },
                    enabled = canNext,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "下一轨迹点"
                    )
                }

                if (selectedPoint != null) {
                    IconButton(
                        onClick = onDismissDetail,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "收起详情",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 2. 时间轴滑块（当点数 >= 2 时支持快速拖拽回溯）
            if (points.size >= 2) {
                val sliderValue = if (selectedIndex >= 0) selectedIndex.toFloat() else (points.size - 1).toFloat()
                Slider(
                    value = sliderValue,
                    onValueChange = { v ->
                        val idx = v.roundToInt().coerceIn(0, points.lastIndex)
                        if (idx != selectedIndex) {
                            onSelectIndex(idx)
                        }
                    },
                    valueRange = 0f..(points.size - 1).toFloat(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(28.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = if (selectedIndex >= 0) MaterialTheme.colorScheme.primary else TrajectoryEndColor,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = shortFmt.format(Date(points.first().timestamp * 1000)),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = shortFmt.format(Date(points.last().timestamp * 1000)),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 3. 选中节点展开详情与操作区
            AnimatedVisibility(
                visible = selectedPoint != null,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                selectedPoint?.let { p ->
                    val prevPoint = if (selectedIndex > 0) points.getOrNull(selectedIndex - 1) else null
                    PointDetailSection(
                        point = p,
                        prevPoint = prevPoint,
                        isAddressResolveFailed = isAddressResolveFailed,
                        isManualStart = manualStartTs == p.timestamp,
                        isManualEnd = manualEndTs == p.timestamp,
                        onZoomHere = { onZoomToPoint(p) },
                        onSelectStart = { onSelectStart(p) },
                        onSelectEnd = { onSelectEnd(p) }
                    )
                }
            }
        }
    }
}

@Composable
private fun PointDetailSection(
    point: LocationEntity,
    prevPoint: LocationEntity?,
    isAddressResolveFailed: Boolean,
    isManualStart: Boolean,
    isManualEnd: Boolean,
    onZoomHere: () -> Unit,
    onSelectStart: () -> Unit,
    onSelectEnd: () -> Unit
) {
    val batteryLabel = when (point.batteryStatus) {
        0 -> "正常"
        1 -> "中等"
        2 -> "低"
        3 -> "极低"
        else -> null
    }
    val batteryColor = when (point.batteryStatus) {
        0 -> Color(0xFF4CAF50)
        1 -> Color(0xFFFFC107)
        2 -> Color(0xFFFF9800)
        3 -> Color(0xFFF44336)
        else -> Color.Gray
    }

    Column(modifier = Modifier.padding(top = 6.dp)) {
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            modifier = Modifier.padding(bottom = 8.dp)
        )

        val hasAddress = !point.address.isNullOrBlank()
        val addressText = when {
            hasAddress -> point.address!!
            isAddressResolveFailed -> "${"%.6f".format(point.latitude)}, ${"%.6f".format(point.longitude)}"
            else -> "正在解析位置地址…"
        }
        Text(
            text = addressText,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = if (hasAddress || isAddressResolveFailed) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "精度: ${point.accuracy}m",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (batteryLabel != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.BatteryFull,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = batteryColor
                        )
                        Spacer(Modifier.width(2.dp))
                        Text(
                            text = batteryLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = batteryColor
                        )
                    }
                }
            }

            if (prevPoint != null) {
                val dist = TrajectoryViewModel.haversineMeters(
                    prevPoint.latitude, prevPoint.longitude,
                    point.latitude, point.longitude
                )
                val dtSec = (point.timestamp - prevPoint.timestamp).coerceAtLeast(0L)
                Text(
                    text = "距上一点 ${formatDistance(dist)} · +${formatDuration(dtSec)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onZoomHere,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                modifier = Modifier.height(34.dp)
            ) {
                Icon(
                    Icons.Default.CenterFocusStrong,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text("放大", style = MaterialTheme.typography.labelMedium)
            }
            OutlinedButton(
                onClick = onSelectStart,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(34.dp)
            ) {
                Text(
                    text = if (isManualStart) "清除起点" else "选为起点",
                    style = MaterialTheme.typography.labelMedium
                )
            }
            OutlinedButton(
                onClick = onSelectEnd,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(34.dp)
            ) {
                Text(
                    text = if (isManualEnd) "清除终点" else "选为终点",
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            Icons.Default.LocationOn,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "暂无轨迹点",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "调整时间范围或点击右上角刷新获取最新位置",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomRangeDialog(
    range: TrajectoryRange,
    onConfirm: (Long, Long) -> Unit,
    onDismiss: () -> Unit
) {
    val initialStart = (range as? TrajectoryRange.CustomDates)?.startTs?.let { it * 1000 }
    val initialEnd = (range as? TrajectoryRange.CustomDates)?.endTs?.let { it * 1000 }
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = initialStart,
        initialSelectedEndDateMillis = initialEnd
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自定义时间范围") },
        text = {
            DateRangePicker(state = state, showModeToggle = false)
        },
        confirmButton = {
            TextButton(onClick = {
                val s = state.selectedStartDateMillis
                val e = state.selectedEndDateMillis
                if (s != null && e != null) onConfirm(s / 1000, e / 1000 + 86399L) else onDismiss()
            }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

private fun TimeWindow.label(): String = when (this) {
    TimeWindow.LAST_24H -> "24小时"
    TimeWindow.LAST_3D -> "3天"
    TimeWindow.LAST_7D -> "7天"
    TimeWindow.LAST_30D -> "30天"
    TimeWindow.ALL -> "全部"
}

/**
 * 构建轨迹关键点 Marker（仅包含起点、终点及当前选中节点；中间节点由 TrajectoryOverlay 在 Canvas 动态抽稀绘制并响应点击）。
 */
private fun buildTrajectoryMarkers(
    points: List<LocationEntity>,
    manualStartTs: Long?,
    manualEndTs: Long?,
    icons: MapMarkerIcons,
    startPx: Int,
    selectedPx: Int,
    selectedId: Long?
): List<MapMarkerData> {
    if (points.isEmpty()) return emptyList()
    val list = mutableListOf<MapMarkerData>()

    if (points.size == 1) {
        val p = points.first()
        val icon = if (p.id == selectedId) {
            icons.selectedMarker(TrajectoryEndColor, selectedPx)
        } else {
            icons.startEndMarker(TrajectoryEndColor, "位", startPx)
        }
        list.add(MapMarkerData(p.latitude, p.longitude, "位置", uid = "pt_${p.id}", icon = icon))
        return list
    }

    val first = points.first()
    val last = points.last()
    val sColor = if (manualStartTs != null) ManualStartColor else TrajectoryStartColor
    val eColor = if (manualEndTs != null) ManualEndColor else TrajectoryEndColor

    val startIcon = if (first.id == selectedId) {
        icons.selectedMarker(sColor, selectedPx)
    } else {
        icons.startEndMarker(sColor, "起", startPx)
    }
    val endIcon = if (last.id == selectedId) {
        icons.selectedMarker(eColor, selectedPx)
    } else {
        icons.startEndMarker(eColor, "终", startPx)
    }

    list.add(MapMarkerData(first.latitude, first.longitude, "起点", uid = "start_${first.id}", icon = startIcon))
    list.add(MapMarkerData(last.latitude, last.longitude, "终点", uid = "end_${last.id}", icon = endIcon))

    // 若当前选中的是中间节点，单独添加高亮靶心 Marker 并置顶
    if (selectedId != null && selectedId != first.id && selectedId != last.id) {
        val selIdx = points.indexOfFirst { it.id == selectedId }
        if (selIdx in 1 until points.lastIndex) {
            val selPt = points[selIdx]
            val progress = selIdx.toFloat() / (points.size - 1).toFloat()
            val selColor = interpolateComposeColor(progress)
            list.add(
                MapMarkerData(
                    latitude = selPt.latitude,
                    longitude = selPt.longitude,
                    title = "节点 #${selIdx + 1}",
                    uid = "sel_${selPt.id}",
                    icon = icons.selectedMarker(selColor, selectedPx)
                )
            )
        }
    }

    return list
}

private fun interpolateComposeColor(fraction: Float): Color {
    val f = fraction.coerceIn(0f, 1f)
    val c1: Int
    val c2: Int
    val r: Float
    if (f <= 0.5f) {
        c1 = TrajectoryStartColor.toArgb()
        c2 = TrajectoryMidColor.toArgb()
        r = f * 2f
    } else {
        c1 = TrajectoryMidColor.toArgb()
        c2 = TrajectoryEndColor.toArgb()
        r = (f - 0.5f) * 2f
    }
    val red = (AndroidColor.red(c1) + (AndroidColor.red(c2) - AndroidColor.red(c1)) * r).toInt()
    val green = (AndroidColor.green(c1) + (AndroidColor.green(c2) - AndroidColor.green(c1)) * r).toInt()
    val blue = (AndroidColor.blue(c1) + (AndroidColor.blue(c2) - AndroidColor.blue(c1)) * r).toInt()
    return Color(AndroidColor.argb(255, red, green, blue))
}

private fun resolvePoint(key: String, points: List<LocationEntity>): LocationEntity? {
    val idStr = key.substringAfter("_", "").ifBlank { return null }
    val id = idStr.toLongOrNull() ?: return null
    return points.firstOrNull { it.id == id }
}

private fun formatDistance(meters: Double): String {
    return when {
        meters < 1000.0 -> "${meters.roundToInt()} m"
        meters < 100_000.0 -> "${"%.1f".format(meters / 1000.0)} km"
        else -> "${(meters / 1000.0).roundToInt()} km"
    }
}

private fun formatDuration(seconds: Long): String {
    return when {
        seconds < 60L -> "${seconds}秒"
        seconds < 3600L -> "${seconds / 60}分钟"
        seconds < 86400L -> {
            val h = seconds / 3600
            val m = (seconds % 3600) / 60
            if (m > 0) "${h}小时${m}分" else "${h}小时"
        }
        else -> {
            val d = seconds / 86400
            val h = (seconds % 86400) / 3600
            if (h > 0) "${d}天${h}小时" else "${d}天"
        }
    }
}
