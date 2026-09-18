package com.yuitag.finder.ui.screens

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yuitag.finder.data.GithubReleaseChecker
import com.yuitag.finder.data.NoReleaseException
import com.yuitag.finder.util.VersionUtils
import kotlinx.coroutines.launch

private const val GITHUB_REPO_URL = "https://github.com/Reiyy/YuiTag"

/**
 * 关于页 — App 图标、版本、开源项目链接与 GitHub Release 更新检查。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val versionName = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    val checker = remember { GithubReleaseChecker() }
    val scope = rememberCoroutineScope()
    var isChecking by remember { mutableStateOf(false) }
    var updateStatus by remember { mutableStateOf<String?>(null) }
    var newVersionTag by remember { mutableStateOf<String?>(null) }

    // AdaptiveIconDrawable 无法被 painterResource 直接栅格化，这里手动绘制为位图
    val appIcon = remember {
        try {
            renderAppIcon(context)
        } catch (_: Exception) {
            null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("关于") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(32.dp))

            // App 图标
            if (appIcon != null) {
                Image(
                    bitmap = appIcon,
                    contentDescription = "YuiTag 图标",
                    modifier = Modifier
                        .size(88.dp)
                        .clip(RoundedCornerShape(22.dp))
                )
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text = "YuiTag",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(Modifier.height(4.dp))

            Text(
                text = "版本 $versionName",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(32.dp))

            InfoCard(
                title = "发布地址",
                content = "https://github.com/Reiyy/YuiTag",
                onClick = { openUrl(context, GITHUB_REPO_URL) }
            )

            Spacer(Modifier.height(12.dp))

            InfoCard("许可", "本项目采用 GPL-3.0 许可证。")

            Spacer(Modifier.height(24.dp))

            // 版本更新
            Text(
                text = "版本更新",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "从 GitHub Release 检查是否有新版本",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))

            OutlinedButton(
                onClick = {
                    if (isChecking) return@OutlinedButton
                    isChecking = true
                    updateStatus = "正在检查更新..."
                    newVersionTag = null
                    scope.launch {
                        try {
                            val release = checker.checkLatestRelease()
                            if (release.tagName.isNotEmpty() && VersionUtils.isNewerVersion(release.tagName, versionName)) {
                                newVersionTag = release.tagName
                                updateStatus = "发现新版本 ${release.tagName}"
                            } else {
                                updateStatus = "当前已是最新版本"
                            }
                        } catch (e: NoReleaseException) {
                            updateStatus = e.message ?: "仓库尚未发布版本"
                        } catch (_: Exception) {
                            updateStatus = "检查更新失败，请检查网络"
                        } finally {
                            isChecking = false
                        }
                    }
                },
                enabled = !isChecking,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    if (isChecking) "正在检查更新..." else "检查更新",
                    modifier = Modifier.padding(vertical = 4.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            if (updateStatus != null) {
                Spacer(Modifier.height(8.dp))
                val status = updateStatus!!
                val isError = status.contains("失败") || status.contains("尚未")
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isError)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.primary
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    if (newVersionTag != null) {
        AlertDialog(
            onDismissRequest = { newVersionTag = null },
            title = { Text("发现新版本") },
            text = { Text("检测到新版本 ${newVersionTag}，请前往 GitHub 项目页查看更新内容与下载。") },
            confirmButton = {
                TextButton(onClick = { newVersionTag = null }) {
                    Text("知道了")
                }
            }
        )
    }
}

@Composable
private fun InfoCard(
    title: String,
    content: String,
    onClick: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Start
                )
            }
            if (onClick != null) {
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Default.OpenInNew,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: Exception) {
        // 无可用浏览器时忽略
    }
}

/** 将应用图标（可能是 AdaptiveIconDrawable）绘制为固定尺寸位图。 */
private fun renderAppIcon(context: Context): ImageBitmap {
    val drawable = context.packageManager.getApplicationIcon(context.packageName)
    val size = 96
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, size, size)
    drawable.draw(canvas)
    return bitmap.asImageBitmap()
}
