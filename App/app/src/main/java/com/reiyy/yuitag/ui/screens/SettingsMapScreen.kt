package com.reiyy.yuitag.ui.screens

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
import com.reiyy.yuitag.data.FindMyQueryService
import com.reiyy.yuitag.ui.components.SettingsCard
import com.reiyy.yuitag.ui.components.SettingsHeader
import com.reiyy.yuitag.ui.components.SettingsRadioPreference
import com.reiyy.yuitag.ui.components.SettingsTextPreference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsMapScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val service = remember { FindMyQueryService(context) }

    var geocodeProvider by remember { mutableStateOf(service.getGeocodeProvider()) }
    var amapKey by remember { mutableStateOf(service.getAmapKey()) }
    var tiandituGeocodeKey by remember { mutableStateOf(service.getTiandituGeocodeKey()) }
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
                SettingsRadioPreference(
                    icon = Icons.Outlined.LocationOn,
                    title = "服务商",
                    options = listOf(
                        FindMyQueryService.GEOCODE_PROVIDER_AMAP to "高德地图",
                        FindMyQueryService.GEOCODE_PROVIDER_TIANDITU to "天地图"
                    ),
                    selected = geocodeProvider,
                    onSelect = {
                        geocodeProvider = it
                        service.saveGeocodeProvider(it)
                    }
                )
                HorizontalDivider()
                if (geocodeProvider == FindMyQueryService.GEOCODE_PROVIDER_AMAP) {
                    SettingsTextPreference(
                        icon = Icons.Outlined.LocationOn,
                        title = "高德地图 Web 服务 Key",
                        subtitle = "用于地址反查",
                        value = amapKey,
                        placeholder = "在 console.amap.com 申请",
                        onSave = { amapKey = it; service.saveAmapKey(it) }
                    )
                } else {
                    SettingsTextPreference(
                        icon = Icons.Outlined.LocationOn,
                        title = "天地图 Key（浏览器端）",
                        subtitle = "用于地址反查；需在控制台申请「浏览器端」Key",
                        value = tiandituGeocodeKey,
                        placeholder = "在 console.tianditu.gov.cn 申请浏览器端 Key",
                        onSave = { tiandituGeocodeKey = it; service.saveTiandituGeocodeKey(it) }
                    )
                }
            }

            SettingsHeader("地图底图")
            SettingsCard {
                SettingsTextPreference(
                    icon = Icons.Outlined.Map,
                    title = "天地图 Key（服务端）",
                    subtitle = "用于加载底图；需在控制台申请「服务端」Key；不填回退 OpenStreetMap",
                    value = tiandituKey,
                    placeholder = "在 console.tianditu.gov.cn 申请服务端 Key",
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
