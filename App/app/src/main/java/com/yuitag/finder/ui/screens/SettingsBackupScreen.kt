package com.yuitag.finder.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yuitag.finder.ble.FileLog
import com.yuitag.finder.data.ConfigBackupManager
import com.yuitag.finder.data.DeviceRepository
import com.yuitag.finder.ui.components.SettingsCard
import com.yuitag.finder.ui.components.SettingsHeader
import com.yuitag.finder.ui.components.SettingsRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsBackupScreen(
    repository: DeviceRepository,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var backupBusy by remember { mutableStateOf(false) }
    var pendingImportJson by remember { mutableStateOf<String?>(null) }
    var pendingImportPreview by remember { mutableStateOf<ConfigBackupManager.ImportPreview?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) {
            backupBusy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            ConfigBackupManager.exportConfig(context, repository, out)
                        }
                    }
                    Toast.makeText(context, "配置导出成功", Toast.LENGTH_SHORT).show()
                } catch (e: Throwable) {
                    FileLog.e("Backup", "导出失败: ${e.message}", e)
                    Toast.makeText(context, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
                } finally {
                    backupBusy = false
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            backupBusy = true
            scope.launch {
                try {
                    val json = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            input.bufferedReader(Charsets.UTF_8).use { it.readText() }
                        }
                    } ?: throw Exception("无法读取文件")
                    val preview = withContext(Dispatchers.IO) {
                        ConfigBackupManager.previewConfig(json)
                    }
                    pendingImportPreview = preview
                    pendingImportJson = json
                } catch (e: Throwable) {
                    FileLog.e("Backup", "导入文件读取失败: ${e.message}", e)
                    Toast.makeText(context, "导入文件无效: ${e.message}", Toast.LENGTH_SHORT).show()
                } finally {
                    backupBusy = false
                }
            }
        }
    }

    pendingImportPreview?.let { preview ->
        AlertDialog(
            onDismissRequest = {
                pendingImportPreview = null
                pendingImportJson = null
            },
            title = { Text("导入配置") },
            text = {
                Text(
                    "将恢复备份中的 App 设置${if (preview.hasSettings) "" else "（备份未包含设置）"}，" +
                        "以及 ${preview.deviceCount} 台设备。\n\n" +
                        "同名 UID 的设备将被备份内容覆盖，本机其他设备保留不变，不影响位置历史。确定导入吗？"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val json = pendingImportJson ?: return@TextButton
                    pendingImportPreview = null
                    pendingImportJson = null
                    backupBusy = true
                    scope.launch {
                        try {
                            val result = withContext(Dispatchers.IO) {
                                ConfigBackupManager.importConfig(context, repository, json)
                            }
                            val msg = buildString {
                                append("导入完成，恢复 ${result.devicesRestored} 台设备")
                                if (result.settingsRestored) append("，设置已恢复")
                                append("；精简模式等设置重启后生效")
                            }
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        } catch (e: Throwable) {
                            FileLog.e("Backup", "导入失败: ${e.message}", e)
                            Toast.makeText(context, "导入失败: ${e.message}", Toast.LENGTH_SHORT).show()
                        } finally {
                            backupBusy = false
                        }
                    }
                }) { Text("导入") }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingImportPreview = null
                    pendingImportJson = null
                }) { Text("取消") }
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
                    title = if (backupBusy) "处理中..." else "导出配置",
                    subtitle = "导出全部配置（设置 + 已绑定 Tag + Tag 主题），包含 AES 密钥等敏感数据，请妥善保管",
                    onClick = {
                        if (!backupBusy) {
                            exportLauncher.launch("YuiTag_backup_${System.currentTimeMillis()}.json")
                        }
                    },
                    trailing = {
                        if (backupBusy) {
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
                    title = if (backupBusy) "处理中..." else "导入配置",
                    subtitle = "导入采用合并方式，不会删除本机已有数据",
                    onClick = {
                        if (!backupBusy) {
                            importLauncher.launch(arrayOf("application/json", "*/*"))
                        }
                    }
                )
            }
        }
    }
}
