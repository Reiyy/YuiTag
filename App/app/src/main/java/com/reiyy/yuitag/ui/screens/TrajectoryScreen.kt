package com.reiyy.yuitag.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.reiyy.yuitag.data.FindMyQueryService
import com.reiyy.yuitag.data.local.LocationEntity
import com.reiyy.yuitag.ui.components.MapMarkerData
import com.reiyy.yuitag.ui.components.MapMarkerIcons
import com.reiyy.yuitag.ui.components.MapPolylineData
import com.reiyy.yuitag.ui.components.OsmMapView
import com.reiyy.yuitag.ui.components.rememberMapMarkerIcons
import com.reiyy.yuitag.viewmodel.TimeWindow
import com.reiyy.yuitag.viewmodel.TrajectoryRange
import com.reiyy.yuitag.viewmodel.TrajectoryViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val MAX_DOT_POINTS = 200

private val StartColor = Color(0xFF4CAF50)
private val EndColor = Color(0xFF2196F3)
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
    val deviceName by viewModel.deviceName.collectAsState()

    val context = LocalContext.current
    val density = LocalDensity.current
    val markerIcons = rememberMapMarkerIcons()

    val mapConfig = remember(context) {
        val service = FindMyQueryService(context)
        service.getTiandituKey() to service.getTiandituStyle()
    }

    var selectedPoint by remember { mutableStateOf<LocationEntity?>(null) }
    var showCustomRange by remember { mutableStateOf(false) }

    val manualStartTs = (range as? TrajectoryRange.ManualPoints)?.startTs
    val manualEndTs = (range as? TrajectoryRange.ManualPoints)?.endTs

    val dotColor = MaterialTheme.colorScheme.primary
    val lineColor = MaterialTheme.colorScheme.primary
    val startPx = with(density) { 22.dp.toPx().toInt() }
    val dotPx = with(density) { 10.dp.toPx().toInt() }
    val selectedPx = with(density) { 20.dp.toPx().toInt() }

    val markers = remember(points, manualStartTs, manualEndTs, markerIcons, startPx, dotPx, selectedPx, selectedPoint?.id) {
        buildTrajectoryMarkers(points, manualStartTs, manualEndTs, markerIcons, startPx, dotPx, selectedPx, dotColor, selectedPoint?.id)
    }

    val polylineColor = lineColor.toArgb()
    val polylines = remember(points, polylineColor) {
        if (points.size < 2) emptyList() else listOf(
            MapPolylineData(
                id = "trajectory",
                points = points.map { it.latitude to it.longitude },
                color = polylineColor,
                widthDp = 4f,
                showArrows = true
            )
        )
    }
    val fitBoundsPoints = remember(points) { points.map { it.latitude to it.longitude } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(deviceName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh(uid) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
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
            // ① 背景地图（最先组合，确保顶部控件绘制在其上）
            OsmMapView(
                initialLatitude = points.firstOrNull()?.latitude ?: 39.9042,
                initialLongitude = points.firstOrNull()?.longitude ?: 116.4074,
                zoomLevel = 16.0,
                markers = markers,
                polylines = polylines,
                fitBoundsPoints = fitBoundsPoints,
                onMarkerClick = { key -> selectedPoint = resolvePoint(key, points) },
                tiandituKey = mapConfig.first,
                tiandituStyle = mapConfig.second,
                modifiers = Modifier.fillMaxSize()
            )

            // ② 顶部控件条（地图之后组合 → 绘制在地图之上，实色背景保证可读）
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                RangeSelectorRow(
                    range = range,
                    onSelectPreset = { viewModel.selectPreset(it) },
                    onSelectCustom = { showCustomRange = true }
                )
                ManualRangeBar(range = range, onClear = { viewModel.clearManual() })
                if (points.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Text(
                            text = "轨迹点 ${points.size} 个",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ③ 浮动层（均在地图之后组合）
            if (points.isEmpty()) {
                EmptyState(Modifier.align(Alignment.Center))
            }

            selectedPoint?.let { p ->
                PointInfoCard(
                    point = p,
                    isManualStart = manualStartTs == p.timestamp,
                    isManualEnd = manualEndTs == p.timestamp,
                    onSelectStart = { viewModel.setManualStart(p.timestamp) },
                    onSelectEnd = { viewModel.setManualEnd(p.timestamp) },
                    onDismiss = { selectedPoint = null },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }
    }

    if (showCustomRange) {
        CustomRangeDialog(
            range = range,
            onConfirm = { s, e ->
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
            .padding(horizontal = 12.dp, vertical = 4.dp),
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
        FilterChip(
            selected = range is TrajectoryRange.CustomDates,
            onClick = onSelectCustom,
            label = { Text("自定义") }
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
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "起点 · ${startText ?: "不限"}   终点 · ${endText ?: "不限"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onClear, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Close, contentDescription = "清除手动选择", modifier = Modifier.size(16.dp))
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

@Composable
private fun PointInfoCard(
    point: LocationEntity,
    isManualStart: Boolean,
    isManualEnd: Boolean,
    onSelectStart: () -> Unit,
    onSelectEnd: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    val timeText = fmt.format(Date(point.timestamp * 1000))
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(12.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
        tonalElevation = 2.dp
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = timeText,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "关闭", modifier = Modifier.size(18.dp))
                }
            }

            if (!point.address.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = point.address!!,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(6.dp))
            Row {
                Text(
                    text = "精度: ${point.accuracy}m",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    text = "置信度: ${point.confidence}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!point.payloadType.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "负载: ${point.payloadType}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onSelectStart, modifier = Modifier.weight(1f)) {
                    Text(if (isManualStart) "清除起点" else "选为起点")
                }
                OutlinedButton(onClick = onSelectEnd, modifier = Modifier.weight(1f)) {
                    Text(if (isManualEnd) "清除终点" else "选为终点")
                }
            }
        }
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

private fun buildTrajectoryMarkers(
    points: List<LocationEntity>,
    manualStartTs: Long?,
    manualEndTs: Long?,
    icons: MapMarkerIcons,
    startPx: Int,
    dotPx: Int,
    selectedPx: Int,
    dotColor: Color,
    selectedId: Long?
): List<MapMarkerData> {
    if (points.isEmpty()) return emptyList()
    val list = mutableListOf<MapMarkerData>()

    fun dotIcon(point: LocationEntity, baseColor: Color, baseSize: Int): android.graphics.Bitmap =
        if (point.id == selectedId) icons.selectedMarker(baseColor, selectedPx)
        else icons.circleMarker(baseColor, baseSize)

    fun startIcon(point: LocationEntity, baseColor: Color): android.graphics.Bitmap =
        if (point.id == selectedId) icons.selectedMarker(baseColor, selectedPx)
        else icons.startEndMarker(baseColor, "起", startPx)

    fun endIcon(point: LocationEntity, baseColor: Color): android.graphics.Bitmap =
        if (point.id == selectedId) icons.selectedMarker(baseColor, selectedPx)
        else icons.startEndMarker(baseColor, "终", startPx)

    if (points.size == 1) {
        val p = points.first()
        list.add(MapMarkerData(p.latitude, p.longitude, "位置", uid = "pt_${p.id}", icon = dotIcon(p, dotColor, startPx)))
        return list
    }

    val first = points.first()
    val last = points.last()
    val sColor = if (manualStartTs != null) ManualStartColor else StartColor
    val eColor = if (manualEndTs != null) ManualEndColor else EndColor

    list.add(MapMarkerData(first.latitude, first.longitude, "起点", uid = "start_${first.id}", icon = startIcon(first, sColor)))
    if (points.size > 2 && points.size <= MAX_DOT_POINTS) {
        for (i in 1 until points.size - 1) {
            val p = points[i]
            list.add(MapMarkerData(p.latitude, p.longitude, "", uid = "pt_${p.id}", icon = dotIcon(p, dotColor, dotPx)))
        }
    }
    list.add(MapMarkerData(last.latitude, last.longitude, "终点", uid = "end_${last.id}", icon = endIcon(last, eColor)))
    return list
}

private fun resolvePoint(key: String, points: List<LocationEntity>): LocationEntity? {
    val idStr = key.substringAfter("_", "").ifBlank { return null }
    val id = idStr.toLongOrNull() ?: return null
    return points.firstOrNull { it.id == id }
}
