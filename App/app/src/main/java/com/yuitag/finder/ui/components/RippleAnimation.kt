package com.yuitag.finder.ui.components

import android.graphics.BitmapFactory
import android.graphics.Shader
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import com.yuitag.finder.model.RippleState
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * AirTag 同款涟漪扩散动画。
 *
 * @param state      当前查找状态
 * @param tagSize    中心白色 Tag 圆直径
 * @param maxRadius  参考最大扩散半径（会被屏幕边界自动裁剪）
 * @param rippleColor 涟漪颜色
 * @param rssi       信号强度 dBm，FOUND 状态下决定呼吸圈远近
 */
@Composable
fun RippleEffect(
    state: RippleState,
    tagSize: Dp = 100.dp,
    maxRadius: Dp = 180.dp,
    rippleColor: Color = Color.White,
    rssi: Int = -100,
    themePath: String = "",
    themeVersion: Int = 0,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val config = LocalConfiguration.current
    val infiniteTransition = rememberInfiniteTransition(label = "ripple")

    // 屏幕边界裁剪：防止圆环超出屏幕
    val screenMaxR = remember(config.screenWidthDp) {
        val byScreen = config.screenWidthDp.dp * 0.44f
        byScreen.coerceAtMost(210.dp)
    }
    val clampedMaxRadius = remember(maxRadius, screenMaxR) {
        maxRadius.coerceAtMost(screenMaxR)
    }

    // --- SEARCHING：恒定速度扩散，每圈 1.8 秒 ---
    val searchProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "searchProgress"
    )

    // --- FOUND：单圈呼吸环绕 ---
    val breatheProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breatheProgress"
    )

    // 距离感应呼吸基准半径：越近圈越小（平滑过渡）
    val foundBreatheRadiusTarget = remember(rssi, tagSize, clampedMaxRadius) {
        // rssi ∈ [-100(远), -50(近)] → t ∈ [0, 1]
        val t = ((rssi + 100).coerceIn(0, 50)) / 50f
        val minR = tagSize / 2 + 6.dp        // 最近：紧贴 Tag
        val maxR = clampedMaxRadius          // 最远：屏幕边界（复用 SEARCHING 同一上限）
        minR + (maxR - minR) * (1f - t)
    }
    val foundBreatheRadius by animateDpAsState(
        targetValue = foundBreatheRadiusTarget,
        animationSpec = tween(300),
        label = "foundBreatheRadius"
    )

    // --- HERE：圈收缩消失与 Tag 放大 ---
    val hereShrink = remember { Animatable(1f) }
    LaunchedEffect(state) {
        if (state == RippleState.HERE) {
            hereShrink.snapTo(1f)
            hereShrink.animateTo(
                targetValue = 0f,
                animationSpec = tween(500, easing = FastOutSlowInEasing)
            )
        }
    }

    // 预计算模糊渲染效果（API 31+）
    val blurEffect = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            android.graphics.RenderEffect.createBlurEffect(7f, 7f, Shader.TileMode.CLAMP)
                .asComposeRenderEffect()
        } else null
    }

    // SEARCHING 多圈参数
    val searchRingCount = 3
    val searchPhases = List(searchRingCount) { it.toFloat() / searchRingCount }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        val tagHalfPx = with(density) { tagSize.toPx() / 2f }
        val maxRPx = with(density) { clampedMaxRadius.toPx() }

        // --- SEARCHING：多圈匀速扩散 ---
        if (state == RippleState.SEARCHING) {
            // 辉光模糊层
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { renderEffect = blurEffect }
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val center = Offset(size.width / 2, size.height / 2)
                    for (phase in searchPhases) {
                        val pp = ((searchProgress + phase) % 1f)
                        val r = tagHalfPx + (maxRPx - tagHalfPx) * pp
                        val a = (0.50f * (1f - pp) * (1f - pp)).coerceIn(0f, 1f)
                        if (r > 0f && a > 0.01f) {
                            drawCircle(
                                color = rippleColor.copy(alpha = a * 0.55f),
                                radius = r,
                                center = center,
                                style = Stroke(7.dp.toPx())
                            )
                        }
                    }
                }
            }
            // 锐利描边层
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2, size.height / 2)
                for (phase in searchPhases) {
                    val pp = ((searchProgress + phase) % 1f)
                    val r = tagHalfPx + (maxRPx - tagHalfPx) * pp
                    val a = (0.55f * (1f - pp) * (1f - pp)).coerceIn(0f, 1f)
                    if (r > 0f && a > 0.01f) {
                        drawCircle(
                            color = rippleColor.copy(alpha = a),
                            radius = r,
                            center = center,
                            style = Stroke(2.dp.toPx())
                        )
                        drawCircle(
                            color = rippleColor.copy(alpha = a * 0.2f),
                            radius = r + 3.dp.toPx(),
                            center = center,
                            style = Stroke(5.dp.toPx())
                        )
                    }
                }
            }
        }

        // --- FOUND：单圈呼吸环绕 ---
        if (state == RippleState.FOUND) {
            val baseRPx = with(density) { foundBreatheRadius.toPx() }
            val amplitudePx = with(density) { 2.dp.toPx() }  // 固定 ±2dp 微呼吸，与半径无关
            val currentRPx = baseRPx + amplitudePx * (breatheProgress * 2f - 1f)

            // 辉光模糊层
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { renderEffect = blurEffect }
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val center = Offset(size.width / 2, size.height / 2)
                    drawCircle(
                        color = rippleColor.copy(alpha = 0.45f),
                        radius = currentRPx,
                        center = center,
                        style = Stroke(8.dp.toPx())
                    )
                }
            }
            // 锐利描边层
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2, size.height / 2)
                drawCircle(
                    color = rippleColor.copy(alpha = 0.65f),
                    radius = currentRPx,
                    center = center,
                    style = Stroke(2.5.dp.toPx())
                )
                drawCircle(
                    color = rippleColor.copy(alpha = 0.18f),
                    radius = currentRPx + 4.dp.toPx(),
                    center = center,
                    style = Stroke(6.dp.toPx())
                )
            }
        }

        // --- HERE：圈收缩消失 ---
        if (state == RippleState.HERE) {
            val shrinkStartRPx = with(density) { (tagSize / 2 + 24.dp).toPx() }
            val currentRPx = shrinkStartRPx * hereShrink.value

            if (currentRPx > 2f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { renderEffect = blurEffect }
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val center = Offset(size.width / 2, size.height / 2)
                        drawCircle(
                            color = rippleColor.copy(alpha = hereShrink.value * 0.40f),
                            radius = currentRPx,
                            center = center,
                            style = Stroke(7.dp.toPx())
                        )
                    }
                }
            }
            if (currentRPx > 1f) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val center = Offset(size.width / 2, size.height / 2)
                    drawCircle(
                        color = rippleColor.copy(alpha = hereShrink.value * 0.55f),
                        radius = currentRPx,
                        center = center,
                        style = Stroke(2.5.dp.toPx())
                    )
                }
            }
        }

        // --- 中心 Tag 图标 ---
        val tagScale = when (state) {
            RippleState.HERE -> 1.0f + 0.50f * (1f - hereShrink.value)
            else -> 1.0f
        }

        val customBitmap = remember(themePath, themeVersion) {
            if (themePath.isNotEmpty()) {
                try {
                    val iconFile = java.io.File(themePath, "icon.png")
                    if (iconFile.exists()) {
                        BitmapFactory.decodeFile(iconFile.absolutePath)?.asImageBitmap()
                    } else null
                } catch (e: Exception) {
                    null
                }
            } else null
        }

        Box(
            modifier = Modifier.graphicsLayer {
                scaleX = tagScale
                scaleY = tagScale
            }
        ) {
            Box(
                modifier = Modifier
                    .size(tagSize)
                    .shadow(
                        elevation = 16.dp,
                        shape = CircleShape,
                        ambientColor = Color.Black.copy(alpha = 0.12f),
                        spotColor = Color.Black.copy(alpha = 0.22f)
                    )
                    .clip(CircleShape)
                    .then(
                        if (customBitmap != null) {
                            Modifier.background(Color.White)
                        } else {
                            Modifier.background(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        Color.White,
                                        Color(0xFFFAFAFA),
                                        Color(0xFFF0F0F0),
                                        Color(0xFFE0E0E0),
                                    ),
                                    center = Offset(0.35f, 0.30f),
                                    radius = 1.4f
                                ),
                                shape = CircleShape
                            )
                        }
                    )
                    .border(
                        width = 1.5.dp,
                        color = Color(0xFFC8C8C8),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (customBitmap != null) {
                    Image(
                        bitmap = customBitmap,
                        contentDescription = "Tag图标",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = if (state == RippleState.SEARCHING)
                            Icons.Default.Bluetooth
                        else
                            Icons.Default.BluetoothConnected,
                        contentDescription = "蓝牙",
                        tint = Color(0xFF007AFF),
                        modifier = Modifier.size(48.dp)
                    )
                }
            }
        }
    }
}
