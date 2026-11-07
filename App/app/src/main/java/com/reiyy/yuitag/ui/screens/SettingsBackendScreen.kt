package com.reiyy.yuitag.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.AltRoute
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.LaptopMac
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.reiyy.yuitag.apple.account.AppleAccountManager
import com.reiyy.yuitag.apple.account.AppleLoginException
import com.reiyy.yuitag.apple.account.AppleLoginService
import com.reiyy.yuitag.apple.account.LoginResult
import com.reiyy.yuitag.apple.account.TwoFactorChallenge
import com.reiyy.yuitag.apple.anisette.CoreAdiDownloader
import com.reiyy.yuitag.data.FindMyQueryService
import com.reiyy.yuitag.ui.components.SettingsCard
import com.reiyy.yuitag.ui.components.SettingsHeader
import com.reiyy.yuitag.ui.components.SettingsRow
import com.reiyy.yuitag.ui.components.SettingsTextPreference
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsBackendScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val service = remember { FindMyQueryService(context) }
    val appleManager = remember { AppleAccountManager.getInstance(context) }
    val scope = rememberCoroutineScope()

    var queryMode by remember { mutableStateOf(service.getQueryMode()) }

    // Apple Direct mode state
    var coreAdiReady by remember { mutableStateOf(CoreAdiDownloader.isReady(context)) }
    val runtimeVersion by produceState("0.0.0") {
        value = runCatching { appleManager.anisetteProvider.version() }.getOrDefault("0.0.0")
    }
    var appleAccount by remember { mutableStateOf(appleManager.getAccount()) }
    var appleIdInput by remember { mutableStateOf(appleAccount.username ?: "") }
    var applePasswordInput by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var isLoggingIn by remember { mutableStateOf(false) }
    var loginError by remember { mutableStateOf<String?>(null) }

    // 2FA Dialog state
    var showTwoFactorDialog by remember { mutableStateOf(false) }
    var twoFactorMethods by remember { mutableStateOf<List<TwoFactorChallenge>>(emptyList()) }
    var selectedMethodIndex by remember { mutableStateOf(0) }
    var twoFactorCode by remember { mutableStateOf("") }
    var isRequestingCode by remember { mutableStateOf(false) }
    var isSubmittingCode by remember { mutableStateOf(false) }
    var twoFactorError by remember { mutableStateOf<String?>(null) }
    var pendingLoginService by remember { mutableStateOf<AppleLoginService?>(null) }
    var lastSentTimeMap by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var countdownTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(showTwoFactorDialog, lastSentTimeMap, selectedMethodIndex) {
        if (!showTwoFactorDialog) return@LaunchedEffect
        while (true) {
            val currentMethod = twoFactorMethods.getOrNull(selectedMethodIndex) ?: break
            val lastSent = lastSentTimeMap[currentMethod.uniqueKey] ?: 0L
            if (lastSent == 0L) {
                countdownTick = 0
                break
            }
            val elapsed = (System.currentTimeMillis() - lastSent) / 1000
            val remaining = (60 - elapsed).coerceIn(0, 60)
            countdownTick = remaining.toInt()
            if (remaining <= 0) break
            delay(1000)
        }
    }

    // Custom backend mode state
    var endpointUrl by remember { mutableStateOf(service.getEndpointUrl()) }
    var endpointUrlFallback by remember { mutableStateOf(service.getEndpointUrlFallback()) }
    var endpointUser by remember { mutableStateOf(service.getEndpointUser()) }
    var endpointPass by remember { mutableStateOf(service.getEndpointPass()) }

    var isTesting by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val stream = context.contentResolver.openInputStream(uri)
                    if (stream != null) {
                        val success = CoreAdiDownloader.importFromStream(context, stream)
                        if (success) {
                            coreAdiReady = true
                            Toast.makeText(context, "CoreADI.x86_64 导入成功", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "导入失败：文件 SHA-256 校验不匹配", Toast.LENGTH_LONG).show()
                        }
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "读取文件失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("位置服务") },
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
            SettingsHeader("查询源")
            SettingsCard {
                // Radio 1: 本机直连
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = queryMode == FindMyQueryService.QUERY_MODE_APPLE_DIRECT,
                            onClick = {
                                queryMode = FindMyQueryService.QUERY_MODE_APPLE_DIRECT
                                service.saveQueryMode(FindMyQueryService.QUERY_MODE_APPLE_DIRECT)
                            },
                            role = Role.RadioButton
                        )
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = queryMode == FindMyQueryService.QUERY_MODE_APPLE_DIRECT,
                        onClick = null
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "本机直连",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "App 直接向 Apple iCloud 查询位置信息",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                HorizontalDivider()

                // Radio 2: 外部 macless-haystack
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = queryMode == FindMyQueryService.QUERY_MODE_CUSTOM_BACKEND,
                            onClick = {
                                queryMode = FindMyQueryService.QUERY_MODE_CUSTOM_BACKEND
                                service.saveQueryMode(FindMyQueryService.QUERY_MODE_CUSTOM_BACKEND)
                            },
                            role = Role.RadioButton
                        )
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = queryMode == FindMyQueryService.QUERY_MODE_CUSTOM_BACKEND,
                        onClick = null
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "外部 macless-haystack",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "通过外部服务查询位置信息",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (queryMode == FindMyQueryService.QUERY_MODE_APPLE_DIRECT) {
                // 苹果直连配置项
                SettingsHeader("CoreADI 核心状态")
                SettingsCard {
                    if (coreAdiReady) {
                        SettingsRow(
                            icon = Icons.Outlined.CheckCircle,
                            title = "CoreADI 4.19 (macOS x86_64)",
                            subtitle = "已就绪 · 运行库：v$runtimeVersion",
                            iconTint = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        SettingsRow(
                            icon = Icons.Outlined.Warning,
                            title = "CoreADI 核心未找到",
                            subtitle = "需要导入 authentic macOS CoreADI.x86_64 二进制文件",
                            iconTint = MaterialTheme.colorScheme.error
                        )
                        HorizontalDivider()
                        SettingsRow(
                            icon = Icons.Outlined.FileUpload,
                            title = "导入 CoreADI.x86_64",
                            subtitle = "从手机存储导入 (SHA-256 自动验证)",
                            onClick = { filePickerLauncher.launch("*/*") }
                        )
                    }

                    HorizontalDivider()

                    SettingsRow(
                        icon = Icons.Outlined.LaptopMac,
                        title = "虚拟 Mac 设备标识",
                        subtitle = "MacBookPro13,2 (macOS 13.1 22C65)"
                    )
                }

                SettingsHeader("Apple ID 账户")
                SettingsCard {
                    if (appleManager.isLoggedIn()) {
                        val name = appleAccount.accountInfo?.let {
                            "${it.firstName.orEmpty()} ${it.lastName.orEmpty()}".trim()
                        }?.ifEmpty { null } ?: appleAccount.username ?: "Apple 用户"

                        SettingsRow(
                            icon = Icons.Outlined.AccountCircle,
                            title = name,
                            subtitle = "${appleAccount.username ?: "-"}"
                        )
                        HorizontalDivider()
                        SettingsRow(
                            icon = Icons.Outlined.Key,
                            title = "凭据",
                            subtitle = "DSID: ${appleAccount.getDsid() ?: "-"} · searchPartyToken 存在"
                        )
                        HorizontalDivider()
                        SettingsRow(
                            icon = Icons.Outlined.NetworkCheck,
                            title = if (isTesting) "正在测试连接..." else "测试服务可用性",
                            subtitle = testResult,
                            onClick = {
                                if (!isTesting) {
                                    isTesting = true
                                    testResult = null
                                    scope.launch {
                                        try {
                                            testResult = service.testAppleDirectConnection()
                                        } catch (e: Exception) {
                                            testResult = "测试异常: ${e.message}"
                                        } finally {
                                            isTesting = false
                                        }
                                    }
                                }
                            },
                            trailing = {
                                if (isTesting) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                }
                            }
                        )
                        if (testResult?.contains("401") == true || testResult?.contains("过期") == true) {
                            HorizontalDivider()
                            SettingsRow(
                                icon = Icons.Outlined.Lock,
                                title = "凭据失效",
                                subtitle = "凭据已过期或无效，点击重新登录",
                                iconTint = MaterialTheme.colorScheme.primary,
                                onClick = {
                                    appleManager.clearAccount()
                                    appleAccount = appleManager.getAccount()
                                    testResult = null
                                }
                            )
                        }
                        HorizontalDivider()
                        SettingsRow(
                            icon = Icons.AutoMirrored.Outlined.Logout,
                            title = "退出 Apple ID",
                            subtitle = "退出 Apple ID 并清除凭据",
                            iconTint = MaterialTheme.colorScheme.error,
                            onClick = {
                                appleManager.clearAccount()
                                appleAccount = appleManager.getAccount()
                                testResult = null
                                Toast.makeText(context, "已退出 Apple ID", Toast.LENGTH_SHORT).show()
                            }
                        )
                    } else {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "登录 Apple ID",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            OutlinedTextField(
                                value = appleIdInput,
                                onValueChange = { appleIdInput = it },
                                label = { Text("Apple ID (邮箱)") },
                                placeholder = { Text("Ciallo@icloud.com") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            OutlinedTextField(
                                value = applePasswordInput,
                                onValueChange = { applePasswordInput = it },
                                label = { Text("密码") },
                                singleLine = true,
                                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                trailingIcon = {
                                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                        Icon(
                                            imageVector = if (passwordVisible) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                                            contentDescription = if (passwordVisible) "隐藏密码" else "显示密码"
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )

                            if (loginError != null) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = loginError ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            Button(
                                onClick = {
                                    val id = appleIdInput.trim()
                                    val pwd = applePasswordInput
                                    if (id.isEmpty() || pwd.isEmpty()) {
                                        loginError = "请输入 Apple ID 和密码"
                                        return@Button
                                    }
                                    if (!coreAdiReady) {
                                        loginError = "CoreADI 核心未就绪，请先导入 CoreADI.x86_64"
                                        return@Button
                                    }

                                    isLoggingIn = true
                                    loginError = null
                                    scope.launch {
                                        try {
                                            val loginService = appleManager.createLoginService()
                                            pendingLoginService = loginService
                                            val res = loginService.login(id, pwd)
                                            when (res) {
                                                is LoginResult.LoggedIn -> {
                                                    appleManager.saveAccount(loginService.account)
                                                    appleAccount = appleManager.getAccount()
                                                    applePasswordInput = ""
                                                    service.saveQueryMode(FindMyQueryService.QUERY_MODE_APPLE_DIRECT)
                                                    queryMode = FindMyQueryService.QUERY_MODE_APPLE_DIRECT
                                                    Toast.makeText(context, "Apple ID 登录成功！", Toast.LENGTH_SHORT).show()
                                                }
                                                is LoginResult.RequireTwoFactor -> {
                                                    if (res.methods.isEmpty()) {
                                                        loginError = "Apple ID 要求双重认证，但未能获取到可用的验证设备或验证手机号，请检查网络后重试"
                                                    } else {
                                                        twoFactorMethods = res.methods
                                                        selectedMethodIndex = 0
                                                        twoFactorCode = ""
                                                        twoFactorError = null
                                                        val initialMap = mutableMapOf<String, Long>()
                                                        if (res.methods.any { it is TwoFactorChallenge.TrustedDevice }) {
                                                            initialMap["trusted_device"] = System.currentTimeMillis()
                                                        }
                                                        lastSentTimeMap = initialMap
                                                        showTwoFactorDialog = true
                                                    }
                                                }
                                            }
                                        } catch (e: AppleLoginException) {
                                            loginError = when (e.kind) {
                                                AppleLoginException.Kind.INVALID_CREDENTIALS -> "账号或密码错误"
                                                AppleLoginException.Kind.UNHANDLED_PROTOCOL -> "协议异常: ${e.message}"
                                                else -> e.message ?: "登录失败"
                                            }
                                        } catch (e: Exception) {
                                            loginError = "网络或连接异常: ${e.message}"
                                        } finally {
                                            isLoggingIn = false
                                        }
                                    }
                                },
                                enabled = !isLoggingIn,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (isLoggingIn) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("正在验证 Apple ID...")
                                } else {
                                    Text("登录")
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = "隐私提示：Apple ID 登录凭据仅用于查询FindMy位置服务，密码不会被保存或上传",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            } else {
                // 外部自建后端配置项
                SettingsHeader("自建服务器连接")
                SettingsCard {
                    SettingsTextPreference(
                        icon = Icons.Outlined.Link,
                        title = "后端地址",
                        subtitle = "查询后端服务器 API 地址",
                        value = endpointUrl,
                        placeholder = "http://192.168.1.233:6176",
                        onSave = { endpointUrl = it; service.saveEndpointUrl(it) }
                    )
                    HorizontalDivider()
                    SettingsTextPreference(
                        icon = Icons.AutoMirrored.Outlined.AltRoute,
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

    // 双重认证验证码弹窗
    if (showTwoFactorDialog && twoFactorMethods.isNotEmpty()) {
        val currentMethod = twoFactorMethods.getOrNull(selectedMethodIndex) ?: twoFactorMethods.first()
        val currentKey = currentMethod.uniqueKey
        val lastSentTime = lastSentTimeMap[currentKey] ?: 0L
        val hasSent = lastSentTime > 0L
        @Suppress("UNUSED_VARIABLE")
        val tick = countdownTick // 触发每秒重组
        val remainingSeconds = if (hasSent) {
            (60 - (System.currentTimeMillis() - lastSentTime) / 1000).coerceIn(0, 60).toInt()
        } else 0
        val isInCooldown = remainingSeconds > 0

        AlertDialog(
            onDismissRequest = {
                if (!isSubmittingCode && !isRequestingCode) {
                    showTwoFactorDialog = false
                    twoFactorError = null
                }
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Apple ID 双重认证")
                }
            },
            text = {
                Column {
                    Text(
                        text = "Apple 要求进行二次安全验证，请选择验证方式并输入收到的 6 位验证码：",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    if (twoFactorMethods.size > 1) {
                        twoFactorMethods.forEachIndexed { index, method ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = selectedMethodIndex == index,
                                        enabled = !isRequestingCode && !isSubmittingCode,
                                        onClick = { selectedMethodIndex = index },
                                        role = Role.RadioButton
                                    )
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = selectedMethodIndex == index,
                                    enabled = !isRequestingCode && !isSubmittingCode,
                                    onClick = null
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(method.displayName, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    } else {
                        Text(
                            text = "验证方式：${currentMethod.displayName}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .size(18.dp)
                                    .padding(top = 1.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            val hintText = when (currentMethod) {
                                is TwoFactorChallenge.TrustedDevice -> {
                                    if (isInCooldown) {
                                        "验证码已自动发送至您的 Apple 信任设备（如 iPhone/iPad/Mac），请在设备弹窗中轻点“允许”查看 6 位验证码并填入下方。"
                                    } else {
                                        "若您的 Apple 信任设备未收到弹窗提示，可点击下方“重新发送验证码”。"
                                    }
                                }
                                is TwoFactorChallenge.Sms -> {
                                    if (!hasSent) {
                                        "请点击下方“发送验证码”，Apple 将向绑定的受信任手机号发送短信验证码。"
                                    } else if (isInCooldown) {
                                        "短信验证码已发送至 ${currentMethod.phoneNumber}，请注意查收。"
                                    } else {
                                        "若未收到短信，可点击下方“重新发送验证码”。"
                                    }
                                }
                            }
                            Text(
                                text = hintText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            isRequestingCode = true
                            twoFactorError = null
                            scope.launch {
                                try {
                                    currentMethod.request()
                                    lastSentTimeMap = lastSentTimeMap + (currentKey to System.currentTimeMillis())
                                    val tip = if (currentMethod is TwoFactorChallenge.TrustedDevice) {
                                        "验证码已重新发送至您的 Apple 信任设备"
                                    } else {
                                        "短信验证码已发送至所选手机号"
                                    }
                                    Toast.makeText(context, tip, Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    twoFactorError = "发送验证码失败: ${e.message}"
                                } finally {
                                    isRequestingCode = false
                                }
                            }
                        },
                        enabled = !isRequestingCode && !isSubmittingCode && !isInCooldown,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isRequestingCode) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("正在发送...")
                        } else if (isInCooldown) {
                            Text("重新发送 (${remainingSeconds}s)")
                        } else if (hasSent) {
                            Text("重新发送验证码")
                        } else {
                            Text("发送验证码")
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = twoFactorCode,
                        onValueChange = {
                            if (it.length <= 6 && it.all { c -> c.isDigit() }) {
                                twoFactorCode = it
                            }
                        },
                        label = { Text("6 位验证码") },
                        placeholder = { Text("例如 123456") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (twoFactorError != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = twoFactorError ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val pendingService = pendingLoginService ?: return@Button
                        isSubmittingCode = true
                        twoFactorError = null
                        scope.launch {
                            try {
                                val res = currentMethod.submit(twoFactorCode.trim())
                                if (res is LoginResult.LoggedIn) {
                                    appleManager.saveAccount(pendingService.account)
                                    appleAccount = appleManager.getAccount()
                                    applePasswordInput = ""
                                    service.saveQueryMode(FindMyQueryService.QUERY_MODE_APPLE_DIRECT)
                                    queryMode = FindMyQueryService.QUERY_MODE_APPLE_DIRECT
                                    showTwoFactorDialog = false
                                    Toast.makeText(context, "Apple ID 认证成功！", Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: Exception) {
                                twoFactorError = "验证码错误或过期: ${e.message}"
                            } finally {
                                isSubmittingCode = false
                            }
                        }
                    },
                    enabled = twoFactorCode.length == 6 && !isSubmittingCode
                ) {
                    if (isSubmittingCode) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("正在验证...")
                    } else {
                        Text("提交验证")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showTwoFactorDialog = false },
                    enabled = !isSubmittingCode && !isRequestingCode
                ) {
                    Text("取消")
                }
            }
        )
    }
}

private val TwoFactorChallenge.uniqueKey: String
    get() = when (this) {
        is TwoFactorChallenge.TrustedDevice -> "trusted_device"
        is TwoFactorChallenge.Sms -> "sms_${phoneNumberId}_${phoneNumber}"
    }
