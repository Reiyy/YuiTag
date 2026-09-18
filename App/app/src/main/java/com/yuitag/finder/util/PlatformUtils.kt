package com.yuitag.finder.util

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.os.Build
import androidx.core.content.ContextCompat

fun isBluetoothEnabled(context: Context): Boolean {
    val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter
    return adapter?.isEnabled == true
}

fun hasBlePermissions(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
               ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }
    return true
}

fun hasLocationPermission(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
           ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
}

fun toggleFlashlight(context: Context, on: Boolean): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val camManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return false
        return try {
            val cameraId = camManager.cameraIdList.firstOrNull() ?: return false
            camManager.setTorchMode(cameraId, on)
            true
        } catch (_: Exception) {
            false
        }
    }
    return false
}

/** 找到设备时的短震动反馈（不需要振动权限运行时申请，普通权限已在 Manifest 声明） */
fun vibrateShort(context: Context) {
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibrator = (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager)?.defaultVibrator
                ?: return
            vibrator.vibrate(android.os.VibrationEffect.createOneShot(
                120, android.os.VibrationEffect.DEFAULT_AMPLITUDE
            ))
        } else {
            @Suppress("DEPRECATION")
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator ?: return
            @Suppress("DEPRECATION")
            vibrator.vibrate(120)
        }
    } catch (_: Exception) {}
}
