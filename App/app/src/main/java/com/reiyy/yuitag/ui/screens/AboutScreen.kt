package com.reiyy.yuitag.ui.screens

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.AltRoute
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import com.reiyy.yuitag.data.GithubRelease
import com.reiyy.yuitag.data.GithubReleaseChecker
import com.reiyy.yuitag.data.NoReleaseException
import com.reiyy.yuitag.data.NotificationRepository
import com.reiyy.yuitag.data.UpdateChannel
import com.reiyy.yuitag.ui.components.SettingsCard
import com.reiyy.yuitag.ui.components.SettingsHeader
import com.reiyy.yuitag.ui.components.SettingsRadioPreference
import com.reiyy.yuitag.ui.components.SettingsRow
import com.reiyy.yuitag.util.VersionUtils
import kotlinx.coroutines.launch

private const val GITHUB_REPO_URL = "https://github.com/Reiyy/YuiTag"
private const val APP_LICENSE_URL = "https://github.com/Reiyy/YuiTag/blob/main/App/LICENSE"

/**
 * 关于页 — App 信息、版本检查、开源协议、第三方许可与项目致谢。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    onNavigateToLicenses: () -> Unit = {}
) {
    val context = LocalContext.current
    val (versionName, versionCode) = remember {
        try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val name = pInfo.versionName ?: "1.0.0"
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode.toLong()
            }
            name to code
        } catch (_: Exception) {
            "1.0.0" to 1L
        }
    }

    val checker = remember { GithubReleaseChecker() }
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("yuitag_settings", Context.MODE_PRIVATE) }
    var updateChannel by remember {
        val saved = prefs.getString(UpdateChannel.PREF_KEY, null)
        val initial = if (saved != null) {
            UpdateChannel.fromKey(saved)
        } else if (VersionUtils.isPrerelease(versionName)) {
            UpdateChannel.BETA
        } else {
            UpdateChannel.RELEASE
        }
        mutableStateOf(initial)
    }
    var isChecking by remember { mutableStateOf(false) }
    var updateStatus by remember { mutableStateOf<String?>(null) }
    var newVersionTag by remember { mutableStateOf<String?>(null) }
    var newReleaseInfo by remember { mutableStateOf<GithubRelease?>(null) }

    // 将 AdaptiveIconDrawable 栅格化为位图展示
    val appIcon = remember {
        try {
            renderAppIcon(context)
        } catch (_: Exception) {
            null
        }
    }

    fun onUpdateChannelChanged(channel: UpdateChannel) {
        updateChannel = channel
        prefs.edit().putString(UpdateChannel.PREF_KEY, channel.key).apply()
        updateStatus = null
        newVersionTag = null
        newReleaseInfo = null
    }

    fun doCheckUpdate() {
        if (isChecking) return
        isChecking = true
        updateStatus = "正在检查更新 (${updateChannel.title})..."
        newVersionTag = null
        newReleaseInfo = null
        scope.launch {
            try {
                val release = checker.checkLatestRelease(updateChannel)
                if (release.tagName.isNotEmpty() && VersionUtils.isNewerVersion(release.tagName, versionName)) {
                    newVersionTag = release.tagName
                    newReleaseInfo = release
                    val typeLabel = if (release.isPrerelease) "开发版" else "正式版"
                    updateStatus = "发现新版本 ${release.tagName} ($typeLabel)"
                    NotificationRepository.getInstance(context)
                        .postAppUpdateNotification(release.tagName, release.body)
                } else {
                    updateStatus = "当前已是最新版本 (${updateChannel.title})"
                }
            } catch (e: NoReleaseException) {
                updateStatus = e.message ?: "仓库尚未发布版本"
            } catch (e: Exception) {
                updateStatus = e.message ?: "检查更新失败，请检查网络"
            } finally {
                isChecking = false
            }
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
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(24.dp))

            // App 图标与品牌信息
            if (appIcon != null) {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    tonalElevation = 2.dp,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Image(
                        bitmap = appIcon,
                        contentDescription = "YuiTag 图标",
                        modifier = Modifier
                            .size(84.dp)
                            .clip(RoundedCornerShape(22.dp))
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            Text(
                text = "YuiTag",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(4.dp))

            Text(
                text = "版本 $versionName ($versionCode)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(4.dp))

            Text(
                text = "Yuitwork Labs",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp)
            )

            Spacer(Modifier.height(16.dp))

            // 版本与更新
            SettingsHeader("更新")
            SettingsCard {
                SettingsRadioPreference(
                    icon = Icons.Outlined.AltRoute,
                    title = "更新通道",
                    subtitle = updateChannel.subtitle,
                    options = listOf(
                        UpdateChannel.RELEASE to UpdateChannel.RELEASE.title,
                        UpdateChannel.BETA to UpdateChannel.BETA.title
                    ),
                    selected = updateChannel,
                    onSelect = { onUpdateChannelChanged(it) }
                )
                HorizontalDivider()
                SettingsRow(
                    icon = Icons.Outlined.Sync,
                    title = "检查新版本",
                    subtitle = updateStatus ?: "当前通道: ${updateChannel.title}",
                    trailing = {
                        if (isChecking) {
                            Box(
                                modifier = Modifier.size(36.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.5.dp
                                )
                            }
                        } else {
                            FilledTonalButton(
                                onClick = { doCheckUpdate() },
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Text("检查", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    },
                    onClick = { doCheckUpdate() }
                )
            }

            // 开源与协议
            SettingsHeader("开源相关")
            SettingsCard {
                SettingsRow(
                    icon = Icons.Outlined.Code,
                    title = "项目仓库",
                    subtitle = "https://github.com/Reiyy/YuiTag",
                    trailing = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    onClick = { openUrl(context, GITHUB_REPO_URL) }
                )
                HorizontalDivider()
                SettingsRow(
                    icon = Icons.Outlined.Gavel,
                    title = "许可证",
                    subtitle = "GPL-2.0-or-later",
                    trailing = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    onClick = { openUrl(context, APP_LICENSE_URL) }
                )
                HorizontalDivider()
                SettingsRow(
                    icon = Icons.AutoMirrored.Outlined.Article,
                    title = "第三方开源库许可",
                    trailing = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                    },
                    onClick = onNavigateToLicenses
                )
            }

            // 致谢
            SettingsHeader("致谢")
            SettingsCard {
                SettingsRow(
                    icon = Icons.Outlined.FavoriteBorder,
                    title = "macless-haystack",
                    subtitle = "macless-haystack 后端与 FindMy 协议参考",
                    trailing = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    onClick = { openUrl(context, "https://gitee.com/lovelyelfpop/macless-haystack") }
                )
                HorizontalDivider()
                SettingsRow(
                    icon = Icons.Outlined.FavoriteBorder,
                    title = "openhaystack-firmware",
                    subtitle = "FindMy 固件编写与数据格式参考",
                    trailing = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    onClick = { openUrl(context, "https://github.com/acalatrava/openhaystack-firmware") }
                )
                HorizontalDivider()
                SettingsRow(
                    icon = Icons.Outlined.FavoriteBorder,
                    title = "starSchedule",
                    subtitle = "魅族 Flyme 系统实况通知实现参考",
                    trailing = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    onClick = { openUrl(context, "https://github.com/lightStarrr/starSchedule") }
                )
                HorizontalDivider()
                SettingsRow(
                    icon = Icons.Outlined.FavoriteBorder,
                    title = "TagHistory",
                    subtitle = "本机直连位置查询与协议交互参考，以及 JNI 运行库",
                    trailing = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    onClick = { openUrl(context, "https://github.com/tieo/TagHistory") }
                )
                HorizontalDivider()
                SettingsRow(
                    icon = Icons.Outlined.FavoriteBorder,
                    title = "os-findmy-e",
                    subtitle = "CoreADI 提取方案与 ADI 模拟实现参考",
                    trailing = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    onClick = { openUrl(context, "https://github.com/sacca97/os-findmy-e") }
                )
            }

            Spacer(Modifier.height(32.dp))

            // 底部版权信息
            Text(
                text = "Designed by Yuitwork in China",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "K - ON! =w=",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )

            Spacer(Modifier.height(32.dp))
        }
    }

    if (newVersionTag != null) {
        val release = newReleaseInfo
        val isPre = release?.isPrerelease == true || VersionUtils.isPrerelease(newVersionTag!!)
        val typeBadge = if (isPre) "开发版" else "正式版"
        AlertDialog(
            onDismissRequest = {
                newVersionTag = null
                newReleaseInfo = null
            },
            title = { Text("发现新版本 ($typeBadge)") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        text = "检测到新版本 $newVersionTag",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    val notes = release?.body?.trim()
                    if (!notes.isNullOrEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "更新说明：",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = if (notes.length > 200) notes.take(200) + "..." else notes,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val url = release?.htmlUrl?.ifEmpty { null } ?: "$GITHUB_REPO_URL/releases"
                    openUrl(context, url)
                    newVersionTag = null
                    newReleaseInfo = null
                }) {
                    Text("前往更新")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    newVersionTag = null
                    newReleaseInfo = null
                }) {
                    Text("稍后")
                }
            }
        )
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
