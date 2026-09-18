package com.yuitag.finder.data.local

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backpack
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Luggage
import androidx.compose.material.icons.filled.Wallet
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 设备类型预设 — APP本地分类，用户绑定后手动选择
 */
enum class DeviceType(val label: String, val icon: ImageVector) {
    BACKPACK("背包", Icons.Default.Backpack),
    KEY("钥匙", Icons.Default.Key),
    WALLET("钱包", Icons.Default.Wallet),
    LUGGAGE("行李箱", Icons.Default.Luggage),
    CUSTOM("自定义", Icons.Default.Category);

    companion object {
        fun fromLabel(label: String): DeviceType =
            entries.firstOrNull { it.label == label } ?: CUSTOM
    }
}
