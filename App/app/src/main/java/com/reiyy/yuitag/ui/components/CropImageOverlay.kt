package com.reiyy.yuitag.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

enum class CropShape { CIRCLE, RECT_9_16 }

@Composable
fun CropImageOverlay(
    imageUri: Uri,
    cropShape: CropShape = CropShape.CIRCLE,
    outputSize: Int = 1024,
    onCropComplete: (Bitmap) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val config = LocalConfiguration.current
    val isCircle = cropShape == CropShape.CIRCLE

    val cropCircleRadiusPx = with(density) { 140.dp.toPx() }
    val cropRectRatio = remember(config.screenWidthDp, config.screenHeightDp) {
        val w = config.screenWidthDp.coerceAtLeast(1)
        val h = config.screenHeightDp.coerceAtLeast(1)
        (w.toFloat() / h.toFloat()).coerceIn(1f / 3f, 1f)
    }
    val outputHeight = remember(cropRectRatio) { (outputSize / cropRectRatio).toInt() }

    var androidBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var imageBitmap by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }

    LaunchedEffect(imageUri) {
        withContext(Dispatchers.IO) {
            val bmp = context.contentResolver.openInputStream(imageUri)?.use {
                BitmapFactory.decodeStream(it)
            }
            androidBitmap = bmp
            imageBitmap = bmp?.asImageBitmap()
        }
    }

    if (imageBitmap == null || androidBitmap == null) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black))
        return
    }

    val sourceImage = imageBitmap!!
    val sourceAndroid = androidBitmap!!

    var zoomScale by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }
    var canvasSize by remember { mutableStateOf(Size.Zero) }

    fun cropRectSize(): Pair<Float, Float> {
        val w = canvasSize.width
        val h = canvasSize.height
        val rectH = h * 0.8f
        val rectW = rectH * cropRectRatio
        return rectW to rectH
    }

    fun minCropExtent(): Float {
        return if (isCircle) {
            cropCircleRadiusPx * 2f
        } else {
            val (rw, rh) = cropRectSize()
            min(rw, rh)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds()
                .onSizeChanged { canvasSize = Size(it.width.toFloat(), it.height.toFloat()) }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val w = canvasSize.width
                        val h = canvasSize.height
                        if (w <= 0 || h <= 0) return@detectTransformGestures

                        val fitScale = min(w / sourceAndroid.width, h / sourceAndroid.height)
                        val minExtent = minCropExtent()
                        val minZoom = max(
                            minExtent / (sourceAndroid.width * fitScale),
                            minExtent / (sourceAndroid.height * fitScale)
                        ).coerceAtMost(1f)

                        zoomScale = (zoomScale * zoom).coerceIn(minZoom, 5f)

                        val totalScale = fitScale * zoomScale
                        val imgW = sourceAndroid.width * totalScale
                        val imgH = sourceAndroid.height * totalScale
                        val fitCenterX = (w - imgW) / 2f
                        val fitCenterY = (h - imgH) / 2f

                        panX += pan.x
                        panY += pan.y

                        val (halfW, halfH) = if (isCircle) {
                            cropCircleRadiusPx to cropCircleRadiusPx
                        } else {
                            val (rw, rh) = cropRectSize()
                            rw / 2f to rh / 2f
                        }
                        val minPanX = w / 2f + halfW - fitCenterX - imgW
                        val maxPanX = w / 2f - halfW - fitCenterX
                        val minPanY = h / 2f + halfH - fitCenterY - imgH
                        val maxPanY = h / 2f - halfH - fitCenterY

                        panX = panX.coerceIn(minOf(minPanX, maxPanX), maxOf(minPanX, maxPanX))
                        panY = panY.coerceIn(minOf(minPanY, maxPanY), maxOf(minPanY, maxPanY))
                    }
                }
        ) {
            val w = size.width
            val h = size.height
            val fitScale = min(w / sourceAndroid.width, h / sourceAndroid.height)
            val totalScale = fitScale * zoomScale
            val imgW = sourceAndroid.width * totalScale
            val imgH = sourceAndroid.height * totalScale
            val imgX = (w - imgW) / 2f + panX
            val imgY = (h - imgH) / 2f + panY

            drawImage(
                image = sourceImage,
                dstOffset = androidx.compose.ui.unit.IntOffset(imgX.toInt(), imgY.toInt()),
                dstSize = androidx.compose.ui.unit.IntSize(imgW.toInt(), imgH.toInt())
            )
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = 0.99f }
        ) {
            drawRect(Color.Black.copy(alpha = 0.5f))
            if (isCircle) {
                drawCircle(
                    color = Color.Black,
                    radius = cropCircleRadiusPx,
                    center = Offset(size.width / 2f, size.height / 2f),
                    blendMode = BlendMode.Clear
                )
                drawCircle(
                    color = Color.White.copy(alpha = 0.8f),
                    radius = cropCircleRadiusPx,
                    center = Offset(size.width / 2f, size.height / 2f),
                    style = Stroke(2.dp.toPx())
                )
            } else {
                val (rw, rh) = cropRectSize()
                val left = (size.width - rw) / 2f
                val top = (size.height - rh) / 2f
                drawRect(
                    color = Color.Black,
                    topLeft = Offset(left, top),
                    size = Size(rw, rh),
                    blendMode = BlendMode.Clear
                )
                drawRect(
                    color = Color.White.copy(alpha = 0.8f),
                    topLeft = Offset(left, top),
                    size = Size(rw, rh),
                    style = Stroke(2.dp.toPx())
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(32.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(
                onClick = onCancel,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White.copy(alpha = 0.2f),
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("取消")
            }
            Button(
                onClick = {
                    val w = canvasSize.width
                    val h = canvasSize.height
                    if (w <= 0 || h <= 0) return@Button

                    val fitScale = min(w / sourceAndroid.width, h / sourceAndroid.height)
                    val totalScale = fitScale * zoomScale
                    val imgCenterX = (w - sourceAndroid.width * totalScale) / 2f + panX + sourceAndroid.width * totalScale / 2f
                    val imgCenterY = (h - sourceAndroid.height * totalScale) / 2f + panY + sourceAndroid.height * totalScale / 2f
                    val imgCropCenterX = (w / 2f - imgCenterX) / totalScale + sourceAndroid.width / 2f
                    val imgCropCenterY = (h / 2f - imgCenterY) / totalScale + sourceAndroid.height / 2f

                    val cropped = if (isCircle) {
                        val imgCropRadius = cropCircleRadiusPx / totalScale
                        cropCircleBitmap(sourceAndroid, imgCropCenterX, imgCropCenterY, imgCropRadius, outputSize)
                    } else {
                        val (rw, rh) = cropRectSize()
                        val imgCropW = rw / totalScale
                        val imgCropH = rh / totalScale
                        cropRectBitmap(sourceAndroid, imgCropCenterX, imgCropCenterY, imgCropW, imgCropH, outputSize, outputHeight)
                    }
                    onCropComplete(cropped)
                },
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("确认")
            }
        }
    }
}

private fun cropCircleBitmap(
    source: Bitmap,
    cropCenterX: Float,
    cropCenterY: Float,
    cropRadius: Float,
    outputSize: Int
): Bitmap {
    val output = Bitmap.createBitmap(outputSize, outputSize, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(output)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)

    val path = android.graphics.Path()
    path.addCircle(outputSize / 2f, outputSize / 2f, outputSize / 2f, android.graphics.Path.Direction.CW)
    canvas.clipPath(path)

    val scale = outputSize / (2f * cropRadius)
    canvas.translate(outputSize / 2f, outputSize / 2f)
    canvas.scale(scale, scale)
    canvas.translate(-cropCenterX, -cropCenterY)

    canvas.drawBitmap(source, 0f, 0f, paint)
    return output
}

private fun cropRectBitmap(
    source: Bitmap,
    cropCenterX: Float,
    cropCenterY: Float,
    cropW: Float,
    cropH: Float,
    outputW: Int,
    outputH: Int
): Bitmap {
    val output = Bitmap.createBitmap(outputW, outputH, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(output)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)

    val scaleX = outputW / cropW
    val scaleY = outputH / cropH
    val scale = min(scaleX, scaleY)
    val offsetX = (outputW - cropW * scale) / 2f
    val offsetY = (outputH - cropH * scale) / 2f

    canvas.translate(offsetX, offsetY)
    canvas.scale(scale, scale)
    canvas.translate(-(cropCenterX - cropW / 2f), -(cropCenterY - cropH / 2f))

    canvas.drawBitmap(source, 0f, 0f, paint)
    return output
}