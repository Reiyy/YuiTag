package com.reiyy.yuitag

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.reiyy.yuitag.ble.FileLog
import com.reiyy.yuitag.data.DeviceRepository
import com.reiyy.yuitag.data.local.YuiTagDatabase
import com.reiyy.yuitag.service.SeparationAlertService
import com.reiyy.yuitag.ui.navigation.Routes
import com.reiyy.yuitag.ui.theme.ThemeConfig
import com.reiyy.yuitag.ui.theme.ThemeMode
import com.reiyy.yuitag.ui.theme.YuiTagTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (getSystemService(BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter
    }

    // 权限请求结果回调
    private var onPermissionsResult: ((Boolean) -> Unit)? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        // BLE 权限是 App 基础运行的必要条件
        val bleGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            result[Manifest.permission.BLUETOOTH_SCAN] != false &&
            result[Manifest.permission.BLUETOOTH_CONNECT] != false
        } else true
        onPermissionsResult?.invoke(bleGranted)
        if (result[Manifest.permission.POST_NOTIFICATIONS] == true) {
            SeparationAlertService.syncServiceState(this)
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            SeparationAlertService.syncServiceState(this)
        }
    }

    private lateinit var repository: DeviceRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // 初始化文件日志：I/W/E 级始终记录，V/D 级（含敏感信息）由设置页"调试模式"开关控制
        FileLog.init(this)
        // 全局未捕获异常写入文件日志，便于定位崩溃
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                FileLog.e("CRASH", "未捕获异常 ${thread.name}: ${throwable.message}", throwable)
            } catch (_: Exception) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }

        // 初始化设备仓库（单例）
        repository = DeviceRepository.getInstance(this)

        // 异步执行旧版本密钥迁移
        lifecycleScope.launch(Dispatchers.IO) {
            repository.migrateLegacyKeys(this@MainActivity)
        }

        // Android 13+ 自动检查并请求通知权限（遗落防丢提醒必需）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // 同步遗落提醒后台守护服务状态
        SeparationAlertService.syncServiceState(this)

        // 初始化实况通知渠道
        com.reiyy.yuitag.notification.live.LiveNotificationManager.init(this)

        // 初始化主题配置
        ThemeConfig.init(this)

        // 解析冷启动通知跳转
        handleIntent(intent)

        setContent {
            val systemInDark = isSystemInDarkTheme()
            val useDark = when (ThemeConfig.currentThemeMode) {
                ThemeMode.SYSTEM -> systemInDark
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            YuiTagTheme(
                darkTheme = useDark,
                dynamicColor = ThemeConfig.isDynamicColor
            ) {
                YuiTagApp(
                    repository = repository,
                    onRequestBluetoothEnable = { requestBluetoothEnable() },
                    onRequestPermissions = { onResult ->
                        requestPermissions(onResult)
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (bluetoothAdapter?.isEnabled != true) {
            requestBluetoothEnable()
        }
        SeparationAlertService.refreshWifiState(this)
    }

    private fun requestBluetoothEnable() {
        if (bluetoothAdapter?.isEnabled != true) {
            val enableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivity(enableIntent)
        }
    }

    // 请求运行时权限
    private fun requestPermissions(onResult: (Boolean) -> Unit) {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
        // GPS 定位权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        // Android 13+ 通知权限（遗落警报必需）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (permissions.isNotEmpty()) {
            onPermissionsResult = onResult
            permissionLauncher.launch(permissions.toTypedArray())
        } else {
            // 已全部授予
            onResult(true)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val route = intent.getStringExtra(EXTRA_NAV_ROUTE)
            ?: intent.getStringExtra(EXTRA_DEVICE_UID)?.let { Routes.findRoute(it) }
        if (!route.isNullOrBlank()) {
            FileLog.i("MainActivity", "收到通知路由跳转目标: $route")
            pendingNavigationRoute.value = route
        }
    }

    companion object {
        const val EXTRA_NAV_ROUTE = "com.reiyy.yuitag.extra.NAV_ROUTE"
        const val EXTRA_DEVICE_UID = "com.reiyy.yuitag.extra.DEVICE_UID"

        val pendingNavigationRoute = MutableStateFlow<String?>(null)
    }
}
