package com.reiyy.yuitag.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.reiyy.yuitag.data.FindMyQueryService
import com.reiyy.yuitag.data.YuiTagDevice
import com.reiyy.yuitag.data.local.AddressLevel
import com.reiyy.yuitag.data.local.LocationEntity
import com.reiyy.yuitag.viewmodel.DeviceHealthState
import com.reiyy.yuitag.viewmodel.MapMainViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

private fun formatRelativeTime(lastSeenMs: Long): String {
    if (lastSeenMs <= 0) return ""
    val elapsed = System.currentTimeMillis() - lastSeenMs
    val seconds = elapsed / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    val days = hours / 24
    val months = days / 30
    val years = months / 12

    return when {
        seconds < 60 -> "刚刚"
        minutes < 10 -> "${minutes}分钟前"
        minutes < 60 -> "${(minutes / 10) * 10}分钟前"
        hours < 24 -> "${hours}小时前"
        days < 30 -> "${days}天前"
        months < 6 -> "${months}个月前"
        months < 12 -> "半年前"
        else -> "${years}年前"
    }
}

private fun rssiStatusText(rssi: Int): String {
    return if (rssi >= -80) "在你身边" else "在你附近"
}

/**
 * 列表行"上次在线"文本：
 * FindMy 已启用且位置上报时间比 BLE 广播最新在线时间更晚时，采用 FindMy 位置（按所选地址层级解析，
 * 缺失时逐级向上取更粗层级；仍无则回退短地址/完整地址/经纬度）；
 * 否则回退到 BLE 广播在线状态。始终显示时间最新的一种在线事件。
 */
private fun lastOnlineSubText(device: YuiTagDevice, latestLocation: LocationEntity?, addressLevelKey: String): String {
    val findMyMs = if (device.findmyEnabled == true) latestLocation?.timestamp?.times(1000L) else null
    val useFindMy = findMyMs != null && findMyMs > device.lastSeen

    if (useFindMy) {
        val timeText = formatRelativeTime(findMyMs!!)
        // 按所选层级解析；缺失时逐级向上；仍无则回退旧 shortAddress/完整地址/经纬度
        val resolved = latestLocation?.addressDetail
            ?.resolve(AddressLevel.fromKey(addressLevelKey))
            ?.takeIf { it.isNotBlank() }
        val address = resolved
            ?: latestLocation?.shortAddress?.takeIf { it.isNotBlank() }
            ?: latestLocation?.address?.takeIf { it.isNotBlank() }
        val prefix = if (address != null) {
            address
        } else {
            "${"%.7f".format(latestLocation!!.latitude)}, ${"%.7f".format(latestLocation.longitude)}"
        }
        return if (timeText.isNotEmpty()) "$prefix  $timeText" else prefix
    }

    val timeText = formatRelativeTime(device.lastSeen)
    return if (device.lastSeen <= 0) {
        "尚未在线"
    } else if (timeText.isNotEmpty()) {
        "${rssiStatusText(device.rssi)}  $timeText"
    } else {
        rssiStatusText(device.rssi)
    }
}

@Composable
fun DeviceListContent(
    devices: List<YuiTagDevice>,
    deviceBatteries: Map<String, Int>,
    deviceHealthStates: Map<String, DeviceHealthState>,
    onDeviceClick: (YuiTagDevice) -> Unit,
    onNavigateToManualImport: () -> Unit = {},
    viewModel: MapMainViewModel,
    modifier: Modifier = Modifier,
    lazyListState: LazyListState = rememberLazyListState()
) {
    val uiState by viewModel.uiState.collectAsState()
    val hapticFeedback = LocalHapticFeedback.current

    // 拖拽排序本地镜像列表：拖拽时直接改本地顺序并持久化，
    // Room 回推同序数据时不做覆盖（仅比对 uid 顺序）
    val localList = remember { mutableStateListOf<YuiTagDevice>().apply { addAll(devices) } }
    LaunchedEffect(devices) {
        val currentIds = localList.map { it.uid }
        val newIds = devices.map { it.uid }
        if (currentIds == newIds) {
            // 仅刷新数据，保持用户自定义顺序
            devices.forEachIndexed { i, d ->
                if (localList.getOrNull(i)?.uid == d.uid) localList[i] = d
            }
        } else {
            localList.clear()
            localList.addAll(devices)
        }
    }

    Column(modifier = modifier) {
        if (localList.isEmpty()) {
            EmptyState(
                isScanning = uiState.isScanning,
                onScan = { viewModel.triggerScan() },
                onNavigateToManualImport = onNavigateToManualImport,
                modifier = Modifier.weight(1f)
            )
        } else {
            val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
                val moving = localList.removeAt(from.index)
                localList.add(to.index, moving)
                viewModel.updateDeviceOrder(localList.map { it.uid })
            }

            LazyColumn(
                state = lazyListState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .simpleVerticalScrollbar(lazyListState)
            ) {
                items(localList, key = { it.uid }) { device ->
                    ReorderableItem(reorderableState, key = device.uid) { isDragging ->
                        Column(modifier = Modifier.fillMaxWidth()) {
                            DeviceListItem(
                                device = device,
                                batteryPercent = deviceBatteries[device.uid] ?: device.battery,
                                healthState = deviceHealthStates[device.uid] ?: DeviceHealthState.JUDGING,
                                latestLocation = uiState.deviceLatestLocations[device.uid],
                                onClick = { onDeviceClick(device) },
                                dragging = isDragging,
                                dragHandleModifier = Modifier.longPressDraggableHandle(
                                    onDragStarted = {
                                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                    }
                                )
                            )
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                thickness = 0.5.dp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(
    isScanning: Boolean,
    onScan: () -> Unit,
    onNavigateToManualImport: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Devices,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "暂无已绑定设备",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "靠近你的 YuiTag 设备即可自动发现并绑定",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        OutlinedButton(
            onClick = onScan,
            enabled = !isScanning,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isScanning) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("正在搜索附近设备...")
            } else {
                Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("重新扫描")
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onNavigateToManualImport,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("手动导入设备")
        }
    }
}

@Composable
private fun DeviceListItem(
    device: YuiTagDevice,
    batteryPercent: Int,
    healthState: DeviceHealthState,
    latestLocation: LocationEntity?,
    onClick: () -> Unit,
    dragging: Boolean,
    dragHandleModifier: Modifier = Modifier
) {
    val displayName = device.customName.ifEmpty { device.name.ifEmpty { "YuiTag" } }
    // 列表行始终反映"上次在线"状态：显示最后一次在线的相对时间，
    // 仅在产生新在线事件时更新；离线后保持冻结（时间自然增长），不显示"离线"字样。
    // FindMy 位置上报也是一种在线事件：FindMy 已启用且位置时间比 BLE 广播更新时，
    // 显示 FindMy 位置（按设置中的地址层级，缺失时逐级向上），否则显示 BLE 广播状态。
    val context = LocalContext.current
    val addressLevel = remember { FindMyQueryService(context).getAddressLevel() }
    val subText = lastOnlineSubText(device, latestLocation, addressLevel)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !dragging, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(48.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Box(contentAlignment = Alignment.Center) {
DeviceTypeIcon(
                    deviceType = device.deviceType,
                    customIcon = device.customIcon,
                    size = 24.dp,
                    tint = MaterialTheme.colorScheme.primary,
                    typeIconPath = device.themePath
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.width(8.dp))

        if (batteryPercent >= 0) {
            Text(
                text = "${batteryPercent}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(10.dp))
        }

        HealthStateTag(health = healthState)
        // 长按拖拽排序手柄
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = dragHandleModifier
                .padding(4.dp)
                .size(20.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.DragHandle,
                contentDescription = "拖动排序",
                modifier = Modifier.size(18.dp),
                tint = if (dragging)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}
