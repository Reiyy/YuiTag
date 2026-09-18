package com.yuitag.finder

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
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.yuitag.finder.ble.FileLog
import com.yuitag.finder.data.DeviceRepository
import com.yuitag.finder.data.local.YuiTagDatabase
import com.yuitag.finder.ui.theme.YuiTagTheme
import kotlinx.coroutines.Dispatchers
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
        val allGranted = result.values.all { it }
        onPermissionsResult?.invoke(allGranted)
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

        // 初始化 Room 数据库
        val database = YuiTagDatabase.getInstance(this)
        repository = DeviceRepository(database.deviceDao(), database.locationDao())

        // 异步执行旧版本密钥迁移
        lifecycleScope.launch(Dispatchers.IO) {
            repository.migrateLegacyKeys(this@MainActivity)
        }

        setContent {
            YuiTagTheme {
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
        if (permissions.isNotEmpty()) {
            onPermissionsResult = onResult
            permissionLauncher.launch(permissions.toTypedArray())
        } else {
            // 已全部授予
            onResult(true)
        }
    }
}
