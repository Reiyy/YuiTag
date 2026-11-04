package com.reiyy.yuitag.viewmodel

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.reiyy.yuitag.data.DeviceRepository
import com.reiyy.yuitag.data.FindMyQueryService
import com.reiyy.yuitag.data.GeocodeBackfillHelper
import com.reiyy.yuitag.data.LocationQueryHelper
import com.reiyy.yuitag.data.NoEndpointException
import com.reiyy.yuitag.data.AppleSessionExpiredException
import com.reiyy.yuitag.data.BackendAuthException
import com.reiyy.yuitag.data.BackendServerException
import com.reiyy.yuitag.data.local.LocationEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/** 预设时间窗口（相对当前时间） */
enum class TimeWindow { LAST_24H, LAST_3D, LAST_7D, LAST_30D, ALL }

/** 轨迹范围：预设 / 自定义日期 / 手动起终点，三种来源互相切换（后操作覆盖） */
sealed class TrajectoryRange {
    data class Preset(val window: TimeWindow) : TrajectoryRange()
    data class CustomDates(val startTs: Long, val endTs: Long) : TrajectoryRange()
    data class ManualPoints(val startTs: Long?, val endTs: Long?) : TrajectoryRange()
}

/** 轨迹统计摘要信息 */
data class TrajectorySummary(
    val rawCount: Int = 0,
    val displayCount: Int = 0,
    val totalDistanceMeters: Double = 0.0,
    val startTimestamp: Long? = null,
    val endTimestamp: Long? = null,
    val gapSegmentCount: Int = 0
)

/**
 * 轨迹页 ViewModel — 负责范围过滤、时序连续停留折叠去重、升序轨迹点及统计摘要。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrajectoryViewModel(application: Application) : AndroidViewModel(application) {

    lateinit var repository: DeviceRepository
        private set

    private val queryService by lazy { FindMyQueryService(getApplication()) }
    private val queryHelper by lazy { LocationQueryHelper(repository, queryService) }
    private val backfillHelper by lazy { GeocodeBackfillHelper(repository, queryService) }

    private val _uid = MutableStateFlow<String?>(null)

    private val _range = MutableStateFlow<TrajectoryRange>(TrajectoryRange.Preset(TimeWindow.ALL))
    val range: StateFlow<TrajectoryRange> = _range.asStateFlow()

    private val _isDeduplicateEnabled = MutableStateFlow(true)
    val isDeduplicateEnabled: StateFlow<Boolean> = _isDeduplicateEnabled.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _deviceName = MutableStateFlow("YuiTag")
    val deviceName: StateFlow<String> = _deviceName.asStateFlow()

    private val _resolvingAddressIds = MutableStateFlow<Set<Long>>(emptySet())
    val resolvingAddressIds: StateFlow<Set<Long>> = _resolvingAddressIds.asStateFlow()

    private val _failedAddressIds = MutableStateFlow<Set<Long>>(emptySet())
    val failedAddressIds: StateFlow<Set<Long>> = _failedAddressIds.asStateFlow()

    private val rawLocations: Flow<List<LocationEntity>> = _uid
        .filterNotNull()
        .flatMapLatest { uid -> repository.getLocationHistoryFlow(uid) }

    /** 过滤 + 连续停留去重 + 升序后的轨迹点 */
    val trajectoryPoints: StateFlow<List<LocationEntity>> =
        combine(rawLocations, _range, _isDeduplicateEnabled) { locations, range, dedupe ->
            val filtered = filterByRange(locations, range).sortedBy { it.timestamp }
            if (dedupe) dedupeConsecutiveStays(filtered, 4) else filtered
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 当前范围的轨迹统计摘要（原始点数、去重后点数、总里程、时间跨度、断层段数） */
    val summary: StateFlow<TrajectorySummary> =
        combine(rawLocations, _range, _isDeduplicateEnabled) { locations, range, dedupe ->
            val filtered = filterByRange(locations, range).sortedBy { it.timestamp }
            val displayed = if (dedupe) dedupeConsecutiveStays(filtered, 4) else filtered
            var totalDist = 0.0
            var gaps = 0
            for (i in 0 until displayed.size - 1) {
                val a = displayed[i]
                val b = displayed[i + 1]
                val d = haversineMeters(a.latitude, a.longitude, b.latitude, b.longitude)
                totalDist += d
                val dt = b.timestamp - a.timestamp
                if (dt >= TIME_GAP_THRESHOLD_SEC && d >= TIME_GAP_MIN_DISTANCE_M) {
                    gaps++
                }
            }
            TrajectorySummary(
                rawCount = filtered.size,
                displayCount = displayed.size,
                totalDistanceMeters = totalDist,
                startTimestamp = displayed.firstOrNull()?.timestamp,
                endTimestamp = displayed.lastOrNull()?.timestamp,
                gapSegmentCount = gaps
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TrajectorySummary())

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
        // 进入轨迹页时自动检测并后台补全未解析的位置地址
        viewModelScope.launch {
            delay(300)
            if (repository.getUnresolvedLocationCount(uid) > 0) {
                backfillHelper.backfill(uid, forceNetwork = true)
            }
        }
    }

    /** 当用户选中尚未解析地址的轨迹点时，优先即时解析该点地址并回写数据库；若境外/无数据无法解析则标记失败兜底展示经纬度 */
    fun ensureAddressResolved(point: LocationEntity) {
        if (!point.address.isNullOrBlank()) return
        if (point.id in _failedAddressIds.value || point.id in _resolvingAddressIds.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _resolvingAddressIds.update { it + point.id }
            try {
                val currentProvider = queryService.getCurrentGeocodeProvider()
                val fallbackProvider = queryService.getFallbackGeocodeProvider()
                val addr = currentProvider.getCached(point.latitude, point.longitude)
                    ?: fallbackProvider.getCached(point.latitude, point.longitude)
                    ?: currentProvider.lookup(point.latitude, point.longitude)
                    ?: fallbackProvider.lookup(point.latitude, point.longitude)
                val display = addr?.display()?.takeIf { it.isNotBlank() }
                if (display != null) {
                    repository.updateLocationAddress(point.id, display, addr.toAddressComponents())
                } else {
                    _failedAddressIds.update { it + point.id }
                }
            } catch (_: Exception) {
                _failedAddressIds.update { it + point.id }
            } finally {
                _resolvingAddressIds.update { it - point.id }
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
        _range.value = if (start == null && end == null) {
            TrajectoryRange.Preset(TimeWindow.ALL)
        } else {
            TrajectoryRange.ManualPoints(start, end)
        }
    }

    fun setManualEnd(ts: Long) {
        val cur = _range.value
        val start = if (cur is TrajectoryRange.ManualPoints) cur.startTs else null
        val end = if (cur is TrajectoryRange.ManualPoints && cur.endTs == ts) null else ts
        _range.value = if (start == null && end == null) {
            TrajectoryRange.Preset(TimeWindow.ALL)
        } else {
            TrajectoryRange.ManualPoints(start, end)
        }
    }

    fun clearManual() {
        _range.value = TrajectoryRange.Preset(TimeWindow.ALL)
    }

    fun toggleDeduplicate(): Boolean {
        val next = !_isDeduplicateEnabled.value
        _isDeduplicateEnabled.value = next
        return next
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
                    backfillHelper.backfill(uid, forceNetwork = true)
                } else {
                    Toast.makeText(getApplication(), "未找到位置信息", Toast.LENGTH_SHORT).show()
                }
            } catch (e: NoEndpointException) {
                Toast.makeText(getApplication(), e.message ?: "未配置后端地址", Toast.LENGTH_SHORT).show()
            } catch (e: AppleSessionExpiredException) {
                Toast.makeText(getApplication(), "AppleID 登录失效: 请在后端服务重新登录", Toast.LENGTH_LONG).show()
            } catch (e: BackendAuthException) {
                Toast.makeText(getApplication(), e.message ?: "后端认证失败 (HTTP 401)", Toast.LENGTH_LONG).show()
            } catch (e: BackendServerException) {
                Toast.makeText(getApplication(), "后端服务异常 (HTTP ${e.statusCode})", Toast.LENGTH_LONG).show()
            } catch (e: IOException) {
                Toast.makeText(getApplication(), "网络请求失败: ${e.message}", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(getApplication(), "查询失败: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    // --- 过滤 / 连续停留去重 ---

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

    /**
     * 智能停留聚类 + 乒乓折返（A→B→A）漂移滤波：
     * 1. 按两点定位精度自适应合并半径（65m ~ 180m）将同一建筑/街区内的连续停留点聚合成单一节点；
     * 2. 识别短时间或低精度下从 A 跳到 B 又立即回到 A 的单点信号漂移毛刺并滤除；
     * 3. 再次合并相邻同位置簇，在彻底消除原地放射状杂乱直线的同时，完整保留真实的跨区域往返行程。
     */
    private fun dedupeConsecutiveStays(sortedAsc: List<LocationEntity>, @Suppress("UNUSED_PARAMETER") decimals: Int = 4): List<LocationEntity> {
        if (sortedAsc.size <= 1) return sortedAsc

        val pass1 = mergeConsecutiveByRadius(sortedAsc)
        if (pass1.size <= 2) return pass1

        val despiked = filterPingPongSpikes(pass1)
        return mergeConsecutiveByRadius(despiked)
    }

    private fun mergeConsecutiveByRadius(points: List<LocationEntity>): List<LocationEntity> {
        if (points.size <= 1) return points
        val clusters = mutableListOf<MutableList<LocationEntity>>()

        for (loc in points) {
            if (clusters.isEmpty()) {
                clusters.add(mutableListOf(loc))
                continue
            }
            val currentCluster = clusters.last()
            // 使用簇内精度最高（accuracy 最小）的点作为位置锚点
            val anchor = currentCluster.minByOrNull { it.accuracy.coerceAtLeast(1) } ?: currentCluster.first()
            val dist = haversineMeters(anchor.latitude, anchor.longitude, loc.latitude, loc.longitude)
            val radius = maxOf(65.0, (anchor.accuracy + loc.accuracy) * 0.85).coerceAtMost(180.0)

            if (dist <= radius) {
                currentCluster.add(loc)
            } else {
                clusters.add(mutableListOf(loc))
            }
        }

        return clusters.mapIndexed { idx, cluster ->
            val fallbackAddr = cluster.firstNotNullOfOrNull { it.address?.takeIf { s -> s.isNotBlank() } }
            when {
                cluster.size == 1 -> cluster.first()
                idx == 0 -> {
                    val best = cluster.minByOrNull { it.accuracy.coerceAtLeast(1) } ?: cluster.first()
                    cluster.first().copy(
                        latitude = best.latitude,
                        longitude = best.longitude,
                        accuracy = best.accuracy,
                        address = best.address?.takeIf { it.isNotBlank() } ?: fallbackAddr
                    )
                }
                idx == clusters.lastIndex -> {
                    val best = cluster.minByOrNull { it.accuracy.coerceAtLeast(1) } ?: cluster.last()
                    cluster.last().copy(
                        latitude = best.latitude,
                        longitude = best.longitude,
                        accuracy = best.accuracy,
                        address = best.address?.takeIf { it.isNotBlank() } ?: fallbackAddr
                    )
                }
                else -> {
                    val best = cluster.minByOrNull { it.accuracy.coerceAtLeast(1) } ?: cluster.first()
                    if (!best.address.isNullOrBlank() || fallbackAddr == null) best
                    else best.copy(address = fallbackAddr)
                }
            }
        }
    }

    private fun filterPingPongSpikes(points: List<LocationEntity>): List<LocationEntity> {
        if (points.size < 3) return points
        val out = mutableListOf<LocationEntity>()
        var i = 0
        while (i < points.size) {
            if (out.isNotEmpty() && i < points.lastIndex) {
                val prev = out.last()
                val curr = points[i]
                val next = points[i + 1]

                val distPrevNext = haversineMeters(prev.latitude, prev.longitude, next.latitude, next.longitude)
                val sameBaseRadius = maxOf(85.0, (prev.accuracy + next.accuracy).toDouble()).coerceAtMost(200.0)

                if (distPrevNext <= sameBaseRadius) {
                    val distSpike = haversineMeters(prev.latitude, prev.longitude, curr.latitude, curr.longitude)
                    val dtTotal = (next.timestamp - prev.timestamp).coerceAtLeast(0L)
                    val isLowAccDrift = curr.accuracy >= 75 && distSpike <= curr.accuracy * 3.2
                    val isQuickBounce = dtTotal <= 45 * 60L && distSpike <= 1200.0
                    if (isLowAccDrift || isQuickBounce) {
                        // 跳过单点乒乓漂移 curr
                        i++
                        continue
                    }
                }
            }
            out.add(points[i])
            i++
        }
        return out
    }

    companion object {
        /** 相邻定位点超过 2 小时且距离超过 500 米时视为非连续时间断层（以虚线绘制） */
        const val TIME_GAP_THRESHOLD_SEC = 2 * 3600L
        const val TIME_GAP_MIN_DISTANCE_M = 500.0

        /** 计算两点间大圆距离（米） */
        fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6371000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2).pow(2.0) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2.0)
            val c = 2 * atan2(sqrt(a), sqrt((1.0 - a).coerceAtLeast(0.0)))
            return r * c
        }
    }
}
