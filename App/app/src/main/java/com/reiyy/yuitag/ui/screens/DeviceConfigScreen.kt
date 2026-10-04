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
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Radio
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Timelapse
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.reiyy.yuitag.ble.YuiTagProtocol
import com.reiyy.yuitag.ui.components.ConnectingOverlay
import com.reiyy.yuitag.ui.components.SettingsCard
import com.reiyy.yuitag.ui.components.SettingsHeader
import com.reiyy.yuitag.ui.components.SettingsRadioPreference
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
        }
    }
}
