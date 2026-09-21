package com.reiyy.yuitag.ui.screens

import android.util.Base64
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.reiyy.yuitag.data.DeviceRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualImportScreen(
    repository: DeviceRepository,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var uid by remember { mutableStateOf("") }
    var aesKeyHex by remember { mutableStateOf("") }
    var mac by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }
    var isImporting by remember { mutableStateOf(false) }

    fun hexToBytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        return try {
            hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        } catch (_: Exception) { null }
    }

    fun validate(): String? {
        if (uid.length != 4) return "UID 必须为 4 位十六进制字符"
        if (!uid.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return "UID 只能包含 0-9, A-F"

        val aesBytes = hexToBytes(aesKeyHex)
        if (aesBytes == null || aesBytes.size != 16) return "AES Key 必须为 32 位十六进制字符 (16 字节)"

        if (mac.isNotBlank() && !mac.matches(Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$"))) {
            return "MAC 地址格式错误，应为 XX:XX:XX:XX:XX:XX"
        }

        return null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("手动导入设备") },
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
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(8.dp))

            Text(
                text = "导入后设备将出现列表中，连接一次即可自动读取设备信息",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = uid,
                onValueChange = { uid = it.uppercase().take(4) },
                label = { Text("UID") },
                placeholder = { Text("4 位十六进制，如 ABCD") },
                singleLine = true,
                enabled = !isImporting,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = aesKeyHex,
                onValueChange = { aesKeyHex = it },
                label = { Text("AES Key") },
                placeholder = { Text("32 位十六进制") },
                singleLine = true,
                enabled = !isImporting,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = mac,
                onValueChange = { mac = it.uppercase().take(17) },
                label = { Text("MAC 地址 (选填)") },
                placeholder = { Text("XX:XX:XX:XX:XX:XX") },
                singleLine = true,
                enabled = !isImporting,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                modifier = Modifier.fillMaxWidth()
            )

            if (errorText != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = errorText!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(Modifier.height(24.dp))

            OutlinedButton(
                onClick = {
                    val err = validate()
                    if (err != null) {
                        errorText = err
                        return@OutlinedButton
                    }
                    errorText = null
                    isImporting = true

                    scope.launch {
                        try {
                            val aesBytes = hexToBytes(aesKeyHex)!!
                            repository.insertManualDevice(uid, aesBytes, mac)
                            Toast.makeText(context, "设备导入成功", Toast.LENGTH_SHORT).show()
                            onBack()
                        } catch (e: Exception) {
                            errorText = "导入失败: ${e.message}"
                        } finally {
                            isImporting = false
                        }
                    }
                },
                enabled = !isImporting,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    if (isImporting) "导入中..." else "导入",
                    modifier = Modifier.padding(vertical = 4.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
