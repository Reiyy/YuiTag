package com.reiyy.yuitag.viewmodel

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.reiyy.yuitag.data.BackfillProgress
import com.reiyy.yuitag.data.DeviceRepository
import com.reiyy.yuitag.data.FindMyQueryService
import com.reiyy.yuitag.data.GeocodeBackfillHelper
import com.reiyy.yuitag.data.LocationQueryHelper
import com.reiyy.yuitag.data.NoEndpointException
import com.reiyy.yuitag.data.AppleSessionExpiredException
import com.reiyy.yuitag.data.BackendAuthException
import com.reiyy.yuitag.data.BackendServerException
import com.reiyy.yuitag.data.local.LocationEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * 位置历史页 ViewModel — 封装 FindMy 位置查询、设备名加载与缺失地址后台补全。
 */
class LocationHistoryViewModel(application: Application) : AndroidViewModel(application) {

    lateinit var repository: DeviceRepository
        private set

    private val queryService by lazy { FindMyQueryService(getApplication()) }
    private val queryHelper by lazy { LocationQueryHelper(repository, queryService) }
    private val backfillHelper by lazy { GeocodeBackfillHelper(repository, queryService) }

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _deviceName = MutableStateFlow("YuiTag")
    val deviceName: StateFlow<String> = _deviceName.asStateFlow()

    val backfillProgress: StateFlow<BackfillProgress> by lazy { backfillHelper.progress }

    fun initRepository(repo: DeviceRepository) {
        repository = repo
    }

    private var initializedUid: String? = null

    fun initialize(uid: String) {
        if (initializedUid == uid) return
        initializedUid = uid

        viewModelScope.launch {
            repository.boundDevicesCache.collect { cache ->
                _deviceName.value =
                    cache[uid]?.customName?.ifEmpty { "YuiTag_${uid.take(4)}" } ?: "YuiTag_${uid.take(4)}"
            }
        }
        // 打开页面时自动检测存量未解析数据，静默启动后台限流补全
        viewModelScope.launch {
            delay(500)
            if (repository.getUnresolvedLocationCount(uid) > 0) {
                backfillHelper.backfill(uid, forceNetwork = true)
            }
        }
    }

    fun locationsFor(uid: String): Flow<List<LocationEntity>> = repository.getLocationHistoryFlow(uid)

    /** 用户手动触发补全缺失地址 */
    fun triggerBackfill(uid: String) {
        if (backfillProgress.value is BackfillProgress.Running) {
            Toast.makeText(getApplication(), "地址解析正在进行中...", Toast.LENGTH_SHORT).show()
            return
        }
        viewModelScope.launch {
            val count = repository.getUnresolvedLocationCount(uid)
            if (count == 0) {
                Toast.makeText(getApplication(), "所有位置均已解析地址", Toast.LENGTH_SHORT).show()
                return@launch
            }
            backfillHelper.resetFailedGrids()
            Toast.makeText(getApplication(), "正在解析 $count 条未完成位置...", Toast.LENGTH_SHORT).show()
            val resolved = backfillHelper.backfill(uid, forceNetwork = true)
            if (resolved > 0) {
                Toast.makeText(getApplication(), "已补全 $resolved 条记录的地址", Toast.LENGTH_SHORT).show()
            }
        }
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
                    val msg = if (queryService.isAppleDirect()) "请先在设置中登录 Apple ID" else "请先在设置中配置后端地址"
                    Toast.makeText(getApplication(), msg, Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val results = queryHelper.queryAndStore(uid)
                if (results.isNotEmpty()) {
                    Toast.makeText(getApplication(), "找到 ${results.size} 条位置记录", Toast.LENGTH_SHORT).show()
                    // 增量位置入库后，自动启动后台限流补全
                    backfillHelper.backfill(uid, forceNetwork = true)
                } else {
                    Toast.makeText(getApplication(), "未找到位置信息", Toast.LENGTH_SHORT).show()
                }
            } catch (e: NoEndpointException) {
                Toast.makeText(getApplication(), e.message ?: "未配置后端地址", Toast.LENGTH_SHORT).show()
            } catch (e: AppleSessionExpiredException) {
                Toast.makeText(getApplication(), e.message ?: "Apple ID 登录失效，请前往设置重新登录", Toast.LENGTH_LONG).show()
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
}
