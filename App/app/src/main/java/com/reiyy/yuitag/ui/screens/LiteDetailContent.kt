package com.reiyy.yuitag.ui.screens

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MyLocation
import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import com.reiyy.yuitag.util.hasNotificationPermission
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.reiyy.yuitag.data.YuiTagDevice
import com.reiyy.yuitag.data.local.DeviceType
import com.reiyy.yuitag.ui.components.DeviceTypeIcon
import com.reiyy.yuitag.ui.components.HealthStateTag
import com.reiyy.yuitag.viewmodel.DeviceConnectionState
import com.reiyy.yuitag.viewmodel.DeviceHealthState
import com.reiyy.yuitag.viewmodel.MapMainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LiteDetailContent(
    device: YuiTagDevice,
    viewModel: MapMainViewModel,
    onBack: () -> Unit,
    onNavigateToFind: (String) -> Unit,
    onNavigateToConfig: (String) -> Unit,
    onNavigateToPersonalization: (String) -> Unit,
    onNavigateToDetail: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val batteryPercent = uiState.deviceBatteries[device.uid] ?: device.battery
    val connectionState = uiState.deviceConnectionStates[device.uid] ?: DeviceConnectionState.OFFLINE
    val healthState = uiState.deviceHealthStates[device.uid] ?: DeviceHealthState.JUDGING
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(context, "未授予通知权限，遗落报警可能无法在锁屏或后台弹出提示", Toast.LENGTH_LONG).show()
        }
    }

    val displayName = device.customName.ifEmpty { device.name.ifEmpty { "YuiTag" } }
    val dateFormatter = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    val lastSeenText = remember(device.lastSeen) {
        dateFormatter.format(Date(device.lastSeen))
    }

    Column(modifier = modifier) {
        // --- 标题栏 ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }

            Text(
                text = displayName,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            HealthStateTag(
                health = healthState,
                connection = connectionState,
                onClick = { viewModel.retryConnect(device.uid) }
            )

            Spacer(Modifier.width(4.dp))

            Box {
                IconButton(onClick = { showMenu = true }, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "更多选项",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    shape = RoundedCornerShape(16.dp),
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ) {
                    DropdownMenuItem(
                        text = { Text("个性化") },
                        onClick = { showMenu = false; onNavigateToPersonalization(device.uid) },
                        leadingIcon = { Icon(Icons.Default.Settings, null, Modifier.size(20.dp)) }
                    )
                    DropdownMenuItem(
                        text = { Text("Tag设置") },
                        onClick = { showMenu = false; onNavigateToConfig(device.uid) },
                        leadingIcon = { Icon(Icons.Default.Settings, null, Modifier.size(20.dp)) }
                    )
                    DropdownMenuItem(
                        text = { Text("关于Tag") },
                        onClick = { showMenu = false; onNavigateToDetail(device.uid) },
                        leadingIcon = { Icon(Icons.Default.MyLocation, null, Modifier.size(20.dp)) }
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // --- 卡片区 ---
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 设备信息卡片
            LiteActionCard(
                deviceType = device.deviceType,
                customIcon = device.customIcon,
                themePath = device.themePath,
                iconTint = MaterialTheme.colorScheme.primary,
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.BatteryFull, null, Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = if (batteryPercent >= 0) "${batteryPercent}%" else "--",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "BLE: $lastSeenText",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 查找与响铃按钮
            val isRinging = uiState.ringActive && uiState.ringTargetUid == device.uid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                LiteActionCard(
                    icon = Icons.Default.TravelExplore,
                    iconTint = MaterialTheme.colorScheme.secondary,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    onClick = { onNavigateToFind(device.uid) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "查找",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }

                LiteActionCard(
                    icon = Icons.Default.Notifications,
                    iconTint = if (isRinging) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    containerColor = if (isRinging)
                        MaterialTheme.colorScheme.errorContainer
                    else
                        MaterialTheme.colorScheme.primaryContainer,
                    onClick = { viewModel.ringDevice(device.uid) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = if (isRinging) "停止响铃" else "响铃",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (isRinging) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                }
            }

            // 遗落提醒卡片开关（跟随全局总开关显示/隐藏，置于最下方居中收窄）
            if (uiState.isSeparationAlertMasterEnabled) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .fillMaxWidth(0.68f),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.NotificationsActive,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = if (device.separationAlertEnabled)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "遗落提醒",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Switch(
                            checked = device.separationAlertEnabled,
                            onCheckedChange = { enabled ->
                                viewModel.setSeparationAlertEnabled(device.uid, enabled)
                                if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission(context)) {
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LiteActionCard(
    icon: ImageVector,
    iconTint: Color,
    containerColor: Color,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    if (onClick != null) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = containerColor,
            onClick = onClick
        ) { ActionCardContent({ Icon(icon, null, Modifier.size(24.dp), tint = iconTint) }, content) }
    } else {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = containerColor
        ) { ActionCardContent({ Icon(icon, null, Modifier.size(24.dp), tint = iconTint) }, content) }
    }
}

@Composable
private fun LiteActionCard(
    deviceType: DeviceType,
    customIcon: String,
    themePath: String,
    iconTint: Color,
    containerColor: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = containerColor
    ) {
        ActionCardContent(
            { Box(Modifier.size(24.dp)) { DeviceTypeIcon(deviceType, customIcon, size = 24.dp, tint = iconTint, typeIconPath = themePath) } },
            content
        )
    }
}

@Composable
private fun ActionCardContent(
    leading: @Composable () -> Unit,
    content: @Composable () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        leading()
        Spacer(Modifier.width(12.dp))
        content()
    }
}