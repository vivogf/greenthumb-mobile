package site.xmpp.greenthumb.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.savedstate.read
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import site.xmpp.greenthumb.core.network.ApiError
import site.xmpp.greenthumb.core.platform.Connectivity
import site.xmpp.greenthumb.core.storage.SessionManager
import site.xmpp.greenthumb.data.PlantRepositoryOpener
import site.xmpp.greenthumb.ui.components.GtTextField
import site.xmpp.greenthumb.ui.components.PrimaryButton
import site.xmpp.greenthumb.ui.components.SecondaryButton
import site.xmpp.greenthumb.ui.screens.dashboard.DashboardScreen
import site.xmpp.greenthumb.ui.theme.Spacing

/**
 * Маршруты графа — 1:1 с expo-router `app/` (RN): имена фичи kmp-navigation
 * (Stage 6 п.1). Аргумент — `plant/{id}` (RN `plant/[id]`).
 */
public object NavRoutes {
    public const val WELCOME: String = "intro/welcome"
    public const val LOGIN: String = "auth/login"
    public const val ENABLE_NOTIFICATIONS: String = "auth/enable-notifications"
    public const val TABS_DASHBOARD: String = "tabs/dashboard"
    public const val TABS_PROFILE: String = "tabs/profile"
    public const val ADD_PLANT: String = "add-plant"
    public const val PLANT: String = "plant/{id}"

    /** Имя аргумента маршрута растения. */
    public const val PLANT_ARG: String = "id"
}

/**
 * Граф навигации (Stage 6 п.1, VAL-SHELL-001): маршруты [NavRoutes] + нижние
 * вкладки [GtBottomTabs] на tab-экранах (в RN add-plant/plant/[id] живут вне
 * группы (tabs) — панель на них не показывается).
 *
 * Экраны — плейсхолдеры до Stage 7 (фичи screen-*): интерим-поверхность
 * дашборда несёт данные и триггеры навигации; welcome/enable-notifications/
 * add-plant/plant/{id} — маршрутные заглушки (enable-notifications по контракту
 * VAL-INTRO-003 недостижима из логина напрямую — только из show-key, Stage 7).
 * Старт — [resolveStartRoute]; смена состояния сессии (вход/выход) пересоздаёт
 * граф с новым стартовым маршрутом — как RN-редирект с index-экрана.
 */
@Composable
public fun GtAppNavGraph(
    startDestination: String,
    sessionUserId: String?,
    onlineSession: Boolean,
    session: SessionManager,
    connectivity: Connectivity,
    plants: PlantRepositoryOpener,
) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = startDestination) {
        composable(NavRoutes.WELCOME) {
            PlaceholderScreen(title = NavRoutes.WELCOME, note = "карусель интро — Stage 7")
        }
        composable(NavRoutes.LOGIN) {
            InterimLoginScreen(session = session)
        }
        composable(NavRoutes.ENABLE_NOTIFICATIONS) {
            PlaceholderScreen(
                title = NavRoutes.ENABLE_NOTIFICATIONS,
                note = "запрос разрешения — Stage 7 (достижим только после show-key)",
            )
        }
        composable(NavRoutes.TABS_DASHBOARD) {
            TabShell(selected = GtTab.Plants, onSelect = { navController.navigateTab(it.route) }) {
                if (sessionUserId != null) {
                    DashboardScreen(
                        userId = sessionUserId,
                        onlineSession = onlineSession,
                        connectivity = connectivity,
                        opener = plants,
                        onOpenPlant = { plantId -> navController.navigate("plant/$plantId") },
                        onAddPlant = { navController.navigate(NavRoutes.ADD_PLANT) },
                    )
                }
            }
        }
        composable(NavRoutes.TABS_PROFILE) {
            TabShell(selected = GtTab.Profile, onSelect = { navController.navigateTab(it.route) }) {
                InterimProfileScreen(session = session)
            }
        }
        composable(NavRoutes.ADD_PLANT) {
            PlaceholderScreen(title = NavRoutes.ADD_PLANT, note = "форма растения — Stage 7")
        }
        composable(NavRoutes.PLANT) { entry ->
            // SavedState в navigation 2.9.2 мультиплатформенный: строковые
            // аргументы читаются SavedStateReader'ом (на Android SavedState —
            // Bundle, читатель тот же API).
            val plantId = entry.arguments?.read { getStringOrNull(NavRoutes.PLANT_ARG) }
            PlaceholderScreen(
                title = "plant/$plantId",
                note = "детали растения — Stage 7",
            )
        }
    }
}

/**
 * Переключение вкладок (канонический паттерн navigation-compose): сохранение
 * состояния соседней вкладки и возврат к нему — как собственные стеки вкладок
 * expo-router Tabs.
 */
private fun NavHostController.navigateTab(route: String) {
    navigate(route) {
        popUpTo(graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** Каркас tab-экрана: контент + панель вкладок снизу. */
@Composable
private fun TabShell(
    selected: GtTab,
    onSelect: (GtTab) -> Unit,
    content: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background),
    ) {
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            content()
        }
        GtBottomTabs(selected = selected, onSelect = onSelect)
    }
}

/** Заглушка экрана до Stage 7: маршрут + примечание, по центру. */
@Composable
private fun PlaceholderScreen(
    title: String,
    note: String,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background)
            .padding(Spacing.lg),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            PlaceholderHeader(title = title, note = note)
        }
    }
}

/** Шапка-заглушка для интерим-экранов с контентом (без full-size). */
@Composable
private fun PlaceholderHeader(
    title: String,
    note: String,
) {
    val scheme = MaterialTheme.colorScheme
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = scheme.onSurface,
        )
        Spacer(modifier = Modifier.height(Spacing.sm))
        Text(
            text = note,
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
        )
    }
}

/**
 * Промежуточный логин: dev-форма входа ключом из скелета Stage 3/4 — держит
 * вход достижимости харнесса до Stage 7 (screen-login: 4 режима + шлюз
 * show-key). Заменяется целиком фичей screen-login.
 */
@Composable
private fun InterimLoginScreen(session: SessionManager) {
    var key by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.lg),
    ) {
        Spacer(modifier = Modifier.height(Spacing.xxl))
        PlaceholderHeader(title = NavRoutes.LOGIN, note = "вход — Stage 7 (4 режима)")
        Spacer(modifier = Modifier.height(Spacing.lg))
        GtTextField(
            value = key,
            onValueChange = {
                key = it
                error = ""
            },
            label = "Recovery key",
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(Spacing.sm))
        PrimaryButton(
            text = "Sign in",
            onClick = {
                val entered = key.trim()
                if (entered.isEmpty()) return@PrimaryButton
                scope.launch {
                    error = ""
                    try {
                        session.signInWithRecoveryKey(entered)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: ApiError) {
                        error = "Sign-in failed"
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        if (error.isNotEmpty()) {
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * Промежуточный профиль: выход из скелета — единственный UI-триггер выхода
 * до Stage 7 (screen-profile портит все настройки). Держит матрицу выхода
 * (VAL-DATA-008) доступной из UI.
 */
@Composable
private fun InterimProfileScreen(session: SessionManager) {
    val scope = rememberCoroutineScope()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.lg),
    ) {
        Spacer(modifier = Modifier.height(Spacing.xxl))
        PlaceholderHeader(title = NavRoutes.TABS_PROFILE, note = "настройки профиля — Stage 7")
        Spacer(modifier = Modifier.height(Spacing.lg))
        SecondaryButton(
            text = "Sign out",
            onClick = { scope.launch { session.signOut() } },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
