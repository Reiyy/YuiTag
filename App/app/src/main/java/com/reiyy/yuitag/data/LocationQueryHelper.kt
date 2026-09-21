package com.reiyy.yuitag.data

import com.reiyy.yuitag.ble.CryptoUtils
import com.reiyy.yuitag.data.local.LocationEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 封装 Find My 位置查询与入库的公共流程，
 * 供主页（MapMainViewModel）与位置历史页（LocationHistoryViewModel）复用。
 *
 * 职责：
 * 1. 从 Room 加载设备实体并解析种子密钥（丢失时自愈重派生）
 * 2. 以最新记录时间为起点增量查询
 * 3. 结果写入 Room 并返回新位置列表
 *
 * 抛出类型化异常（NoEndpointException / IOException），由调用方决定 UI 反馈。
 */
class LocationQueryHelper(
    private val repository: DeviceRepository,
    private val queryService: FindMyQueryService
) {

    /** 增量查询并入库，返回新增位置列表；设备不存在或缺少密钥时返回空列表 */
    suspend fun queryAndStore(uid: String): List<LocationEntity> {
        val entity = repository.getDeviceEntity(uid) ?: return emptyList()

        val seed = resolveSeed(entity) ?: return emptyList()
        val periodSec = entity.findmyPeriodSec ?: 3600
        val lastTime = repository.getLatestLocation(uid)?.timestamp

        val results = withContext(Dispatchers.IO) {
            queryService.queryLocations(uid, seed, periodSec, lastTime)
        }
        if (results.isNotEmpty()) {
            repository.insertLocations(results)
        }
        return results
    }

    /** 设备是否具备可查询的密钥（用于前置 Toast 提示） */
    suspend fun hasQueryableKey(uid: String): Boolean {
        val entity = repository.getDeviceEntity(uid) ?: return false
        return entity.findmySeed != null || entity.aesKey != null
    }

    /** 后端地址是否已配置（用于前置 Toast 提示） */
    val hasEndpointConfigured: Boolean
        get() = queryService.getEndpointUrl().isNotBlank()

    private suspend fun resolveSeed(entity: com.reiyy.yuitag.data.local.DeviceEntity): ByteArray? {
        entity.findmySeed?.let { return it }
        return entity.aesKey?.let { aesKey ->
            // 自愈：DB 迁移丢失种子时从 AES 密钥重新派生（SHA256 确定对应）
            val derived = CryptoUtils.deriveFindMySeed(aesKey)
            repository.updateFindMySeed(entity.uid, derived)
            derived
        }
    }
}

