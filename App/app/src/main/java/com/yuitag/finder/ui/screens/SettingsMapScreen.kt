package com.yuitag.finder.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yuitag.finder.data.FindMyQueryService
import com.yuitag.finder.ui.components.SettingsCard
import com.yuitag.finder.ui.components.SettingsHeader
import com.yuitag.finder.ui.components.SettingsRadioPreference
import com.yuitag.finder.ui.components.SettingsTextPreference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsMapScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val service = remember { FindMyQueryService(context) }

    var amapKey by remember { mutableStateOf(service.getAmapKey()) }
    var tiandituKey by remember { mutableStateOf(service.getTiandituKey()) }
    var tiandituStyle by remember { mutableStateOf(service.getTiandituStyle()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("地图") },
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
                .padding(bottom = 24.dp)
        ) {
            SettingsHeader("逆地理编码")
            SettingsCard {
                SettingsTextPreference(
                    icon = Icons.Outlined.LocationOn,
                    title = "高德地图 Web 服务 Key",
                    subtitle = "用于地址反查",
                    value = amapKey,
                    placeholder = "在 console.amap.com 申请",
                    onSave = { amapKey = it; service.saveAmapKey(it) }
                )
            }

            SettingsHeader("地图底图")
            SettingsCard {
                SettingsTextPreference(
                    icon = Icons.Outlined.Map,
                    title = "天地图 Key",
                    subtitle = "填写后主界面使用天地图；不填回退 OpenStreetMap",
                    value = tiandituKey,
                    placeholder = "在 console.tianditu.gov.cn 申请",
                    onSave = { tiandituKey = it; service.saveTiandituKey(it) }
                )
                HorizontalDivider()
                SettingsRadioPreference(
                    icon = Icons.Outlined.Layers,
                    title = "底图样式",
                    options = listOf(
                        "vector" to "矢量地图",
                        "satellite" to "影像（卫星）地图",
                        "terrain" to "地形晕渲地图"
                    ),
                    selected = tiandituStyle,
                    onSelect = { tiandituStyle = it; service.saveTiandituStyle(it) }
                )
            }
        }
    }
}
