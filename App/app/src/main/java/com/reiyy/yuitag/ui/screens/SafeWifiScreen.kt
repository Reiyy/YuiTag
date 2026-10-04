package com.reiyy.yuitag.ui.screens

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.reiyy.yuitag.data.SeparationAlertPreferences
import com.reiyy.yuitag.service.SeparationAlertService
import com.reiyy.yuitag.util.WifiNetworkUtil
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SafeWifiScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val preferences = remember { SeparationAlertPreferences(context) }

    val isSafeWifiEnabled by preferences.safeWifiEnabledFlow().collectAsState(initial = preferences.isSafeWifiEnabled())
    val safeWifiSet by preferences.safeWifiSetFlow().collectAsState(initial = preferences.getSafeWifiSet())

    var currentSsid by remember {
        mutableStateOf(WifiNetworkUtil.getCurrentlyConnectedWifiSsid(context))
    }
    var isWifiActive by remember {
        mutableStateOf(WifiNetworkUtil.isWifiConnected(context))
    }
    var isLocationEnabled by remember {
        mutableStateOf(WifiNetworkUtil.isLocationEnabled(context))
    }

    var showManualAddDialog by remember { mutableStateOf(false) }
    var showSavedWifiDialog by remember { mutableStateOf(false) }
    var pendingDeleteSsid by remember { mutableStateOf<String?>(null) }

    // 页面内动态监听 Wi-Fi 变动
    DisposableEffect(context) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                val wifiInfo = networkCapabilities.transportInfo as? WifiInfo
                val newSsid = WifiNetworkUtil.sanitizeSsid(wifiInfo?.ssid)
                currentSsid = newSsid
                isWifiActive = true
                isLocationEnabled = WifiNetworkUtil.isLocationEnabled(context)
                if (newSsid != null) {
                    SeparationAlertService.refreshWifiState(context, newSsid)
                }
            }

            override fun onLost(network: Network) {
                currentSsid = null
                isWifiActive = false
                SeparationAlertService.refreshWifiState(context)
            }
        }

        try {
            cm?.registerNetworkCallback(request, callback)
        } catch (_: Exception) {}

        onDispose {
            try {
                cm?.unregisterNetworkCallback(callback)
            } catch (_: Exception) {}
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Wi-Fi网络白名单") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 未开启总开关提示
            if (!isSafeWifiEnabled) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Outlined.WarningAmber,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "Wi-Fi网络白名单总开关已关闭，当前配置暂未生效",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 功能介绍说明卡片
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                Icons.Outlined.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp).padding(top = 2.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = "手机连接到列表中的 Wi-Fi 网络时，将暂停物品遗落提醒。离开 Wi-Fi 覆盖范围后自动恢复监测。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // 当前 Wi-Fi 状态卡片
                item {
                    Text(
                        text = "当前网络",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(8.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .background(
                                            if (currentSsid != null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                            CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = if (currentSsid != null) Icons.Outlined.Wifi else Icons.Outlined.WifiOff,
                                        contentDescription = null,
                                        tint = if (currentSsid != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = currentSsid ?: if (isWifiActive) "Wi-Fi 已连接（名称不可用）" else "当前未连接 Wi-Fi",
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val statusSubtitle = when {
                                        currentSsid != null && safeWifiSet.contains(currentSsid) -> "已处于该Wi-Fi范围"
                                        currentSsid != null -> "未添加为Wi-Fi网络白名单"
                                        isWifiActive && !isLocationEnabled -> "需开启系统定位以获取 Wi-Fi 名称"
                                        else -> "连接 Wi-Fi 后可一键添加"
                                    }
                                    Text(
                                        text = statusSubtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (currentSsid != null && safeWifiSet.contains(currentSsid))
                                            MaterialTheme.colorScheme.primary
                                        else
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            // 快捷添加当前 Wi-Fi 按钮
                            if (currentSsid != null) {
                                val alreadyAdded = safeWifiSet.contains(currentSsid)
                                Spacer(Modifier.height(12.dp))
                                if (alreadyAdded) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.Center
                                        ) {
                                            Icon(
                                                Icons.Outlined.Check,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                text = "已添加为白名单网络",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                } else {
                                    Button(
                                        onClick = {
                                            currentSsid?.let { ssid ->
                                                val added = preferences.addSafeWifi(ssid)
                                                if (added) {
                                                    SeparationAlertService.refreshWifiState(context, ssid)
                                                    coroutineScope.launch {
                                                        snackbarHostState.showSnackbar("已添加「$ssid」为白名单网络")
                                                    }
                                                }
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("添加当前 Wi-Fi 为白名单网络")
                                    }
                                }
                            }
                        }
                    }
                }

                // 操作按钮栏：从系统网络添加 / 手动输入添加
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "白名单 Wi-Fi 列表 (${safeWifiSet.size})",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { showSavedWifiDialog = true },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Outlined.FormatListBulleted, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("系统网络", style = MaterialTheme.typography.labelMedium)
                            }
                            OutlinedButton(
                                onClick = { showManualAddDialog = true },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("手动添加", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }

                // 列表内容
                if (safeWifiSet.isEmpty()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.WifiOff,
                                        contentDescription = null,
                                        modifier = Modifier.size(48.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                    )
                                    Spacer(Modifier.height(12.dp))
                                    Text(
                                        text = "暂无配置的白名单 Wi-Fi",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = "连接 Wi-Fi 后一键添加，或点击右上角手动输入 SSID",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                } else {
                    items(safeWifiSet.toList(), key = { it }) { ssid ->
                        val isCurrent = ssid == currentSsid
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isCurrent)
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                                else
                                    MaterialTheme.colorScheme.surfaceContainer
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .background(
                                            if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                                            CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Wifi,
                                        contentDescription = null,
                                        tint = if (isCurrent) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = ssid,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (isCurrent) {
                                        Text(
                                            text = "当前已连接",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = { pendingDeleteSsid = ssid }
                                ) {
                                    Icon(
                                        Icons.Outlined.Delete,
                                        contentDescription = "删除",
                                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 从系统保存网络中选择对话框
    if (showSavedWifiDialog) {
        val savedList = remember { WifiNetworkUtil.getSystemSavedWifiList(context) }
        val nearbyList = remember { WifiNetworkUtil.getNearbyWifiList(context) }
        val displayList = remember(savedList, nearbyList) {
            (savedList + nearbyList).distinct().sorted()
        }

        AlertDialog(
            onDismissRequest = { showSavedWifiDialog = false },
            title = { Text("选择系统网络") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (displayList.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Outlined.WifiOff,
                                contentDescription = null,
                                modifier = Modifier.size(40.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "未获取到已保存或周边的 Wi-Fi 网络",
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "注：Android 10+ 系统对第三方应用读取保存网络实施了隐私保护。建议连接该 Wi-Fi 后一键添加，或使用「手动添加」。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        val tipText = if (savedList.isNotEmpty()) {
                            "点击即可添加至白名单网络："
                        } else {
                            "（系统受限未返回保存列表，显示周边扫描 Wi-Fi）："
                        }
                        Text(
                            text = tipText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 320.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(displayList, key = { it }) { ssid ->
                                val isAdded = safeWifiSet.contains(ssid)
                                val isCurrent = ssid == currentSsid
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isAdded) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surface,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = !isAdded) {
                                            val added = preferences.addSafeWifi(ssid)
                                            if (added) {
                                                SeparationAlertService.refreshWifiState(context, currentSsid)
                                                coroutineScope.launch {
                                                    snackbarHostState.showSnackbar("已添加「$ssid」")
                                                }
                                            }
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Wifi,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp),
                                            tint = if (isAdded) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f) else MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = ssid,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = if (isAdded) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurface
                                            )
                                            if (isCurrent) {
                                                Text(
                                                    text = "当前连接",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                        if (isAdded) {
                                            Text(
                                                text = "已添加",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                            )
                                        } else {
                                            Icon(
                                                Icons.Outlined.Add,
                                                contentDescription = "添加",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
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
                TextButton(onClick = { showSavedWifiDialog = false }) {
                    Text("完成")
                }
            }
        )
    }

    // 手动输入添加对话框
    if (showManualAddDialog) {
        var inputSsid by remember { mutableStateOf("") }
        var isError by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showManualAddDialog = false },
            title = { Text("手动添加 Wi-Fi") },
            text = {
                Column {
                    Text(
                        text = "请输入要设为白名单的 Wi-Fi 名称 (SSID)：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = inputSsid,
                        onValueChange = {
                            inputSsid = it
                            isError = false
                        },
                        placeholder = { Text("例如：Home_5G") },
                        singleLine = true,
                        isError = isError,
                        supportingText = if (isError) {
                            { Text("Wi-Fi 名称不能为空") }
                        } else null,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val cleaned = WifiNetworkUtil.sanitizeSsid(inputSsid)
                        if (cleaned.isNullOrBlank()) {
                            isError = true
                        } else {
                            val added = preferences.addSafeWifi(cleaned)
                            if (added) {
                                SeparationAlertService.refreshWifiState(context, currentSsid)
                            }
                            showManualAddDialog = false
                            coroutineScope.launch {
                                if (added) {
                                    snackbarHostState.showSnackbar("已添加「$cleaned」")
                                } else {
                                    snackbarHostState.showSnackbar("「$cleaned」已存在")
                                }
                            }
                        }
                    }
                ) {
                    Text("添加")
                }
            },
            dismissButton = {
                TextButton(onClick = { showManualAddDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // 删除确认对话框
    pendingDeleteSsid?.let { ssidToDelete ->
        AlertDialog(
            onDismissRequest = { pendingDeleteSsid = null },
            title = { Text("移除白名单 Wi-Fi") },
            text = {
                Text("确定要将「$ssidToDelete」从白名单列表中移除吗？\n移除后，连接此 Wi-Fi 时将恢复正常的物品遗落监测。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        preferences.removeSafeWifi(ssidToDelete)
                        SeparationAlertService.refreshWifiState(context, currentSsid)
                        pendingDeleteSsid = null
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar("已移除「$ssidToDelete」")
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("移除")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteSsid = null }) {
                    Text("取消")
                }
            }
        )
    }
}
