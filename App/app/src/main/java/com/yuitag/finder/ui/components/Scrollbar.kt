package com.yuitag.finder.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * 为 LazyListState 提供垂直滚动条指示器，基于 drawWithContent 绘制。
 */
@Composable
fun Modifier.simpleVerticalScrollbar(
    state: LazyListState,
    thumbWidth: Dp = 2.dp,
    thumbColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
    trackColor: Color? = null,
    endPadding: Dp = 2.5.dp,
    topPadding: Dp = 6.dp,
    bottomPadding: Dp = 6.dp,
    minThumbHeight: Dp = 36.dp,
    autoFade: Boolean = true
): Modifier {
    val alpha = remember { Animatable(if (autoFade) 0f else 1f) }
    var hintTrigger by remember { mutableIntStateOf(0) }

    if (autoFade) {
        LaunchedEffect(state.layoutInfo.totalItemsCount) {
            if (state.layoutInfo.totalItemsCount > 0 && hintTrigger == 0) {
                delay(300)
                val visible = state.layoutInfo.visibleItemsInfo
                val isScrollable = (visible.lastOrNull()?.index ?: 0) < state.layoutInfo.totalItemsCount - 1 ||
                        (visible.firstOrNull()?.index ?: 0) > 0
                if (isScrollable) {
                    hintTrigger = 1
                }
            }
        }

        val isScrolling = state.isScrollInProgress
        LaunchedEffect(isScrolling, hintTrigger) {
            if (isScrolling) {
                alpha.animateTo(1f, animationSpec = tween(durationMillis = 120, easing = FastOutSlowInEasing))
            } else if (hintTrigger > 0) {
                alpha.animateTo(1f, animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing))
                delay(1200)
                alpha.animateTo(0f, animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing))
                hintTrigger = 2
            } else if (alpha.value > 0f) {
                delay(1200)
                alpha.animateTo(0f, animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing))
            }
        }
    }

    return this.drawWithContent {
        drawContent()

        val currentAlpha = if (autoFade) alpha.value else 1f
        if (currentAlpha <= 0f) return@drawWithContent

        val layoutInfo = state.layoutInfo
        val totalItems = layoutInfo.totalItemsCount
        val visibleItems = layoutInfo.visibleItemsInfo
        if (totalItems <= 0 || visibleItems.isEmpty()) return@drawWithContent

        val viewportHeight = size.height
        if (viewportHeight <= 0f) return@drawWithContent

        val firstVisible = visibleItems.first()
        val lastVisible = visibleItems.last()

        val allItemsVisible = firstVisible.index == 0 &&
                firstVisible.offset >= 0 &&
                lastVisible.index == totalItems - 1 &&
                (lastVisible.offset + lastVisible.size) <= viewportHeight

        if (allItemsVisible) return@drawWithContent

        val topMarginPx = topPadding.toPx()
        val bottomMarginPx = bottomPadding.toPx()
        val trackHeight = (viewportHeight - topMarginPx - bottomMarginPx).coerceAtLeast(0f)
        val minThumbHeightPx = minThumbHeight.toPx()
        if (trackHeight <= minThumbHeightPx) return@drawWithContent

        val visibleHeight = visibleItems.sumOf { it.size }.toFloat()
        val avgItemHeight = (visibleHeight / visibleItems.size).coerceAtLeast(1f)
        val estimatedTotalHeight = (avgItemHeight * totalItems).coerceAtLeast(viewportHeight)

        if (estimatedTotalHeight <= viewportHeight) return@drawWithContent

        val visibleFraction = (viewportHeight / estimatedTotalHeight).coerceIn(0f, 1f)
        val effectiveMinThumbHeight = minThumbHeightPx.coerceAtMost(trackHeight)
        val thumbHeight = (trackHeight * visibleFraction).coerceIn(effectiveMinThumbHeight, trackHeight)

        val scrollFraction = when {
            firstVisible.index == 0 && firstVisible.offset >= 0 -> 0f
            lastVisible.index == totalItems - 1 && (lastVisible.offset + lastVisible.size) <= viewportHeight -> 1f
            else -> {
                val currentScrollPx = (firstVisible.index * avgItemHeight - firstVisible.offset).coerceAtLeast(0f)
                val maxScrollPx = (estimatedTotalHeight - viewportHeight).coerceAtLeast(1f)
                (currentScrollPx / maxScrollPx).coerceIn(0f, 1f)
            }
        }

        val thumbY = topMarginPx + scrollFraction * (trackHeight - thumbHeight)
        val thumbWidthPx = thumbWidth.toPx()
        val thumbX = size.width - endPadding.toPx() - thumbWidthPx
        val cornerRadius = CornerRadius(thumbWidthPx / 2f, thumbWidthPx / 2f)

        if (trackColor != null && trackColor != Color.Transparent) {
            drawRoundRect(
                color = trackColor.copy(alpha = trackColor.alpha * currentAlpha),
                topLeft = Offset(thumbX, topMarginPx),
                size = Size(thumbWidthPx, trackHeight),
                cornerRadius = cornerRadius
            )
        }

        drawRoundRect(
            color = thumbColor.copy(alpha = thumbColor.alpha * currentAlpha),
            topLeft = Offset(thumbX, thumbY),
            size = Size(thumbWidthPx, thumbHeight),
            cornerRadius = cornerRadius
        )
    }
}
