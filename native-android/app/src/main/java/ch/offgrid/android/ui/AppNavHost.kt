package ch.offgrid.android.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import ch.offgrid.android.AppContainer
import ch.offgrid.android.analytics.AnalyticsEvent
import ch.offgrid.android.analytics.Screen
import ch.offgrid.android.domain.Conversation
import ch.offgrid.android.domain.Settings
import ch.offgrid.android.ui.screens.AddContactScreen
import ch.offgrid.android.ui.screens.AdvancedScreen
import ch.offgrid.android.ui.screens.ChatScreen
import ch.offgrid.android.ui.screens.ConnectScreen
import ch.offgrid.android.ui.screens.ContactAddedScreen
import ch.offgrid.android.ui.screens.ContactsScreen
import ch.offgrid.android.ui.screens.ExportScreen
import ch.offgrid.android.ui.screens.GroupChatScreen
import ch.offgrid.android.ui.screens.GroupMembersScreen
import ch.offgrid.android.ui.screens.ImportScreen
import ch.offgrid.android.ui.screens.LocationScreen
import ch.offgrid.android.ui.screens.NameScreen
import ch.offgrid.android.ui.screens.NewGroupScreen
import ch.offgrid.android.ui.screens.SettingsScreen
import ch.offgrid.android.ui.theme.Og
import kotlinx.coroutines.flow.MutableStateFlow

val LocalApp = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }

/** Route names follow the team app (`/verbinden`, `/kontakte`, …) so both apps are easy to compare. */
object Routes {
    const val CONNECT = "verbinden"
    const val NAME = "name"
    const val CONTACTS = "kontakte"
    const val ADD = "kontakte/neu?tab={tab}"
    const val ADDED = "kontakte/neu/fertig/{contactId}"
    const val CHAT = "chat/{contactId}"
    const val LOCATION = "chat/{contactId}/standort/{messageId}"
    const val SETTINGS = "einstellungen"
    const val EXPORT = "einstellungen/export"
    const val IMPORT = "einstellungen/import"
    const val ADVANCED = "einstellungen/erweitert"
    const val NEW_GROUP = "gruppe/neu"
    const val GROUP = "gruppe/{groupId}"
    const val GROUP_MEMBERS = "gruppe/{groupId}/mitglieder"

    fun add(scan: Boolean = false) = "kontakte/neu?tab=${if (scan) "scan" else "mine"}"
    fun added(id: Long) = "kontakte/neu/fertig/$id"
    fun chat(id: Long) = "chat/$id"
    fun location(contactId: Long, messageId: Long) = "chat/$contactId/standort/$messageId"
    fun group(id: Long) = "gruppe/$id"
    fun groupMembers(id: Long) = "gruppe/$id/mitglieder"
}

/** Shared-axis step between screens: 30 dp slide plus fade, reversed on back. */
private const val AXIS_MS = 300
private val axisEasing = FastOutSlowInEasing

@Composable
fun AppNavHost(openRequest: MutableStateFlow<Conversation?>) {
    val app = LocalApp.current
    val onboarded by produceState<Boolean?>(null) { value = app.settings.get(Settings.ONBOARDED) == "true" }
    val done = onboarded ?: return
    LaunchedEffect(Unit) { if (done) app.antenna.connect() }
    val nav = rememberNavController()
    LaunchedEffect(nav) {
        // Only the route pattern (`chat/{contactId}`) is reported, never the filled-in IDs.
        nav.currentBackStackEntryFlow.collect { entry ->
            Screen.fromRoute(entry.destination.route)?.let { app.analytics.track(AnalyticsEvent.ScreenViewed(it)) }
        }
    }
    LaunchedEffect(nav, done) {
        // Notification taps open the conversation on top of the contact list. Before onboarding
        // there is nothing to open.
        openRequest.collect { conversation ->
            if (conversation == null || !done) return@collect
            val route = when (conversation) {
                is Conversation.Direct -> Routes.chat(conversation.contactId)
                is Conversation.Group -> Routes.group(conversation.groupId)
            }
            nav.navigate(route) {
                popUpTo(Routes.CONTACTS)
                launchSingleTop = true
            }
            openRequest.value = null
        }
    }
    val shift = with(LocalDensity.current) { 30.dp.roundToPx() }
    NavHost(
        navController = nav,
        startDestination = if (done) Routes.CONTACTS else Routes.CONNECT,
        modifier = Modifier.fillMaxSize().background(Og.bg),
        enterTransition = { slideInHorizontally(tween(AXIS_MS, easing = axisEasing)) { shift } + fadeIn(tween(AXIS_MS)) },
        exitTransition = { slideOutHorizontally(tween(AXIS_MS, easing = axisEasing)) { -shift } + fadeOut(tween(AXIS_MS / 2)) },
        popEnterTransition = { slideInHorizontally(tween(AXIS_MS, easing = axisEasing)) { -shift } + fadeIn(tween(AXIS_MS)) },
        popExitTransition = { slideOutHorizontally(tween(AXIS_MS, easing = axisEasing)) { shift } + fadeOut(tween(AXIS_MS / 2)) },
    ) {
        composable(Routes.CONNECT) {
            ConnectScreen(onNext = { onboardingDone ->
                if (onboardingDone) nav.popBackStack() else nav.navigate(Routes.NAME)
            })
        }
        composable(Routes.NAME) {
            NameScreen(onDone = { nav.navigate(Routes.CONTACTS) { popUpTo(0) } })
        }
        composable(Routes.CONTACTS) { ContactsScreen(nav) }
        composable(Routes.ADD, arguments = listOf(navArgument("tab") { type = NavType.StringType; defaultValue = "mine" })) {
            AddContactScreen(nav, scanTab = it.arguments?.getString("tab") == "scan")
        }
        composable(Routes.ADDED, arguments = listOf(navArgument("contactId") { type = NavType.LongType })) {
            ContactAddedScreen(nav, it.arguments!!.getLong("contactId"))
        }
        composable(Routes.CHAT, arguments = listOf(navArgument("contactId") { type = NavType.LongType })) {
            ChatScreen(nav, it.arguments!!.getLong("contactId"))
        }
        composable(
            Routes.LOCATION,
            arguments = listOf(navArgument("contactId") { type = NavType.LongType }, navArgument("messageId") { type = NavType.LongType }),
        ) {
            LocationScreen(nav, it.arguments!!.getLong("contactId"), it.arguments!!.getLong("messageId"))
        }
        composable(Routes.SETTINGS) { SettingsScreen(nav) }
        composable(Routes.EXPORT) { ExportScreen(nav) }
        composable(Routes.IMPORT) { ImportScreen(nav) }
        composable(Routes.ADVANCED) { AdvancedScreen(nav) }
        composable(Routes.NEW_GROUP) { NewGroupScreen(nav) }
        composable(Routes.GROUP, arguments = listOf(navArgument("groupId") { type = NavType.LongType })) {
            GroupChatScreen(nav, it.arguments!!.getLong("groupId"))
        }
        composable(Routes.GROUP_MEMBERS, arguments = listOf(navArgument("groupId") { type = NavType.LongType })) {
            GroupMembersScreen(nav, it.arguments!!.getLong("groupId"))
        }
    }
}

/** Navigates back, or to the contacts list when the screen was opened directly. */
fun NavHostController.back() {
    if (!popBackStack()) navigate(Routes.CONTACTS)
}
