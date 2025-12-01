package net.kagamir.pickeep.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import net.kagamir.pickeep.data.local.PicKeepDatabase
import net.kagamir.pickeep.data.repository.SettingsRepository
import net.kagamir.pickeep.ui.screen.SetupScreen
import net.kagamir.pickeep.ui.screen.UnlockScreen
import net.kagamir.pickeep.ui.screen.SettingsScreen
import net.kagamir.pickeep.ui.screen.SyncStatusScreen
import net.kagamir.pickeep.ui.screen.BrowseScreen
import net.kagamir.pickeep.worker.WorkManagerScheduler

/**
 * 导航路由
 */
object Routes {
    const val SETUP = "setup"
    const val UNLOCK = "unlock"
    const val STATUS = "status"
    const val SETTINGS = "settings"
    const val BROWSE = "browse"
}

/**
 * 应用导航图
 */
@Composable
fun AppNavGraph(
    navController: NavHostController,
    startDestination: String,
    database: PicKeepDatabase,
    settingsRepository: SettingsRepository,
    workManagerScheduler: WorkManagerScheduler
) {
    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Routes.SETUP) {
            SetupScreen(
                settingsRepository = settingsRepository,
                onSetupComplete = {
                    navController.navigate(Routes.STATUS) {
                        popUpTo(Routes.SETUP) { inclusive = true }
                    }
                }
            )
        }
        
        composable(Routes.UNLOCK) {
            UnlockScreen(
                settingsRepository = settingsRepository,
                onUnlockSuccess = {
                    navController.navigate(Routes.STATUS) {
                        popUpTo(Routes.UNLOCK) { inclusive = true }
                    }
                }
            )
        }
        
        composable(Routes.STATUS) {
            SyncStatusScreen(
                database = database,
                settingsRepository = settingsRepository,
                workManagerScheduler = workManagerScheduler,
                onNavigateToSettings = {
                    navController.navigate(Routes.SETTINGS)
                },
                onNavigateToBrowse = {
                    navController.navigate(Routes.BROWSE)
                }
            )
        }
        
        composable(Routes.SETTINGS) {
            SettingsScreen(
                settingsRepository = settingsRepository,
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
        
        composable(Routes.BROWSE) {
            BrowseScreen(
                database = database,
                settingsRepository = settingsRepository,
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}

