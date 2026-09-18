package com.yuitag.finder.util

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class LocationProvider(private val context: Context) {

    private val fusedClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private val locationManager: LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private val _locationState = MutableStateFlow(LocationState())
    val locationState: StateFlow<LocationState> = _locationState.asStateFlow()

    private var locationCallback: LocationCallback? = null

    val isLocationEnabled: Boolean
        get() = locationManager?.let { mgr ->
            mgr.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            mgr.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } ?: false

    fun requestLocation() {
        if (!hasPermission()) {
            _locationState.value = _locationState.value.copy(isLocating = false)
            return
        }

        // 步骤 1：获取缓存的最后位置以实现即时响应
        _locationState.value = _locationState.value.copy(isLocating = true)
        try {
            fusedClient.lastLocation.addOnSuccessListener { loc: Location? ->
                if (loc != null) {
                    _locationState.value = LocationState(
                        latitude = loc.latitude,
                        longitude = loc.longitude,
                        accuracy = loc.accuracy,
                        isLocating = false
                    )
                } else if (_locationState.value.lastUpdateTime == 0L) {
                    // 无缓存位置，保持正在定位状态
                    _locationState.value = _locationState.value.copy(isLocating = true)
                }
            }.addOnFailureListener {
                _locationState.value = _locationState.value.copy(isLocating = _locationState.value.lastUpdateTime == 0L)
            }
        } catch (_: SecurityException) {}

        // 步骤 2：启动持续位置更新
        startLocationUpdates()
    }

    /**
     * 单次定位：获取一个全新的位置后即结束，不启动持续定位流。
     * 成功时同步更新 _locationState（"我的位置"标记随之移动）；失败/无权限返回 null。
     */
    suspend fun requestCurrentLocationOnce(): LocationState? {
        if (!hasPermission()) return null
        _locationState.value = _locationState.value.copy(isLocating = true)
        val location = try {
            suspendCancellableCoroutine<Location?> { cont ->
                val cts = CancellationTokenSource()
                val task = fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                task.addOnSuccessListener { loc ->
                    if (cont.isActive) cont.resume(loc)
                }
                task.addOnFailureListener {
                    if (cont.isActive) cont.resume(null)
                }
                task.addOnCanceledListener {
                    if (cont.isActive) cont.resume(null)
                }
                cont.invokeOnCancellation { cts.cancel() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            null
        }
        if (location != null) {
            _locationState.value = LocationState(
                latitude = location.latitude,
                longitude = location.longitude,
                accuracy = location.accuracy,
                isLocating = false,
                lastUpdateTime = System.currentTimeMillis()
            )
            return _locationState.value
        }
        _locationState.value = _locationState.value.copy(isLocating = false)
        return null
    }

    private fun startLocationUpdates() {
        stopLocationUpdates()

        _locationState.value = _locationState.value.copy(isLocating = true)

        // 降频省电：30s 一次更新，对查找场景无感知且显著降低功耗
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 30000L)
            .setMinUpdateIntervalMillis(20000L)
            .setMaxUpdateDelayMillis(45000L)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc: Location = result.lastLocation ?: return
                _locationState.value = LocationState(
                    latitude = loc.latitude,
                    longitude = loc.longitude,
                    accuracy = loc.accuracy,
                    isLocating = false,
                    lastUpdateTime = System.currentTimeMillis()
                )
            }
        }

        try {
            fusedClient.requestLocationUpdates(
                locationRequest,
                locationCallback!!,
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            _locationState.value = _locationState.value.copy(isLocating = false)
        }
    }

    fun stopLocationUpdates() {
        locationCallback?.let {
            try { fusedClient.removeLocationUpdates(it) } catch (_: Exception) {}
            locationCallback = null
        }
        _locationState.value = _locationState.value.copy(isLocating = false)
    }

    private fun hasPermission(): Boolean = hasLocationPermission(context)

    data class LocationState(
        val latitude: Double = 0.0,
        val longitude: Double = 0.0,
        val accuracy: Float = 0f,
        val isLocating: Boolean = false,
        val lastUpdateTime: Long = 0L
    ) {
        val hasLocation: Boolean get() = latitude != 0.0 || longitude != 0.0
    }
}
