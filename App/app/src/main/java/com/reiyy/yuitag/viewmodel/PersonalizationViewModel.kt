package com.reiyy.yuitag.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.reiyy.yuitag.ble.FileLog
import com.reiyy.yuitag.data.DeviceRepository
import com.reiyy.yuitag.data.local.DeviceType
import com.reiyy.yuitag.data.theme.DeviceTheme
import com.reiyy.yuitag.ui.components.CropShape
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class PersonalizationViewModel(application: Application) : AndroidViewModel(application) {

    private var uid: String? = null

    lateinit var repository: DeviceRepository
        private set

    fun initRepository(repo: DeviceRepository) {
        repository = repo
    }

    private val _uiState = MutableStateFlow(PersonalizationUiState())
    val uiState: StateFlow<PersonalizationUiState> = _uiState.asStateFlow()

    private var originalCustomName = ""
    private var originalDeviceType = DeviceType.CUSTOM
    private var originalCustomIcon = ""
    private var originalThemePath = ""
    private var originalTheme: DeviceTheme? = null

    private var currentCropTarget: CropTarget = CropTarget.ICON

    fun initialize(deviceUid: String) {
        if (uid != null) return
        uid = deviceUid

        viewModelScope.launch {
            try {
                val entity = repository.getDeviceEntity(deviceUid)
                val memory = repository.getDevice(deviceUid)

                originalCustomName = entity?.customName ?: memory?.customName.orEmpty()
                originalDeviceType = if (entity != null) DeviceType.fromLabel(entity.deviceType) else (memory?.deviceType ?: DeviceType.CUSTOM)
                originalCustomIcon = entity?.customIcon ?: memory?.customIcon.orEmpty()
                originalThemePath = entity?.themePath ?: memory?.themePath.orEmpty()

                val theme = if (originalThemePath.isNotEmpty()) {
                    repository.loadTheme(originalThemePath)
                } else null
                originalTheme = theme

                _uiState.update {
                    it.copy(
                        customName = originalCustomName,
                        deviceType = originalDeviceType,
                        customIcon = originalCustomIcon,
                        themePath = originalThemePath,
                        theme = theme ?: DeviceTheme(),
                        themeEnabled = originalThemePath.isNotEmpty()
                    )
                }
            } catch (e: Exception) {
                FileLog.e("PersonalizationVM", "初始化异常: ${e.message}", e)
                _uiState.update { it.copy(saveError = "加载配置异常: ${e.message}") }
            }
        }
    }

    fun updateCustomName(name: String) {
        if (name.length > 40) return
        _uiState.update { it.copy(customName = name) }
    }

    fun updateDeviceType(type: DeviceType) {
        _uiState.update { it.copy(deviceType = type) }
    }

    fun updateCustomIcon(icon: String) {
        if (icon.codePointCount(0, icon.length) > 2) return
        _uiState.update { it.copy(customIcon = icon) }
    }

    fun updateTrendHere(text: String) {
        _uiState.update { it.copy(theme = it.theme.copy(trendHere = text)) }
    }

    fun updateTrendApproaching(text: String) {
        _uiState.update { it.copy(theme = it.theme.copy(trendApproaching = text)) }
    }

    fun updateTrendAway(text: String) {
        _uiState.update { it.copy(theme = it.theme.copy(trendAway = text)) }
    }

    fun updateTrendStable(text: String) {
        _uiState.update { it.copy(theme = it.theme.copy(trendStable = text)) }
    }

    fun pickImage(target: CropTarget) {
        currentCropTarget = target
        val shape = when (target) {
            CropTarget.BACKGROUND -> com.reiyy.yuitag.ui.components.CropShape.RECT_9_16
            else -> com.reiyy.yuitag.ui.components.CropShape.CIRCLE
        }
        val size = when (target) {
            CropTarget.MINI_ICON -> 256
            else -> 1024
        }
        _uiState.update { it.copy(showCrop = false, pickedImageUri = null, cropShape = shape, cropOutputSize = size) }
    }

    fun onImagePicked(uri: Uri) {
        _uiState.update { it.copy(pickedImageUri = uri, showCrop = true) }
    }

    fun onCropComplete(bitmap: Bitmap) {
        val deviceUid = uid ?: return
        viewModelScope.launch {
            try {
                val context = getApplication<Application>()
                val themeDir = repository.getThemeDir(context, deviceUid)
                val filename = when (currentCropTarget) {
                    CropTarget.ICON -> "icon.png"
                    CropTarget.MINI_ICON -> "mini_icon.png"
                    CropTarget.BACKGROUND -> "background.png"
                }
                repository.saveThemeImage(context, deviceUid, filename, bitmap)
                val path = _uiState.value.themePath.ifEmpty { themeDir }
                _uiState.update { it.copy(
                    themePath = path,
                    themeEnabled = true,
                    themeVersion = it.themeVersion + 1,
                    showCrop = false,
                    pickedImageUri = null
                ) }
            } catch (e: Exception) {
                FileLog.e("PersonalizationVM", "保存图片失败: ${e.message}", e)
                _uiState.update { it.copy(showCrop = false, pickedImageUri = null, saveError = "保存图片失败: ${e.message}") }
            }
        }
    }

    fun onCropCancel() {
        _uiState.update { it.copy(showCrop = false, pickedImageUri = null) }
    }

    fun resetTheme() {
        val deviceUid = uid ?: return
        val context = getApplication<Application>()
        val themeDir = repository.getThemeDir(context, deviceUid)
        repository.deleteTheme(themeDir)
        _uiState.update { it.copy(
            themePath = "",
            theme = DeviceTheme(),
            themeEnabled = false,
            themeVersion = it.themeVersion + 1
        ) }
    }

    fun saveConfig(onSaved: () -> Unit) {
        val deviceUid = uid ?: return
        if (_uiState.value.isSaving) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, saveError = null) }

            val state = _uiState.value
            val customName = state.customName.ifBlank { "YuiTag_${deviceUid.take(4)}" }
            val deviceType = state.deviceType
            val customIcon = if (deviceType == DeviceType.CUSTOM) state.customIcon.trim() else ""
            val themePath = if (state.themeEnabled) state.themePath else ""

            val localChanged = customName != originalCustomName ||
                deviceType != originalDeviceType ||
                customIcon != originalCustomIcon
            val themePathChanged = themePath != originalThemePath
            val themeChanged = state.theme != originalTheme

            if (!localChanged && !themePathChanged && !themeChanged) {
                _uiState.update { it.copy(isSaving = false) }
                onSaved()
                return@launch
            }

            try {
                if (localChanged) {
                    repository.updateDeviceConfig(deviceUid, customName, deviceType, customIcon)
                }
                if (themePathChanged) {
                    if (themePath.isEmpty()) {
                        repository.updateThemePath(deviceUid, "")
                    } else {
                        repository.updateThemePath(deviceUid, themePath)
                    }
                }
                if (themeChanged && themePath.isNotEmpty()) {
                    repository.saveTheme(themePath, state.theme)
                }
                originalCustomName = customName
                originalDeviceType = deviceType
                originalCustomIcon = customIcon
                originalThemePath = themePath
                originalTheme = state.theme
                _uiState.update { it.copy(isSaving = false, saveError = null) }
                onSaved()
            } catch (e: Exception) {
                FileLog.e("PersonalizationVM", "保存失败: ${e.message}", e)
                _uiState.update { it.copy(isSaving = false, saveError = "保存失败: ${e.message}") }
            }
        }
    }

    fun dismissSaveError() {
        _uiState.update { it.copy(saveError = null) }
    }

    enum class CropTarget { ICON, MINI_ICON, BACKGROUND }

    data class PersonalizationUiState(
        val customName: String = "",
        val deviceType: DeviceType = DeviceType.CUSTOM,
        val customIcon: String = "",
        val themePath: String = "",
        val themeEnabled: Boolean = false,
        val theme: DeviceTheme = DeviceTheme(),
        val showCrop: Boolean = false,
        val pickedImageUri: Uri? = null,
        val cropShape: CropShape = CropShape.CIRCLE,
        val cropOutputSize: Int = 1024,
        val themeVersion: Int = 0,
        val isSaving: Boolean = false,
        val saveError: String? = null
    )
}