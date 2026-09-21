package com.reiyy.yuitag.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reiyy.yuitag.data.local.DeviceType
import java.io.File

@Composable
fun DeviceTypeIcon(
    deviceType: DeviceType,
    customIcon: String,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    tint: Color = Color.Unspecified,
    typeIconPath: String = "",
    contentDescription: String? = null
) {
    val customBitmap = remember(typeIconPath) {
        if (typeIconPath.isNotEmpty()) {
            try {
                val file = File(typeIconPath, "mini_icon.png")
                if (file.exists()) BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() else null
            } catch (e: Exception) {
                null
            }
        } else null
    }

    if (customBitmap != null) {
        Image(
            bitmap = customBitmap,
            contentDescription = contentDescription,
            modifier = modifier.fillMaxSize().clip(androidx.compose.foundation.shape.CircleShape),
            contentScale = ContentScale.Crop
        )
    } else if (deviceType == DeviceType.CUSTOM && customIcon.isNotBlank()) {
        Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
            Text(
                text = customIcon,
                fontSize = (size.value * 0.8f).sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false
            )
        }
    } else {
        Icon(
            imageVector = deviceType.icon,
            contentDescription = contentDescription,
            modifier = modifier.size(size),
            tint = if (tint != Color.Unspecified) tint else LocalContentColor.current
        )
    }
}