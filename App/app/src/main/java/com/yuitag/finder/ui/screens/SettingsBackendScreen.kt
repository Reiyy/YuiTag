package com.yuitag.finder.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AltRoute
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.yuitag.finder.data.FindMyQueryService
import com.yuitag.finder.ui.components.SettingsCard
import com.yuitag.finder.ui.components.SettingsHeader
import com.yuitag.finder.ui.components.SettingsRow
import com.yuitag.finder.ui.components.SettingsTextPreference
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsBackendScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val service = remember { FindMyQueryService(context) }

    var endpointUrl by remember { mutableStateOf(service.getEndpointUrl()) }
    var endpointUrlFallback by remember { mutableStateOf(service.getEndpointUrlFallback()) }
    var endpointUser by remember { mutableStateOf(service.getEndpointUser()) }
    var endpointPass by remember { mutableStateOf(service.getEndpointPass()) }

    var isTesting by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("后端服务") },
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
            SettingsHeader("连接")
            SettingsCard {
                SettingsTextPreference(
                    icon = Icons.Outlined.Link,
                    title = "后端地址",
                    subtitle = "查询后端服务器 API 地址",
                    value = endpointUrl,
                    placeholder = "http://192.168.1.100:6176",
                    onSave = { endpointUrl = it; service.saveEndpointUrl(it) }
                )
                HorizontalDivider()
                SettingsTextPreference(
                    icon = Icons.Outlined.AltRoute,
                    title = "备用后端地址",
                    subtitle = "主地址不可用时自动切换",
                    value = endpointUrlFallback,
                    placeholder = "选填",
                    onSave = { endpointUrlFallback = it; service.saveEndpointUrlFallback(it) }
                )
                HorizontalDivider()
                SettingsTextPreference(
                    icon = Icons.Outlined.Person,
                    title = "用户名",
                    subtitle = "选填 (Basic Auth)",
                    value = endpointUser,
                    placeholder = "选填",
                    onSave = { endpointUser = it; service.saveEndpointUser(it) }
                )
                HorizontalDivider()
                SettingsTextPreference(
                    icon = Icons.Outlined.Lock,
                    title = "密码",
                    subtitle = "选填 (Basic Auth)",
                    value = endpointPass,
                    placeholder = "选填",
                    isPassword = true,
                    onSave = { endpointPass = it; service.saveEndpointPass(it) }
                )
            }

            SettingsHeader("诊断")
            SettingsCard {
                SettingsRow(
                    icon = Icons.Outlined.NetworkCheck,
                    title = if (isTesting) "正在测试连接..." else "测试后端连接",
                    subtitle = testResult,
                    onClick = {
                        if (!isTesting) {
                            isTesting = true
                            testResult = null
                            scope.launch {
                                try {
                                    testResult = service.testConnection(endpointUrl, endpointUrlFallback)
                                } catch (_: Exception) {
                                    testResult = "测试失败，请检查网络"
                                } finally {
                                    isTesting = false
                                }
                            }
                        }
                    },
                    trailing = {
                        if (isTesting) {
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
