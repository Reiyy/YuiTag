package com.reiyy.yuitag.ui.screens

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.reiyy.yuitag.data.local.DeviceType
import com.reiyy.yuitag.ui.components.CropImageOverlay
import com.reiyy.yuitag.ui.components.CropShape
import com.reiyy.yuitag.viewmodel.PersonalizationViewModel
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonalizationScreen(
    uid: String,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: PersonalizationViewModel = viewModel()
) {
    LaunchedEffect(uid) {
        viewModel.initialize(uid)
    }

    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showResetDialog by remember { mutableStateOf(false) }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            viewModel.onImagePicked(uri)
        }
    }

    LaunchedEffect(uiState.saveError) {
        uiState.saveError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissSaveError()
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("重置主题") },
            text = { Text("将清除所有自定义主题设置（图标、背景图、状态文本），恢复为默认样式。确定要重置吗？") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.resetTheme()
                    showResetDialog = false
                }) { Text("确认重置", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) { Text("取消") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("个性化") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 20.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(20.dp))

                Text(
                    text = "个性化",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(4.dp))

                Text(
                    text = "修改设备名称和图标",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(20.dp))

                OutlinedTextField(
                    value = uiState.customName,
                    onValueChange = { viewModel.updateCustomName(it) },
                    label = { Text("物品名称") },
                    placeholder = { Text("例如：我的车钥匙") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    supportingText = { Text("本地显示名称，最长 40 字符") }
                )

                Spacer(Modifier.height(16.dp))

                Text(
                    text = "设备类型",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DeviceType.entries.forEach { type ->
                        val isSelected = uiState.deviceType == type
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .border(
                                    width = if (isSelected) 2.dp else 1.dp,
                                    color = if (isSelected)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.outline,
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .clickable { viewModel.updateDeviceType(type) }
                                .padding(vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = type.icon,
                                contentDescription = type.label,
                                tint = if (isSelected)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = type.label,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isSelected)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (uiState.deviceType == DeviceType.CUSTOM) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = uiState.customIcon,
                        onValueChange = { viewModel.updateCustomIcon(it) },
                        label = { Text("自定义图标") },
                        placeholder = { Text("例如：🔑 或 📱") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        supportingText = { Text("输入 1~2 个字符的 emoji 或符号") }
                    )
                }

                Spacer(Modifier.height(28.dp))
                HorizontalDivider()
                Spacer(Modifier.height(20.dp))

                Text(
                    text = "主题设置",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "自定义查找页的图标、背景和状态文字",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    ThemeImageBlock(
                        label = "中心图标",
                        imagePath = if (uiState.themePath.isNotEmpty()) File(uiState.themePath, "icon.png").absolutePath else "",
                        isCircular = true,
                        version = uiState.themeVersion,
                        onClick = {
                            viewModel.pickImage(PersonalizationViewModel.CropTarget.ICON)
                            imagePicker.launch(pickVisualMediaRequest())
                        }
                    )
                    ThemeImageBlock(
                        label = "类型图标",
                        imagePath = if (uiState.themePath.isNotEmpty()) File(uiState.themePath, "mini_icon.png").absolutePath else "",
                        isCircular = true,
                        version = uiState.themeVersion,
                        onClick = {
                            viewModel.pickImage(PersonalizationViewModel.CropTarget.MINI_ICON)
                            imagePicker.launch(pickVisualMediaRequest())
                        }
                    )
                    ThemeImageBlock(
                        label = "查找页背景",
                        imagePath = if (uiState.themePath.isNotEmpty()) File(uiState.themePath, "background.png").absolutePath else "",
                        isCircular = false,
                        version = uiState.themeVersion,
                        onClick = {
                            viewModel.pickImage(PersonalizationViewModel.CropTarget.BACKGROUND)
                            imagePicker.launch(pickVisualMediaRequest())
                        }
                    )
                }

                Spacer(Modifier.height(20.dp))
                HorizontalDivider()
                Spacer(Modifier.height(16.dp))

                Text(
                    text = "状态提示文字",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "自定义查找页上显示的提示文字，留空则使用默认值",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = uiState.theme.trendHere,
                    onValueChange = { viewModel.updateTrendHere(it) },
label = { Text("在这里啦") },
placeholder = { Text("默认：在这呢！~\\(≧▽≦)/~") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = uiState.theme.trendApproaching,
                    onValueChange = { viewModel.updateTrendApproaching(it) },
label = { Text("正在接近") },
placeholder = { Text("默认：这个方向没错哦~罒ω罒") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = uiState.theme.trendAway,
                    onValueChange = { viewModel.updateTrendAway(it) },
label = { Text("正在远离") },
placeholder = { Text("默认：好像走反了呢(ー_ー)!!") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = uiState.theme.trendStable,
                    onValueChange = { viewModel.updateTrendStable(it) },
label = { Text("无变化") },
placeholder = { Text("默认：愣着干嘛呢？(~￣△￣)~") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(Modifier.height(20.dp))

                Button(
                    onClick = { showResetDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !uiState.isSaving,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Text(
                        text = "重置主题",
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                Spacer(Modifier.height(12.dp))

                Button(
                    onClick = { viewModel.saveConfig(onSaved) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !uiState.isSaving
                ) {
                    Text(
                        text = if (uiState.isSaving) "保存中..." else "保存",
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                Spacer(Modifier.height(16.dp))
            }

            if (uiState.showCrop && uiState.pickedImageUri != null) {
                CropImageOverlay(
                    imageUri = uiState.pickedImageUri!!,
                    cropShape = uiState.cropShape,
                    outputSize = uiState.cropOutputSize,
                    onCropComplete = { bitmap -> viewModel.onCropComplete(bitmap) },
                    onCancel = { viewModel.onCropCancel() }
                )
            }
        }
    }
}

@Composable
private fun ThemeImageBlock(
    label: String,
    imagePath: String,
    isCircular: Boolean,
    version: Int,
    onClick: () -> Unit
) {
    val previewSize = 80.dp
    val shape = if (isCircular) CircleShape else RoundedCornerShape(8.dp)

    val bitmap = remember(imagePath, version) {
        if (imagePath.isNotEmpty()) {
            try {
                BitmapFactory.decodeFile(imagePath)?.asImageBitmap()
            } catch (e: Exception) {
                null
            }
        } else null
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .size(previewSize)
                .clip(shape)
                .border(1.dp, MaterialTheme.colorScheme.outline, shape),
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = label,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "上传",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ThemeImageRow(
    label: String,
    hint: String,
    imagePath: String,
    isCircular: Boolean,
    onClick: () -> Unit
) {
    val previewSize = if (isCircular) 80.dp else 80.dp
    val previewHeight = if (isCircular) 80.dp else 80.dp * 16f / 9f
    val shape = if (isCircular) CircleShape else RoundedCornerShape(8.dp)

    val bitmap = remember(imagePath) {
        if (imagePath.isNotEmpty()) {
            try {
                BitmapFactory.decodeFile(imagePath)?.asImageBitmap()
            } catch (e: Exception) {
                null
            }
        } else null
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(previewSize, previewHeight)
                .clip(shape)
                .border(1.dp, MaterialTheme.colorScheme.outline, shape)
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = label,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "上传",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.clickable { onClick() }) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun pickVisualMediaRequest(): PickVisualMediaRequest {
    return PickVisualMediaRequest.Builder()
        .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly)
        .build()
}