package com.reiyy.yuitag.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Numbers
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Radio
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SignalCellularAlt
import androidx.compose.material.icons.outlined.Timelapse
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.reiyy.yuitag.ble.YuiTagProtocol
import com.reiyy.yuitag.ui.components.ConnectingOverlay
import com.reiyy.yuitag.ui.components.SettingsCard
import com.reiyy.yuitag.ui.components.SettingsHeader
import com.reiyy.yuitag.ui.components.SettingsRadioPreference
import com.reiyy.yuitag.ui.components.SettingsRow
import com.reiyy.yuitag.ui.components.SettingsSwitchRow
import com.reiyy.yuitag.ui.components.SettingsTextPreference
import com.reiyy.yuitag.viewmodel.DeviceConfigViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceConfigScreen(
    uid: String,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: DeviceConfigViewModel = viewModel()
) {
    LaunchedEffect(uid) {
        viewModel.initialize(uid)
    }

    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showConfirmSyncDialog by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.saveError) {
        uiState.saveError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissSaveError()
        }
    }

    LaunchedEffect(uiState.connectError) {
        uiState.connectError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissConnectError()
        }
    }

    LaunchedEffect(uiState.syncKeySuccess) {
        if (uiState.syncKeySuccess) {
            snackbarHostState.showSnackbar("固定密钥同步成功")
            viewModel.dismissSyncKeyResult()
        }
    }

    LaunchedEffect(uiState.syncKeyError) {
        uiState.syncKeyError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissSyncKeyResult()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tag设置") },
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
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp)
            ) {
                SettingsHeader("广播参数")
                SettingsCard {
                    SettingsTextPreference(
                        icon = Icons.Outlined.Radio,
                        title = "常规广播间隔",
                        subtitle = "范围 100-10000 ms，默认 1500 ms",
                        value = uiState.advIntervalNormal,
                        placeholder = "1500",
                        keyboardType = KeyboardType.Number,
                        onSave = { viewModel.updateAdvIntervalNormal(it) }
                    )
                    HorizontalDivider()
                    SettingsTextPreference(
                        icon = Icons.Outlined.Search,
                        title = "查找状态广播间隔",
                        subtitle = "范围 100-10000 ms，默认 500 ms",
                        value = uiState.advIntervalFind,
                        placeholder = "500",
                        keyboardType = KeyboardType.Number,
                        onSave = { viewModel.updateAdvIntervalFind(it) }
                    )
                    if (uiState.txPowerSupported) {
                        HorizontalDivider()
                        SettingsRadioPreference(
                            icon = Icons.Outlined.SignalCellularAlt,
                            title = "常规射频功率",
                            options = YuiTagProtocol.TxPowerPreset.getAvailableOptions(
                                minDbm = uiState.minTxPower,
                                maxDbm = uiState.maxTxPower,
                                currentDbm = uiState.txPower,
                                platform = uiState.firmwarePlatform
                            ),
                            selected = uiState.txPower ?: 0,
                            onSelect = { viewModel.updateTxPower(it) }
                        )
                    }
                }

                if (uiState.findmySupported != false) {
                    SettingsHeader("Find My 网络")
                    SettingsCard {
                        SettingsSwitchRow(
                            icon = Icons.Outlined.Public,
                            title = "Find My 网络",
                            subtitle = when (uiState.findmyEnabled) {
                                true -> "已启用 — 设备可被Apple查找网络定位"
                                false -> "已停用 — 仅可使用本App查找"
                                null -> "未知 — 请先连接设备读取配置"
                            },
                            checked = uiState.findmyEnabled ?: false,
                            enabled = uiState.findmyEnabled != null,
                            onCheckedChange = { viewModel.updateFindMyEnabled(it) }
                        )
                        if (uiState.findmyEnabled == true) {
                            HorizontalDivider()
                            SettingsRadioPreference(
                                icon = Icons.Outlined.Timelapse,
                                title = "密钥轮换间隔",
                                subtitle = "更短的间隔提供更频繁的位置更新，但消耗更多电量",
                                options = YuiTagProtocol.FindMyPeriod.entries.map { it.index to it.label },
                                selected = uiState.findmyPeriodIndex ?: 2,
                                onSelect = { viewModel.updateFindMyPeriodIndex(it) }
                            )
                            if (uiState.isSparks) {
                                HorizontalDivider()
                                SettingsTextPreference(
                                    icon = Icons.Outlined.Numbers,
                                    title = "轮换密钥总数",
                                    subtitle = "预生成的轮换公钥数量 (5-50，默认 20)",
                                    value = "${uiState.fixedKeyCount}",
                                    placeholder = "20",
                                    keyboardType = KeyboardType.Number,
                                    onSave = { viewModel.updateFixedKeyCount(it.toIntOrNull() ?: 20) }
                                )
                                HorizontalDivider()
                                SettingsRow(
                                    icon = Icons.Outlined.Refresh,
                                    title = "生成并同步密钥至 Tag",
                                    subtitle = "重新生成 ${uiState.fixedKeyCount} 个全新公钥并写入设备",
                                    onClick = { showConfirmSyncDialog = true }
                                )
                            }
                            if (uiState.findmyAdvIntervalSupported) {
                                HorizontalDivider()
                                SettingsTextPreference(
                                    icon = Icons.Outlined.Timelapse,
                                    title = "Find My 广播间隔",
                                    subtitle = "范围 200-5000 ms，默认 2000 ms",
                                    value = uiState.findmyAdvInterval,
                                    placeholder = "2000",
                                    keyboardType = KeyboardType.Number,
                                    onSave = { viewModel.updateFindMyAdvInterval(it) }
                                )
                            }
                            if (uiState.findmyTxPowerSupported) {
                                HorizontalDivider()
                                SettingsRadioPreference(
                                    icon = Icons.Outlined.SignalCellularAlt,
                                    title = "FindMy射频功率",
                                    options = YuiTagProtocol.TxPowerPreset.getAvailableOptions(
                                        minDbm = uiState.minTxPower,
                                        maxDbm = uiState.maxTxPower,
                                        currentDbm = uiState.findmyTxPower,
                                        platform = uiState.firmwarePlatform
                                    ),
                                    selected = uiState.findmyTxPower ?: 4,
                                    onSelect = { viewModel.updateFindMyTxPower(it) }
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))

                Button(
                    onClick = { viewModel.saveConfig(onSaved) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    enabled = !uiState.isSaving && !uiState.isConnecting
                ) {
                    Text(
                        text = if (uiState.isSaving) "保存中..." else "保存",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }

            ConnectingOverlay(visible = uiState.isConnecting)

            if (showConfirmSyncDialog) {
                AlertDialog(
                    onDismissRequest = { showConfirmSyncDialog = false },
                    title = { Text("同步固定密钥池") },
                    text = { Text("将重新生成 ${uiState.fixedKeyCount} 个全新的 Find My 轮换公钥并通过蓝牙下发至 Tag。旧公钥将失效。是否继续？") },
                    confirmButton = {
                        TextButton(onClick = {
                            showConfirmSyncDialog = false
                            viewModel.regenerateAndSyncFixedKeys()
                        }) {
                            Text("立即同步")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showConfirmSyncDialog = false }) {
                            Text("取消")
                        }
                    }
                )
            }

            if (uiState.isSyncingKeys) {
                AlertDialog(
                    onDismissRequest = { /* 正在写入，不允许取消 */ },
                    title = { Text("正在同步密钥") },
                    text = {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(modifier = Modifier.padding(16.dp))
                            Text(
                                text = uiState.syncKeyProgress?.let { "正在发送: ${it.first} / ${it.second}" } ?: "正在准备密钥数据...",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    },
                    confirmButton = {}
                )
            }
        }
    }
}
