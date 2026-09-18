package com.yuitag.finder.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 绑定弹窗状态 */
enum class BindPopupState {
    /** 发现未绑定设备 */
    DISCOVERED,
    /** 正在绑定中 */
    BINDING,
    /** 绑定成功 */
    SUCCESS,
    /** 绑定失败 */
    ERROR
}

/**
 * AirTag 风格的设备发现弹窗。
 * 底部滑入式深色卡片 + 3D 立体 Tag 图标 + 涟漪扩散动画。
 */
@Composable
fun BindPopupCard(
    state: BindPopupState,
    deviceName: String = "YuiTag",
    errorMessage: String? = null,
    bindProgress: String? = null,
    onBindClick: () -> Unit = {},
    onNextClick: () -> Unit = {},
    onDismiss: () -> Unit = {},
    onIgnoreClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // 背景遮罩淡入
    val overlayAlpha by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(280),
        label = "overlay_alpha"
    )

    // 忽略确认对话框状态
    var showIgnoreConfirm by remember { mutableStateOf(false) }

    if (showIgnoreConfirm) {
        AlertDialog(
            onDismissRequest = { showIgnoreConfirm = false },
            title = { Text("不是你的Tag？") },
            text = { Text("是否永久忽略该Tag，此后将不再弹出该Tag的绑定弹窗") },
            confirmButton = {
                TextButton(onClick = {
                    showIgnoreConfirm = false
                    onIgnoreClick()
                }) { Text("忽略", color = Color(0xFFEF4444)) }
            },
            dismissButton = {
                TextButton(onClick = { showIgnoreConfirm = false }) { Text("取消") }
            }
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f * overlayAlpha))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter
    ) {
        // 浮动卡片从底部滑入
        AnimatedVisibility(
            visible = true,
            enter = slideInVertically(
                initialOffsetY = { it },
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            ) + fadeIn(tween(220)),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
        ) {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 16.dp)
                    .fillMaxWidth()
                    .clickable(enabled = false) {},  // 阻止点击穿透
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFF7F8FA)
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 14.dp)
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    // 右下角短 UID 灰色小字
                    val nameParts = remember(deviceName) {
                        val prefix = deviceName.substringBeforeLast("_", deviceName)
                        val suffix = deviceName.substringAfterLast("_", "")
                        if (suffix.isNotEmpty() && suffix != deviceName) prefix to suffix
                        else prefix to ""
                    }
                    if (nameParts.second.isNotEmpty()) {
                        Text(
                            text = nameParts.second,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = 24.dp, bottom = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF9A9A9E),
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 30.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                    Spacer(Modifier.height(26.dp))

                    // 标题
                    AnimatedContent(
                        targetState = state,
                        transitionSpec = {
                            (fadeIn(tween(300)) + slideInVertically(
                                initialOffsetY = { it / 5 }
                            )) togetherWith (fadeOut(tween(180)) + slideOutVertically(
                                targetOffsetY = { -it / 5 }
                            ))
                        },
                        label = "title_transition"
                    ) { s ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            when (s) {
                                BindPopupState.BINDING -> {
                                    // 绑定中：产品名作为主标题
                                    if (nameParts.first.isNotEmpty()) {
                                        Text(
                                            text = nameParts.first,
                                            style = MaterialTheme.typography.headlineSmall,
                                            color = Color(0xFF1A1C1E),
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                else -> {
                                    Text(
                                        text = when (s) {
                                            BindPopupState.DISCOVERED -> "发现新设备"
                                            BindPopupState.SUCCESS -> "绑定成功"
                                            BindPopupState.ERROR -> "绑定失败"
                                            else -> ""
                                        },
                                        style = MaterialTheme.typography.headlineSmall,
                                        color = when (s) {
                                            BindPopupState.ERROR -> Color(0xFFEF4444)
                                            else -> Color(0xFF1A1C1E)
                                        },
                                        fontWeight = FontWeight.Bold
                                    )
                                    // 产品名称
                                    if (nameParts.first.isNotEmpty()) {
                                        Spacer(Modifier.height(6.dp))
                                        Text(
                                            text = nameParts.first,
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = Color(0xFF3A3A3C),
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(18.dp))

                    // 3D Tag 图标与涟漪动画
                    TagDiscoveryIcon(state = state)

                    Spacer(Modifier.height(16.dp))

                    // 状态描述
                    AnimatedContent(
                        targetState = state,
                        transitionSpec = {
                            fadeIn(tween(300)) togetherWith fadeOut(tween(180))
                        },
                        label = "subtitle_transition"
                    ) { s ->
                        when (s) {
                            BindPopupState.DISCOVERED -> Spacer(Modifier.height(0.dp))

                            BindPopupState.BINDING -> {
                                // 动态进度文本（切换时淡入淡出）
                                AnimatedContent(
                                    targetState = bindProgress ?: "正在连接设备...",
                                    transitionSpec = {
                                        (fadeIn(tween(220)) + slideInVertically(
                                            initialOffsetY = { it / 6 }
                                        )) togetherWith (fadeOut(tween(160)) + slideOutVertically(
                                            targetOffsetY = { -it / 6 }
                                        ))
                                    },
                                    label = "progress_transition"
                                ) { progress ->
                                    Text(
                                        text = progress,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color(0xFF6B7280),
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }

                            BindPopupState.SUCCESS -> Spacer(Modifier.height(0.dp))

                            BindPopupState.ERROR -> Text(
                                text = errorMessage ?: "请靠近设备后重试",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFF6B7280),
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    Spacer(Modifier.height(22.dp))

                    // 主按钮
                    AnimatedContent(
                        targetState = state,
                        transitionSpec = {
                            fadeIn(tween(200)) togetherWith fadeOut(tween(160))
                        },
                        label = "button_transition"
                    ) { s ->
                        when (s) {
                            BindPopupState.DISCOVERED -> PrimaryButton(
                                text = "绑定设备",
                                containerColor = Color(0xFF007AFF),
                                onClick = onBindClick
                            )

                            BindPopupState.BINDING -> PrimaryButton(
                                text = "",
                                containerColor = Color(0xFF007AFF).copy(alpha = 0.55f),
                                onClick = {},
                                enabled = false,
                                showProgress = true
                            )

                            BindPopupState.SUCCESS -> PrimaryButton(
                                text = "配置设备",
                                containerColor = Color(0xFF007AFF),
                                onClick = onNextClick
                            )

                            BindPopupState.ERROR -> PrimaryButton(
                                text = "重试",
                                containerColor = Color(0xFF007AFF),
                                onClick = onBindClick
                            )
                        }
                    }

                    Spacer(Modifier.height(22.dp))

                    // 忽略入口（仅明确点击忽略后不再弹出）
                    Text(
                        text = "不是你的Tag？",
                        modifier = Modifier
                            .clickable { showIgnoreConfirm = true }
                            .padding(vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFF9A9A9E)
                    )

                    Spacer(Modifier.height(16.dp))
                    }
                }
            }
        }
    }
}

// --- 主按钮 ---

@Composable
private fun PrimaryButton(
    text: String,
    containerColor: Color,
    onClick: () -> Unit,
    enabled: Boolean = true,
    showProgress: Boolean = false
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            disabledContainerColor = containerColor
        ),
        enabled = enabled
    ) {
        if (showProgress) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                color = Color.White,
                strokeWidth = 2.5.dp
            )
            if (text.isNotEmpty()) Spacer(Modifier.width(10.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            fontWeight = FontWeight.SemiBold
        )
    }
}

// --- 3D Tag 图标与动画 ---

@Composable
private fun TagDiscoveryIcon(state: BindPopupState) {
    val tagSize = 148.dp
    val density = LocalDensity.current

    // 涟漪扩散动画（发现设备时持续扩散）
    val infiniteTransition = rememberInfiniteTransition(label = "discover_anim")
    val rippleProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ripple"
    )

    // 绑定中：轻微脉动缩放
    val pulseScaleRaw by infiniteTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    val pulseScale = if (state == BindPopupState.BINDING) pulseScaleRaw else 1f

    // 图标掉落弹跳（首次出现）
    val dropAnim = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        dropAnim.snapTo(1f)
        dropAnim.animateTo(
            targetValue = 0f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMediumLow
            )
        )
    }
    val dropPx = with(density) { 140.dp.toPx() }

    // 成功状态缩放反弹
    val successScale = remember { Animatable(1f) }
    LaunchedEffect(state) {
        if (state == BindPopupState.SUCCESS) {
            successScale.snapTo(0.82f)
            successScale.animateTo(
                1.15f,
                spring(dampingRatio = Spring.DampingRatioMediumBouncy)
            )
            successScale.animateTo(1f, tween(220))
        }
    }

    // 错误状态左右抖动
    val shakeAnim = remember { Animatable(0f) }
    LaunchedEffect(state) {
        if (state == BindPopupState.ERROR) {
            shakeAnim.snapTo(0f)
            repeat(3) {
                shakeAnim.animateTo(1f, tween(45))
                shakeAnim.animateTo(-1f, tween(45))
            }
            shakeAnim.animateTo(0f, tween(45))
        }
    }
    val shakePx = with(density) { 9.dp.toPx() }

    Box(
        modifier = Modifier.size(210.dp),
        contentAlignment = Alignment.Center
    ) {
        // 成功状态：大圆框绿勾（取代 3D 图标）
        if (state == BindPopupState.SUCCESS) {
            Box(
                modifier = Modifier.graphicsLayer {
                    scaleX = successScale.value
                    scaleY = successScale.value
                },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(130.dp)
                        .border(
                            width = 4.dp,
                            color = Color(0xFF10B981),
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = Color(0xFF10B981),
                        modifier = Modifier.size(68.dp)
                    )
                }
            }
            return
        }

        // 涟漪扩散（仅发现状态）
        if (state == BindPopupState.DISCOVERED) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2, size.height / 2)
                val tagHalfPx = tagSize.toPx() / 2f
                val maxR = size.minDimension / 2f
                for (i in 0..2) {
                    val phase = (rippleProgress + i * 0.33f) % 1f
                    val r = tagHalfPx + (maxR - tagHalfPx) * phase
                    val a = (0.42f * (1f - phase) * (1f - phase)).coerceIn(0f, 1f)
                    if (r > 0f && a > 0.01f) {
                        drawCircle(
                            color = Color(0xFF007AFF).copy(alpha = a),
                            radius = r,
                            center = center,
                            style = Stroke(2.5.dp.toPx())
                        )
                    }
                }
            }
        }

        // 3D 立体 Tag 图标
        Box(
            modifier = Modifier.graphicsLayer {
                translationY = dropAnim.value * dropPx
                translationX = shakeAnim.value * shakePx
                scaleX = pulseScale * successScale.value
                scaleY = pulseScale * successScale.value
            }
        ) {
            Box(
                modifier = Modifier
                    .size(tagSize)
                    .shadow(
                        elevation = 32.dp,
                        shape = CircleShape,
                        ambientColor = Color.Black.copy(alpha = 0.18f),
                        spotColor = Color.Black.copy(alpha = 0.32f)
                    )
                    .clip(CircleShape)
                    .background(
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
                    .border(
                        width = 1.5.dp,
                        color = Color(0xFFC8C8C8),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                when (state) {
                    BindPopupState.DISCOVERED, BindPopupState.BINDING -> Icon(
                        imageVector = Icons.Default.Bluetooth,
                        contentDescription = null,
                        tint = Color(0xFF007AFF),
                        modifier = Modifier.size(44.dp)
                    )

                    BindPopupState.ERROR -> Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = null,
                        tint = Color(0xFFEF4444),
                        modifier = Modifier.size(44.dp)
                    )

                    BindPopupState.SUCCESS -> {}  // 已由大圆框绿勾取代
                }
            }
        }
    }
}
