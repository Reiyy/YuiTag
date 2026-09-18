package com.yuitag.finder.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yuitag.finder.ble.YuiTagProtocol
import com.yuitag.finder.viewmodel.DevOptionsViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevOptionsScreen(
    uid: String,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: DevOptionsViewModel
) {
    LaunchedEffect(uid) {
        viewModel.initialize(uid)
    }

    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.saveError) {
        uiState.saveError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissSaveError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("开发者选项") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(20.dp))

            // Tag 设备广播名称
            OutlinedTextField(
                value = uiState.broadcastName,
                onValueChange = { viewModel.updateBroadcastName(it) },
                label = { Text("Tag设备名称") },
                placeholder = { Text("例如：YuiTag_A1B2") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                supportingText = { Text("最大24字节") }
            )

            Spacer(Modifier.height(32.dp))

            // 保存按钮
            Button(
                onClick = { viewModel.saveName(onSaved) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !uiState.isSaving,
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(
                    text = if (uiState.isSaving) "保存中..." else "保存",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(Modifier.height(16.dp))

            // --- FindMy 调试区块 ---
            if (uiState.findmySupported != false) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Spacer(Modifier.height(8.dp))

            Text(
                text = "FindMy",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(4.dp))

            Text(
                text = "查询设备端 Find My 状态（CMD 0x63）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = { viewModel.fetchFindMyStatus() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !uiState.isFetchingFindMyStatus,
                shape = RoundedCornerShape(14.dp)
            ) {
                if (uiState.isFetchingFindMyStatus) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = "查询中...",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                } else {
                    Text(
                        text = "获取当前FindMy状态",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = { viewModel.deriveCurrentKey() },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(
                    text = "派生当前密钥",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(Modifier.height(16.dp))

            // --- FindMy 广播扫描区块 ---
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Spacer(Modifier.height(8.dp))

            Text(
                text = "FindMy 广播扫描",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(4.dp))

            Text(
                text = "扫描空中 FindMy 广播，验证设备是否正常广播（±5 时间窗口）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))

            val scanState = uiState.findMyBroadcast

            // 错误提示
            scanState.error?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { viewModel.dismissFindMyBroadcastError() }) {
                    Text("关闭")
                }
                Spacer(Modifier.height(8.dp))
            }

            // 扫描按钮
            Button(
                onClick = {
                    if (scanState.isScanning || scanState.isDeriving) {
                        viewModel.stopFindMyBroadcastScan()
                    } else {
                        viewModel.startFindMyBroadcastScan()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = scanState.error == null,
                shape = RoundedCornerShape(14.dp)
            ) {
                if (scanState.isDeriving) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Text("正在派生候选密钥...")
                    }
                } else if (scanState.isScanning) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Text("停止扫描")
                    }
                } else {
                    Text(
                        text = "开始扫描 FindMy 广播",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // 扫描状态提示
            if (scanState.isScanning && scanState.derivedMacCount > 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "扫描中... 监听 ${scanState.derivedMacCount} 个候选 MAC",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // 扫描结果
            if (scanState.detectedMac != null && scanState.rssi != null) {
                Spacer(Modifier.height(12.dp))
                FindMyBroadcastResultCard(scanState)
            } else if (scanState.isScanning && !scanState.isDeriving && scanState.derivedMacCount > 0) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "未检测到 FindMy 广播，请确认设备 FindMy 已开启且在范围内",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(16.dp))
            }
        }

        // FindMy 状态查询结果弹窗
        if (uiState.showFindMyStatusDialog) {
            AlertDialog(
                onDismissRequest = { viewModel.dismissFindMyStatusDialog() },
                title = { Text("FindMy 状态") },
                text = {
                    val err = uiState.findMyStatusError
                    val status = uiState.findMyStatus
                    when {
                        uiState.isFetchingFindMyStatus -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                                Text("正在查询设备状态...")
                            }
                        }
                        err != null -> {
                            Text(
                                err,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        status != null -> {
                            FindMyStatusContent(
                                status = status,
                                periodSec = uiState.findMyPeriodSec
                            )
                        }
                        else -> Text("无数据")
                    }
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.dismissFindMyStatusDialog() }) {
                        Text("关闭")
                    }
                }
            )
        }
    }
}

@Composable
private fun FindMyStatusContent(
    status: YuiTagProtocol.FindMyStatus,
    periodSec: Int?
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "时间已同步：",
                fontWeight = FontWeight.Medium
            )
            Text(if (status.timeSynced) "是" else "否")
        }
        Text(
            "设备当前时间：${status.currentTime} 秒",
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            formatUnixTime(status.currentTime),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "当前时间窗口：#${status.currentPeriod}",
            style = MaterialTheme.typography.bodyMedium
        )
        periodSec?.takeIf { it > 0 }?.let { sec ->
            val label = YuiTagProtocol.FindMyPeriod.fromSeconds(sec).label
            val elapsed = if (status.currentTime > 0) status.currentTime % sec else 0
            Text(
                "窗口时长：$label，窗口内已过：$elapsed 秒",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatUnixTime(unixSeconds: Long): String {
    if (unixSeconds <= 0) return "（未同步）"
    return try {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .format(Date(unixSeconds * 1000L))
    } catch (_: Exception) {
        "（时间格式化失败）"
    }
}

@Composable
private fun FindMyBroadcastResultCard(state: DevOptionsViewModel.FindMyBroadcastState) {
    val signalPercent = state.signalPercent ?: 0f
    val signalColor = when {
        signalPercent > 70f -> MaterialTheme.colorScheme.primary
        signalPercent > 40f -> androidx.compose.ui.graphics.Color(0xFFFFA000)
        else -> MaterialTheme.colorScheme.error
    }
    val batteryLabel = when (state.batteryStatus) {
        0 -> "正常"
        1 -> "中等"
        2 -> "低"
        3 -> "极低"
        else -> "未知"
    }
    val timeText = state.lastSeenTime?.let { relativeTime(it) } ?: "--"

    Card(
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(64.dp)
            ) {
                CircularProgressIndicator(
                    progress = { signalPercent / 100f },
                    modifier = Modifier.size(64.dp),
                    strokeWidth = 5.dp,
                    color = signalColor,
                    trackColor = signalColor.copy(alpha = 0.15f)
                )
                Text(
                    text = "${signalPercent.toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = signalColor
                )
            }

            Spacer(Modifier.size(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "MAC: ${state.detectedMac}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                state.pubXHex?.let { pubX ->
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "pubX: $pubX",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "信号强度: ${state.rssi} dBm  |  电池: $batteryLabel",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "检测于: $timeText",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun relativeTime(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 0 -> "刚刚"
        diff < 1000 -> "刚刚"
        diff < 60_000 -> "${diff / 1000} 秒前"
        diff < 3_600_000 -> "${diff / 60_000} 分 ${(diff % 60_000) / 1000} 秒前"
        else -> "${diff / 3_600_000} 小时前"
    }
}
