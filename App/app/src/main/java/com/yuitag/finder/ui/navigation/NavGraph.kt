package com.yuitag.finder.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.yuitag.finder.data.DeviceRepository
import com.yuitag.finder.ui.screens.AboutScreen
import com.yuitag.finder.ui.screens.DeviceConfigScreen
import com.yuitag.finder.ui.screens.DetailScreen
import com.yuitag.finder.ui.screens.DevOptionsScreen
import com.yuitag.finder.ui.screens.FindScreen
import com.yuitag.finder.ui.screens.LocationHistoryScreen
import com.yuitag.finder.ui.screens.ManualImportScreen
import com.yuitag.finder.ui.screens.LiteMainScreen
import com.yuitag.finder.ui.screens.MapMainScreen
import com.yuitag.finder.ui.screens.OtaFirmwareUpdateScreen
import com.yuitag.finder.ui.screens.PersonalizationScreen
import com.yuitag.finder.ui.screens.SettingsBackendScreen
import com.yuitag.finder.ui.screens.SettingsBackupScreen
import com.yuitag.finder.ui.screens.SettingsMapScreen
import com.yuitag.finder.ui.screens.SettingsQueryScreen
import com.yuitag.finder.ui.screens.SettingsScreen
import com.yuitag.finder.ui.screens.TrajectoryScreen
import com.yuitag.finder.viewmodel.DetailViewModel
import com.yuitag.finder.viewmodel.DeviceConfigViewModel
import com.yuitag.finder.viewmodel.PersonalizationViewModel
import com.yuitag.finder.viewmodel.DevOptionsViewModel
import com.yuitag.finder.viewmodel.FindViewModel
import com.yuitag.finder.viewmodel.LocationHistoryViewModel
import com.yuitag.finder.viewmodel.MapMainViewModel
import com.yuitag.finder.viewmodel.OtaViewModel
import com.yuitag.finder.viewmodel.TrajectoryViewModel

object Routes {
    const val MAIN = "main"
    const val LITE_MAIN = "lite_main"
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
    const val ABOUT = "about"
    const val LOCATION_HISTORY = "locationHistory/{uid}"
    const val TRAJECTORY = "trajectory/{uid}"
    const val MANUAL_IMPORT = "manualImport"
    const val DEV_OPTIONS = "devOptions/{uid}"

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
    NavHost(
        navController = navController,
        startDestination = if (liteMode) Routes.LITE_MAIN else Routes.MAIN
    ) {
        composable(Routes.MAIN) {
            val vm: MapMainViewModel = viewModel()
            vm.initRepository(repository)

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
                viewModel = vm
            )
        }

        composable(Routes.LITE_MAIN) {
            val vm: MapMainViewModel = viewModel()
            vm.initRepository(repository, lite = true)

            LiteMainScreen(
                onNavigateToFind = { uid -> navController.navigate(Routes.findRoute(uid)) },
                onNavigateToConfig = { uid -> navController.navigate(Routes.deviceConfigRoute(uid)) },
                onNavigateToPersonalization = { uid -> navController.navigate(Routes.personalizationRoute(uid)) },
                onNavigateToDetail = { uid -> navController.navigate(Routes.detailRoute(uid)) },
                onNavigateToSettings = { navController.navigate(Routes.SETTINGS) },
                onNavigateToManualImport = { navController.navigate(Routes.MANUAL_IMPORT) },
                onNavigateToAbout = { navController.navigate(Routes.ABOUT) },
                viewModel = vm
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
                onNavigateToAbout = { navController.navigate(Routes.ABOUT) }
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

            LocationHistoryScreen(
                uid = uid,
                deviceName = deviceName,
                locations = locations,
                isRefreshing = isRefreshing,
                onBack = { navController.popBackStack() },
                onRefresh = { vm.refresh(uid) }
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
    }
}
