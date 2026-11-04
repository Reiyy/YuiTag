package com.reiyy.yuitag.ui.screens

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import android.provider.Settings
import androidx.core.net.toUri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Palette
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
import com.reiyy.yuitag.ui.theme.ThemeConfig
import com.reiyy.yuitag.ui.theme.ThemeMode
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
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.Router
import androidx.compose.runtime.collectAsState
import com.reiyy.yuitag.data.DeviceRepository
import com.reiyy.yuitag.data.SeparationAlertLevel
import com.reiyy.yuitag.data.SeparationAlertPreferences
import com.reiyy.yuitag.service.SeparationAlertService
import com.reiyy.yuitag.ui.components.SettingsCard
import com.reiyy.yuitag.ui.components.SettingsHeader
import com.reiyy.yuitag.ui.components.SettingsRadioPreference
import com.reiyy.yuitag.ui.components.SettingsRow
import com.reiyy.yuitag.ui.components.SettingsSwitchRow
import com.reiyy.yuitag.util.hasNotificationPermission
import kotlinx.coroutines.flow.map

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigateToManualImport: () -> Unit,
    repository: DeviceRepository? = null,
    onNavigateToMonitoredDevices: () -> Unit = {},
    onNavigateToSafeWifi: () -> Unit = {},
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
    val repo = remember {
        repository ?: DeviceRepository.getInstance(context)
    }
    val alertPrefs = remember { SeparationAlertPreferences(context) }
    val liveNotificationPrefs = remember { com.reiyy.yuitag.data.LiveNotificationPreferences(context) }

    var liteMode by remember { mutableStateOf(service.isLiteModeEnabled()) }
    var debugMode by remember { mutableStateOf(service.isDebugModeEnabled()) }
    var liveNotificationEnabled by remember { mutableStateOf(liveNotificationPrefs.isLiveNotificationEnabled()) }
    var isLiveSupported by remember { mutableStateOf(com.reiyy.yuitag.data.LiveNotificationPreferences.isSystemSupported(context)) }
    var endpointUrl by remember { mutableStateOf(service.getEndpointUrl()) }
    var alertMasterEnabled by remember { mutableStateOf(alertPrefs.isMasterEnabled()) }
    var alertTriggerLevel by remember { mutableStateOf(alertPrefs.getTriggerLevel()) }
    var alertDelaySeconds by remember { mutableStateOf(alertPrefs.getDelaySeconds()) }
    var alertFinalVerification by remember { mutableStateOf(alertPrefs.isFinalVerificationEnabled()) }
    var alertSafeWifiEnabled by remember { mutableStateOf(alertPrefs.isSafeWifiEnabled()) }
    var safeWifiCount by remember { mutableStateOf(alertPrefs.getSafeWifiSet().size) }

    val monitoredCount by remember(repo) {
        repo.getMonitoredDevicesFlow().map { it.size }
    }.collectAsState(initial = 0)

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            SeparationAlertService.syncServiceState(context)
        } else {
            Toast.makeText(context, "未授予通知权限，遗落报警可能无法在锁屏或后台弹出提示", Toast.LENGTH_LONG).show()
        }
    }

    LifecycleResumeEffect(Unit) {
        liteMode = service.isLiteModeEnabled()
        debugMode = service.isDebugModeEnabled()
        liveNotificationEnabled = liveNotificationPrefs.isLiveNotificationEnabled()
        isLiveSupported = com.reiyy.yuitag.data.LiveNotificationPreferences.isSystemSupported(context)
        endpointUrl = service.getEndpointUrl()
        alertMasterEnabled = alertPrefs.isMasterEnabled()
        alertTriggerLevel = alertPrefs.getTriggerLevel()
        alertDelaySeconds = alertPrefs.getDelaySeconds()
        alertFinalVerification = alertPrefs.isFinalVerificationEnabled()
        alertSafeWifiEnabled = alertPrefs.isSafeWifiEnabled()
        safeWifiCount = alertPrefs.getSafeWifiSet().size
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
                    icon = Icons.Outlined.Radar,
                    title = "实况通知 (实验性)",
                    subtitle = if (liveNotificationEnabled) {
                        "已开启 (${com.reiyy.yuitag.data.LiveNotificationPreferences.getDriverName(context)})"
                    } else "已关闭",
                    checked = liveNotificationEnabled,
                    onCheckedChange = { enabled ->
                        liveNotificationEnabled = enabled
                        liveNotificationPrefs.setLiveNotificationEnabled(enabled)
                    }
                )
                if (liveNotificationEnabled && isLiveSupported) {
                    val isFlymePermissionGranted = remember(liveNotificationEnabled) {
                        com.reiyy.yuitag.data.LiveNotificationPreferences.isFlymeSystemLiveNotificationEnabled(context)
                    }
                    if (!isFlymePermissionGranted && com.reiyy.yuitag.data.LiveNotificationPreferences.isFlymeDevice()) {
                        Text(
                            text = "⚠️ 魅族系统实况通知权限似乎未开启，点击此处前往系统设置允许",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    try {
                                        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                        }
                                        context.startActivity(intent)
                                    } catch (_: Exception) {
                                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                            data = "package:${context.packageName}".toUri()
                                        }
                                        context.startActivity(intent)
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                        )
                    }
                }
                HorizontalDivider()
                SettingsSwitchRow(
                    icon = Icons.Outlined.BugReport,
                    title = "调试模式",
                    checked = debugMode,
                    onCheckedChange = { enabled ->
                        debugMode = enabled
                        service.setDebugModeEnabled(enabled)
                    }
                )
            }

            SettingsHeader("外观")
            SettingsCard {
                SettingsRadioPreference(
                    icon = Icons.Outlined.DarkMode,
                    title = "深色模式",
                    subtitle = when (ThemeConfig.currentThemeMode) {
                        ThemeMode.SYSTEM -> "跟随系统"
                        ThemeMode.LIGHT -> "浅色模式"
                        ThemeMode.DARK -> "深色模式"
                    },
                    options = listOf(
                        ThemeMode.SYSTEM to "跟随系统",
                        ThemeMode.LIGHT to "浅色模式",
                        ThemeMode.DARK to "深色模式"
                    ),
                    selected = ThemeConfig.currentThemeMode,
                    onSelect = { mode ->
                        ThemeConfig.setThemeMode(context, mode)
                    }
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    HorizontalDivider()
                    SettingsSwitchRow(
                        icon = Icons.Outlined.Palette,
                        title = "Material You 动态色彩",
                        checked = ThemeConfig.isDynamicColor,
                        onCheckedChange = { enabled ->
                            ThemeConfig.setDynamicColor(context, enabled)
                        }
                    )
                }
            }

            SettingsHeader("遗落提醒")
            SettingsCard {
                SettingsSwitchRow(
                    icon = Icons.Outlined.NotificationsActive,
                    title = "遗落提醒 (实验性)",
                    subtitle = if (alertMasterEnabled) "设备离开身边时发出通知" else "已关闭",
                    checked = alertMasterEnabled,
                    onCheckedChange = { enabled ->
                        alertMasterEnabled = enabled
                        alertPrefs.setMasterEnabled(enabled)
                        SeparationAlertService.syncServiceState(context)
                        if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission(context)) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                )
                HorizontalDivider()
                SettingsRadioPreference(
                    icon = Icons.Outlined.Sensors,
                    title = "触发距离",
                    subtitle = "超出设定距离时提醒",
                    options = listOf(
                        SeparationAlertLevel.NEARBY.key to "在附近",
                        SeparationAlertLevel.CLOSE.key to "距离近",
                        SeparationAlertLevel.FAR.key to "距离远",
                        SeparationAlertLevel.WEAK.key to "信号弱",
                        SeparationAlertLevel.NO_SIGNAL.key to "无信号"
                    ),
                    selected = alertTriggerLevel.key,
                    onSelect = { key ->
                        val newLevel = SeparationAlertLevel.fromKey(key)
                        alertTriggerLevel = newLevel
                        alertPrefs.setTriggerLevel(newLevel)
                    }
                )
                HorizontalDivider()
                SettingsRadioPreference(
                    icon = Icons.Outlined.Timer,
                    title = "确认延时",
                    subtitle = "持续满足条件后触发",
                    options = listOf(
                        30 to "30 秒",
                        60 to "1 分钟",
                        120 to "2 分钟"
                    ),
                    selected = alertDelaySeconds,
                    onSelect = { seconds ->
                        alertDelaySeconds = seconds
                        alertPrefs.setDelaySeconds(seconds)
                    }
                )
                HorizontalDivider()
                SettingsSwitchRow(
                    icon = Icons.Outlined.Radar,
                    title = "最后复核",
                    subtitle = if (alertFinalVerification) "报警前全速扫描 6 秒以降低误报" else "已关闭",
                    checked = alertFinalVerification,
                    onCheckedChange = { enabled ->
                        alertFinalVerification = enabled
                        alertPrefs.setFinalVerificationEnabled(enabled)
                    }
                )
                HorizontalDivider()
                SettingsRow(
                    icon = Icons.Outlined.Devices,
                    title = "监控设备",
                    subtitle = "${monitoredCount} 个设备已开启",
                    onClick = onNavigateToMonitoredDevices
                )
                HorizontalDivider()
                SettingsSwitchRow(
                    icon = Icons.Outlined.Wifi,
                    title = "Wi-Fi网络白名单",
                    subtitle = if (alertSafeWifiEnabled) "连接信任网络时不发出遗落提醒" else "已关闭",
                    checked = alertSafeWifiEnabled,
                    onCheckedChange = { enabled ->
                        alertSafeWifiEnabled = enabled
                        alertPrefs.setSafeWifiEnabled(enabled)
                        SeparationAlertService.refreshWifiState(context)
                    }
                )
                if (alertSafeWifiEnabled) {
                    HorizontalDivider()
                    SettingsRow(
                        icon = Icons.Outlined.Router,
                        title = "白名单 Wi-Fi 列表",
                        subtitle = if (safeWifiCount == 0) "未配置网络" else "已添加 ${safeWifiCount} 个网络",
                        onClick = onNavigateToSafeWifi
                    )
                }
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
                    subtitle = "版本、开源许可与更新",
                    onClick = onNavigateToAbout
                )
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}
