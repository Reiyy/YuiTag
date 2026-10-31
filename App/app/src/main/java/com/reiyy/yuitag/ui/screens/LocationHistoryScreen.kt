package com.reiyy.yuitag.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.MoreVert
import android.widget.Toast
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import com.reiyy.yuitag.data.BackfillProgress
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.reiyy.yuitag.data.FindMyQueryService
import com.reiyy.yuitag.data.local.LocationEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationHistoryScreen(
    uid: String,
    deviceName: String,
    locations: List<LocationEntity>,
    isRefreshing: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    isDebugMode: Boolean? = null,
    backfillProgress: BackfillProgress = BackfillProgress.Idle,
    onTriggerBackfill: () -> Unit = {}
) {
    val context = LocalContext.current
    val service = remember(context) { FindMyQueryService(context) }
    var effectiveDebugMode by remember { mutableStateOf(isDebugMode ?: service.isDebugModeEnabled()) }
    var isDeduplicateEnabled by rememberSaveable { mutableStateOf(false) }

    val displayLocations = remember(locations, isDeduplicateEnabled) {
        if (isDeduplicateEnabled) {
            deduplicateLocations(locations)
        } else {
            locations
        }
    }

    LifecycleResumeEffect(isDebugMode) {
        effectiveDebugMode = isDebugMode ?: service.isDebugModeEnabled()
        onPauseOrDispose { }
    }
    Scaffold(
        topBar = {
            var menuExpanded by remember { mutableStateOf(false) }
            TopAppBar(
                title = {
                    Column {
                        Text(deviceName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        when (val p = backfillProgress) {
                            is BackfillProgress.Running -> {
                                Text(
                                    text = "正在解析地址 ${p.current}/${p.total}…",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            is BackfillProgress.Error -> {
                                Text(
                                    text = "地址解析未完成",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            else -> {}
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (backfillProgress is BackfillProgress.Running) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(
                        onClick = {
                            isDeduplicateEnabled = !isDeduplicateEnabled
                            val hiddenCount = locations.size - deduplicateLocations(locations).size
                            if (isDeduplicateEnabled) {
                                val msg = if (hiddenCount > 0) "已启用位置去重（隐藏 $hiddenCount 条重复记录）" else "已启用位置去重"
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "已显示全部位置记录", Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.FilterList,
                            contentDescription = if (isDeduplicateEnabled) "关闭去重" else "开启去重",
                            tint = if (isDeduplicateEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "更多")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        val hasUnresolved = locations.any { it.address.isNullOrBlank() }
                        DropdownMenuItem(
                            text = {
                                Text(if (hasUnresolved) "补全缺失地址" else "重新检查地址")
                            },
                            onClick = {
                                menuExpanded = false
                                onTriggerBackfill()
                            },
                            enabled = backfillProgress !is BackfillProgress.Running
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (displayLocations.isEmpty() && !isRefreshing) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(Modifier.height(80.dp))
                    Icon(
                        Icons.Default.LocationOn,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        if (locations.isNotEmpty() && isDeduplicateEnabled) "去重后暂无位置" else "暂无位置记录",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "下拉刷新获取最新位置",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp)
                ) {
                    item { Spacer(Modifier.height(8.dp)) }
                    items(displayLocations, key = { it.id }) { location ->
                        LocationHistoryCard(location, isDebugMode = effectiveDebugMode)
                        Spacer(Modifier.height(8.dp))
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
            }
        }
    }
}

@Composable
private fun LocationHistoryCard(
    location: LocationEntity,
    isDebugMode: Boolean = false
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    val timeText = dateFormat.format(Date(location.timestamp * 1000))

    val batteryLabel = when (location.batteryStatus) {
        0 -> "正常"
        1 -> "中等"
        2 -> "低"
        3 -> "极低"
        else -> "未知"
    }
    val batteryColor = when (location.batteryStatus) {
        0 -> Color(0xFF4CAF50)
        1 -> Color(0xFFFFC107)
        2 -> Color(0xFFFF9800)
        3 -> Color(0xFFF44336)
        else -> Color.Gray
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.LocationOn,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = timeText,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(8.dp))

            if (!location.address.isNullOrBlank()) {
                Text(
                    text = location.address!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Text(
                text = "${"%.7f".format(location.latitude)}, ${"%.7f".format(location.longitude)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )

            Spacer(Modifier.height(6.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "精度: ${location.accuracy}m",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (isDebugMode) {
                    Spacer(Modifier.width(16.dp))
                    Text(
                        text = "置信度: ${location.confidence}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(
                        text = "计数器: ${location.counter}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (!location.payloadType.isNullOrBlank()) {
                        Spacer(Modifier.width(16.dp))
                        Text(
                            text = "负载: ${location.payloadType}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.width(16.dp))
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

            if (isDebugMode) {
                Spacer(Modifier.height(6.dp))
                KeyInfoText(location.periodIndex, location.hashedKey)
            }
        }
    }
}

@Composable
private fun KeyInfoText(periodIndex: Long?, hashedKey: String?) {
    if (periodIndex == null && hashedKey.isNullOrEmpty()) {
        Text(
            text = "密钥信息: 暂无（重新查询可补全）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            maxLines = 1
        )
        return
    }
    Column {
        periodIndex?.let {
            Text(
                text = "周期索引: #$it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
                maxLines = 1
            )
        }
        hashedKey?.takeIf { it.isNotEmpty() }?.let { key ->
            Text(
                text = "广告密钥: ${key.take(24)}${if (key.length > 24) "…" else ""}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 连续相同位置去重：按经纬度保留3位小数判定网格，
 * 连续停留在同一网格内的多个记录只保留最早到达的一条，恢复为降序呈现。
 */
private fun deduplicateLocations(locations: List<LocationEntity>): List<LocationEntity> {
    if (locations.size <= 1) return locations
    val asc = locations.sortedBy { it.timestamp }
    val result = mutableListOf<LocationEntity>()
    var lastGrid: String? = null
    for (loc in asc) {
        val grid = "${"%.3f".format(loc.latitude)}_${"%.3f".format(loc.longitude)}"
        if (grid != lastGrid) {
            result.add(loc)
            lastGrid = grid
        }
    }
    return result.sortedByDescending { it.timestamp }
}
