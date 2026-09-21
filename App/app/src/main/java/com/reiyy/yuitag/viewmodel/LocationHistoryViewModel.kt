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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * 位置历史页 ViewModel — 封装 FindMy 位置查询与设备名加载，移除 NavGraph 中的内联逻辑。
 */
class LocationHistoryViewModel(application: Application) : AndroidViewModel(application) {

    lateinit var repository: DeviceRepository
        private set

    private val queryHelper by lazy { LocationQueryHelper(repository, FindMyQueryService(getApplication())) }

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _deviceName = MutableStateFlow("YuiTag")
    val deviceName: StateFlow<String> = _deviceName.asStateFlow()

    fun initRepository(repo: DeviceRepository) {
        repository = repo
    }

    fun initialize(uid: String) {
        viewModelScope.launch {
            repository.boundDevicesCache.collect { cache ->
                _deviceName.value =
                    cache[uid]?.customName?.ifEmpty { "YuiTag_${uid.take(4)}" } ?: "YuiTag_${uid.take(4)}"
            }
        }
    }

    fun locationsFor(uid: String): Flow<List<LocationEntity>> = repository.getLocationHistoryFlow(uid)

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
}
