package com.reiyy.yuitag.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Deselect
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.reiyy.yuitag.ble.FileLog
import com.reiyy.yuitag.data.ConfigBackupManager
import com.reiyy.yuitag.data.DeviceRepository
import com.reiyy.yuitag.data.formatUid
import com.reiyy.yuitag.data.local.DeviceEntity
import com.reiyy.yuitag.data.local.DeviceType
import com.reiyy.yuitag.ui.components.DeviceTypeIcon
import com.reiyy.yuitag.ui.components.SettingsCard
import com.reiyy.yuitag.ui.components.SettingsHeader
import com.reiyy.yuitag.ui.components.SettingsRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsBackupScreen(
    repository: DeviceRepository,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val boundDevices by repository.boundDevicesFlow.collectAsState(initial = emptyList())

    var isExporting by remember { mutableStateOf(false) }
    var isImporting by remember { mutableStateOf(false) }
    val isBusy = isExporting || isImporting
    var showExportDialog by remember { mutableStateOf(false) }
    var currentExportOptions by remember { mutableStateOf<ConfigBackupManager.ExportOptions?>(null) }

    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    var pendingImportPreview by remember { mutableStateOf<ConfigBackupManager.ImportPreview?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        if (uri != null) {
            val options = currentExportOptions ?: return@rememberLauncherForActivityResult
            isExporting = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val out = context.contentResolver.openOutputStream(uri)
                            ?: throw ConfigBackupManager.BackupException("无法创建目标文件进行写入")
                        out.use {
                            ConfigBackupManager.exportBackup(context, repository, it, options)
                        }
                    }
                    val detail = buildString {
                        if (options.includeSettings && options.selectedDeviceUids.isNotEmpty()) {
                            append("App 设置及 ${options.selectedDeviceUids.size} 台 Tag 数据")
                        } else if (options.includeSettings) {
                            append("App 全局设置")
                        } else {
                            append("${options.selectedDeviceUids.size} 台 Tag 数据")
                        }
                    }
                    Toast.makeText(context, "备份导出成功（包含 $detail）", Toast.LENGTH_SHORT).show()
                } catch (e: Throwable) {
                    FileLog.e("Backup", "导出失败: ${e.message}", e)
                    Toast.makeText(context, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
                } finally {
                    isExporting = false
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            isImporting = true
            scope.launch {
                try {
                    val preview = withContext(Dispatchers.IO) {
                        ConfigBackupManager.previewBackup(context, uri)
                    }
                    pendingImportUri = uri
                    pendingImportPreview = preview
                } catch (e: Throwable) {
                    FileLog.e("Backup", "导入文件读取失败: ${e.message}", e)
                    Toast.makeText(context, "导入文件无效: ${e.message}", Toast.LENGTH_SHORT).show()
                } finally {
                    isImporting = false
                }
            }
        }
    }

    // --- 导出选择对话框 ---
    if (showExportDialog) {
        ExportSelectionDialog(
            devices = boundDevices,
            onDismiss = { showExportDialog = false },
            onConfirm = { options, suggestedFileName ->
                showExportDialog = false
                currentExportOptions = options
                exportLauncher.launch(suggestedFileName)
            }
        )
    }

    // --- 导入选择对话框 ---
    pendingImportPreview?.let { preview ->
        ImportSelectionDialog(
            preview = preview,
            onDismiss = {
                pendingImportPreview = null
                pendingImportUri = null
            },
            onConfirm = { options ->
                val uri = pendingImportUri ?: return@ImportSelectionDialog
                pendingImportPreview = null
                pendingImportUri = null
                isImporting = true
                scope.launch {
                    try {
                        val result = withContext(Dispatchers.IO) {
                            ConfigBackupManager.importBackup(context, repository, uri, options)
                        }
                        val msg = buildString {
                            if (result.devicesRestored > 0 && result.settingsRestored) {
                                append("导入完成：恢复了 App 设置与 ${result.devicesRestored} 台设备")
                            } else if (result.settingsRestored) {
                                append("导入完成：App 设置已恢复（部分选项重启后生效）")
                            } else if (result.devicesRestored > 0) {
                                append("导入完成：已恢复 ${result.devicesRestored} 台设备")
                            } else {
                                append("未导入任何数据")
                            }
                        }
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    } catch (e: Throwable) {
                        FileLog.e("Backup", "导入失败: ${e.message}", e)
                        Toast.makeText(context, "导入失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    } finally {
                        isImporting = false
                    }
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("备份与恢复") },
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
            SettingsHeader("备份")
            SettingsCard {
                SettingsRow(
                    icon = Icons.Outlined.Backup,
                    title = if (isExporting) "导出中..." else "导出备份",
                    subtitle = "可自定义选择导出 App 设置及指定 Tag 数据，打包为 ZIP 格式导出",
                    onClick = {
                        if (!isBusy) {
                            showExportDialog = true
                        }
                    },
                    trailing = {
                        if (isExporting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        }
                    }
                )
                HorizontalDivider()
                SettingsRow(
                    icon = Icons.Outlined.Restore,
                    title = if (isImporting) "导入中..." else "导入备份",
                    subtitle = "可自定义选择恢复 App 设置及指定 Tag 数据，采用合并方式，不会删除本机未包含的数据",
                    onClick = {
                        if (!isBusy) {
                            importLauncher.launch(
                                arrayOf(
                                    "application/zip",
                                    "application/x-zip-compressed",
                                    "application/octet-stream",
                                    "multipart/x-zip",
                                    "*/*"
                                )
                            )
                        }
                    },
                    trailing = {
                        if (isImporting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        }
                    }
                )
            }
        }
    }
}

/**
 * 导出范围选择弹窗
 */
@Composable
private fun ExportSelectionDialog(
    devices: List<DeviceEntity>,
    onDismiss: () -> Unit,
    onConfirm: (options: ConfigBackupManager.ExportOptions, suggestedFileName: String) -> Unit
) {
    var includeSettings by remember { mutableStateOf(true) }
    var selectedUids by remember { mutableStateOf(devices.map { it.uid }.toSet()) }

    val hasSelection = includeSettings || selectedUids.isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("选择导出内容", style = MaterialTheme.typography.titleLarge)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
            ) {
                // 快捷操作条：全选 / 全不选
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = {
                            includeSettings = true
                            selectedUids = devices.map { it.uid }.toSet()
                        }
                    ) {
                        Icon(Icons.Outlined.SelectAll, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("全选")
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    TextButton(
                        onClick = {
                            includeSettings = false
                            selectedUids = emptySet()
                        }
                    ) {
                        Icon(Icons.Outlined.Deselect, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("清空")
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                ) {
                    // --- App 配置选项 ---
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { includeSettings = !includeSettings },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Outlined.Settings,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "应用全局设置",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "地图 Key、API 端点、防丢报警策略等",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = includeSettings,
                                onCheckedChange = { includeSettings = it }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // --- Tag 设备选项 ---
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Tag 设备数据 (${selectedUids.size}/${devices.size})",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    if (devices.isEmpty()) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "暂无已绑定的 Tag",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    } else {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                devices.forEachIndexed { index, dev ->
                                    val isSelected = selectedUids.contains(dev.uid)
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable {
                                                selectedUids = if (isSelected) {
                                                    selectedUids - dev.uid
                                                } else {
                                                    selectedUids + dev.uid
                                                }
                                            }
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(modifier = Modifier.size(32.dp)) {
                                            DeviceTypeIcon(
                                                deviceType = DeviceType.fromLabel(dev.deviceType),
                                                customIcon = dev.customIcon,
                                                typeIconPath = dev.themePath,
                                                size = 32.dp
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = dev.customName.ifBlank { "YuiTag_${dev.uid.take(4)}" },
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = "ID: ${formatUid(if (dev.serialNumber.isNotEmpty()) dev.serialNumber else dev.uid)}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Checkbox(
                                            checked = isSelected,
                                            onCheckedChange = { checked ->
                                                selectedUids = if (checked) {
                                                    selectedUids + dev.uid
                                                } else {
                                                    selectedUids - dev.uid
                                                }
                                            }
                                        )
                                    }
                                    if (index < devices.lastIndex) {
                                        HorizontalDivider(
                                            modifier = Modifier.padding(horizontal = 12.dp),
                                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val timeStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                    val suggestedName = when {
                        includeSettings && selectedUids.isEmpty() -> "YuiTag_Settings_$timeStr.zip"
                        !includeSettings && selectedUids.isNotEmpty() -> "YuiTag_Tags_$timeStr.zip"
                        else -> "YuiTag_Backup_$timeStr.zip"
                    }
                    onConfirm(
                        ConfigBackupManager.ExportOptions(
                            includeSettings = includeSettings,
                            selectedDeviceUids = selectedUids
                        ),
                        suggestedName
                    )
                },
                enabled = hasSelection
            ) {
                Text("导出")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

/**
 * 导入范围选择弹窗
 */
@Composable
private fun ImportSelectionDialog(
    preview: ConfigBackupManager.ImportPreview,
    onDismiss: () -> Unit,
    onConfirm: (options: ConfigBackupManager.ImportOptions) -> Unit
) {
    var restoreSettings by remember { mutableStateOf(preview.hasSettings) }
    var selectedUids by remember { mutableStateOf(preview.tags.map { it.uid }.toSet()) }

    val hasSelection = (restoreSettings && preview.hasSettings) || selectedUids.isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("选择导入内容", style = MaterialTheme.typography.titleLarge)
                if (preview.exportedAt > 0L) {
                    val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(preview.exportedAt))
                    val verStr = if (preview.appVersion.isNotBlank()) " (v${preview.appVersion})" else ""
                    Text(
                        text = "备份信息: $timeStr$verStr",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
            ) {
                // 快捷操作条：全选 / 全不选
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = {
                            restoreSettings = preview.hasSettings
                            selectedUids = preview.tags.map { it.uid }.toSet()
                        }
                    ) {
                        Icon(Icons.Outlined.SelectAll, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("全选")
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    TextButton(
                        onClick = {
                            restoreSettings = false
                            selectedUids = emptySet()
                        }
                    ) {
                        Icon(Icons.Outlined.Deselect, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("清空")
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                ) {
                    // --- App 配置选项 ---
                    if (preview.hasSettings) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { restoreSettings = !restoreSettings },
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Outlined.Settings,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "应用全局设置",
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = "地图 Key、API 端点、防丢报警策略等",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = restoreSettings,
                                    onCheckedChange = { restoreSettings = it }
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    // --- Tag 设备选项 ---
                    if (preview.tags.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Tag 设备数据 (${selectedUids.size}/${preview.tags.size})",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                preview.tags.forEachIndexed { index, tagItem ->
                                    val isSelected = selectedUids.contains(tagItem.uid)
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable {
                                                selectedUids = if (isSelected) {
                                                    selectedUids - tagItem.uid
                                                } else {
                                                    selectedUids + tagItem.uid
                                                }
                                            }
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Surface(
                                            modifier = Modifier.size(32.dp),
                                            shape = RoundedCornerShape(8.dp),
                                            color = MaterialTheme.colorScheme.primaryContainer
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    Icons.Outlined.Devices,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(18.dp),
                                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = tagItem.name.ifBlank { "YuiTag_${tagItem.uid.take(4)}" },
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = "ID: ${formatUid(tagItem.uid)}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Checkbox(
                                            checked = isSelected,
                                            onCheckedChange = { checked ->
                                                selectedUids = if (checked) {
                                                    selectedUids + tagItem.uid
                                                } else {
                                                    selectedUids - tagItem.uid
                                                }
                                            }
                                        )
                                    }
                                    if (index < preview.tags.lastIndex) {
                                        HorizontalDivider(
                                            modifier = Modifier.padding(horizontal = 12.dp),
                                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                                        )
                                    }
                                }
                            }
                        }
                    } else if (!preview.hasSettings) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "备份文件中未找到任何可恢复的数据",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(
                        ConfigBackupManager.ImportOptions(
                            restoreSettings = restoreSettings && preview.hasSettings,
                            selectedDeviceUids = selectedUids
                        )
                    )
                },
                enabled = hasSelection
            ) {
                Text("导入")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
