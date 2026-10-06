package com.reiyy.yuitag.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.reiyy.yuitag.data.local.NotificationEntity
import com.reiyy.yuitag.data.local.NotificationLevel
import com.reiyy.yuitag.data.local.NotificationType
import com.reiyy.yuitag.viewmodel.NotificationViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationScreen(
    onBack: () -> Unit,
    onNavigateToRoute: (String) -> Unit = {},
    viewModel: NotificationViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showTopMenu by remember { mutableStateOf(false) }
    var showClearConfirmDialog by remember { mutableStateOf(false) }
    var showDeviceDropdown by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "通知",
                            style = MaterialTheme.typography.titleLarge
                        )
                        if (uiState.unreadCount > 0) {
                            Spacer(Modifier.width(8.dp))
                            Badge(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError
                            ) {
                                Text(if (uiState.unreadCount > 99) "99+" else "${uiState.unreadCount}")
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (uiState.unreadCount > 0) {
                        TextButton(onClick = { viewModel.markAllAsRead() }) {
                            Icon(
                                Icons.Default.DoneAll,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("全部已读")
                        }
                    }
                    Box {
                        IconButton(onClick = { showTopMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "更多操作")
                        }
                        DropdownMenu(
                            expanded = showTopMenu,
                            onDismissRequest = { showTopMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("全部标为已读") },
                                onClick = {
                                    showTopMenu = false
                                    viewModel.markAllAsRead()
                                },
                                leadingIcon = { Icon(Icons.Default.DoneAll, null) }
                            )
                            DropdownMenuItem(
                                text = { Text("清空已读通知") },
                                onClick = {
                                    showTopMenu = false
                                    viewModel.clearAllRead()
                                },
                                leadingIcon = { Icon(Icons.Default.DeleteOutline, null) }
                            )
                            DropdownMenuItem(
                                text = { Text("清空所有通知") },
                                onClick = {
                                    showTopMenu = false
                                    showClearConfirmDialog = true
                                },
                                leadingIcon = { Icon(Icons.Default.DeleteOutline, null) }
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // --- 顶部筛选栏 ---
            NotificationFilterBar(
                selectedType = uiState.selectedType,
                selectedDeviceUid = uiState.selectedDeviceUid,
                onlyUnread = uiState.onlyUnread,
                boundDevices = uiState.boundDevices,
                showDeviceDropdown = showDeviceDropdown,
                onDismissDeviceDropdown = { showDeviceDropdown = false },
                onToggleDeviceDropdown = { showDeviceDropdown = !showDeviceDropdown },
                onSelectType = { viewModel.setFilterType(it) },
                onSelectDevice = { viewModel.setFilterDeviceUid(it) },
                onToggleUnread = { viewModel.setOnlyUnread(!uiState.onlyUnread) }
            )

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            // --- 通知列表或空状态 ---
            if (uiState.notifications.isEmpty()) {
                EmptyNotificationState(
                    hasAnyNotifications = uiState.totalCount > 0,
                    onResetFilter = {
                        viewModel.setFilterType(null)
                        viewModel.setFilterDeviceUid(null)
                        viewModel.setOnlyUnread(false)
                    }
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(
                        items = uiState.notifications,
                        key = { it.id }
                    ) { item ->
                        NotificationItemCard(
                            item = item,
                            onClick = {
                                viewModel.markAsRead(item.id)
                                item.actionRoute?.let { route ->
                                    onNavigateToRoute(route)
                                }
                            },
                            onDelete = {
                                viewModel.deleteNotification(item.id)
                            }
                        )
                    }
                }
            }
        }
    }

    if (showClearConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            title = { Text("清空所有通知") },
            text = { Text("确定要删除全部通知记录吗？此操作不可撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirmDialog = false
                        viewModel.clearAll()
                    }
                ) {
                    Text("清空", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

/**
 * 筛选栏组件：支持按通知类型、关联设备及未读状态筛选
 */
@Composable
private fun NotificationFilterBar(
    selectedType: NotificationType?,
    selectedDeviceUid: String?,
    onlyUnread: Boolean,
    boundDevices: List<com.reiyy.yuitag.data.local.DeviceEntity>,
    showDeviceDropdown: Boolean,
    onDismissDeviceDropdown: () -> Unit,
    onToggleDeviceDropdown: () -> Unit,
    onSelectType: (NotificationType?) -> Unit,
    onSelectDevice: (String?) -> Unit,
    onToggleUnread: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // 第一行：类型 FilterChips 与 未读快捷开关
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = selectedType == null,
                onClick = { onSelectType(null) },
                label = { Text("全部类型") }
            )
            FilterChip(
                selected = selectedType == NotificationType.BATTERY,
                onClick = {
                    onSelectType(if (selectedType == NotificationType.BATTERY) null else NotificationType.BATTERY)
                },
                label = { Text("电池") },
                leadingIcon = {
                    Icon(Icons.Default.BatteryAlert, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            )
            FilterChip(
                selected = selectedType == NotificationType.DEVICE_STATUS,
                onClick = {
                    onSelectType(if (selectedType == NotificationType.DEVICE_STATUS) null else NotificationType.DEVICE_STATUS)
                },
                label = { Text("状态") },
                leadingIcon = {
                    Icon(Icons.Default.Sensors, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            )
            FilterChip(
                selected = selectedType == NotificationType.UPDATE,
                onClick = {
                    onSelectType(if (selectedType == NotificationType.UPDATE) null else NotificationType.UPDATE)
                },
                label = { Text("更新") },
                leadingIcon = {
                    Icon(Icons.Default.SystemUpdate, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            )
            FilterChip(
                selected = selectedType == NotificationType.SYSTEM,
                onClick = {
                    onSelectType(if (selectedType == NotificationType.SYSTEM) null else NotificationType.SYSTEM)
                },
                label = { Text("系统") },
                leadingIcon = {
                    Icon(Icons.Default.Notifications, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            )
        }

        Spacer(Modifier.height(6.dp))

        // 第二行：设备筛选器与仅看未读筛选
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 设备筛选下拉
            Box {
                val currentDeviceName = when (selectedDeviceUid) {
                    null -> "全部设备"
                    "SYSTEM" -> "系统/无设备"
                    else -> boundDevices.find { it.uid == selectedDeviceUid }?.customName ?: "未知设备 ($selectedDeviceUid)"
                }
                FilterChip(
                    selected = selectedDeviceUid != null,
                    onClick = onToggleDeviceDropdown,
                    label = { Text(currentDeviceName) },
                    leadingIcon = {
                        Icon(Icons.Default.FilterList, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                )

                DropdownMenu(
                    expanded = showDeviceDropdown,
                    onDismissRequest = onDismissDeviceDropdown
                ) {
                    DropdownMenuItem(
                        text = { Text("全部设备") },
                        onClick = {
                            onSelectDevice(null)
                            onDismissDeviceDropdown()
                        },
                        trailingIcon = if (selectedDeviceUid == null) {
                            { Icon(Icons.Default.Check, null) }
                        } else null
                    )
                    boundDevices.forEach { device ->
                        DropdownMenuItem(
                            text = { Text(device.customName.ifEmpty { "Tag (${device.uid})" }) },
                            onClick = {
                                onSelectDevice(device.uid)
                                onDismissDeviceDropdown()
                            },
                            trailingIcon = if (selectedDeviceUid == device.uid) {
                                { Icon(Icons.Default.Check, null) }
                            } else null
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("系统/无设备关联") },
                        onClick = {
                            onSelectDevice("SYSTEM")
                            onDismissDeviceDropdown()
                        },
                        trailingIcon = if (selectedDeviceUid == "SYSTEM") {
                            { Icon(Icons.Default.Check, null) }
                        } else null
                    )
                }
            }

            // 仅看未读 Chip
            FilterChip(
                selected = onlyUnread,
                onClick = onToggleUnread,
                label = { Text("仅看未读") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            )
        }
    }
}

/**
 * 单条通知卡片：
 * - 左侧边缘颜色指示条：蓝色（低级别）、黄色（中级别）、红色（高级别）
 * - 顶部行：左侧发送方名称，右侧类型微标签贴近时间信息
 * - 中部：通知标题与详细正文
 * - 底部：右下角轻量删除按钮
 */
@Composable
private fun NotificationItemCard(
    item: NotificationEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val level = NotificationLevel.fromCode(item.level)
    val type = NotificationType.fromCode(item.type)

    // 左侧边缘指示条颜色：蓝色=低级别，黄色=中级别，红色=高级别
    val levelIndicatorColor = when (level) {
        NotificationLevel.HIGH -> Color(0xFFEF4444) // 红色
        NotificationLevel.MEDIUM -> Color(0xFFF59E0B) // 黄色
        NotificationLevel.LOW -> Color(0xFF2563EB) // 蓝色
    }

    val typeIcon: ImageVector = when (type) {
        NotificationType.BATTERY -> Icons.Default.BatteryAlert
        NotificationType.DEVICE_STATUS -> Icons.Default.Sensors
        NotificationType.UPDATE -> Icons.Default.SystemUpdate
        NotificationType.SYSTEM -> Icons.Default.Notifications
    }

    val cardAlpha = if (item.isRead) 0.72f else 1f
    val cardContainerColor = if (item.isRead) {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(cardAlpha)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = cardContainerColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
        ) {
            // 左侧边缘颜色指示条
            Box(
                modifier = Modifier
                    .width(5.dp)
                    .fillMaxHeight()
                    .background(levelIndicatorColor)
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, top = 12.dp, end = 14.dp, bottom = 12.dp)
                ) {
                    // --- 顶部行：左侧发送方（获得最大空间），右侧类型微标签贴着时间 ---
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 未读红点指示
                        if (!item.isRead) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(MaterialTheme.colorScheme.error, CircleShape)
                            )
                            Spacer(Modifier.width(6.dp))
                        }

                        // 发送方：weight(1f) 获得最大展示空间
                        Text(
                            text = item.sender,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (item.isRead) FontWeight.Medium else FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )

                        Spacer(Modifier.width(8.dp))

                        // 类型微标签（靠右贴着时间显示）
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = typeIcon,
                                    contentDescription = null,
                                    modifier = Modifier.size(11.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.width(3.dp))
                                Text(
                                    text = type.displayName,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Spacer(Modifier.width(6.dp))

                        // 时间信息
                        Text(
                            text = formatNotificationTime(item.timestamp),
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    // --- 中部行：通知标题 ---
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (item.isRead) FontWeight.Normal else FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(Modifier.height(4.dp))

                    // --- 正文内容（右侧留出避让空间防止遮挡删除图标） ---
                    Text(
                        text = item.content,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 19.sp,
                        modifier = Modifier.padding(end = 28.dp)
                    )
                }

                // --- 删除按钮：置于卡片右下角，不单独占行 ---
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 8.dp, bottom = 8.dp)
                        .size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyNotificationState(
    hasAnyNotifications: Boolean,
    onResetFilter: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.NotificationsNone,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = if (hasAnyNotifications) "没有符合筛选条件的通知" else "暂无通知消息",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (hasAnyNotifications) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onResetFilter) {
                Text("重置筛选条件")
            }
        }
    }
}

private fun formatNotificationTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    if (diff < 60_000L) return "刚刚"
    if (diff < 3600_000L) return "${diff / 60_000L} 分钟前"

    val calNow = Calendar.getInstance()
    val calTime = Calendar.getInstance().apply { timeInMillis = timestamp }

    val isToday = calNow.get(Calendar.YEAR) == calTime.get(Calendar.YEAR) &&
            calNow.get(Calendar.DAY_OF_YEAR) == calTime.get(Calendar.DAY_OF_YEAR)
    if (isToday) {
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    val isYesterday = calNow.get(Calendar.YEAR) == calTime.get(Calendar.YEAR) &&
            calNow.get(Calendar.DAY_OF_YEAR) - calTime.get(Calendar.DAY_OF_YEAR) == 1
    if (isYesterday) {
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        return "昨天 " + sdf.format(Date(timestamp))
    }

    val isSameYear = calNow.get(Calendar.YEAR) == calTime.get(Calendar.YEAR)
    val pattern = if (isSameYear) "MM-dd HH:mm" else "yyyy-MM-dd HH:mm"
    val sdf = SimpleDateFormat(pattern, Locale.getDefault())
    return sdf.format(Date(timestamp))
}
