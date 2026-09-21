package com.reiyy.yuitag.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reiyy.yuitag.data.YuiTagDevice
import com.reiyy.yuitag.data.formatUid
import com.reiyy.yuitag.data.local.DeviceType

/**
 * 可展开设备卡片组件
 * 折叠态: 左侧设备类型图标 + 自定义名称
 * 展开态: 名称+电量、查找/响铃按钮、设备ID、右上三点菜单
 */
@Composable
fun DeviceCard(
    device: YuiTagDevice,
    batteryPercent: Int = 0,
    isExpanded: Boolean = false,
    onToggleExpand: () -> Unit = {},
    onFindClick: (String) -> Unit = {},
    onDetailClick: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val deviceType = device.deviceType

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onToggleExpand() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            // 折叠态：图标与名称
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 设备类型图标
                DeviceTypeIcon(
                    deviceType = deviceType,
                    customIcon = device.customIcon,
                    contentDescription = deviceType.label,
                    tint = MaterialTheme.colorScheme.primary,
                    size = 32.dp,
                    typeIconPath = device.themePath
                )

                Spacer(Modifier.width(14.dp))

                // 设备名称
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = device.customName.ifEmpty { "YuiTag_${device.uid.take(4)}" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = deviceType.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // 电池信息（折叠态）
                if (batteryPercent >= 0) {
                    Icon(
                        imageVector = Icons.Default.BatteryFull,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "$batteryPercent%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // 展开箭头
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(20.dp)
                )
            }

            // 展开态
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                ) {
                    HorizontalDivider(
                        modifier = Modifier.padding(bottom = 12.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )

                    // 顶部：名称 + 电量 + 三点菜单
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = device.customName.ifEmpty { "YuiTag_${device.uid.take(4)}" },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.BatteryFull,
                                    contentDescription = null,
                                    tint = if (batteryPercent > 20)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = "$batteryPercent%",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // 三点按钮 → 跳转详情页
                        IconButton(
                            onClick = { onDetailClick(device.uid) },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "设备详情",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // 按钮行：查找（响铃功能仅在查找模式中可用）
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = { onFindClick(device.uid) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("查找", style = MaterialTheme.typography.bodyMedium)
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // 底部小字：设备ID
                    Text(
                        text = "ID: ${formatUid(if (device.serialNumber.isNotEmpty()) device.serialNumber else device.uid)}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        fontSize = 10.sp
                    )
                }
            }
        }
    }
}
