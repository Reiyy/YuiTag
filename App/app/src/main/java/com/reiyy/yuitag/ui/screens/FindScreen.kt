package com.reiyy.yuitag.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Shader
import android.os.Build
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.reiyy.yuitag.model.RippleState
import com.reiyy.yuitag.ui.components.RippleEffect
import com.reiyy.yuitag.ui.theme.AirTagBlue
import com.reiyy.yuitag.ui.theme.AirTagGray
import com.reiyy.yuitag.util.toggleFlashlight
import com.reiyy.yuitag.viewmodel.FindViewModel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

@Composable
fun FindScreen(
    uid: String,
    onExit: () -> Unit,
    viewModel: FindViewModel = viewModel()
) {
    var hasExited by remember { mutableStateOf(false) }
    val safeExit: () -> Unit = {
        if (!hasExited) {
            hasExited = true
            onExit()
        }
    }

    val shouldExit by viewModel.shouldExit.collectAsState()
    LaunchedEffect(shouldExit) {
        if (shouldExit) {
            safeExit()
        }
    }

    LaunchedEffect(uid) { viewModel.initialize(uid) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (!viewModel.shouldExit.value && !hasExited) {
                    viewModel.initialize(uid)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(Unit) {
        onDispose { viewModel.cleanup() }
    }

    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val view = LocalView.current
    var flashOn by remember { mutableStateOf(false) }

    // 保持屏幕常亮（查找场景用户可能长时间不操作屏幕）
    val activity = remember { view.context as Activity }
    DisposableEffect(Unit) {
        val window = activity.window
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // 手电筒需要 CAMERA 运行时权限，按需请求（仅开启时需要）
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            flashOn = true
            if (!toggleFlashlight(context, true)) {
                flashOn = false
                Toast.makeText(context, "无法开启闪光灯", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, "需要相机权限才能使用手电筒", Toast.LENGTH_SHORT).show()
        }
    }

    DisposableEffect(Unit) {
        onDispose { if (flashOn) toggleFlashlight(context, false) }
    }

    // 背景色动画：搜索中=灰 / 找到=蓝
    val bgColor by animateColorAsState(
        targetValue = when (uiState.rippleState) {
            RippleState.SEARCHING -> AirTagGray
            else -> AirTagBlue
        },
        animationSpec = tween(220),
        label = "bgColor"
    )

    var backgroundBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(uiState.themePath, uiState.themeVersion) {
        val path = uiState.themePath
        if (path.isEmpty()) {
            backgroundBitmap = null
            return@LaunchedEffect
        }
        withContext(Dispatchers.IO) {
            try {
                val bmp = BitmapFactory.decodeFile(File(path, "background.png").absolutePath)
                backgroundBitmap = bmp?.asImageBitmap()
            } catch (e: Exception) {
                backgroundBitmap = null
            }
        }
    }

    // 设置状态栏和导航栏图标为浅色（暗色背景需要浅色图标）
    SideEffect {
        if (!view.isInEditMode) {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    val revealRadius = remember { Animatable(0f) }
    var actualSize by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }
    val maxRevealRadius = remember(actualSize) {
        if (actualSize.width > 0) {
            sqrt(actualSize.width * actualSize.width + actualSize.height * actualSize.height) / 2f * 1.1f
        } else 3000f
    }

    LaunchedEffect(uiState.rippleState) {
        if (uiState.rippleState != RippleState.SEARCHING) {
            revealRadius.animateTo(maxRevealRadius, tween(1500))
        } else {
            revealRadius.snapTo(0f)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { actualSize = androidx.compose.ui.geometry.Size(it.width.toFloat(), it.height.toFloat()) }
            .background(bgColor)
    ) {
        if (backgroundBitmap != null) {
            val bgBitmap = backgroundBitmap!!
            // 底层：70% 模糊 + 70% 暗色（始终全屏）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            Modifier.graphicsLayer {
                                renderEffect = android.graphics.RenderEffect
                                    .createBlurEffect(70f, 70f, Shader.TileMode.CLAMP)
                                    .asComposeRenderEffect()
                            }
                        } else Modifier
                    )
            ) {
                Image(
                    bitmap = bgBitmap,
                    contentDescription = "背景",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.7f))
            )

            // 顶层：20% 模糊 + 20% 暗色（圆形扩散遮罩）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = 0.99f }
                    .drawWithContent {
                        val r = revealRadius.value
                        val path = Path().apply {
                            addOval(Rect(
                                size.width / 2f - r,
                                size.height / 2f - r,
                                size.width / 2f + r,
                                size.height / 2f + r
                            ))
                        }
                        clipPath(path) {
                            this@drawWithContent.drawContent()
                        }
                    }
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                Modifier.graphicsLayer {
                                    renderEffect = android.graphics.RenderEffect
                                        .createBlurEffect(30f, 30f, Shader.TileMode.CLAMP)
                                        .asComposeRenderEffect()
                                }
                            } else Modifier
                        )
                ) {
                    Image(
                        bitmap = bgBitmap,
                        contentDescription = "背景",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.2f))
                )
            }
        }
        // 顶部文字区域（避开状态栏）
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(top = 16.dp)
        ) {
            // 左上小字：设备名称
            Text(
                text = uiState.deviceName,
                fontSize = 14.sp,
                color = Color.White.copy(alpha = 0.5f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 32.dp)
            )

            Spacer(Modifier.height(8.dp))

            // 状态提示大字
            Text(
                text = uiState.statusText,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp)
            )

            // 接近/远离趋势提示（纯文本 + 白色辉光描边）
            if (uiState.trendText.isNotEmpty()) {
                Spacer(Modifier.height(58.dp))
                Text(
                    text = uiState.trendText,
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    style = TextStyle(shadow = Shadow(color = Color.White, blurRadius = 5f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp)
                )
            }
        }

        // 中心 Tag 图标 + 涟漪 — 填满整个 Box，内部自动居中
        RippleEffect(
            state = uiState.rippleState,
            tagSize = 150.dp,
            maxRadius = 180.dp,
            rssi = uiState.rssi,
            themePath = uiState.themePath,
            themeVersion = uiState.themeVersion,
            modifier = Modifier.fillMaxSize()
        )

        // 底部控制按钮（避开导航栏）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 16.dp, start = 32.dp, end = 32.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 退出
            FindCircleButton(
                icon = Icons.Default.Close,
                label = "退出",
                bgColor = Color.White.copy(alpha = 0.15f),
                onClick = { viewModel.exitFinder(); safeExit() }
            )

            // 手电筒
            FindCircleButton(
                icon = if (flashOn) Icons.Default.FlashOff else Icons.Default.FlashOn,
                label = if (flashOn) "关灯" else "手电",
                bgColor = if (flashOn) Color.Yellow.copy(alpha = 0.35f)
                          else Color.White.copy(alpha = 0.15f),
                onClick = {
                    if (flashOn) {
                        // 关闭无需权限
                        flashOn = false
                        toggleFlashlight(context, false)
                    } else {
                        val granted = ContextCompat.checkSelfPermission(
                            context, Manifest.permission.CAMERA
                        ) == PackageManager.PERMISSION_GRANTED
                        if (granted) {
                            flashOn = true
                            if (!toggleFlashlight(context, true)) {
                                flashOn = false
                                Toast.makeText(context, "无法开启闪光灯", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    }
                }
            )

            // 响铃（切换模式并显示激活状态）
            FindCircleButton(
                icon = Icons.Default.NotificationsActive,
                label = if (uiState.ringActive) "关闭" else "响铃",
                bgColor = if (uiState.ringActive) Color(0xFFE57373).copy(alpha = 0.5f)
                          else Color.White.copy(alpha = 0.15f),
                enabled = !uiState.ringBusy,
                onClick = { viewModel.sendRing() }
            )
        }
    }
}

@Composable
private fun FindCircleButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    bgColor: Color,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier
                .size(60.dp)
                .clip(CircleShape)
                .background(bgColor),
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = Color.Transparent,
                contentColor = Color.White
            )
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
    }
}


