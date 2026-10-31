package com.reiyy.yuitag.data

import com.reiyy.yuitag.ble.FileLog
import com.reiyy.yuitag.data.local.LocationEntity
import com.reiyy.yuitag.util.GeocodeAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * 逆地理编码补全进度状态
 */
sealed interface BackfillProgress {
    object Idle : BackfillProgress
    data class Running(val current: Int, val total: Int) : BackfillProgress
    data class Completed(val resolvedCount: Int, val totalUnresolved: Int) : BackfillProgress
    data class Error(val message: String) : BackfillProgress
}

/**
 * 历史位置逆地理编码后台限速补全调度器。
 *
 * 核心策略：
 * 1. 空间网格聚类（按保留 3 位小数约 100m 聚类，同网格一次请求批量更新全部实体）
 * 2. 优先本地缓存（无网络请求、零延迟批量回写）
 * 3. 严格 QPS 控制（每次网络请求间隔约 750ms，保持在 1~1.5 QPS 安全阈值内）
 * 4. 连续全量推进（取消单次 30 个的硬编码截断，根据待处理网格持续平滑解析直至全部完成）
 * 5. 防死循环机制（记录无数据/不可解析的网格，避免对境外、海域等无效坐标反复刷接口）
 * 6. 熔断保护（连续多次网络异常时及时中止，避免耗尽配额）
 */
class GeocodeBackfillHelper(
    private val repository: DeviceRepository,
    private val queryService: FindMyQueryService
) {
    companion object {
        private const val TAG = "GeocodeBackfill"
        /** 网络请求安全间隔（毫秒），保持在约 1~1.5 QPS 安全阈值内 */
        const val REQUEST_INTERVAL_MS = 750L
        /** 触发熔断保护的连续网络失败次数上限 */
        private const val MAX_CONSECUTIVE_ERRORS = 4
    }

    private val mutex = Mutex()

    private val _progress = MutableStateFlow<BackfillProgress>(BackfillProgress.Idle)
    val progress: StateFlow<BackfillProgress> = _progress.asStateFlow()

    // 内存中记录不可解析（无数据/境外/海域）的网格，避免本会话反复无谓重试刷接口
    private val failedGrids = mutableSetOf<String>()

    /** 空间网格 Key（保留 3 位小数，约 100m 精度） */
    fun spatialGridKey(lat: Double, lon: Double): String =
        "${"%.3f".format(lat)}_${"%.3f".format(lon)}"

    /** 清空失败网格黑名单（例如用户重新配置了 Key 或手动强制重查时） */
    fun resetFailedGrids() {
        failedGrids.clear()
    }

    /**
     * 执行指定设备的地址补全任务。
     * @param deviceUid 设备 UID
     * @param maxNetworkGrids 最大网络请求网格数（默认为全部，不再强制硬截断）
     * @param forceNetwork 是否允许发起网络请求（false 时仅读本地缓存快速回写）
     * @return 实际解析并更新的记录数
     */
    suspend fun backfill(
        deviceUid: String,
        maxNetworkGrids: Int = Int.MAX_VALUE,
        forceNetwork: Boolean = true
    ): Int = withContext(Dispatchers.IO) {
        if (!mutex.tryLock()) {
            FileLog.d(TAG, "已有一个补全任务正在运行，跳过重复触发: $deviceUid")
            return@withContext 0
        }
        try {
            val unresolved = repository.getUnresolvedLocations(deviceUid)
            if (unresolved.isEmpty()) {
                _progress.value = BackfillProgress.Completed(0, 0)
                return@withContext 0
            }

            FileLog.d(TAG, "开始补全 $deviceUid 的地址，未解析记录数: ${unresolved.size}")

            val currentProvider = queryService.getCurrentGeocodeProvider()
            val fallbackProvider = queryService.getFallbackGeocodeProvider()

            // 1. 空间网格分组
            val gridGroups = unresolved.groupBy { spatialGridKey(it.latitude, it.longitude) }

            var resolvedRecordCount = 0
            val pendingNetworkGrids = mutableListOf<Pair<String, List<LocationEntity>>>()

            // 2. 第一轮：快速检查本地缓存（内存+磁盘），若命中直接回写 Room，不发网络请求
            for ((gridKey, items) in gridGroups) {
                val sample = items.first()
                val cached = currentProvider.getCached(sample.latitude, sample.longitude)
                    ?: fallbackProvider.getCached(sample.latitude, sample.longitude)

                if (cached != null) {
                    val display = cached.display()
                    val detail = cached.toAddressComponents()
                    val ids = items.map { it.id }
                    repository.updateLocationAddressByIds(ids, display, detail)
                    resolvedRecordCount += items.size
                    FileLog.d(TAG, "网格 $gridKey 命中缓存，直接批量补全 ${items.size} 条记录: $display")
                } else if (gridKey !in failedGrids) {
                    pendingNetworkGrids.add(gridKey to items)
                }
            }

            if (!forceNetwork || pendingNetworkGrids.isEmpty()) {
                _progress.value = BackfillProgress.Completed(resolvedRecordCount, unresolved.size)
                return@withContext resolvedRecordCount
            }

            // 3. 第二轮：需要网络解析的网格，按 QPS 节流调度连续全量执行
            val gridsToFetch = if (maxNetworkGrids < Int.MAX_VALUE) {
                pendingNetworkGrids.take(maxNetworkGrids)
            } else {
                pendingNetworkGrids
            }
            val totalGrids = gridsToFetch.size
            var consecutiveErrors = 0

            for ((index, pair) in gridsToFetch.withIndex()) {
                if (!coroutineContext.isActive) {
                    FileLog.d(TAG, "补全任务被取消")
                    break
                }

                _progress.value = BackfillProgress.Running(current = index + 1, total = totalGrids)
                val (gridKey, items) = pair
                val sample = items.first()

                try {
                    val addr: GeocodeAddress? = currentProvider.lookup(sample.latitude, sample.longitude)
                        ?: fallbackProvider.lookup(sample.latitude, sample.longitude)

                    if (addr != null && addr.display().isNotBlank()) {
                        consecutiveErrors = 0
                        val display = addr.display()
                        val detail = addr.toAddressComponents()
                        val ids = items.map { it.id }
                        repository.updateLocationAddressByIds(ids, display, detail)
                        resolvedRecordCount += items.size
                        FileLog.d(TAG, "网格 $gridKey [${index + 1}/$totalGrids] 网络解析成功，批量补全 ${items.size} 条: $display")
                    } else {
                        // 返回空结果（如坐标在海里、境外或服务商无数据），记入黑名单防死循环刷接口
                        failedGrids.add(gridKey)
                        consecutiveErrors++
                        FileLog.w(TAG, "网格 $gridKey [${index + 1}/$totalGrids] 解析无结果，已记录防死循环 (连续失败 $consecutiveErrors)")
                    }
                } catch (e: Exception) {
                    consecutiveErrors++
                    FileLog.w(TAG, "网格 $gridKey 网络请求异常: ${e.message}")
                }

                // 熔断保护：连续失败达到上限（通常是 Key 无效、配额耗尽 403/429 或完全断网），及时中止
                if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                    FileLog.w(TAG, "连续多次解析失败，触发熔断中止当前批次")
                    _progress.value = BackfillProgress.Error("请求连续失败，已暂停解析（请检查网络或地图 Key）")
                    return@withContext resolvedRecordCount
                }

                // 安全限流延迟
                if (index < totalGrids - 1) {
                    delay(REQUEST_INTERVAL_MS)
                }
            }

            _progress.value = BackfillProgress.Completed(resolvedRecordCount, unresolved.size)
            return@withContext resolvedRecordCount
        } catch (e: Exception) {
            FileLog.w(TAG, "补全流程发生未知异常: ${e.message}", e)
            _progress.value = BackfillProgress.Error(e.message ?: "未知异常")
            return@withContext 0
        } finally {
            mutex.unlock()
        }
    }

    /** 重置状态为 Idle */
    fun resetProgress() {
        _progress.value = BackfillProgress.Idle
    }
}
