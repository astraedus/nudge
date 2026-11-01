package com.astraedus.nudge.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.ui.screens.apps.AppListScreen
import com.astraedus.nudge.ui.screens.apps.AppListViewModel
import com.astraedus.nudge.ui.screens.allapps.AllAppsEditorScreen
import com.astraedus.nudge.ui.screens.allapps.AllAppsEditorViewModel
import com.astraedus.nudge.ui.screens.groups.GroupEditorScreen
import com.astraedus.nudge.ui.screens.groups.GroupEditorViewModel
import com.astraedus.nudge.ui.screens.home.HomeScreen
import com.astraedus.nudge.ui.screens.home.HomeViewModel
import com.astraedus.nudge.ui.screens.nuke.NukeScreen
import com.astraedus.nudge.ui.screens.nuke.NukeViewModel
import com.astraedus.nudge.ui.screens.onboarding.OnboardingScreen
import com.astraedus.nudge.ui.screens.config.UnifiedAppConfigScreen
import com.astraedus.nudge.ui.screens.config.UnifiedAppConfigViewModel
import com.astraedus.nudge.ui.screens.settings.GrayscaleGuideScreen
import com.astraedus.nudge.ui.screens.settings.MessagesEditorScreen
import com.astraedus.nudge.ui.screens.settings.SettingsScreen
import com.astraedus.nudge.ui.screens.stats.AppDetailScreen
import com.astraedus.nudge.ui.screens.stats.AppDetailViewModel
import com.astraedus.nudge.ui.screens.stats.InterventionsScreen
import com.astraedus.nudge.ui.screens.stats.InterventionsViewModel
import com.astraedus.nudge.ui.screens.stats.StatsScreen
import com.astraedus.nudge.ui.screens.stats.StatsViewModel
import com.astraedus.nudge.ui.screens.stats.WillpowerScreen
import com.astraedus.nudge.ui.screens.stats.WillpowerViewModel
import kotlinx.coroutines.launch

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Apps : Screen("apps")
    data object Stats : Screen("stats")
    data object Willpower : Screen("willpower")
    data object Interventions : Screen("interventions")
    data object Settings : Screen("settings")
    data object Onboarding : Screen("onboarding")
    data object GrayscaleGuide : Screen("grayscale_guide")
    data object MessagesEditor : Screen("messages_editor")
    data object AppConfig : Screen("app_config/{packageName}") {
        fun createRoute(packageName: String) = "app_config/$packageName"
    }
    data object AppDetail : Screen("app_detail/{packageName}") {
        fun createRoute(packageName: String) = "app_detail/$packageName"
    }
    data object Nuke : Screen("nuke")

    /** The group editor (spec 3.3). No id, or an id of 0 or less, opens a new group. */
    data object GroupEditor : Screen("group_editor?groupId={groupId}") {
        fun createRoute(groupId: Long? = null) =
            if (groupId == null) "group_editor" else "group_editor?groupId=$groupId"
    }

    /** The All apps editor (spec 3.4). No id, or an id of 0 or less, opens a new rule. */
    data object AllAppsEditor : Screen("all_apps_editor?ruleId={ruleId}") {
        fun createRoute(ruleId: Long? = null) =
            if (ruleId == null) "all_apps_editor" else "all_apps_editor?ruleId=$ruleId"
    }
}

@Composable
fun NudgeNavGraph(
    nudgePreferences: NudgePreferences? = null,
    /**
     * The route an outside tap asked for, or null for an ordinary launch.
     *
     * This replaced an `openSettingsOnLaunch: Boolean`. Two entry points now navigate from outside
     * the app — the protection alert ("tap to fix it", which must land on the screen carrying the
     * permission rows and the prominent-disclosure dialog, not on the dashboard with the fix two
     * taps further on) and the home-screen widgets — and a second boolean per destination would
     * have meant a boolean per destination forever. `MainActivity` maps both through
     * `WidgetDeepLink.routeFor`, which returns null for anything it does not recognise, so an
     * unknown route can never reach `navigate()`.
     */
    deepLinkRoute: String? = null,
    /**
     * Called once the route has been navigated to, so `MainActivity` can clear it. Without it,
     * tapping the same widget twice would be swallowed: the second tap sets an identical value and
     * `LaunchedEffect` would not re-run.
     */
    onDeepLinkConsumed: () -> Unit = {}
) {
    val navController = rememberNavController()

    // Determine start destination
    val onboardingComplete = if (nudgePreferences != null) {
        val complete by nudgePreferences.isOnboardingComplete.collectAsStateWithLifecycle(initialValue = true)
        complete
    } else {
        true
    }
    val startDestination = if (onboardingComplete) Screen.Home.route else Screen.Onboarding.route

    // Never jump a first-run user out of onboarding: an alert or a widget can only have fired for
    // someone who has already been through it, but the start destination reads `true` for one frame
    // while the preference loads, and that frame must not be enough to navigate away from it.
    LaunchedEffect(deepLinkRoute, onboardingComplete) {
        val route = deepLinkRoute
        if (route != null && onboardingComplete) {
            navController.navigate(route) { launchSingleTop = true }
            onDeepLinkConsumed()
        }
    }

    NavHost(navController = navController, startDestination = startDestination) {
        composable(Screen.Onboarding.route) {
            val scope = rememberCoroutineScope()
            OnboardingScreen(
                onComplete = {
                    scope.launch {
                        nudgePreferences?.setOnboardingComplete(true)
                    }
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Onboarding.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.Home.route) {
            val viewModel: HomeViewModel = hiltViewModel()
            HomeScreen(
                viewModel = viewModel,
                onNavigateToApps = { navController.navigate(Screen.Apps.route) },
                onNavigateToStats = { navController.navigate(Screen.Stats.route) },
                onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                onNavigateToWillpower = { navController.navigate(Screen.Willpower.route) },
                onNavigateToInterventions = { navController.navigate(Screen.Interventions.route) },
                onNavigateToAppDetail = { pkg ->
                    navController.navigate(Screen.AppDetail.createRoute(pkg))
                },
                onNavigateToNuke = { navController.navigate(Screen.Nuke.route) },
                onOpenAllAppsRule = { ruleId ->
                    navController.navigate(Screen.AllAppsEditor.createRoute(ruleId))
                }
            )
        }

        composable(Screen.Nuke.route) {
            val viewModel: NukeViewModel = hiltViewModel()
            NukeScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Willpower.route) {
            val viewModel: WillpowerViewModel = hiltViewModel()
            WillpowerScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Interventions.route) {
            val viewModel: InterventionsViewModel = hiltViewModel()
            InterventionsScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Apps.route) {
            val viewModel: AppListViewModel = hiltViewModel()
            AppListScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToAppConfig = { pkg ->
                    navController.navigate(Screen.AppConfig.createRoute(pkg))
                },
                onOpenGroup = { groupId ->
                    navController.navigate(Screen.GroupEditor.createRoute(groupId))
                },
                onOpenAllAppsRule = { ruleId ->
                    navController.navigate(Screen.AllAppsEditor.createRoute(ruleId))
                }
            )
        }

        composable(
            route = Screen.AppConfig.route,
            arguments = listOf(navArgument("packageName") { type = NavType.StringType })
        ) {
            val viewModel: UnifiedAppConfigViewModel = hiltViewModel()
            UnifiedAppConfigScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() },
                onOpenGroup = { groupId ->
                    navController.navigate(Screen.GroupEditor.createRoute(groupId))
                },
                onOpenAllAppsRule = { ruleId ->
                    navController.navigate(Screen.AllAppsEditor.createRoute(ruleId))
                }
            )
        }

        composable(
            route = Screen.GroupEditor.route,
            arguments = listOf(
                navArgument(GroupEditorViewModel.ARG_GROUP_ID) {
                    type = NavType.LongType
                    defaultValue = -1L
                }
            )
        ) {
            val viewModel: GroupEditorViewModel = hiltViewModel()
            GroupEditorScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() },
                onOpenAllAppsRule = { ruleId ->
                    navController.navigate(Screen.AllAppsEditor.createRoute(ruleId))
                }
            )
        }

        composable(
            route = Screen.AllAppsEditor.route,
            arguments = listOf(
                navArgument(AllAppsEditorViewModel.ARG_RULE_ID) {
                    type = NavType.LongType
                    defaultValue = -1L
                }
            )
        ) {
            val viewModel: AllAppsEditorViewModel = hiltViewModel()
            AllAppsEditorScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() },
                onOpenGroup = { groupId ->
                    navController.navigate(Screen.GroupEditor.createRoute(groupId))
                }
            )
        }

        composable(Screen.Stats.route) {
            val viewModel: StatsViewModel = hiltViewModel()
            StatsScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToAppDetail = { pkg ->
                    navController.navigate(Screen.AppDetail.createRoute(pkg))
                },
                onNavigateToWillpower = { navController.navigate(Screen.Willpower.route) },
                onNavigateToInterventions = { navController.navigate(Screen.Interventions.route) }
            )
        }

        composable(
            route = Screen.AppDetail.route,
            arguments = listOf(navArgument("packageName") { type = NavType.StringType })
        ) {
            val viewModel: AppDetailViewModel = hiltViewModel()
            AppDetailScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToGrayscaleGuide = {
                    navController.navigate(Screen.GrayscaleGuide.route)
                },
                onNavigateToMessagesEditor = {
                    navController.navigate(Screen.MessagesEditor.route)
                }
            )
        }

        composable(Screen.GrayscaleGuide.route) {
            GrayscaleGuideScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.MessagesEditor.route) {
            MessagesEditorScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
