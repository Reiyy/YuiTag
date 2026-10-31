package com.reiyy.yuitag.data

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.graphics.Bitmap
import com.reiyy.yuitag.ble.BleConnectionManager
import com.reiyy.yuitag.ble.BleGattClient
import com.reiyy.yuitag.ble.CryptoUtils
import com.reiyy.yuitag.ble.FindSession
import com.reiyy.yuitag.data.local.DeviceDao
import com.reiyy.yuitag.data.local.DeviceEntity
import com.reiyy.yuitag.data.local.DeviceType
import com.reiyy.yuitag.data.theme.DeviceTheme
import com.reiyy.yuitag.ble.FileLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import com.reiyy.yuitag.data.local.toDomainModel
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * 设备仓库 — 整合 Room 持久化存储 + 内存 BLE 扫描实时缓存
 */
class DeviceRepository(
    private val dao: DeviceDao,
    private val locationDao: com.reiyy.yuitag.data.local.LocationDao,
    private val notificationRepository: NotificationRepository? = null
) {

    // ── 内存缓存：BLE 扫描实时数据 ──
    private val _scannedDevices = MutableStateFlow<Map<String, YuiTagDevice>>(emptyMap())
    val scannedDevices: StateFlow<Map<String, YuiTagDevice>> = _scannedDevices.asStateFlow()

    // 线程安全的 BluetoothDevice 引用映射（BLE 回调线程写，UI 线程读）
    private val deviceRefs = ConcurrentHashMap<String, BluetoothDevice>()

    // lastSeen 写库节流（避免每个广播包都写 Room）
    private val lastSeenWriteThrottle = ConcurrentHashMap<String, Long>()

    // GATT FindSession 统一连接管理（跨页面共享，唯一活跃连接）
    val bleConnectionManager = BleConnectionManager(BleGattClient())

    // ── Room 持久化：已绑定设备 Flow ──
    val boundDevicesFlow: Flow<List<DeviceEntity>> = dao.getAllFlow()

    // ── 已绑定设备缓存：从 Room 加载，用于未扫描到的设备也能查询 ──
    private val _boundDevicesCache = MutableStateFlow<Map<String, DeviceEntity>>(emptyMap())
    val boundDevicesCache: StateFlow<Map<String, DeviceEntity>> = _boundDevicesCache.asStateFlow()

    // ── 兼容旧代码 ──
    val devices: StateFlow<Map<String, YuiTagDevice>> = _scannedDevices.asStateFlow()

    // --- BLE 扫描缓存操作（内存） ---

    fun upsertScannedDevice(device: YuiTagDevice, btDevice: BluetoothDevice? = null) {
        _scannedDevices.update { current ->
            val existing = current[device.uid]
            val merged = if (existing != null) {
                device.copy(
                    aesKey = device.aesKey ?: existing.aesKey,
                    isBound = device.isBound || existing.isBound,
                    customName = device.customName.ifEmpty { existing.customName },
                    deviceType = if (device.deviceType == DeviceType.CUSTOM) existing.deviceType else device.deviceType,
                    customIcon = device.customIcon.ifBlank { existing.customIcon },
                    firmwareVersion = device.firmwareVersion.ifEmpty { existing.firmwareVersion },
                    firmwarePlatform = device.firmwarePlatform.ifEmpty { existing.firmwarePlatform },
                    hardwareVersion = device.hardwareVersion.ifEmpty { existing.hardwareVersion },
                    manufacturer = device.manufacturer.ifEmpty { existing.manufacturer },
                    productName = device.productName.ifEmpty { existing.productName },
                    productModel = device.productModel.ifEmpty { existing.productModel },
                    coreVersion = device.coreVersion.ifEmpty { existing.coreVersion },
                    serialNumber = device.serialNumber.ifEmpty { existing.serialNumber },
                    advInterval = if (device.advInterval > 0) device.advInterval else existing.advInterval,
                    broadcastName = device.broadcastName.ifEmpty { existing.broadcastName },
                    advIntervalNormal = if ((device.advIntervalNormal ?: 0) > 0) device.advIntervalNormal else existing.advIntervalNormal,
                    advIntervalFind = if ((device.advIntervalFind ?: 0) > 0) device.advIntervalFind else existing.advIntervalFind,
                    findmyEnabled = device.findmyEnabled ?: existing.findmyEnabled,
                    findmySupported = device.findmySupported ?: existing.findmySupported,
                    findmySeed = device.findmySeed ?: existing.findmySeed,
                    findmyPeriodSec = if ((device.findmyPeriodSec ?: 0) > 0) device.findmyPeriodSec else existing.findmyPeriodSec,
                    macAddress = device.macAddress.ifEmpty { existing.macAddress },
                    deviceName = device.deviceName.ifEmpty { existing.deviceName },
                    themePath = device.themePath.ifEmpty { existing.themePath },
                    battery = if (device.battery >= 0) device.battery else existing.battery,
                    fixedKeysJson = device.fixedKeysJson.ifEmpty { existing.fixedKeysJson },
                    fixedKeyCount = device.fixedKeyCount ?: existing.fixedKeyCount
                )
            } else device
            current + (device.uid to merged)
        }
        if (btDevice != null) {
            deviceRefs[device.uid] = btDevice
        }
    }

    fun saveBluetoothDevice(uid: String, device: BluetoothDevice) {
        deviceRefs[uid] = device
    }

    fun getDevice(uid: String): YuiTagDevice? {
        // 优先从扫描缓存获取（含实时 RSSI 等动态数据）
        _scannedDevices.value[uid]?.let { return it }
        // 回退到已绑定设备缓存（未扫描到的设备也能查询基本信息）
        _boundDevicesCache.value[uid]?.let { entity ->
            return entity.toDomainModel().copy(rssi = 0)
        }
        return null
    }

    fun getBluetoothDevice(uid: String): BluetoothDevice? {
        deviceRefs[uid]?.let { return it }
        // 回退使用缓存的 MAC 地址构造 BluetoothDevice，覆盖手动导入或暂未扫描到的设备
        val mac = _boundDevicesCache.value[uid]?.macAddress ?: return null
        if (mac.isBlank()) return null
        return try {
            android.bluetooth.BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(mac)
        } catch (_: Exception) {
            null
        }
    }

    fun clearScannedDevices() {
        _scannedDevices.value = emptyMap()
        deviceRefs.clear()
    }

    // --- Room 持久化操作 ---

    /** 绑定设备：将绑定后的信息写入 Room 数据库 */
    suspend fun bindDevice(
        uid: String,
        serialNumber: String = "",
        aesKey: ByteArray,
        customName: String,
        deviceType: DeviceType,
        protocolVersion: Int,
        firmwareVersion: String = "",
        firmwarePlatform: String = "",
        hardwareVersion: String = "",
        manufacturer: String = "",
        productName: String = "",
        productModel: String = "",
        coreVersion: String = "",
        deviceName: String = "",
        findmySupported: Boolean? = null,
        findmyEnabled: Boolean? = null,
        fixedKeysJson: String = "",
        fixedKeyCount: Int? = null
    ) {
        // 新增设备默认排序排在最后
        val nextOrder = dao.maxSortOrder() + 1
        val entity = DeviceEntity(
            uid = uid,
            serialNumber = serialNumber,
            customName = customName,
            deviceType = deviceType.label,
            protocolVersion = protocolVersion,
            firmwareVersion = firmwareVersion,
            firmwarePlatform = firmwarePlatform,
            hardwareVersion = hardwareVersion,
            manufacturer = manufacturer,
            productName = productName,
            productModel = productModel,
            coreVersion = coreVersion,
            aesKey = aesKey,
            boundAt = System.currentTimeMillis(),
            deviceName = deviceName,
            sortOrder = nextOrder,
            findmySupported = findmySupported,
            findmyEnabled = findmyEnabled,
            fixedKeysJson = fixedKeysJson,
            fixedKeyCount = fixedKeyCount
        )
        dao.upsert(entity)
        _boundDevicesCache.update { it + (uid to entity) }

        _scannedDevices.update { current ->
            val existing = current[uid]
            if (existing != null) {
                current + (uid to existing.copy(
                    isBound = true,
                    aesKey = aesKey,
                    customName = customName,
                    deviceType = deviceType,
                    firmwareVersion = firmwareVersion,
                    firmwarePlatform = firmwarePlatform,
                    hardwareVersion = hardwareVersion,
                    manufacturer = manufacturer,
                    productName = productName,
                    productModel = productModel,
                    coreVersion = coreVersion,
                    serialNumber = serialNumber,
                    deviceName = deviceName,
                    findmySupported = findmySupported,
                    findmyEnabled = findmyEnabled ?: existing.findmyEnabled,
                    fixedKeysJson = fixedKeysJson.ifEmpty { existing.fixedKeysJson },
                    fixedKeyCount = fixedKeyCount ?: existing.fixedKeyCount
                ))
            } else current
        }
    }

    /** 持久化 GATT 读取的设备信息（DIS）并刷新缓存 */
    suspend fun updateDeviceReadInfo(
        uid: String,
        deviceName: String,
        serialNumber: String,
        firmwareVersion: String,
        firmwarePlatform: String,
        hardwareVersion: String,
        manufacturer: String,
        productName: String,
        productModel: String,
        coreVersion: String = ""
    ) {
        dao.updateDeviceReadInfo(
            uid = uid,
            deviceName = deviceName,
            serialNumber = serialNumber,
            firmwareVersion = firmwareVersion,
            firmwarePlatform = firmwarePlatform,
            hardwareVersion = hardwareVersion,
            manufacturer = manufacturer,
            productName = productName,
            productModel = productModel,
            coreVersion = coreVersion
        )
        _boundDevicesCache.update { current ->
            val existing = current[uid]
            if (existing != null) {
                current + (uid to existing.copy(
                    deviceName = deviceName,
                    serialNumber = serialNumber,
                    firmwareVersion = firmwareVersion,
                    firmwarePlatform = firmwarePlatform,
                    hardwareVersion = hardwareVersion,
                    manufacturer = manufacturer,
                    productName = productName,
                    productModel = productModel,
                    coreVersion = coreVersion
                ))
            } else current
        }
        _scannedDevices.update { current ->
            val existing = current[uid]
            if (existing != null) {
                current + (uid to existing.copy(
                    deviceName = deviceName,
                    serialNumber = serialNumber,
                    firmwareVersion = firmwareVersion,
                    firmwarePlatform = firmwarePlatform,
                    hardwareVersion = hardwareVersion,
                    manufacturer = manufacturer,
                    productName = productName,
                    productModel = productModel,
                    coreVersion = coreVersion
                ))
            } else current
        }
    }

    /** 更新设备配置（名称、类型、自定义图标） */
    suspend fun updateDeviceConfig(uid: String, customName: String, deviceType: DeviceType, customIcon: String) {
        dao.updateNameAndType(uid, customName, deviceType.label, customIcon)
        _scannedDevices.update { current ->
            val existing = current[uid]
            if (existing != null) {
                current + (uid to existing.copy(customName = customName, deviceType = deviceType, customIcon = customIcon))
            } else current
        }
    }

    /** 更新主题包路径 */
    suspend fun updateThemePath(uid: String, path: String) {
        dao.updateThemePath(uid, path)
        _scannedDevices.update { current ->
            val existing = current[uid]
            if (existing != null) {
                current + (uid to existing.copy(themePath = path))
            } else current
        }
        _boundDevicesCache.update { current ->
            val existing = current[uid]
            if (existing != null) {
                current + (uid to existing.copy(themePath = path))
            } else current
        }
    }

    /** 保存主题图片到主题包目录，返回文件路径 */
    fun saveThemeImage(context: Context, uid: String, filename: String, bitmap: Bitmap): String {
        val dir = File(context.filesDir, "theme/tag/$uid")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, filename)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file.absolutePath
    }

    /** 获取主题包根目录路径 */
    fun getThemeDir(context: Context, uid: String): String {
        return File(context.filesDir, "theme/tag/$uid").absolutePath
    }

    /** 加载主题配置 */
    fun loadTheme(themeDir: String): DeviceTheme? {
        return DeviceTheme.loadFromDir(themeDir)
    }

    /** 保存主题配置 */
    fun saveTheme(themeDir: String, theme: DeviceTheme) {
        DeviceTheme.saveToDir(themeDir, theme)
    }

    /** 删除整个主题包 */
    fun deleteTheme(themeDir: String) {
        val dir = File(themeDir)
        if (dir.exists()) dir.deleteRecursively()
    }

    /** 更新设备广播配置（广播名称与广播间隔） */
    suspend fun updateBroadcastConfig(uid: String, broadcastName: String, advIntervalNormal: Int, advIntervalFind: Int) {
        dao.updateBroadcastConfig(uid, broadcastName, advIntervalNormal, advIntervalFind)
        _scannedDevices.update { current ->
            val existing = current[uid]
            if (existing != null) {
                current + (uid to existing.copy(
                    broadcastName = broadcastName,
                    advIntervalNormal = advIntervalNormal,
                    advIntervalFind = advIntervalFind
                ))
            } else current
        }
    }

    /** 更新常规广播发射功率 (dBm) */
    suspend fun updateTxPower(uid: String, txPower: Int) {
        dao.updateTxPower(uid, txPower)
        _scannedDevices.update { current ->
            val existing = current[uid]
            if (existing != null) current + (uid to existing.copy(txPower = txPower)) else current
        }
        _boundDevicesCache.update { current ->
            val existing = current[uid]
            if (existing != null) current + (uid to existing.copy(txPower = txPower)) else current
        }
    }

    /** 更新 Find My 广播发射功率 (dBm) */
    suspend fun updateFindMyTxPower(uid: String, txPower: Int) {
        dao.updateFindMyTxPower(uid, txPower)
        _boundDevicesCache.update { current ->
            val existing = current[uid]
            if (existing != null) current + (uid to existing.copy(findmyTxPower = txPower)) else current
        }
    }

    /** 持久化设备 MAC 地址（仅地址变化时写库，避免重复写入） */
    suspend fun saveMacAddress(uid: String, mac: String) {
        if (mac.isBlank()) return
        val cached = _boundDevicesCache.value[uid]?.macAddress
        if (cached == mac) return
        dao.updateMacAddress(uid, mac)
        _boundDevicesCache.update { current ->
            val existing = current[uid]
            if (existing != null) current + (uid to existing.copy(macAddress = mac)) else current
        }
        _scannedDevices.update { current ->
            val existing = current[uid]
            if (existing != null) current + (uid to existing.copy(macAddress = mac)) else current
        }
    }

    /** 节流持久化最近在线时间戳与广播 RSSI（间隔超阈值才写库） */
    suspend fun updateLastSeen(uid: String, lastSeenMs: Long, rssi: Int) {
        if (lastSeenMs <= 0) return
        val lastWrite = lastSeenWriteThrottle[uid] ?: 0L
        if (lastSeenMs - lastWrite < LAST_SEEN_WRITE_THROTTLE_MS) return
        lastSeenWriteThrottle[uid] = lastSeenMs
        dao.updateLastSeen(uid, lastSeenMs, rssi)
        _boundDevicesCache.update { current ->
            val existing = current[uid]
            if (existing != null) current + (uid to existing.copy(lastSeen = lastSeenMs, lastRssi = rssi)) else current
        }
    }

    /** 缓存最近一次广播电量（%，-1=未知），电量未变化时跳过写库 */
    suspend fun updateBattery(uid: String, battery: Int) {
        if (battery < 0) return
        if (_boundDevicesCache.value[uid]?.battery == battery) return
        dao.updateBattery(uid, battery)
        _boundDevicesCache.update { current ->
            val existing = current[uid]
            if (existing != null) current + (uid to existing.copy(battery = battery)) else current
        }
        _scannedDevices.update { current ->
            val existing = current[uid]
            if (existing != null) current + (uid to existing.copy(battery = battery)) else current
        }
        val boundDevice = _boundDevicesCache.value[uid]
        if (boundDevice != null) {
            notificationRepository?.checkBatteryAlert(uid, boundDevice.customName, battery)
        }
    }

    /** 更新 Find My 网络开关 */
    suspend fun updateFindMyEnabled(uid: String, enabled: Boolean) {
        dao.updateFindMyEnabled(uid, enabled)
        _scannedDevices.update { current ->
            val existing = current[uid]
            if (existing != null) {
                current + (uid to existing.copy(findmyEnabled = enabled))
            } else current
        }
    }

    /** 更新 Find My 支持状态（false=设备不支持该功能） */
    suspend fun updateFindMySupport(uid: String, supported: Boolean) {
        dao.updateFindMySupport(uid, supported)
        _scannedDevices.update { current ->
            val existing = current[uid]
            if (existing != null) {
                current + (uid to existing.copy(findmySupported = supported))
            } else current
        }
    }

    /** 更新 Find My 种子密钥 */
    suspend fun updateFindMySeed(uid: String, seed: ByteArray) {
        dao.updateFindMySeed(uid, seed)
        _scannedDevices.update { current ->
            val existing = current[uid]
            if (existing != null) {
                current + (uid to existing.copy(findmySeed = seed))
            } else current
        }
    }

    /** 更新 Find My 轮换周期 */
    suspend fun updateFindMyPeriod(uid: String, seconds: Int) {
        dao.updateFindMyPeriod(uid, seconds)
        _scannedDevices.update { current ->
            val existing = current[uid]
            if (existing != null) {
                current + (uid to existing.copy(findmyPeriodSec = seconds))
            } else current
        }
    }

    /** 更新遗落提醒开关 */
    suspend fun updateSeparationAlertEnabled(uid: String, enabled: Boolean) {
        dao.updateSeparationAlertEnabled(uid, enabled)
        _boundDevicesCache.update { current ->
            val existing = current[uid]
            if (existing != null) current + (uid to existing.copy(separationAlertEnabled = enabled)) else current
        }
        _scannedDevices.update { current ->
            val existing = current[uid]
            if (existing != null) current + (uid to existing.copy(separationAlertEnabled = enabled)) else current
        }
    }

    /** 获取所有已启用遗落提醒的设备（Flow） */
    fun getMonitoredDevicesFlow(): Flow<List<YuiTagDevice>> =
        dao.getMonitoredDevicesFlow().map { list -> list.map { it.toDomainModel() } }

    /** 获取所有已启用遗落提醒的设备（同步列表） */
    suspend fun getMonitoredDevices(): List<YuiTagDevice> =
        dao.getMonitoredDevices().map { it.toDomainModel() }

    /** 手动导入设备：根据 uid + aesKey + mac 自动派生 seed 并保存 */
    suspend fun insertManualDevice(uid: String, aesKey: ByteArray, macAddress: String) {
        val seed = CryptoUtils.deriveFindMySeed(aesKey)
        val entity = DeviceEntity(
            uid = uid,
            customName = "YuiTag_${uid.take(4)}",
            aesKey = aesKey,
            findmySeed = seed,
            macAddress = macAddress,
            boundAt = System.currentTimeMillis(),
            sortOrder = dao.maxSortOrder() + 1
        )
        dao.upsert(entity)
        _boundDevicesCache.update { it + (uid to entity) }
    }

    /** 更新 Sparks 固定密钥池与配置数量 */
    suspend fun updateFixedKeys(uid: String, keysJson: String, count: Int) {
        dao.updateFixedKeys(uid, keysJson, count)
        _boundDevicesCache.update { current ->
            val existing = current[uid]
            if (existing != null) current + (uid to existing.copy(fixedKeysJson = keysJson, fixedKeyCount = count)) else current
        }
        _scannedDevices.update { current ->
            val existing = current[uid]
            if (existing != null) current + (uid to existing.copy(fixedKeysJson = keysJson, fixedKeyCount = count)) else current
        }
    }

    /** 备份恢复：合并导入单个设备 — 存在则 UPDATE（保留位置历史），不存在则 INSERT，并刷新内存缓存 */
    suspend fun restoreDevice(entity: DeviceEntity) {
        if (dao.getByUid(entity.uid) == null) {
            dao.insert(entity)
        } else {
            dao.update(entity)
        }
        _boundDevicesCache.update { it + (entity.uid to entity) }
        _scannedDevices.update { current ->
            val existing = current[entity.uid]
            if (existing != null) {
                current + (entity.uid to existing.copy(
                    isBound = true,
                    aesKey = entity.aesKey,
                    customName = entity.customName,
                    deviceType = DeviceType.fromLabel(entity.deviceType),
                    customIcon = entity.customIcon,
                    themePath = entity.themePath,
                    firmwareVersion = entity.firmwareVersion,
                    firmwarePlatform = entity.firmwarePlatform,
                    hardwareVersion = entity.hardwareVersion,
                    manufacturer = entity.manufacturer,
                    productName = entity.productName,
                    productModel = entity.productModel,
                    coreVersion = entity.coreVersion,
                    serialNumber = entity.serialNumber,
                    broadcastName = entity.broadcastName,
                    advIntervalNormal = entity.advIntervalNormal,
                    advIntervalFind = entity.advIntervalFind,
                    findmyEnabled = entity.findmyEnabled,
                    findmySupported = entity.findmySupported,
                    findmySeed = entity.findmySeed,
                    findmyPeriodSec = entity.findmyPeriodSec,
                    macAddress = entity.macAddress,
                    deviceName = entity.deviceName,
                    battery = entity.battery,
                    fixedKeysJson = entity.fixedKeysJson,
                    fixedKeyCount = entity.fixedKeyCount
                ))
            } else current
        }
    }

    /** 按新顺序批量持久化设备排序值 */
    suspend fun updateDeviceOrder(uidOrder: List<String>) {
        uidOrder.forEachIndexed { index, uid ->
            dao.updateSortOrder(uid, index)
        }
    }

    // --- 位置历史记录 ---

    /** 获取设备位置历史 Flow */
    fun getLocationHistoryFlow(uid: String): Flow<List<com.reiyy.yuitag.data.local.LocationEntity>> =
        locationDao.getByDeviceUid(uid)

    /** 获取最新一条位置记录 */
    suspend fun getLatestLocation(uid: String): com.reiyy.yuitag.data.local.LocationEntity? =
        locationDao.getLatestByDeviceUid(uid)

    /** 批量插入位置记录（基于唯一索引自动去重），并对已存在的记录补全密钥信息 */
    suspend fun insertLocations(locations: List<com.reiyy.yuitag.data.local.LocationEntity>) {
        if (locations.isEmpty()) return
        // 过滤已删除设备的孤儿位置记录（忽略删除/解绑后在途的查询结果）
        val boundUids = dao.getAll().map { it.uid }.toSet()
        if (boundUids.isEmpty()) return
        val valid = locations.filter { it.deviceUid in boundUids }
        if (valid.isEmpty()) return
        locationDao.insertAll(valid)
        for (loc in valid) {
            if (loc.periodIndex != null || loc.hashedKey != null) {
                locationDao.backfillKeyInfo(loc.deviceUid, loc.timestamp, loc.periodIndex, loc.hashedKey)
            }
            if (!loc.address.isNullOrBlank()) {
                locationDao.backfillAddress(loc.deviceUid, loc.timestamp, loc.address, loc.addressDetail)
            }
        }
    }

    /** 获取指定设备地址未解析的位置记录 */
    suspend fun getUnresolvedLocations(uid: String): List<com.reiyy.yuitag.data.local.LocationEntity> =
        locationDao.getUnresolvedLocations(uid)

    /** 获取指定设备未解析地址的记录数 */
    suspend fun getUnresolvedLocationCount(uid: String): Int =
        locationDao.getUnresolvedCount(uid)

    /** 更新单条位置记录的地址信息 */
    suspend fun updateLocationAddress(
        id: Long,
        address: String,
        detail: com.reiyy.yuitag.data.local.AddressComponents?
    ) {
        locationDao.updateAddress(id, address, detail)
    }

    /** 批量更新指定位置记录的地址信息 */
    suspend fun updateLocationAddressByIds(
        ids: List<Long>,
        address: String,
        detail: com.reiyy.yuitag.data.local.AddressComponents?
    ) {
        if (ids.isEmpty()) return
        locationDao.updateAddressByIds(ids, address, detail)
    }

    /** 根据 UID 从 Room 加载设备实体 */
    suspend fun getDeviceEntity(uid: String): DeviceEntity? = dao.getByUid(uid)

    /** 获取所有已绑定设备实体 (suspend) */
    suspend fun getAllBoundEntities(): List<DeviceEntity> = dao.getAll()

    /** 删除已绑定设备（完全移除记录和缓存） */
    suspend fun deleteDevice(uid: String) {
        dao.deleteByUid(uid)
        locationDao.deleteByDeviceUid(uid)
        _scannedDevices.update { current -> current - uid }
        _boundDevicesCache.update { current -> current - uid }
        deviceRefs.remove(uid)
        notificationRepository?.clearDeviceState(uid)
    }

    /** 解绑设备：清除绑定状态和密钥，保留设备扫描缓存 */
    suspend fun unbindDevice(uid: String) {
        dao.deleteByUid(uid)
        locationDao.deleteByDeviceUid(uid)
        _boundDevicesCache.update { current -> current - uid }
        _scannedDevices.update { current ->
            val existing = current[uid]
            if (existing != null) {
                current + (uid to existing.copy(
                    isBound = false,
                    aesKey = null
                ))
            } else current
        }
        notificationRepository?.clearDeviceState(uid)
    }

    /** 获取已绑定设备数量 */
    suspend fun boundDeviceCount(): Int = dao.count()

    // --- 数据迁移：从 SharedPreferences 迁移旧密钥到 Room ---

    suspend fun migrateLegacyKeys(androidContext: android.content.Context) {
        val settingsPrefs = androidContext.getSharedPreferences("yuitag_settings", android.content.Context.MODE_PRIVATE)
        if (settingsPrefs.getBoolean("legacy_keys_migrated", false)) return

        val prefs = androidContext.getSharedPreferences("yuitag_keys", android.content.Context.MODE_PRIVATE)
        val allKeys = prefs.all.filterKeys { it.startsWith("aes_") }
        if (allKeys.isEmpty()) {
            settingsPrefs.edit().putBoolean("legacy_keys_migrated", true).apply()
            return
        }

        var migratedCount = 0
        for ((key, value) in allKeys) {
            val uid = key.removePrefix("aes_")
            val existing = dao.getByUid(uid)
            if (existing == null || existing.aesKey == null) {
                try {
                    val aesKey = android.util.Base64.decode(value as String, android.util.Base64.NO_WRAP)
                    if (existing != null) {
                        // 已有 Room 实体但缺密钥 → 更新密钥
                        dao.upsert(existing.copy(aesKey = aesKey))
                    } else {
                        // 无 Room 实体 → 创建新实体并持久化
                        dao.upsert(DeviceEntity(
                            uid = uid,
                            customName = "YuiTag_${uid.take(4)}",
                            aesKey = aesKey,
                            boundAt = System.currentTimeMillis()
                        ))
                    }
                    _scannedDevices.update { current ->
                        val dev = current[uid]
                        if (dev != null) {
                            current + (uid to dev.copy(isBound = true, aesKey = aesKey))
                        } else current
                    }
                    migratedCount++
                } catch (e: Exception) {
                    FileLog.e("DB", "旧密钥迁移失败 [uid=$uid]: ${e.message}", e)
                }
            }
        }
        FileLog.i("DB", "旧版密钥迁移完成: 共迁移 $migratedCount 条设备密钥")
        prefs.edit().clear().apply()
        settingsPrefs.edit().putBoolean("legacy_keys_migrated", true).apply()
    }

    // --- GATT FindSession 会话管理 ---

    private val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun getActiveFindSession(uid: String): FindSession? = bleConnectionManager.getSession(uid)

    /** 检查指定设备当前是否存在活跃且未断开的 GATT 会话 */
    fun isDeviceConnected(uid: String): Boolean = bleConnectionManager.isConnected(uid)

    /** 断开指定设备的活跃会话（断开 + 清理缓存 + 广播断线事件） */
    fun disconnectActiveFindSession(uid: String) {
        bleConnectionManager.disconnect(uid)
    }

    /**
     * 安全退出查找模式：
     * 1. 独立作用域执行，不受 ViewModel onCleared / UI 销毁影响；
     * 2. 若之前处于响铃状态，先发送关铃指令并等待确认；
     * 3. 挂起发送关闭查找模式指令 (0x4F, 0x00)，等待固件确认或短超时，确保在空口发完；
     * 4. 若 shouldDisconnect 为 true（非复用会话），指令送达后再断开物理连接；若为复用会话则保留连接。
     */
    fun safeExitFindMode(
        uid: String,
        session: FindSession,
        wasRingActive: Boolean,
        shouldDisconnect: Boolean
    ) {
        repositoryScope.launch {
            try {
                if (wasRingActive) {
                    session.sendRingAwait(false, timeoutMs = 800L)
                }
                session.sendFindModeAwait(false, timeoutMs = 1200L)
                FileLog.i("DeviceRepo", "查找模式关闭指令已确认发送: uid=$uid")
            } catch (e: Exception) {
                FileLog.w("DeviceRepo", "退出查找模式指令发送异常: ${e.message}")
            } finally {
                if (shouldDisconnect) {
                    bleConnectionManager.disconnect(uid)
                    FileLog.d("DeviceRepo", "查找模式已安全断开物理连接: uid=$uid")
                }
            }
        }
    }

    /** 启动时加载已绑定设备的密钥到扫描缓存，并填充绑定设备缓存 */
    suspend fun loadBoundKeysToCache() {
        val bound = dao.getAll()
        // 填充绑定设备缓存（即使设备未被扫描到也可查询）
        _boundDevicesCache.value = bound.associateBy { it.uid }
        for (entity in bound) {
            _scannedDevices.update { current ->
                val dev = current[entity.uid]
                if (dev != null) {
                    current + (entity.uid to dev.copy(
                        isBound = true,
                        aesKey = entity.aesKey,
                        customName = entity.customName,
                        deviceType = DeviceType.fromLabel(entity.deviceType),
                        customIcon = entity.customIcon,
                        firmwareVersion = entity.firmwareVersion,
                        firmwarePlatform = entity.firmwarePlatform,
                        hardwareVersion = entity.hardwareVersion,
                        manufacturer = entity.manufacturer,
                        productName = entity.productName,
                        productModel = entity.productModel,
                        coreVersion = entity.coreVersion,
                        serialNumber = entity.serialNumber,
                        broadcastName = entity.broadcastName,
                        advIntervalNormal = entity.advIntervalNormal,
                        advIntervalFind = entity.advIntervalFind,
                        findmyEnabled = entity.findmyEnabled,
                        findmySupported = entity.findmySupported,
                        findmyPeriodSec = entity.findmyPeriodSec,
                        macAddress = entity.macAddress,
                        deviceName = entity.deviceName,
                        battery = entity.battery,
                        fixedKeysJson = entity.fixedKeysJson,
                        fixedKeyCount = entity.fixedKeyCount
                    ))
                } else current
            }
        }
    }

    companion object {
        private const val LAST_SEEN_WRITE_THROTTLE_MS = 10_000L

        @Volatile
        private var INSTANCE: DeviceRepository? = null

        fun getInstance(context: Context): DeviceRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: run {
                    val appContext = context.applicationContext
                    val db = com.reiyy.yuitag.data.local.YuiTagDatabase.getInstance(appContext)
                    val notifRepo = NotificationRepository.getInstance(appContext)
                    DeviceRepository(db.deviceDao(), db.locationDao(), notifRepo).also { INSTANCE = it }
                }
            }
        }
    }
}
