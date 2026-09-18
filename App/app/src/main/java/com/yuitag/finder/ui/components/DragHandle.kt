package com.yuitag.finder.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

@Composable
fun DragHandle(modifier: Modifier = Modifier, isDragging: Boolean = false) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight(),
        contentAlignment = Alignment.Center
    ) {
        Spacer(
            modifier = Modifier
                .width(40.dp)
                .height(4.dp)
                .graphicsLayer {
                    val scale = if (isDragging) 1.2f else 1f
                    scaleX = scale
                    scaleY = scale
                }
                .background(
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                        alpha = if (isDragging) 0.7f else 0.4f
                    ),
                    shape = RoundedCornerShape(2.dp)
                )
        )
    }
}
