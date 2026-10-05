package com.reiyy.yuitag.ui.screens

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.reiyy.yuitag.ble.YuiTagProtocol
import com.reiyy.yuitag.data.formatUid
import com.reiyy.yuitag.ui.components.ConnectingOverlay
import com.reiyy.yuitag.ui.theme.statusColors
import com.reiyy.yuitag.viewmodel.DetailViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    uid: String,
    onBack: () -> Unit,
    onNavigateToOta: (String) -> Unit = {},
    onNavigateToDevOptions: (String) -> Unit = {},
    viewModel: DetailViewModel = viewModel()
) {
    LaunchedEffect(uid) {
        viewModel.initialize(uid)
    }

    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // 解绑成功后返回
    LaunchedEffect(uiState.unbindSuccess) {
        if (uiState.unbindSuccess) {
            viewModel.dismissUnbindSuccess()
            onBack()
        }
    }

    // 显示错误提示
    LaunchedEffect(uiState.unbindError) {
        uiState.unbindError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissUnbindError()
        }
    }

    // 显示错误后清除状态，使后续相同错误也能再次弹出
    LaunchedEffect(uiState.loadError) {
        uiState.loadError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissLoadError()
        }
    }

    // PARAM 配置读取失败提示（非致命）
    LaunchedEffect(uiState.paramLoadError) {
        uiState.paramLoadError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissParamLoadError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("\u5173\u4E8ETag") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "\u8FD4\u56DE")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, "\u66F4\u591A\u9009\u9879")
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            shape = RoundedCornerShape(16.dp),
                            containerColor = MaterialTheme.colorScheme.surfaceContainer
                        ) {
                            if (YuiTagProtocol.supportsOta(uiState.productModel)) {
                                DropdownMenuItem(
                                    text = { Text("\u56FA\u4EF6\u66F4\u65B0") },
                                    onClick = {
                                        showMenu = false
                                        onNavigateToOta(uid)
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.SystemUpdateAlt, null, Modifier.size(20.dp))
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("\u5F00\u53D1\u8005\u9009\u9879") },
                                onClick = {
                                    showMenu = false
                                    onNavigateToDevOptions(uid)
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Build, null, Modifier.size(20.dp))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("\u5220\u9664\u8BBE\u5907", color = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    showMenu = false
                                    showDeleteConfirm = true
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Delete, null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(20.dp))
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        // 删除确认对话框
        if (showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                title = { Text("\u5220\u9664\u8BBE\u5907") },
                text = { Text("\u5C06\u5411\u8BBE\u5907\u53D1\u9001\u89E3\u7ED1\u6307\u4EE4\u5E76\u6E05\u9664\u672C\u5730\u6570\u636E\u3002\n\u82E5\u8BBE\u5907\u4E0D\u5728\u8EAB\u8FB9\u6216\u65E0\u6CD5\u6B63\u5E38\u89E3\u7ED1\uFF0C\u8BF7\u4F7F\u7528\u300C\u5F3A\u5236\u5220\u9664\u300D\u3002") },
                confirmButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = {
                                showDeleteConfirm = false
                                viewModel.forceUnbindDevice()
                            }
                        ) {
                            Text("强制删除（设备不在身边）", color = MaterialTheme.statusColors.warning)
                        }
                        TextButton(
                            onClick = {
                                showDeleteConfirm = false
                                viewModel.unbindDevice()
                            }
                        ) {
                            Text("\u786E\u5B9A\u5220\u9664", color = MaterialTheme.colorScheme.error)
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirm = false }) {
                        Text("\u53D6\u6D88")
                    }
                }
            )
        }

        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Spacer(Modifier.height(8.dp))

                    // 顶部居中：自定义名称
                    Text(
                        text = uiState.customName.ifEmpty { "\u672A\u77E5" },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                    if (uiState.deviceTypeLabel.isNotEmpty()) {
                        Text(
                            text = uiState.deviceTypeLabel,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                    }

                    Spacer(Modifier.height(20.dp))

                    // 设备信息卡片
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                            Text("\u8BBE\u5907\u4FE1\u606F", style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(12.dp))

                            InfoRow("\u8BBE\u5907\u540D\u79F0", uiState.deviceName)
                            InfoRow("\u4EA7\u54C1\u540D\u79F0", uiState.productName)
                            InfoRow("\u4EA7\u54C1\u578B\u53F7", uiState.productModel)
                            InfoRow("\u8BBE\u5907SN", formatUid(uiState.serialNumber), monospace = true)
                            InfoRow("\u56FA\u4EF6\u7248\u672C", uiState.firmwareVer)
                            if (uiState.coreVersion.isNotEmpty()) {
                                InfoRow("\u6838\u5FC3\u7248\u672C", uiState.coreVersion)
                            }
                            InfoRow("\u786C\u4EF6\u7248\u672C", uiState.hardwareVersion)
                            InfoRow("\u5E73\u53F0\u4FE1\u606F", uiState.firmwarePlatform)
                            InfoRow("\u5382\u5546\u4FE1\u606F", uiState.manufacturer)
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // 设备配置卡片
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                            Text("\u8BBE\u5907\u914D\u7F6E", style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(12.dp))

                            InfoRow("\u5E7F\u64AD\u540D\u79F0", uiState.broadcastName)
                            InfoRow("\u6B63\u5E38\u6A21\u5F0F\u5E7F\u64AD\u95F4\u9694",
                                uiState.advIntervalNormal?.let { "${it} ms" } ?: "\u672A\u77E5")
                            InfoRow("\u67E5\u627E\u6A21\u5F0F\u5E7F\u64AD\u95F4\u9694",
                                uiState.advIntervalFind?.let { "${it} ms" } ?: "\u672A\u77E5")
                            if (uiState.txPowerSupported) {
                                val power = uiState.txPower
                                val sign = if (power > 0) "+$power" else "$power"
                                InfoRow("常规射频功率", "$sign dBm")
                            }
                            if (uiState.findmySupported != false) {
                                InfoRow("Find My 网络",
                                    when (uiState.findmyEnabled) {
                                        true -> "已启用"
                                        false -> "已停用"
                                        null -> "未知"
                                    })
                                if (uiState.findmyEnabled == true && uiState.findmyTxPowerSupported) {
                                    val fPower = uiState.findmyTxPower
                                    val fSign = if (fPower > 0) "+$fPower" else "$fPower"
                                    InfoRow("FindMy射频功率", "$fSign dBm")
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    // 解绑进度
                    if (uiState.isUnbinding) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("\u6B63\u5728\u89E3\u7ED1...", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    Spacer(Modifier.height(24.dp))
                }

            // 连接中悬浮窗口
            ConnectingOverlay(visible = uiState.isLoading)
        }
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    monospace: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.weight(0.4f)
        )
        Text(
            text = value.ifEmpty { "\u672A\u77E5" },
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (monospace) FontFamily.Monospace else null,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(0.6f),
            textAlign = TextAlign.End
        )
    }
}
