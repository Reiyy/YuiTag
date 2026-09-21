package com.reiyy.yuitag.viewmodel

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.reiyy.yuitag.data.DeviceRepository
import com.reiyy.yuitag.data.FindMyQueryService
import com.reiyy.yuitag.data.LocationQueryHelper
import com.reiyy.yuitag.data.NoEndpointException
import com.reiyy.yuitag.data.local.LocationEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException

/** 预设时间窗口（相对当前时间） */
enum class TimeWindow { LAST_24H, LAST_3D, LAST_7D, LAST_30D, ALL }

/** 轨迹范围：预设 / 自定义日期 / 手动起终点，三种来源互相切换（后操作覆盖） */
sealed class TrajectoryRange {
    data class Preset(val window: TimeWindow) : TrajectoryRange()
    data class CustomDates(val startTs: Long, val endTs: Long) : TrajectoryRange()
    data class ManualPoints(val startTs: Long?, val endTs: Long?) : TrajectoryRange()
}

/**
 * 轨迹页 ViewModel — 负责范围过滤、经纬度 4 位小数去重、升序轨迹点。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrajectoryViewModel(application: Application) : AndroidViewModel(application) {

    lateinit var repository: DeviceRepository
        private set

    private val queryHelper by lazy { LocationQueryHelper(repository, FindMyQueryService(getApplication())) }

    private val _uid = MutableStateFlow<String?>(null)

    private val _range = MutableStateFlow<TrajectoryRange>(TrajectoryRange.Preset(TimeWindow.ALL))
    val range: StateFlow<TrajectoryRange> = _range.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _deviceName = MutableStateFlow("YuiTag")
    val deviceName: StateFlow<String> = _deviceName.asStateFlow()

    private val rawLocations: Flow<List<LocationEntity>> = _uid
        .filterNotNull()
        .flatMapLatest { uid -> repository.getLocationHistoryFlow(uid) }

    /** 过滤 + 去重 + 升序后的轨迹点 */
    val trajectoryPoints: StateFlow<List<LocationEntity>> =
        combine(rawLocations, _range) { locations, range ->
            filterByRange(locations, range)
                .let { dedupeByRoundedCoordinate(it, 4) }
                .sortedBy { it.timestamp }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun initRepository(repo: DeviceRepository) {
        repository = repo
    }

    fun initialize(uid: String) {
        _uid.value = uid
        viewModelScope.launch {
            repository.boundDevicesCache.collect { cache ->
                _deviceName.value =
                    cache[uid]?.customName?.ifEmpty { "YuiTag_${uid.take(4)}" } ?: "YuiTag_${uid.take(4)}"
            }
        }
    }

    // --- 范围设置 ---

    fun selectPreset(window: TimeWindow) {
        _range.value = TrajectoryRange.Preset(window)
    }

    fun selectCustomDates(startTs: Long, endTs: Long) {
        _range.value = TrajectoryRange.CustomDates(minOf(startTs, endTs), maxOf(startTs, endTs))
    }

    fun setManualStart(ts: Long) {
        val cur = _range.value
        val start = if (cur is TrajectoryRange.ManualPoints && cur.startTs == ts) null else ts
        val end = if (cur is TrajectoryRange.ManualPoints) cur.endTs else null
        _range.value = TrajectoryRange.ManualPoints(start, end)
    }

    fun setManualEnd(ts: Long) {
        val cur = _range.value
        val start = if (cur is TrajectoryRange.ManualPoints) cur.startTs else null
        val end = if (cur is TrajectoryRange.ManualPoints && cur.endTs == ts) null else ts
        _range.value = TrajectoryRange.ManualPoints(start, end)
    }

    fun clearManual() {
        _range.value = TrajectoryRange.Preset(TimeWindow.ALL)
    }

    fun refresh(uid: String) {
        if (_isRefreshing.value) return
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                if (!queryHelper.hasQueryableKey(uid)) {
                    Toast.makeText(getApplication(), "设备未配置 FindMy 密钥", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                if (!queryHelper.hasEndpointConfigured) {
                    Toast.makeText(getApplication(), "请先在设置中配置后端地址", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val results = queryHelper.queryAndStore(uid)
                if (results.isNotEmpty()) {
                    Toast.makeText(getApplication(), "找到 ${results.size} 条位置记录", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(getApplication(), "未找到位置信息", Toast.LENGTH_SHORT).show()
                }
            } catch (e: NoEndpointException) {
                Toast.makeText(getApplication(), e.message ?: "未配置后端地址", Toast.LENGTH_SHORT).show()
            } catch (e: IOException) {
                Toast.makeText(getApplication(), "网络请求失败: ${e.message}", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(getApplication(), "查询失败: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    // --- 过滤 / 去重 ---

    private fun filterByRange(list: List<LocationEntity>, range: TrajectoryRange): List<LocationEntity> {
        return when (range) {
            is TrajectoryRange.Preset -> {
                val now = System.currentTimeMillis() / 1000
                val start = when (range.window) {
                    TimeWindow.LAST_24H -> now - 24 * 3600L
                    TimeWindow.LAST_3D -> now - 3 * 86400L
                    TimeWindow.LAST_7D -> now - 7 * 86400L
                    TimeWindow.LAST_30D -> now - 30 * 86400L
                    TimeWindow.ALL -> Long.MIN_VALUE
                }
                list.filter { it.timestamp >= start }
            }
            is TrajectoryRange.CustomDates ->
                list.filter { it.timestamp in range.startTs..range.endTs }
            is TrajectoryRange.ManualPoints ->
                list.filter {
                    (range.startTs == null || it.timestamp >= range.startTs) &&
                        (range.endTs == null || it.timestamp <= range.endTs)
                }
        }
    }

    /** 经纬度 4 位小数（≈11m 网格）去重，每组仅保留时间最新的一条 */
    private fun dedupeByRoundedCoordinate(list: List<LocationEntity>, decimals: Int = 4): List<LocationEntity> {
        val scale = Math.pow(10.0, decimals.toDouble())
        return list.groupBy { Math.round(it.latitude * scale) to Math.round(it.longitude * scale) }
            .values.mapNotNull { group -> group.maxByOrNull { it.timestamp } }
    }
}
