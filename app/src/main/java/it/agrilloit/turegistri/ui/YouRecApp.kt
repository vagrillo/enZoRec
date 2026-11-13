package it.agrilloit.turegistri.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import it.agrilloit.turegistri.ui.editor.EditorScreen
import it.agrilloit.turegistri.ui.projects.ProjectsScreen
import it.agrilloit.turegistri.ui.record.RecordScreen
import it.agrilloit.turegistri.ui.theme.BackgroundDark
import it.agrilloit.turegistri.ui.theme.DividerDark
import it.agrilloit.turegistri.ui.theme.PrimaryAccent
import it.agrilloit.turegistri.ui.theme.TextSecondary

/** Destinazioni di primo livello di YouRec. */
object Routes {
    const val RECORD = "record"
    const val PROJECTS = "projects"
    const val EDITOR = "editor/{projectId}"
    fun editor(projectId: String) = "editor/$projectId"
}

@Composable
fun YouRecApp(
    activity: YouRecActivity,
    permissionsGranted: Boolean,
    onRequestPermissions: () -> Unit,
) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    // Modalità Screen display: niente nav bar (l'app è la finestra PiP minimale).
    val screenDisplay by androidx.compose.runtime.remember(activity) {
        activity.engine.screenDisplayFlow
    }.collectAsState()
    val showBottomBar = (currentRoute == Routes.RECORD || currentRoute == Routes.PROJECTS) &&
        !screenDisplay

    Scaffold(
        containerColor = BackgroundDark,
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(
                    containerColor = BackgroundDark,
                    tonalElevation = 0.dp,
                    modifier = Modifier
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .height(64.dp)
                        .border(1.dp, DividerDark),
                ) {
                    TabItem(
                        selected = currentRoute == Routes.RECORD,
                        onClick = {
                            navController.navigate(Routes.RECORD) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(Icons.Filled.Videocam, contentDescription = "Record") },
                        label = "Record",
                    )
                    TabItem(
                        selected = currentRoute == Routes.PROJECTS,
                        onClick = {
                            navController.navigate(Routes.PROJECTS) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(Icons.Filled.PhotoLibrary, contentDescription = "Projects") },
                        label = "Projects",
                    )
                    TabItem(
                        selected = currentRoute?.startsWith("editor") == true,
                        onClick = { navController.navigate(Routes.editor("LATEST")) },
                        icon = { Icon(Icons.Filled.Tune, contentDescription = "Edit") },
                        label = "Edit",
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.RECORD,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            composable(Routes.RECORD) {
                RecordScreen(
                    activity = activity,
                    permissionsGranted = permissionsGranted,
                    onRequestPermissions = onRequestPermissions,
                    onOpenEditor = { projectId ->
                        navController.navigate(Routes.editor(projectId))
                    },
                )
            }
            composable(Routes.PROJECTS) {
                ProjectsScreen(
                    onOpenEditor = { projectId -> navController.navigate(Routes.editor(projectId)) },
                    onGoRecord = { navController.navigate(Routes.RECORD) },
                )
            }
            composable(
                Routes.EDITOR,
                arguments = listOf(navArgument("projectId") { defaultValue = "" }),
            ) { entry ->
                val projectId = entry.arguments?.getString("projectId").orEmpty()
                EditorScreen(projectIdRaw = projectId, onBack = { navController.popBackStack() })
            }
        }
    }
}

@Composable
private fun RowScope.TabItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
    label: String,
) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = icon,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = PrimaryAccent,
            selectedTextColor = PrimaryAccent,
            unselectedIconColor = TextSecondary,
            unselectedTextColor = TextSecondary,
            indicatorColor = Color.Transparent,
        ),
    )
}
