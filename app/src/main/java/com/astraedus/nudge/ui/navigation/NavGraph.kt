package com.astraedus.nudge.ui.navigation

import androidx.compose.runtime.Composable
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
import com.astraedus.nudge.ui.screens.groups.GroupScreen
import com.astraedus.nudge.ui.screens.groups.GroupViewModel
import com.astraedus.nudge.ui.screens.home.HomeScreen
import com.astraedus.nudge.ui.screens.home.HomeViewModel
import com.astraedus.nudge.ui.screens.onboarding.OnboardingScreen
import com.astraedus.nudge.ui.screens.rules.ActiveRulesScreen
import com.astraedus.nudge.ui.screens.rules.ActiveRulesViewModel
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
    data object RuleEditor : Screen("rule_editor/{packageName}?ruleId={ruleId}") {
        fun createRoute(packageName: String) = "rule_editor/$packageName"
        fun createRoute(packageName: String, ruleId: Long) = "rule_editor/$packageName?ruleId=$ruleId"
        fun createNewRoute(packageName: String) = "rule_editor/$packageName?ruleId=0"
    }
    data object Groups : Screen("groups")
    data object Stats : Screen("stats")
    data object Settings : Screen("settings")
    data object Onboarding : Screen("onboarding")
    data object GrayscaleGuide : Screen("grayscale_guide")
    data object ActiveRules : Screen("active_rules")
}

@Composable
fun NudgeNavGraph(
    nudgePreferences: NudgePreferences? = null
) {
    val navController = rememberNavController()

    // Determine start destination
    val startDestination = if (nudgePreferences != null) {
        val onboardingComplete by nudgePreferences.isOnboardingComplete.collectAsStateWithLifecycle(initialValue = true)
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
                onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                onNavigateToActiveRules = { navController.navigate(Screen.ActiveRules.route) }
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
            arguments = listOf(
                navArgument("packageName") { type = NavType.StringType },
                navArgument("ruleId") {
                    type = NavType.LongType
                    defaultValue = -1L
                }
            )
        ) {
            val viewModel: RuleEditorViewModel = hiltViewModel()
            RuleEditorScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToRuleEditor = { pkg, ruleId ->
                    navController.navigate(Screen.RuleEditor.createRoute(pkg, ruleId))
                },
                onCreateNewRule = { pkg ->
                    navController.navigate(Screen.RuleEditor.createNewRoute(pkg))
                }
            )
        }

        composable(Screen.ActiveRules.route) {
            val viewModel: ActiveRulesViewModel = hiltViewModel()
            ActiveRulesScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToRuleEditor = { pkg, ruleId ->
                    navController.navigate(Screen.RuleEditor.createRoute(pkg, ruleId))
                }
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
