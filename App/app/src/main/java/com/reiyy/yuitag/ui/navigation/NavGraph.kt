package com.reiyy.yuitag.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.reiyy.yuitag.MainActivity
import com.reiyy.yuitag.ble.FileLog
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.reiyy.yuitag.data.DeviceRepository
import com.reiyy.yuitag.ui.screens.AboutScreen
import com.reiyy.yuitag.ui.screens.DeviceConfigScreen
import com.reiyy.yuitag.ui.screens.DetailScreen
import com.reiyy.yuitag.ui.screens.DevOptionsScreen
import com.reiyy.yuitag.ui.screens.FindScreen
import com.reiyy.yuitag.ui.screens.LocationHistoryScreen
import com.reiyy.yuitag.ui.screens.ManualImportScreen
import com.reiyy.yuitag.ui.screens.MonitoredDevicesScreen
import com.reiyy.yuitag.ui.screens.LiteMainScreen
import com.reiyy.yuitag.ui.screens.MapMainScreen
import com.reiyy.yuitag.ui.screens.OtaFirmwareUpdateScreen
import com.reiyy.yuitag.ui.screens.PersonalizationScreen
import com.reiyy.yuitag.ui.screens.SafeWifiScreen
import com.reiyy.yuitag.ui.screens.SettingsBackendScreen
import com.reiyy.yuitag.ui.screens.NotificationScreen
import com.reiyy.yuitag.ui.screens.SettingsBackupScreen
import com.reiyy.yuitag.ui.screens.SettingsMapScreen
import com.reiyy.yuitag.ui.screens.SettingsQueryScreen
import com.reiyy.yuitag.ui.screens.SettingsScreen
import com.reiyy.yuitag.ui.screens.TrajectoryScreen
import com.reiyy.yuitag.viewmodel.DetailViewModel
import com.reiyy.yuitag.viewmodel.DeviceConfigViewModel
import com.reiyy.yuitag.viewmodel.PersonalizationViewModel
import com.reiyy.yuitag.viewmodel.DevOptionsViewModel
import com.reiyy.yuitag.viewmodel.FindViewModel
import com.reiyy.yuitag.viewmodel.LocationHistoryViewModel
import com.reiyy.yuitag.viewmodel.MapMainViewModel
import com.reiyy.yuitag.viewmodel.NotificationViewModel
import com.reiyy.yuitag.viewmodel.OtaViewModel
import com.reiyy.yuitag.viewmodel.TrajectoryViewModel

object Routes {
    const val MAIN = "main"
    const val LITE_MAIN = "lite_main"
    const val NOTIFICATIONS = "notifications"
    const val MAIN_DEVICE = "mainDevice/{uid}"
    const val FIND = "find/{uid}"
    const val DEVICE_CONFIG = "deviceConfig/{uid}"
    const val PERSONALIZATION = "personalization/{uid}"
    const val DETAIL = "detail/{uid}"
    const val OTA_FIRMWARE_UPDATE = "otaFirmwareUpdate/{uid}"
    const val SETTINGS = "settings"
    const val SETTINGS_BACKEND = "settings/backend"
    const val SETTINGS_MAP = "settings/map"
    const val SETTINGS_QUERY = "settings/query"
    const val SETTINGS_BACKUP = "settings/backup"
    const val SETTINGS_MONITORED_DEVICES = "settings/monitored_devices"
    const val SETTINGS_SAFE_WIFI = "settings/safe_wifi"
    const val ABOUT = "about"
    const val LOCATION_HISTORY = "locationHistory/{uid}"
    const val TRAJECTORY = "trajectory/{uid}"
    const val MANUAL_IMPORT = "manualImport"
    const val DEV_OPTIONS = "devOptions/{uid}"

    fun mainDeviceRoute(uid: String) = "mainDevice/$uid"
    fun findRoute(uid: String) = "find/$uid"
    fun deviceConfigRoute(uid: String) = "deviceConfig/$uid"
    fun personalizationRoute(uid: String) = "personalization/$uid"
    fun detailRoute(uid: String) = "detail/$uid"
    fun otaFirmwareUpdateRoute(uid: String) = "otaFirmwareUpdate/$uid"
    fun locationHistoryRoute(uid: String) = "locationHistory/$uid"
    fun trajectoryRoute(uid: String) = "trajectory/$uid"
    fun devOptionsRoute(uid: String) = "devOptions/$uid"
}

@Composable
fun YuiTagNavGraph(
    navController: NavHostController,
    repository: DeviceRepository,
    liteMode: Boolean = false
) {
    val mainViewModel: MapMainViewModel = viewModel()
    mainViewModel.initRepository(repository, lite = liteMode)

    val pendingRoute by MainActivity.pendingNavigationRoute.collectAsState()
    LaunchedEffect(pendingRoute) {
        val targetRoute = pendingRoute ?: return@LaunchedEffect
        MainActivity.pendingNavigationRoute.value = null

        if (targetRoute.startsWith("mainDevice/") || targetRoute.startsWith("detail/")) {
            val uid = targetRoute.substringAfter('/')
            mainViewModel.selectDeviceByUid(uid)
            val targetMain = if (liteMode) Routes.LITE_MAIN else Routes.MAIN
            val popped = navController.popBackStack(targetMain, inclusive = false)
            if (!popped) {
                navController.navigate(targetMain) {
                    popUpTo(0) { inclusive = true }
                }
            }
            return@LaunchedEffect
        }

        val currentDestination = navController.currentBackStackEntry?.destination
        val currentUid = navController.currentBackStackEntry?.arguments?.getString("uid")

        val targetUid = if (targetRoute.startsWith("find/")) {
            targetRoute.removePrefix("find/")
        } else null

        val isAlreadyOnTargetFind = targetUid != null &&
            currentDestination?.route == Routes.FIND &&
            currentUid == targetUid

        if (!isAlreadyOnTargetFind) {
            try {
                FileLog.i("NavGraph", "正在跳转目标路由: $targetRoute (当前 destination=${currentDestination?.route}, uid=$currentUid)")
                navController.navigate(targetRoute) {
                    launchSingleTop = true
                }
            } catch (e: Exception) {
                FileLog.e("NavGraph", "导航到 $targetRoute 失败", e)
            }
        } else {
            FileLog.i("NavGraph", "已在目标设备查找页: $targetUid，保持当前活跃查找会话")
        }
    }

    NavHost(
        navController = navController,
        startDestination = if (liteMode) Routes.LITE_MAIN else Routes.MAIN
    ) {
        composable(Routes.MAIN) {
            mainViewModel.initRepository(repository, lite = false)
            MapMainScreen(
                onNavigateToConfig = { uid -> navController.navigate(Routes.deviceConfigRoute(uid)) },
                onNavigateToPersonalization = { uid -> navController.navigate(Routes.personalizationRoute(uid)) },
                onNavigateToFind = { uid -> navController.navigate(Routes.findRoute(uid)) },
                onNavigateToSettings = { navController.navigate(Routes.SETTINGS) },
                onNavigateToLocationHistory = { uid -> navController.navigate(Routes.locationHistoryRoute(uid)) },
                onNavigateToTrajectory = { uid -> navController.navigate(Routes.trajectoryRoute(uid)) },
                onNavigateToDetail = { uid -> navController.navigate(Routes.detailRoute(uid)) },
                onNavigateToManualImport = { navController.navigate(Routes.MANUAL_IMPORT) },
                onNavigateToAbout = { navController.navigate(Routes.ABOUT) },
                onNavigateToNotifications = { navController.navigate(Routes.NOTIFICATIONS) },
                viewModel = mainViewModel
            )
        }

        composable(Routes.LITE_MAIN) {
            mainViewModel.initRepository(repository, lite = true)
            LiteMainScreen(
                onNavigateToFind = { uid -> navController.navigate(Routes.findRoute(uid)) },
                onNavigateToConfig = { uid -> navController.navigate(Routes.deviceConfigRoute(uid)) },
                onNavigateToPersonalization = { uid -> navController.navigate(Routes.personalizationRoute(uid)) },
                onNavigateToDetail = { uid -> navController.navigate(Routes.detailRoute(uid)) },
                onNavigateToSettings = { navController.navigate(Routes.SETTINGS) },
                onNavigateToManualImport = { navController.navigate(Routes.MANUAL_IMPORT) },
                onNavigateToAbout = { navController.navigate(Routes.ABOUT) },
                onNavigateToNotifications = { navController.navigate(Routes.NOTIFICATIONS) },
                viewModel = mainViewModel
            )
        }

        composable(
            route = Routes.FIND,
            arguments = listOf(navArgument("uid") { type = NavType.StringType })
        ) { backStackEntry ->
            val uid = backStackEntry.arguments?.getString("uid") ?: return@composable
            val vm: FindViewModel = viewModel(key = "find_$uid")
            vm.initRepository(repository)
            FindScreen(uid = uid, onExit = { navController.popBackStack() }, viewModel = vm)
        }

        composable(
            route = Routes.DEVICE_CONFIG,
            arguments = listOf(navArgument("uid") { type = NavType.StringType })
        ) { backStackEntry ->
            val uid = backStackEntry.arguments?.getString("uid") ?: return@composable
            val vm: DeviceConfigViewModel = viewModel(key = "config_$uid")
            vm.initRepository(repository)
            DeviceConfigScreen(uid = uid, onBack = { navController.popBackStack() },
                onSaved = { navController.popBackStack() }, viewModel = vm)
        }

        composable(
            route = Routes.PERSONALIZATION,
            arguments = listOf(navArgument("uid") { type = NavType.StringType })
        ) { backStackEntry ->
            val uid = backStackEntry.arguments?.getString("uid") ?: return@composable
            val vm: PersonalizationViewModel = viewModel(key = "personalization_$uid")
            vm.initRepository(repository)
            PersonalizationScreen(uid = uid, onBack = { navController.popBackStack() },
                onSaved = { navController.popBackStack() }, viewModel = vm)
        }

        composable(
            route = Routes.DETAIL,
            arguments = listOf(navArgument("uid") { type = NavType.StringType })
        ) { backStackEntry ->
            val uid = backStackEntry.arguments?.getString("uid") ?: return@composable
            val vm: DetailViewModel = viewModel(key = "detail_$uid")
            vm.initRepository(repository)
            DetailScreen(
                uid = uid,
                onBack = { navController.popBackStack() },
                onNavigateToOta = { otaUid -> navController.navigate(Routes.otaFirmwareUpdateRoute(otaUid)) },
                onNavigateToDevOptions = { optUid -> navController.navigate(Routes.devOptionsRoute(optUid)) },
                viewModel = vm
            )
        }

        composable(
            route = Routes.DEV_OPTIONS,
            arguments = listOf(navArgument("uid") { type = NavType.StringType })
        ) { backStackEntry ->
            val uid = backStackEntry.arguments?.getString("uid") ?: return@composable
            val vm: DevOptionsViewModel = viewModel(key = "devopts_$uid")
            vm.initRepository(repository)
            DevOptionsScreen(uid = uid, onBack = { navController.popBackStack() }, onSaved = { navController.popBackStack() }, viewModel = vm)
        }

        composable(
            route = Routes.OTA_FIRMWARE_UPDATE,
            arguments = listOf(navArgument("uid") { type = NavType.StringType })
        ) { backStackEntry ->
            val uid = backStackEntry.arguments?.getString("uid") ?: return@composable
            val vm: OtaViewModel = viewModel(key = "ota_$uid")
            vm.initRepository(repository)
            OtaFirmwareUpdateScreen(uid = uid, onBack = { navController.popBackStack() }, viewModel = vm)
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onNavigateToManualImport = { navController.navigate(Routes.MANUAL_IMPORT) },
                onNavigateToLiteMain = {
                    navController.navigate(Routes.LITE_MAIN) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onNavigateToFullMain = {
                    navController.navigate(Routes.MAIN) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onNavigateToBackend = { navController.navigate(Routes.SETTINGS_BACKEND) },
                onNavigateToMap = { navController.navigate(Routes.SETTINGS_MAP) },
                onNavigateToQuery = { navController.navigate(Routes.SETTINGS_QUERY) },
                onNavigateToBackup = { navController.navigate(Routes.SETTINGS_BACKUP) },
                onNavigateToAbout = { navController.navigate(Routes.ABOUT) },
                repository = repository,
                onNavigateToMonitoredDevices = { navController.navigate(Routes.SETTINGS_MONITORED_DEVICES) },
                onNavigateToSafeWifi = { navController.navigate(Routes.SETTINGS_SAFE_WIFI) }
            )
        }

        composable(Routes.SETTINGS_MONITORED_DEVICES) {
            MonitoredDevicesScreen(
                repository = repository,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.SETTINGS_SAFE_WIFI) {
            SafeWifiScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.SETTINGS_BACKEND) {
            SettingsBackendScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.SETTINGS_MAP) {
            SettingsMapScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.SETTINGS_QUERY) {
            SettingsQueryScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.SETTINGS_BACKUP) {
            SettingsBackupScreen(
                repository = repository,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.ABOUT) {
            AboutScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.MANUAL_IMPORT) {
            ManualImportScreen(
                repository = repository,
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Routes.LOCATION_HISTORY,
            arguments = listOf(navArgument("uid") { type = NavType.StringType })
        ) { backStackEntry ->
            val uid = backStackEntry.arguments?.getString("uid") ?: return@composable
            val vm: LocationHistoryViewModel = viewModel(key = "history_$uid")
            vm.initRepository(repository)
            vm.initialize(uid)

            val locations by vm.locationsFor(uid).collectAsState(initial = emptyList())
            val isRefreshing by vm.isRefreshing.collectAsState()
            val deviceName by vm.deviceName.collectAsState()
            val backfillProgress by vm.backfillProgress.collectAsState()

            LocationHistoryScreen(
                uid = uid,
                deviceName = deviceName,
                locations = locations,
                isRefreshing = isRefreshing,
                backfillProgress = backfillProgress,
                onBack = { navController.popBackStack() },
                onRefresh = { vm.refresh(uid) },
                onTriggerBackfill = { vm.triggerBackfill(uid) }
            )
        }

        composable(
            route = Routes.TRAJECTORY,
            arguments = listOf(navArgument("uid") { type = NavType.StringType })
        ) { backStackEntry ->
            val uid = backStackEntry.arguments?.getString("uid") ?: return@composable
            val vm: TrajectoryViewModel = viewModel(key = "trajectory_$uid")
            vm.initRepository(repository)
            TrajectoryScreen(
                uid = uid,
                onBack = { navController.popBackStack() },
                viewModel = vm
            )
        }

        composable(Routes.NOTIFICATIONS) {
            val vm: NotificationViewModel = viewModel()
            NotificationScreen(
                onBack = { navController.popBackStack() },
                onNavigateToRoute = { route ->
                    if (route.startsWith("mainDevice/") || route.startsWith("detail/")) {
                        val uid = route.substringAfter('/')
                        mainViewModel.selectDeviceByUid(uid)
                        val targetMain = if (liteMode) Routes.LITE_MAIN else Routes.MAIN
                        val popped = navController.popBackStack(targetMain, inclusive = false)
                        if (!popped) {
                            navController.navigate(targetMain) {
                                popUpTo(0) { inclusive = true }
                            }
                        }
                    } else {
                        navController.navigate(route)
                    }
                },
                viewModel = vm
            )
        }

        composable(
            route = Routes.MAIN_DEVICE,
            arguments = listOf(navArgument("uid") { type = NavType.StringType })
        ) { backStackEntry ->
            val uid = backStackEntry.arguments?.getString("uid")
            LaunchedEffect(uid) {
                if (!uid.isNullOrEmpty()) {
                    mainViewModel.selectDeviceByUid(uid)
                }
                val targetMain = if (liteMode) Routes.LITE_MAIN else Routes.MAIN
                val popped = navController.popBackStack(targetMain, inclusive = false)
                if (!popped) {
                    navController.navigate(targetMain) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            }
        }
    }
}
