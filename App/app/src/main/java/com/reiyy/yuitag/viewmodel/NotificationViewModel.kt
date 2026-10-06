package com.reiyy.yuitag.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.reiyy.yuitag.data.DeviceRepository
import com.reiyy.yuitag.data.NotificationRepository
import com.reiyy.yuitag.data.local.DeviceEntity
import com.reiyy.yuitag.data.local.NotificationEntity
import com.reiyy.yuitag.data.local.NotificationType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class NotificationUiState(
    val notifications: List<NotificationEntity> = emptyList(),
    val totalCount: Int = 0,
    val unreadCount: Int = 0,
    val selectedType: NotificationType? = null,
    val selectedDeviceUid: String? = null, // null = 全部, "SYSTEM" = 系统/无设备, 具体 uid = 该设备
    val onlyUnread: Boolean = false,
    val boundDevices: List<DeviceEntity> = emptyList()
)

class NotificationViewModel(application: Application) : AndroidViewModel(application) {

    private val notificationRepository = NotificationRepository.getInstance(application)
    private val deviceRepository = DeviceRepository.getInstance(application)

    private val _selectedType = MutableStateFlow<NotificationType?>(null)
    val selectedType: StateFlow<NotificationType?> = _selectedType.asStateFlow()

    private val _selectedDeviceUid = MutableStateFlow<String?>(null)
    val selectedDeviceUid: StateFlow<String?> = _selectedDeviceUid.asStateFlow()

    private val _onlyUnread = MutableStateFlow(false)
    val onlyUnread: StateFlow<Boolean> = _onlyUnread.asStateFlow()

    private val filterState = combine(_selectedType, _selectedDeviceUid, _onlyUnread) { type, deviceUid, onlyUnread ->
        Triple(type, deviceUid, onlyUnread)
    }

    val uiState: StateFlow<NotificationUiState> = combine(
        notificationRepository.notifications,
        notificationRepository.unreadCount,
        deviceRepository.boundDevicesFlow,
        filterState
    ) { allNotifications, unreadCount, boundDevices, (selectedType, selectedDeviceUid, onlyUnread) ->
        val filtered = allNotifications.filter { item ->
            // 类型筛选
            val matchType = selectedType == null || item.type.equals(selectedType.code, ignoreCase = true)
            // 设备筛选
            val matchDevice = when (selectedDeviceUid) {
                null -> true
                "SYSTEM" -> item.deviceUid.isNullOrEmpty()
                else -> item.deviceUid == selectedDeviceUid
            }
            // 未读筛选
            val matchRead = !onlyUnread || !item.isRead
            matchType && matchDevice && matchRead
        }

        NotificationUiState(
            notifications = filtered,
            totalCount = allNotifications.size,
            unreadCount = unreadCount,
            selectedType = selectedType,
            selectedDeviceUid = selectedDeviceUid,
            onlyUnread = onlyUnread,
            boundDevices = boundDevices
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = NotificationUiState()
    )

    fun setFilterType(type: NotificationType?) {
        _selectedType.value = type
    }

    fun setFilterDeviceUid(deviceUid: String?) {
        _selectedDeviceUid.value = deviceUid
    }

    fun setOnlyUnread(onlyUnread: Boolean) {
        _onlyUnread.value = onlyUnread
    }

    fun markAsRead(id: Long) {
        viewModelScope.launch {
            notificationRepository.markAsRead(id)
        }
    }

    fun markAllAsRead() {
        viewModelScope.launch {
            notificationRepository.markAllAsRead()
        }
    }

    fun deleteNotification(id: Long) {
        viewModelScope.launch {
            notificationRepository.deleteById(id)
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            notificationRepository.deleteAll()
        }
    }

    fun clearAllRead() {
        viewModelScope.launch {
            notificationRepository.deleteAllRead()
        }
    }
}
