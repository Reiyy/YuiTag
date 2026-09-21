package com.reiyy.yuitag

import android.content.Context
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.compose.rememberNavController
import com.reiyy.yuitag.data.DeviceRepository
import com.reiyy.yuitag.ui.navigation.YuiTagNavGraph
import com.reiyy.yuitag.util.hasBlePermissions
import com.reiyy.yuitag.util.isBluetoothEnabled

@Composable
fun YuiTagApp(
    repository: DeviceRepository,
    onRequestBluetoothEnable: () -> Unit,
    onRequestPermissions: ((Boolean) -> Unit) -> Unit
) {
    val navController = rememberNavController()
    val context = LocalContext.current

    // 蓝牙开启状态（BroadcastReceiver 更新后触发重组）
    var bluetoothEnabled by remember { mutableStateOf(isBluetoothEnabled(context)) }
    // 运行时权限状态
    var permissionsGranted by remember { mutableStateOf(hasBlePermissions(context)) }
    var permissionDenied by remember { mutableStateOf(false) }
    // 权限请求进行中标记，避免与蓝牙状态效应产生竞态
    var permissionRequestInFlight by remember { mutableStateOf(false) }

    // 首次进入时检查并请求权限
    LaunchedEffect(Unit) {
        if (!hasBlePermissions(context)) {
            permissionRequestInFlight = true
            onRequestPermissions { granted ->
                permissionRequestInFlight = false
                permissionsGranted = granted
                permissionDenied = !granted
            }
        } else {
            permissionsGranted = true
        }
    }

    // BroadcastReceiver 监听蓝牙系统状态变更
    DisposableEffect(Unit) {
        // 初始检查一次
        bluetoothEnabled = isBluetoothEnabled(context)
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
                if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                    val state = intent.getIntExtra(
                        BluetoothAdapter.EXTRA_STATE,
                        BluetoothAdapter.ERROR
                    )
                    if (state == BluetoothAdapter.STATE_ON) {
                        bluetoothEnabled = true
                    } else if (state == BluetoothAdapter.STATE_OFF) {
                        bluetoothEnabled = false
                    }
                }
            }
        }
        val filter = android.content.IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(
                receiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
        onDispose {
            try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
        }
    }

    // 每次 recompose 时检查权限状态（覆盖从系统设置返回后的情况）
    LaunchedEffect(bluetoothEnabled) {
        if (bluetoothEnabled) {
            permissionsGranted = hasBlePermissions(context)
            // 蓝牙开启但权限不满足时显示权限提示；权限请求进行中时不置为拒绝态
            if (!permissionsGranted && !permissionRequestInFlight) permissionDenied = true
        }
    }

    // 监听生命周期 ON_RESUME，从系统设置返回时重新检测权限
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionsGranted = hasBlePermissions(context)
                if (!permissionsGranted && !permissionRequestInFlight) permissionDenied = true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    when {
        !bluetoothEnabled -> {
            BluetoothDisabledScreen(onEnable = onRequestBluetoothEnable)
        }
        !permissionsGranted && !permissionDenied -> {
            PermissionLoadingScreen()
        }
        permissionDenied -> {
            PermissionDeniedScreen(
                onRetry = {
                    permissionDenied = false
                    permissionRequestInFlight = true
                    onRequestPermissions { granted ->
                        permissionRequestInFlight = false
                        permissionsGranted = granted
                        permissionDenied = !granted
                    }
                }
            )
        }
        else -> {
            val prefs = context.getSharedPreferences("yuitag_settings", Context.MODE_PRIVATE)
            val liteMode = prefs.getBoolean("lite_mode_enabled", false)
            YuiTagNavGraph(navController = navController, repository = repository, liteMode = liteMode)
        }
    }
}

@Composable
private fun PermissionLoadingScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "正在检查权限...",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun BluetoothDisabledScreen(onEnable: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.BluetoothDisabled,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = "蓝牙未开启",
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "需要开启蓝牙才能扫描\n附近的 YuiTag 设备",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onEnable) {
            Text("开启蓝牙")
        }
    }
}

@Composable
private fun PermissionDeniedScreen(onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = "需要蓝牙权限",
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "需要蓝牙扫描和连接权限\n才能发现和连接 YuiTag 设备",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRetry) {
            Text("授予权限")
        }
    }
}
