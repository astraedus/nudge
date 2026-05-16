package com.astraedus.nudge.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import com.astraedus.nudge.ui.screens.groups.GroupScreen
import com.astraedus.nudge.ui.screens.groups.GroupViewModel
import com.astraedus.nudge.ui.screens.home.HomeScreen
import com.astraedus.nudge.ui.screens.home.HomeViewModel
import com.astraedus.nudge.ui.screens.onboarding.OnboardingScreen
import com.astraedus.nudge.ui.screens.rules.RuleEditorScreen
import com.astraedus.nudge.ui.screens.rules.RuleEditorViewModel
import com.astraedus.nudge.ui.screens.settings.GrayscaleGuideScreen
import com.astraedus.nudge.ui.screens.settings.SettingsScreen
import com.astraedus.nudge.ui.screens.stats.StatsScreen
import com.astraedus.nudge.ui.screens.stats.StatsViewModel
import kotlinx.coroutines.launch

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Apps : Screen("apps")
    data object RuleEditor : Screen("rule_editor/{packageName}") {
        fun createRoute(packageName: String) = "rule_editor/$packageName"
    }
    data object Groups : Screen("groups")
    data object Stats : Screen("stats")
    data object Settings : Screen("settings")
    data object Onboarding : Screen("onboarding")
    data object GrayscaleGuide : Screen("grayscale_guide")
}

@Composable
fun NudgeNavGraph(
    nudgePreferences: NudgePreferences? = null
) {
    val navController = rememberNavController()

    // Determine start destination
    val startDestination = if (nudgePreferences != null) {
        val onboardingComplete by nudgePreferences.isOnboardingComplete.collectAsState(initial = true)
        if (onboardingComplete) Screen.Home.route else Screen.Onboarding.route
    } else {
        Screen.Home.route
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
                onNavigateToSettings = { navController.navigate(Screen.Settings.route) }
            )
        }

        composable(Screen.Apps.route) {
            val viewModel: AppListViewModel = hiltViewModel()
            AppListScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToRuleEditor = { pkg ->
                    navController.navigate(Screen.RuleEditor.createRoute(pkg))
                }
            )
        }

        composable(
            route = Screen.RuleEditor.route,
            arguments = listOf(navArgument("packageName") { type = NavType.StringType })
        ) {
            val viewModel: RuleEditorViewModel = hiltViewModel()
            RuleEditorScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Groups.route) {
            val viewModel: GroupViewModel = hiltViewModel()
            GroupScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Stats.route) {
            val viewModel: StatsViewModel = hiltViewModel()
            StatsScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToGrayscaleGuide = {
                    navController.navigate(Screen.GrayscaleGuide.route)
                }
            )
        }

        composable(Screen.GrayscaleGuide.route) {
            GrayscaleGuideScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
