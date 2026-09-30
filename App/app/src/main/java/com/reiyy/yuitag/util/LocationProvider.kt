package com.reiyy.yuitag.util

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class LocationProvider(private val context: Context) {

    private val locationManager: LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private val _locationState = MutableStateFlow(LocationState())
    val locationState: StateFlow<LocationState> = _locationState.asStateFlow()

    private var isListening = false

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (isBetterLocation(location, _locationState.value)) {
                _locationState.value = LocationState(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracy = location.accuracy,
                    isLocating = false,
                    lastUpdateTime = System.currentTimeMillis()
                )
            }
        }
    }

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

        _locationState.value = _locationState.value.copy(isLocating = true)

        // 步骤 1：获取缓存的最后位置以实现即时响应
        val lastLoc = getBestLastKnownLocation()
        if (lastLoc != null) {
            _locationState.value = LocationState(
                latitude = lastLoc.latitude,
                longitude = lastLoc.longitude,
                accuracy = lastLoc.accuracy,
                isLocating = false,
                lastUpdateTime = lastLoc.time.takeIf { it > 0 } ?: System.currentTimeMillis()
            )
        } else if (_locationState.value.lastUpdateTime == 0L) {
            // 无缓存位置，保持正在定位状态
            _locationState.value = _locationState.value.copy(isLocating = true)
        }

        // 步骤 2：启动持续位置更新
        startLocationUpdates()
    }

    /**
     * 单次定位：获取一个全新的位置后即结束，不启动持续定位流。
     * 成功时同步更新 _locationState（"我的位置"标记随之移动）；失败/无权限返回 null。
     */
    suspend fun requestCurrentLocationOnce(): LocationState? {
        if (!hasPermission()) return null
        val mgr = locationManager ?: return null

        _locationState.value = _locationState.value.copy(isLocating = true)

        // 带 8 秒超时的全新定位请求，若超时则平滑回退使用最新已知位置
        val fresh = withTimeoutOrNull(8_000L) {
            fetchSingleFreshLocation(mgr)
        } ?: getBestLastKnownLocation()

        if (fresh != null) {
            _locationState.value = LocationState(
                latitude = fresh.latitude,
                longitude = fresh.longitude,
                accuracy = fresh.accuracy,
                isLocating = false,
                lastUpdateTime = System.currentTimeMillis()
            )
            return _locationState.value
        }

        _locationState.value = _locationState.value.copy(isLocating = false)
        return null
    }

    private suspend fun fetchSingleFreshLocation(mgr: LocationManager): Location? {
        val providers = mutableListOf<String>()
        if (mgr.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            providers.add(LocationManager.GPS_PROVIDER)
        }
        if (mgr.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            providers.add(LocationManager.NETWORK_PROVIDER)
        }

        if (providers.isEmpty()) return null

        for (provider in providers) {
            val loc = requestProviderCurrentLocation(mgr, provider)
            if (loc != null) return loc
        }
        return null
    }

    private suspend fun requestProviderCurrentLocation(mgr: LocationManager, provider: String): Location? {
        return try {
            suspendCancellableCoroutine { cont ->
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                val executor = ContextCompat.getMainExecutor(context)

                try {
                    mgr.getCurrentLocation(
                        provider,
                        signal,
                        executor
                    ) { location: Location? ->
                        if (cont.isActive) {
                            cont.resume(location)
                        }
                    }
                } catch (_: SecurityException) {
                    if (cont.isActive) cont.resume(null)
                } catch (_: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun startLocationUpdates() {
        stopLocationUpdates()
        val mgr = locationManager ?: return
        if (!hasPermission()) return

        _locationState.value = _locationState.value.copy(isLocating = true)

        var registered = false
        val minTimeMs = 30000L // 降频省电：30s 一次更新，对查找场景无感知且显著降低功耗
        val minDistanceM = 10f // 10米位移过滤

        // 1. 注册 GPS Provider
        if (mgr.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            try {
                mgr.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    minTimeMs,
                    minDistanceM,
                    locationListener,
                    Looper.getMainLooper()
                )
                registered = true
            } catch (_: SecurityException) {} catch (_: Exception) {}
        }

        // 2. 注册 Network Provider
        if (mgr.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            try {
                mgr.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    minTimeMs,
                    minDistanceM,
                    locationListener,
                    Looper.getMainLooper()
                )
                registered = true
            } catch (_: SecurityException) {} catch (_: Exception) {}
        }

        // 3. 注册 Passive Provider（被动接收系统其他活跃应用定位，超低功耗保底）
        try {
            if (mgr.allProviders.contains(LocationManager.PASSIVE_PROVIDER)) {
                mgr.requestLocationUpdates(
                    LocationManager.PASSIVE_PROVIDER,
                    minTimeMs,
                    minDistanceM,
                    locationListener,
                    Looper.getMainLooper()
                )
                registered = true
            }
        } catch (_: SecurityException) {} catch (_: Exception) {}

        isListening = registered
        if (!registered && _locationState.value.lastUpdateTime == 0L) {
            _locationState.value = _locationState.value.copy(isLocating = false)
        }
    }

    fun stopLocationUpdates() {
        if (isListening) {
            try {
                locationManager?.removeUpdates(locationListener)
            } catch (_: Exception) {}
            isListening = false
        }
        _locationState.value = _locationState.value.copy(isLocating = false)
    }

    private fun getBestLastKnownLocation(): Location? {
        val mgr = locationManager ?: return null
        var bestLocation: Location? = null

        val providers = try {
            mgr.getProviders(true)
        } catch (_: SecurityException) {
            return null
        }

        for (provider in providers) {
            val loc = try {
                mgr.getLastKnownLocation(provider)
            } catch (_: SecurityException) {
                null
            } ?: continue

            if (bestLocation == null) {
                bestLocation = loc
            } else {
                val isSignificantlyNewer = loc.time - bestLocation.time > 60_000L
                val isSignificantlyOlder = bestLocation.time - loc.time > 60_000L
                val isNewer = loc.time > bestLocation.time

                if (isSignificantlyNewer) {
                    bestLocation = loc
                } else if (!isSignificantlyOlder) {
                    val accuracyDelta = (loc.accuracy - bestLocation.accuracy).toInt()
                    val isMoreAccurate = accuracyDelta < 0
                    val isSignificantlyLessAccurate = accuracyDelta > 50

                    if (isMoreAccurate || (isNewer && !isSignificantlyLessAccurate)) {
                        bestLocation = loc
                    }
                }
            }
        }
        return bestLocation
    }

    private fun isBetterLocation(location: Location, currentBestLocation: LocationState): Boolean {
        if (currentBestLocation.lastUpdateTime == 0L) {
            return true
        }
        val timeDelta = System.currentTimeMillis() - currentBestLocation.lastUpdateTime
        val isSignificantlyNewer = timeDelta > 60_000L
        val isSignificantlyOlder = timeDelta < -60_000L
        val isNewer = timeDelta > 0

        if (isSignificantlyNewer) {
            return true
        } else if (isSignificantlyOlder) {
            return false
        }

        val accuracyDelta = (location.accuracy - currentBestLocation.accuracy).toInt()
        val isMoreAccurate = accuracyDelta < 0
        val isSignificantlyLessAccurate = accuracyDelta > 100

        if (isMoreAccurate) {
            return true
        } else if (isNewer && !isSignificantlyLessAccurate) {
            return true
        }
        return false
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
