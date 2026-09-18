package com.yuitag.finder.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yuitag.finder.data.YuiTagDevice
import com.yuitag.finder.ui.components.BindPopupCard
import com.yuitag.finder.ui.components.BindPopupState
import com.yuitag.finder.ui.components.DeviceTypeIcon
import com.yuitag.finder.ui.components.HealthStateTag
import com.yuitag.finder.viewmodel.BottomSheetMode
import com.yuitag.finder.viewmodel.DeviceHealthState
import com.yuitag.finder.viewmodel.MapMainViewModel

private enum class LitePageMode { LIST, DETAIL }

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiteMainScreen(
    onNavigateToFind: (String) -> Unit,
    onNavigateToConfig: (String) -> Unit,
    onNavigateToPersonalization: (String) -> Unit,
    onNavigateToDetail: (String) -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToManualImport: () -> Unit = {},
    onNavigateToAbout: () -> Unit = {},
    viewModel: MapMainViewModel
) {
    val uiState by viewModel.uiState.collectAsState()
    val boundDevices by viewModel.boundDevices.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    var pageMode by remember { mutableStateOf(LitePageMode.LIST) }
    var showTopMenu by remember { mutableStateOf(false) }

    val selectedDevice by remember {
        derivedStateOf {
            val uid = uiState.selectedDeviceUid
            if (uid != null) boundDevices.find { it.uid == uid } else null
        }
    }

    // 选中的设备被删除/解绑后自动回到列表模式
    if (pageMode == LitePageMode.DETAIL && uiState.selectedDeviceUid == null) {
        pageMode = LitePageMode.LIST
    }

    LaunchedEffect(uiState.bottomSheetMode) {
        if (uiState.bottomSheetMode == BottomSheetMode.LIST && pageMode == LitePageMode.DETAIL) {
            pageMode = LitePageMode.LIST
        }
    }

    // 生命周期管理
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> viewModel.onPause()
                Lifecycle.Event.ON_RESUME -> viewModel.onResume()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("YuiTag",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface)
                },
                actions = {
                    IconButton(onClick = { viewModel.toggleScan() }) {
                        if (uiState.isScanning) {
                            val infiniteTransition = rememberInfiniteTransition(label = "scan_spin")
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
                                Icons.Default.Sync,
                                contentDescription = "停止扫描",
                                modifier = Modifier.rotate(rotation),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        } else {
                            Icon(
                                Icons.Default.Sync,
                                contentDescription = "开始扫描",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                    Box {
                        IconButton(onClick = { showTopMenu = true }) {
                            Icon(Icons.Default.Settings,
                                contentDescription = "更多",
                                tint = MaterialTheme.colorScheme.onSurface)
                        }
                        DropdownMenu(expanded = showTopMenu, onDismissRequest = { showTopMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("关于") },
                                onClick = {
                                    showTopMenu = false
                                    onNavigateToAbout()
                                },
                                leadingIcon = { Icon(Icons.Default.Info, null) }
                            )
                            DropdownMenuItem(
                                text = { Text("设置") },
                                onClick = {
                                    showTopMenu = false
                                    onNavigateToSettings()
                                },
                                leadingIcon = { Icon(Icons.Default.Settings, null) }
                            )
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
            when (pageMode) {
                LitePageMode.LIST -> {
                    LiteDeviceList(
                        devices = boundDevices,
                        deviceBatteries = uiState.deviceBatteries,
                        deviceHealthStates = uiState.deviceHealthStates,
                        isScanning = uiState.isScanning,
                        onDeviceClick = { device ->
                            viewModel.selectDevice(device)
                            pageMode = LitePageMode.DETAIL
                        },
                        onScan = { viewModel.triggerScan() },
                        onNavigateToManualImport = onNavigateToManualImport,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                LitePageMode.DETAIL -> {
                    selectedDevice?.let { device ->
                        LiteDetailContent(
                            device = device,
                            viewModel = viewModel,
                            onBack = {
                                viewModel.backToList()
                                pageMode = LitePageMode.LIST
                            },
                            onNavigateToFind = onNavigateToFind,
                            onNavigateToConfig = onNavigateToConfig,
                            onNavigateToPersonalization = onNavigateToPersonalization,
                            onNavigateToDetail = onNavigateToDetail,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }

            // 绑定弹窗遮罩
            if (uiState.showBindPopup) {
                BindPopupCard(
                    state = when {
                        uiState.bindError != null -> BindPopupState.ERROR
                        uiState.bindSuccessUid != null -> BindPopupState.SUCCESS
                        uiState.bindingUid != null -> BindPopupState.BINDING
                        else -> BindPopupState.DISCOVERED
                    },
                    deviceName = uiState.unboundDeviceName ?: "YuiTag",
                    errorMessage = uiState.bindError,
                    bindProgress = uiState.bindProgress,
                    onBindClick = {
                        val failedUid = uiState.bindFindMyFailedUid
                        if (failedUid != null) viewModel.retryFindMy(failedUid)
                        else uiState.unboundDeviceUid?.let { viewModel.bindDevice(it) }
                    },
                    onNextClick = { uiState.bindSuccessUid?.let { viewModel.dismissBindPopup() } },
                    onDismiss = { viewModel.dismissBindPopup() },
                    onIgnoreClick = { uiState.unboundDeviceUid?.let { viewModel.ignoreUnboundDevice(it) } },
                    modifier = Modifier.zIndex(2f)
                )
            }
        }
    }
}

@Composable
private fun LiteDeviceList(
    devices: List<YuiTagDevice>,
    deviceBatteries: Map<String, Int>,
    deviceHealthStates: Map<String, DeviceHealthState>,
    isScanning: Boolean,
    onDeviceClick: (YuiTagDevice) -> Unit,
    onScan: () -> Unit,
    onNavigateToManualImport: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (devices.isEmpty()) {
        EmptyState(
            isScanning = isScanning,
            onScan = onScan,
            onNavigateToManualImport = onNavigateToManualImport,
            modifier = modifier
        )
    } else {
        LazyColumn(modifier = modifier) {
            items(devices, key = { it.uid }) { device ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    LiteDeviceListItem(
                        device = device,
                        batteryPercent = deviceBatteries[device.uid] ?: device.battery,
                        healthState = deviceHealthStates[device.uid] ?: DeviceHealthState.JUDGING,
                        onClick = { onDeviceClick(device) }
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

@Composable
private fun LiteDeviceListItem(
    device: YuiTagDevice,
    batteryPercent: Int,
    healthState: DeviceHealthState,
    onClick: () -> Unit
) {
    val displayName = device.customName.ifEmpty { device.name.ifEmpty { "YuiTag" } }
    val timeText = formatRelativeTime(device.lastSeen)
    val subText = if (device.lastSeen <= 0) {
        "尚未在线"
    } else if (timeText.isNotEmpty()) {
        "${rssiStatusText(device.rssi)}  $timeText"
    } else {
        rssiStatusText(device.rssi)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(44.dp),
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
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
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
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("手动导入设备")
        }
    }
}