package com.reiyy.yuitag.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.FmdGood
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.Numbers
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Timer
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.reiyy.yuitag.data.FindMyQueryService
import com.reiyy.yuitag.data.local.AddressLevel
import com.reiyy.yuitag.ui.components.SettingsCard
import com.reiyy.yuitag.ui.components.SettingsHeader
import com.reiyy.yuitag.ui.components.SettingsRadioPreference
import com.reiyy.yuitag.ui.components.SettingsSwitchRow
import com.reiyy.yuitag.ui.components.SettingsTextPreference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsQueryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val service = remember { FindMyQueryService(context) }

    var autoRefreshEnabled by remember { mutableStateOf(service.isAutoRefreshEnabled()) }

    var extensionType by remember { mutableStateOf(service.getExtensionType()) }
    var extensionValueText by remember { mutableStateOf(service.getExtensionValue().toString()) }
    var batchKeyLimitText by remember { mutableStateOf(service.getBatchKeyLimit().toString()) }
    var chunkRetryCountText by remember { mutableStateOf(service.getChunkRetryCount().toString()) }
    var chunkRetryDelayText by remember { mutableStateOf(service.getChunkRetryDelaySec().toString()) }
    var addressLevel by remember { mutableStateOf(service.getAddressLevel()) }
    var healthNormalDaysText by remember { mutableStateOf(service.getHealthNormalDays().toString()) }

    val isTimeMode = extensionType == "time"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("查询设置") },
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
            SettingsHeader("自动刷新")
            SettingsCard {
                SettingsSwitchRow(
                    icon = Icons.Outlined.Sync,
                    title = "自动刷新位置",
                    subtitle = "冷启动主页及进入设备详情页时自动查询最新位置",
                    checked = autoRefreshEnabled,
                    onCheckedChange = {
                        autoRefreshEnabled = it
                        service.saveAutoRefreshEnabled(it)
                    }
                )
            }

            SettingsHeader("查询范围扩展")
            SettingsCard {
                SettingsRadioPreference(
                    icon = Icons.Outlined.Schedule,
                    title = "扩展方式",
                    subtitle = "在时间范围前后额外扩展查询，用于补偿时钟偏差",
                    options = listOf(
                        "time" to "按时间（小时）",
                        "keys" to "按密钥数量"
                    ),
                    selected = extensionType,
                    onSelect = { extensionType = it; service.saveExtensionType(it) }
                )
                HorizontalDivider()
                SettingsTextPreference(
                    icon = Icons.Outlined.Calculate,
                    title = if (isTimeMode) "扩展时间（小时）" else "扩展密钥数",
                    value = extensionValueText,
                    placeholder = if (isTimeMode) "2.0" else "2",
                    keyboardType = KeyboardType.Decimal,
                    onSave = {
                        extensionValueText = it
                        service.saveExtensionValue(it.toFloatOrNull() ?: 2.0f)
                    }
                )
            }

            SettingsHeader("查询分片")
            SettingsCard {
                SettingsTextPreference(
                    icon = Icons.Outlined.Numbers,
                    title = "单次请求最大密钥数",
                    subtitle = "超过上限时分段请求",
                    value = batchKeyLimitText,
                    placeholder = "100",
                    keyboardType = KeyboardType.Number,
                    onSave = {
                        val num = it.trim().toIntOrNull()
                        if (num != null && num > 0) {
                            batchKeyLimitText = num.toString()
                            service.saveBatchKeyLimit(num)
                        } else {
                            service.saveBatchKeyLimit(-1)
                            batchKeyLimitText = service.getBatchKeyLimit().toString()
                        }
                    }
                )
                HorizontalDivider()
                SettingsTextPreference(
                    icon = Icons.Outlined.Refresh,
                    title = "分片重试次数",
                    subtitle = "每段失败独立重试",
                    value = chunkRetryCountText,
                    placeholder = "1",
                    keyboardType = KeyboardType.Number,
                    onSave = {
                        chunkRetryCountText = it
                        service.saveChunkRetryCount((it.toIntOrNull() ?: 1).coerceAtLeast(0))
                    }
                )
                HorizontalDivider()
                SettingsTextPreference(
                    icon = Icons.Outlined.Timer,
                    title = "重试间隔（秒）",
                    value = chunkRetryDelayText,
                    placeholder = "1",
                    keyboardType = KeyboardType.Number,
                    onSave = {
                        chunkRetryDelayText = it
                        service.saveChunkRetryDelaySec((it.toIntOrNull() ?: 1).coerceAtLeast(0))
                    }
                )
            }

            SettingsHeader("显示与健康")
            SettingsCard {
                SettingsRadioPreference(
                    icon = Icons.Outlined.FmdGood,
                    title = "地址显示层级",
                    subtitle = "列表页位置显示所选层级；缺失时逐级向上显示更粗粒度",
                    options = AddressLevel.entries.map { it.key to it.label },
                    selected = addressLevel,
                    onSelect = { addressLevel = it; service.saveAddressLevel(it) }
                )
                HorizontalDivider()
                SettingsTextPreference(
                    icon = Icons.Outlined.HealthAndSafety,
                    title = "设备健康判定天数",
                    subtitle = "超过该天数未收到任何信号则标记为异常",
                    value = healthNormalDaysText,
                    placeholder = "7",
                    keyboardType = KeyboardType.Number,
                    onSave = {
                        healthNormalDaysText = it
                        service.saveHealthNormalDays((it.toIntOrNull() ?: 7).coerceAtLeast(1))
                    }
                )
            }
        }
    }
}
