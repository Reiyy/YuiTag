package com.yuitag.finder.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yuitag.finder.ui.theme.Blue500
import com.yuitag.finder.ui.theme.ErrorRed
import com.yuitag.finder.ui.theme.SuccessGreen
import com.yuitag.finder.viewmodel.DeviceConnectionState
import com.yuitag.finder.viewmodel.DeviceHealthState

/**
 * 设备状态标签（健康度 + 瞬时连接态复合）：
 *  - 已连接  ：蓝色"已连接"，覆盖健康度
 *  - 连接中  ：健康度文字不变，旁边多一个蓝色小加载圈
 *  - 其余    ：健康度文字（正常/异常），判断中隐藏
 */
@Composable
fun HealthStateTag(
    health: DeviceHealthState,
    connection: DeviceConnectionState = DeviceConnectionState.OFFLINE,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val clickModifier = if (onClick != null) Modifier.clickable { onClick() } else Modifier
    when (connection) {
        DeviceConnectionState.CONNECTED -> {
            Surface(
                modifier = modifier.then(clickModifier),
                shape = MaterialTheme.shapes.extraSmall,
                color = Blue500.copy(alpha = 0.15f)
            ) {
                Text(
                    text = "已连接",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = Blue500
                )
            }
        }
        DeviceConnectionState.CONNECTING -> {
            Row(
                modifier = modifier.then(clickModifier),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = Blue500
                )
                if (health != DeviceHealthState.JUDGING) {
                    Spacer(Modifier.width(4.dp))
                    HealthBadge(health)
                }
            }
        }
        else -> {
            if (health == DeviceHealthState.JUDGING) return
            HealthBadge(health, modifier = modifier.then(clickModifier))
        }
    }
}

@Composable
private fun HealthBadge(health: DeviceHealthState, modifier: Modifier = Modifier) {
    val text: String
    val color: androidx.compose.ui.graphics.Color
    when (health) {
        DeviceHealthState.NORMAL -> {
            text = "正常"
            color = SuccessGreen
        }
        DeviceHealthState.ABNORMAL -> {
            text = "异常"
            color = ErrorRed
        }
        DeviceHealthState.JUDGING -> return
    }
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraSmall,
        color = color.copy(alpha = 0.15f)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}
