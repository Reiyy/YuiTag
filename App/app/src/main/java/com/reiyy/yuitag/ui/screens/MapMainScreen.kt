package com.reiyy.yuitag.ui.screens

import android.widget.Toast
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.reiyy.yuitag.data.FindMyQueryService
import com.reiyy.yuitag.data.local.DeviceType
import com.reiyy.yuitag.ui.components.BindPopupCard
import com.reiyy.yuitag.ui.components.BindPopupState
import com.reiyy.yuitag.ui.components.DragHandle
import com.reiyy.yuitag.ui.components.DeviceDetailContent
import com.reiyy.yuitag.ui.components.DeviceListContent
import com.reiyy.yuitag.ui.components.OsmMapView
import com.reiyy.yuitag.ui.components.rememberMapMarkerIcons
import com.reiyy.yuitag.viewmodel.BottomSheetMode
import com.reiyy.yuitag.viewmodel.MapMainViewModel
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

// 卡片切换动画（列表↔详情）：整卡下滑后切换内容并回升
private val CardOutEasing = CubicBezierEasing(0.4f, 0.216f, 0.88f, 1.0f)
private val CardInEasing = CubicBezierEasing(0.12f, 0.26f, 0.0f, 1.0f)
private const val CardOutDuration = 200
private const val CardInDuration = 300
private const val TOP_BAR_HEIGHT_DP = 66
// 吸附弹簧：低阻尼 → 松手过冲回弹（q弹），越小越弹
private val SheetSettleSpring = spring<Float>(dampingRatio = 0.6f, stiffness = 500f)
// 越界橡皮筋：阻力衰减因子 + 视觉延伸上限（以屏幕比例 fraction 计）
private const val SheetRubberBandFactor = 0.08f
private const val SheetRubberBandLimit = 0.12f
// 松手甩动判定：速度阈值（dp/ms）与位移阈值（dp），任一达到即朝方向飞向下一锚点。
// 位移阈值为手指总位移（含 slop，约 18dp），很小即可触发，向下/向上均稳定
private const val SheetFlingVelocityDpPerMs = 0.15f
private const val SheetFlingDistanceDp = 20f
// 注入弹簧的初速上限（fraction/秒），防止超快甩动飞过头太多
private const val SheetMaxFlingVelocity = 2.5f
// 拖拽热区高度：与手柄高度一致，避免手柄下方出现空白
private val SheetDragZoneDp = 20.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapMainScreen(
    onNavigateToConfig: (String) -> Unit,
    onNavigateToPersonalization: (String) -> Unit,
    onNavigateToFind: (String) -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToLocationHistory: (String) -> Unit,
    onNavigateToTrajectory: (String) -> Unit,
    onNavigateToDetail: (String) -> Unit,
    onNavigateToManualImport: () -> Unit = {},
    onNavigateToAbout: () -> Unit = {},
    viewModel: MapMainViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val boundDevices by viewModel.boundDevices.collectAsState()
    val locationState by viewModel.locationState.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val markerIcons = rememberMapMarkerIcons()
    val deviceListState = rememberLazyListState()

    // 缓存从 themePath 加载的 mini_icon.png 位图，避免每次重组重复解码
    val customImageBitmapCache = remember { mutableMapOf<String, Bitmap?>() }

    // 天地图瓦片配置（设置页填写；从设置返回时导航重组会重新读取）
    val context = LocalContext.current
    val mapConfig = remember(context) {
        val service = FindMyQueryService(context)
        service.getTiandituKey() to service.getTiandituStyle()
    }
    val tiandituKey = mapConfig.first
    val tiandituStyle = mapConfig.second

    val screenHeightDp = configuration.screenHeightDp.dp
    val screenHeightPx = with(density) { screenHeightDp.toPx() }

    // 动态锚点：展开态避开顶栏区域
    val topBarDp = TOP_BAR_HEIGHT_DP.dp
    val anchors = remember(screenHeightDp) {
        listOf(0.10f, 0.55f, 1f - (topBarDp / screenHeightDp))
    }
    val defaultAnchor = 0.55f

    // 拖拽底部面板状态
    var sheetFraction by remember { mutableFloatStateOf(defaultAnchor) }
    var isDragging by remember { mutableStateOf(false) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    val sheetAnimatable = remember { Animatable(defaultAnchor) }
    val scope = rememberCoroutineScope()
    val hapticFeedback = LocalHapticFeedback.current
    val touchSlopPx = LocalViewConfiguration.current.touchSlop

    val sheetMidpoints = remember(anchors) {
        (1 until anchors.size).map { (anchors[it - 1] + anchors[it]) / 2f }
    }

    fun snapToNearest(fraction: Float): Float {
        return anchors.minByOrNull { abs(it - fraction) } ?: defaultAnchor
    }

    /** 越界橡皮筋：两端带阻力跟手，视觉延伸有上限 */
    fun rubberBand(value: Float, min: Float, max: Float): Float {
        if (value in min..max) return value
        val boundary = if (value < min) min else max
        val overshoot = abs(value - boundary)
        val eased = 1f - 1f / (overshoot / SheetRubberBandFactor + 1f)
        return (boundary + (if (value < min) -eased else eased) * SheetRubberBandLimit)
            .coerceAtLeast(0f)
    }

    /**
     * 松手吸附目标：优先按「方向 + 位移」判定，速度作补充，否则就近吸附。
     * fingerDisplacementPx 为手指总位移（含 slop 之前的移动），正值 = 向下。
     */
    fun settleTarget(velocity: Velocity, current: Float, fingerDisplacementPx: Float): Float {
        val fractionVelocity = -velocity.y / screenHeightPx
        val velocityThreshold = SheetFlingVelocityDpPerMs * density.density / screenHeightPx
        val displacementThreshold = SheetFlingDistanceDp * density.density / screenHeightPx
        return when {
            fractionVelocity > velocityThreshold || fingerDisplacementPx < -displacementThreshold ->
                anchors.firstOrNull { it > current } ?: anchors.last()
            fractionVelocity < -velocityThreshold || fingerDisplacementPx > displacementThreshold ->
                anchors.lastOrNull { it < current } ?: anchors.first()
            else -> snapToNearest(current)
        }
    }

    /** 弹簧吸附到目标锚点（低阻尼 → 过冲回弹），可注入松手初速延续甩动惯性 */
    fun startSettle(target: Float, initialVelocity: Float = 0f) {
        settleJob?.cancel()
        settleJob = scope.launch {
            sheetAnimatable.snapTo(sheetFraction)
            sheetAnimatable.animateTo(
                target,
                SheetSettleSpring,
                initialVelocity = initialVelocity.coerceIn(-SheetMaxFlingVelocity, SheetMaxFlingVelocity)
            ) {
                sheetFraction = this.value
            }
            sheetFraction = target
        }
    }

    var showTopMenu by remember { mutableStateOf(false) }
    var recenterTarget by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var recenterToken by remember { mutableStateOf(0) }
    var selfMarkerToFrontToken by remember { mutableIntStateOf(0) }
    var isLocating by remember { mutableStateOf(false) }

    // 将地图居中到指定位置（token 强制每次触发动画，即使坐标相同）
    fun centerOn(lat: Double, lng: Double) {
        recenterTarget = lat to lng
        recenterToken++
    }

    val selectedDevice by remember {
        derivedStateOf {
            val uid = uiState.selectedDeviceUid
            if (uid != null) boundDevices.find { it.uid == uid } else null
        }
    }

    val collapsedThreshold = (anchors[0] + anchors[1]) / 2f

    // 卡片切换动画状态：cardOffset(0=原位,1=已滑出)，displayedMode 为当前内容
    val cardDropPx = 320f
    val cardOffset = remember { Animatable(0f) }
    var displayedMode by remember { mutableStateOf(uiState.bottomSheetMode) }
    // 详情内容快照：切换途中 selectedDeviceUid 可能已置空，用快照保持详情不空白
    var detailDevice by remember { mutableStateOf(selectedDevice) }

    // DETAIL 期间 boundDevices 持续刷新，快照跟随 selectedDevice 同步（null 时保留旧值，由 finally 清理）
    LaunchedEffect(selectedDevice) {
        if (selectedDevice != null) detailDevice = selectedDevice
    }

    LaunchedEffect(uiState.bottomSheetMode) {
        val target = uiState.bottomSheetMode
        if (target != displayedMode) {
            if (target == BottomSheetMode.DETAIL) detailDevice = selectedDevice
            try {
                cardOffset.animateTo(1f, tween(CardOutDuration, easing = CardOutEasing))
                displayedMode = target
                cardOffset.animateTo(0f, tween(CardInDuration, easing = CardInEasing))
            } finally {
                cardOffset.snapTo(0f)
                if (displayedMode == BottomSheetMode.LIST) detailDevice = null
            }
            // 卡片动画结束后再刷新位置（错峰，避免动画窗口内数据突变触发详情卡布局跳动）
            if (target == BottomSheetMode.DETAIL) {
                uiState.selectedDeviceUid?.let { viewModel.refreshLocation(it) }
            }
            // 从详情回到列表：动画结束后地图回中到本机位置并置顶本机标记
            if (target == BottomSheetMode.LIST && locationState.hasLocation) {
                centerOn(locationState.latitude, locationState.longitude)
                selfMarkerToFrontToken++
            }
        }
    }

    // 生命周期管理
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> viewModel.onPause()
                Lifecycle.Event.ON_RESUME -> viewModel.onResume()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        viewModel.locationProvider.requestLocation()
    }

    // 启动：首次定位到达时居中一次
    LaunchedEffect(locationState.hasLocation) {
        if (locationState.hasLocation) centerOn(locationState.latitude, locationState.longitude)
    }

    // 选中设备（标记/列表点击进入详情）时居中该设备最新 FindMy 位置；
    // FindMy 不支持/已禁用/暂无位置则忽略
    LaunchedEffect(uiState.selectedDeviceUid) {
        val uid = uiState.selectedDeviceUid ?: return@LaunchedEffect
        val device = boundDevices.find { it.uid == uid } ?: return@LaunchedEffect
        if (device.findmySupported == false) return@LaunchedEffect
        if (device.findmyEnabled == false) return@LaunchedEffect
        val loc = uiState.deviceLatestLocations[uid] ?: return@LaunchedEffect
        centerOn(loc.latitude, loc.longitude)
    }

    val mapMarkers = remember(locationState, uiState.deviceLatestLocations, boundDevices, uiState.selectedDeviceUid, markerIcons) {
        val markers = mutableListOf<com.reiyy.yuitag.ui.components.MapMarkerData>()
        for ((uid, loc) in uiState.deviceLatestLocations) {
            val device = boundDevices.find { it.uid == uid }
            val deviceType = device?.deviceType ?: com.reiyy.yuitag.data.local.DeviceType.CUSTOM
            val name = device?.customName?.ifEmpty { device.name } ?: "YuiTag"
            val isSelected = uid == uiState.selectedDeviceUid
            val themePath = device?.themePath.orEmpty()
            val miniIconBitmap = if (themePath.isNotEmpty()) {
                customImageBitmapCache.getOrPut(themePath) {
                    try {
                        val file = File(themePath, "mini_icon.png")
                        if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
                    } catch (_: Exception) { null }
                }
            } else null
            val icon = when {
                miniIconBitmap != null && isSelected -> markerIcons.selectedCustomImageIcon(miniIconBitmap)
                miniIconBitmap != null -> markerIcons.customImageIcon(miniIconBitmap)
                isSelected -> markerIcons.selectedDeviceIcon(deviceType, device?.customIcon.orEmpty())
                else -> markerIcons.deviceIcon(deviceType, device?.customIcon.orEmpty())
            }
            markers.add(com.reiyy.yuitag.ui.components.MapMarkerData(
                latitude = loc.latitude,
                longitude = loc.longitude,
                title = name,
                uid = uid,
                icon = icon
            ))
        }
        // 本机位置后添加（自然层级在 tag 之上）
        if (locationState.hasLocation) {
            markers.add(com.reiyy.yuitag.ui.components.MapMarkerData(
                latitude = locationState.latitude,
                longitude = locationState.longitude,
                title = "我的位置",
                icon = markerIcons.myLocation
            ))
        }
        markers.toList()
    }

    val mapTargetCenter = recenterTarget

    Box(modifier = Modifier.fillMaxSize()) {
        // --- Layer 0: 全屏地图 ---
        OsmMapView(
            initialLatitude = locationState.latitude.takeIf { it != 0.0 } ?: 39.9042,
            initialLongitude = locationState.longitude.takeIf { it != 0.0 } ?: 116.4074,
            zoomLevel = 18.0,
            targetCenter = mapTargetCenter,
            animateToTarget = true,
            zoomToTargetLevel = 18.0,
            recenterToken = recenterToken,
            onMapTouched = {},
            paused = sheetFraction > 0.8f,
            onMarkerClick = { uid -> viewModel.selectDeviceByUid(uid) },
            modifiers = Modifier.fillMaxSize(),
            markers = mapMarkers,
            selectedUid = uiState.selectedDeviceUid,
            selfMarkerToFrontToken = selfMarkerToFrontToken,
            tiandituKey = tiandituKey,
            tiandituStyle = tiandituStyle
        )

        // --- Layer 2: 可拖拽底部面板 ---
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(screenHeightDp * sheetFraction)
        .graphicsLayer {
            translationY = cardOffset.value * cardDropPx
        }
        .zIndex(1f),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp,
            tonalElevation = 2.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(SheetDragZoneDp)
                        .pointerInput(anchors.first(), anchors.last(), screenHeightPx) {
                            // 自定义手势：从按下就累计全部垂直位移（含 slop 之前），
                            // 使小甩（含向下）也能稳定触发吸附
                            val velocityTracker = VelocityTracker()
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val pointerId = down.id
                                var startFraction = sheetFraction
                                var raw = startFraction
                                var lastPos = down.position
                                var totalDy = 0f
                                var dragActive = false
                                var cancelled = false
                                // 卡片随手指移动会改变拖拽条本地坐标系，导致测量 dy 里混入卡片自身位移。
                                // prevFractionDelta 记录上一帧卡片位移用于补偿，trackedY 为补偿后的手指轨迹（供测速）。
                                var prevFractionDelta = 0f
                                var trackedY = 0f
                                velocityTracker.resetTracking()

                                try {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == pointerId }
                                            ?: continue

                                        val measuredDy = change.position.y - lastPos.y
                                        lastPos = change.position

                                        if (!dragActive) {
                                            totalDy += measuredDy
                                            if (abs(totalDy) <= touchSlopPx) {
                                                if (change.changedToUpIgnoreConsumed()) break
                                                continue
                                            }
                                            dragActive = true
                                            isDragging = true
                                            settleJob?.cancel()
                                            settleJob = null
                                            velocityTracker.resetTracking()
                                            trackedY = 0f
                                            velocityTracker.addPosition(
                                                change.uptimeMillis,
                                                Offset(change.position.x, trackedY)
                                            )
                                        } else {
                                            // 减去卡片上一帧自身位移，得到手指真实位移，消除反馈回路
                                            val actualDy = measuredDy - prevFractionDelta * screenHeightPx
                                            prevFractionDelta = -actualDy / screenHeightPx
                                            totalDy += actualDy
                                            trackedY += actualDy
                                            velocityTracker.addPosition(
                                                change.uptimeMillis,
                                                Offset(change.position.x, trackedY)
                                            )
                                            val newRaw = raw - actualDy / screenHeightPx
                                            for (mid in sheetMidpoints) {
                                                if ((raw - mid) * (newRaw - mid) < 0f) {
                                                    hapticFeedback.performHapticFeedback(
                                                        HapticFeedbackType.TextHandleMove
                                                    )
                                                    break
                                                }
                                            }
                                            raw = newRaw
                                            sheetFraction = rubberBand(raw, anchors.first(), anchors.last())
                                            change.consume()
                                        }

                                        if (change.changedToUpIgnoreConsumed()) break
                                    }
                                } catch (e: CancellationException) {
                                    cancelled = true
                                }

                                if (dragActive) {
                                    isDragging = false
                                    if (cancelled) {
                                        startSettle(settleTarget(Velocity.Zero, raw, totalDy))
                                    } else {
                                        val velocity = velocityTracker.calculateVelocity()
                                        startSettle(
                                            target = settleTarget(velocity, raw, totalDy),
                                            initialVelocity = -velocity.y / screenHeightPx * 1000f
                                        )
                                    }
                                }
                            }
                        }
                ) {
                    DragHandle(isDragging = isDragging)
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    when (displayedMode) {
                        BottomSheetMode.LIST -> {
                            DeviceListContent(
                                devices = boundDevices,
                                deviceBatteries = uiState.deviceBatteries,
                                deviceHealthStates = uiState.deviceHealthStates,
                                onDeviceClick = { device ->
                                    if (sheetFraction < collapsedThreshold) {
                                        startSettle(anchors[1])
                                    } else {
                                        viewModel.selectDevice(device)
                                    }
                                },
                                onNavigateToManualImport = onNavigateToManualImport,
                                viewModel = viewModel,
                                lazyListState = deviceListState,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        BottomSheetMode.DETAIL -> {
                            detailDevice?.let { device ->
                                DeviceDetailContent(
                                    device = device,
                                    onBack = { viewModel.backToList() },
                                    onNavigateToConfig = onNavigateToConfig,
                                    onNavigateToPersonalization = onNavigateToPersonalization,
                                    onNavigateToFind = onNavigateToFind,
                                    onNavigateToLocationHistory = onNavigateToLocationHistory,
                                    onNavigateToTrajectory = onNavigateToTrajectory,
                                    onNavigateToDetail = onNavigateToDetail,
                                    viewModel = viewModel,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                }
            }
        }

        // --- Layer 3: 顶栏 ---
        // 背景透明，固定深色图标（地图瓦片整体偏浅色，可读性足够）
        TopAppBar(
            title = {
                Text("YuiTag查找",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface)
            },
            actions = {
                IconButton(onClick = {
                    if (isLocating) return@IconButton
                    isLocating = true
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    val selectedUid = uiState.selectedDeviceUid
                    scope.launch {
                        val startTime = System.currentTimeMillis()
                        try {
                            if (selectedUid != null) {
                                // 设备详情模式：仅回中到该 tag 当前最新位置，不发起任何查询
                                val loc = uiState.deviceLatestLocations[selectedUid]
                                if (loc != null) {
                                    centerOn(loc.latitude, loc.longitude)
                                } else {
                                    Toast.makeText(context, "该设备暂无位置", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                selfMarkerToFrontToken++
                                val fresh = viewModel.locationProvider.requestCurrentLocationOnce()
                                if (fresh != null) {
                                    centerOn(fresh.latitude, fresh.longitude)
                                } else if (locationState.hasLocation) {
                                    centerOn(locationState.latitude, locationState.longitude)
                                    Toast.makeText(context, "定位更新失败，已使用上次位置", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "无法获取定位，请检查定位权限", Toast.LENGTH_SHORT).show()
                                }
                            }
                        } finally {
                            try {
                                val elapsed = System.currentTimeMillis() - startTime
                                if (elapsed < 650L) {
                                    delay(650L - elapsed)
                                }
                            } finally {
                                isLocating = false
                            }
                        }
                    }
                }) {
                    if (isLocating) {
                        val infiniteTransition = rememberInfiniteTransition(label = "locate_pulse")
                        val scale by infiniteTransition.animateFloat(
                            initialValue = 0.88f,
                            targetValue = 1.22f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(650, easing = FastOutSlowInEasing),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "locate_scale"
                        )
                        val alpha by infiniteTransition.animateFloat(
                            initialValue = 0.65f,
                            targetValue = 1.0f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(650, easing = FastOutSlowInEasing),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "locate_alpha"
                        )
                        Icon(
                            Icons.Default.MyLocation,
                            contentDescription = "定位中",
                            modifier = Modifier.graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                this.alpha = alpha
                            },
                            tint = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Icon(
                            Icons.Default.MyLocation,
                            contentDescription = "我的位置",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                IconButton(onClick = { viewModel.toggleScan() }) {
                    if (uiState.isScanning) {
                        val infiniteTransition = rememberInfiniteTransition(label = "scan_spin")
                        val rotation by infiniteTransition.animateFloat(
                            initialValue = 0f,
                            targetValue = 360f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(1000, easing = LinearEasing),
                                repeatMode = RepeatMode.Restart
                            ),
                            label = "refresh_rotation"
                        )
                        Icon(
                            Icons.Default.Sync,
                            contentDescription = "停止扫描",
                            modifier = Modifier.rotate(rotation),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    } else {
                        Icon(
                            Icons.Default.Sync,
                            contentDescription = "开始扫描",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                Box {
                    IconButton(onClick = { showTopMenu = true }) {
                        Icon(Icons.Default.MoreVert,
                            contentDescription = "更多",
                            tint = MaterialTheme.colorScheme.onSurface)
                    }
                    DropdownMenu(
                        expanded = showTopMenu,
                        onDismissRequest = { showTopMenu = false },
                        shape = RoundedCornerShape(16.dp),
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    ) {
                        DropdownMenuItem(
                            text = { Text("关于") },
                            onClick = {
                                showTopMenu = false
                                onNavigateToAbout()
                            },
                            leadingIcon = { Icon(Icons.Default.Info, null) }
                        )
                        DropdownMenuItem(
                            text = { Text("设置") },
                            onClick = {
                                showTopMenu = false
                                onNavigateToSettings()
                            },
                            leadingIcon = { Icon(Icons.Default.Settings, null) }
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
                scrolledContainerColor = Color.Transparent
            )
        )

        // --- Layer 4: 绑定弹窗遮罩 ---
        if (uiState.showBindPopup) {
            BindPopupCard(
                state = when {
                    uiState.bindError != null -> BindPopupState.ERROR
                    uiState.bindSuccessUid != null -> BindPopupState.SUCCESS
                    uiState.bindingUid != null -> BindPopupState.BINDING
                    else -> BindPopupState.DISCOVERED
                },
                deviceName = uiState.unboundDeviceName ?: "YuiTag",
                errorMessage = uiState.bindError,
                bindProgress = uiState.bindProgress,
                onBindClick = {
                    val failedUid = uiState.bindFindMyFailedUid
                    if (failedUid != null) viewModel.retryFindMy(failedUid)
                    else uiState.unboundDeviceUid?.let { viewModel.bindDevice(it) }
                },
                onNextClick = { uiState.bindSuccessUid?.let { viewModel.dismissBindPopup() } },
                onDismiss = { viewModel.dismissBindPopup() },
                onIgnoreClick = { uiState.unboundDeviceUid?.let { viewModel.ignoreUnboundDevice(it) } },
                modifier = Modifier.zIndex(2f)
            )
        }
    }
}
