package com.reiyy.yuitag.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.reiyy.yuitag.data.FindMyQueryService
import com.reiyy.yuitag.ui.components.SettingsCard
import com.reiyy.yuitag.ui.components.SettingsHeader
import com.reiyy.yuitag.ui.components.SettingsRow
import com.reiyy.yuitag.ui.components.SettingsSwitchRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigateToManualImport: () -> Unit,
    onNavigateToLiteMain: () -> Unit = {},
    onNavigateToFullMain: () -> Unit = {},
    onNavigateToBackend: () -> Unit = {},
    onNavigateToMap: () -> Unit = {},
    onNavigateToQuery: () -> Unit = {},
    onNavigateToBackup: () -> Unit = {},
    onNavigateToAbout: () -> Unit = {}
) {
    val context = LocalContext.current
    val service = remember { FindMyQueryService(context) }

    var liteMode by remember { mutableStateOf(service.isLiteModeEnabled()) }
    var debugMode by remember { mutableStateOf(service.isDebugModeEnabled()) }
    var endpointUrl by remember { mutableStateOf(service.getEndpointUrl()) }

    LifecycleResumeEffect(Unit) {
        liteMode = service.isLiteModeEnabled()
        debugMode = service.isDebugModeEnabled()
        endpointUrl = service.getEndpointUrl()
        onPauseOrDispose { }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp)
        ) {
            SettingsHeader("通用")
            SettingsCard {
                SettingsSwitchRow(
                    icon = Icons.Outlined.Speed,
                    title = "精简模式",
                    subtitle = if (liteMode) "已启用 — 使用精简主页" else "已关闭 — 使用完整主页",
                    checked = liteMode,
                    onCheckedChange = { enabled ->
                        liteMode = enabled
                        service.setLiteModeEnabled(enabled)
                        Toast.makeText(context, if (enabled) "已启用精简模式" else "已关闭精简模式", Toast.LENGTH_SHORT).show()
                        if (enabled) onNavigateToLiteMain() else onNavigateToFullMain()
                    }
                )
                HorizontalDivider()
                SettingsSwitchRow(
                    icon = Icons.Outlined.BugReport,
                    title = "调试日志",
                    subtitle = "开启后额外记录包含密钥等敏感信息的日志",
                    checked = debugMode,
                    onCheckedChange = { enabled ->
                        debugMode = enabled
                        service.setDebugModeEnabled(enabled)
                    }
                )
            }

            SettingsHeader("服务")
            SettingsCard {
                SettingsRow(
                    icon = Icons.Outlined.Dns,
                    title = "后端服务",
                    subtitle = if (endpointUrl.isBlank()) "未配置" else endpointUrl,
                    onClick = onNavigateToBackend
                )
                HorizontalDivider()
                SettingsRow(
                    icon = Icons.Outlined.Map,
                    title = "地图",
                    subtitle = "逆地理编码与地图底图",
                    onClick = onNavigateToMap
                )
            }

            SettingsHeader("查询")
            SettingsCard {
                SettingsRow(
                    icon = Icons.Outlined.QueryStats,
                    title = "查询设置",
                    subtitle = "扩展范围、查询分片、地址显示层级",
                    onClick = onNavigateToQuery
                )
            }

            SettingsHeader("数据")
            SettingsCard {
                SettingsRow(
                    icon = Icons.Outlined.Backup,
                    title = "备份与恢复",
                    subtitle = "导出 / 导入全部配置",
                    onClick = onNavigateToBackup
                )
                HorizontalDivider()
                SettingsRow(
                    icon = Icons.Outlined.FileUpload,
                    title = "手动导入设备",
                    subtitle = "手动绑定 Tag",
                    onClick = onNavigateToManualImport
                )
            }

            SettingsHeader("其他")
            SettingsCard {
                SettingsRow(
                    icon = Icons.Outlined.Info,
                    title = "关于",
                    subtitle = "版本与更新",
                    onClick = onNavigateToAbout
                )
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}
