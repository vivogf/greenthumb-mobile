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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.savedstate.read
import org.jetbrains.compose.resources.stringResource
import site.xmpp.greenthumb.core.platform.AppLocalizedContent
import site.xmpp.greenthumb.core.platform.Connectivity
import site.xmpp.greenthumb.core.platform.PushTokens
import site.xmpp.greenthumb.core.storage.AppPreferencesStore
import site.xmpp.greenthumb.core.storage.SessionManager
import site.xmpp.greenthumb.core.storage.SessionState
import site.xmpp.greenthumb.data.PlantRepositoryOpener
import site.xmpp.greenthumb.ui.components.GtAlertDialog
import site.xmpp.greenthumb.ui.components.GtAlertButton
import site.xmpp.greenthumb.ui.res.Res
import site.xmpp.greenthumb.ui.res.common_error
import site.xmpp.greenthumb.ui.screens.addplant.AddPlantScreen
import site.xmpp.greenthumb.ui.screens.dashboard.DashboardScreen
import site.xmpp.greenthumb.ui.screens.login.LoginMode
import site.xmpp.greenthumb.ui.screens.login.LoginScreen
import site.xmpp.greenthumb.ui.screens.plantdetail.PlantDeleteErrorState
import site.xmpp.greenthumb.ui.screens.plantdetail.PlantDetailScreen
import site.xmpp.greenthumb.ui.screens.profile.ProfileScreen
import site.xmpp.greenthumb.ui.screens.welcome.WelcomeScreen
import site.xmpp.greenthumb.ui.theme.Spacing

/**
 * Маршруты графа — 1:1 с expo-router `app/` (RN): имена фичи kmp-navigation
 * (Stage 6 п.1). Аргумент — `plant/{id}` (RN `plant/[id]`).
 *
 * `auth/enable-notifications` (RN-маршрут `app/(auth)/enable-notifications.tsx`)
 * в графе НЕТ: KMP восстанавливает NavHost при каждом изменении состояния входа
 * (вход = SignedIn → граф вкладок), поэтому после входа на дашборде старый
 * стек не доживает до чтения маршрута. RN-эквивалент шлюза — окно после входа
 * [PostSignInHost] в App(): RN тоже вёл на enable-notifications не маршрутом
 * вкладок, а `router.replace('/(auth)/enable-notifications')` из show-key
 * (`login.tsx:88`) при подавленном автопереходе на вкладки (`login.tsx:41-45`).
 * Экран достижим ТОЛЬКО из этого окна — VAL-INTRO-003 (из choose/login напрямую
 * не открыть; глубокая ссылка на неизвестный маршрут — стартовый экран графа).
 */
public object NavRoutes {
    public const val WELCOME: String = "intro/welcome"
    public const val LOGIN: String = "auth/login"
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
 * дашборда несёт данные и триггеры навигации; welcome/add-plant/plant/{id} —
 * маршрутные заглушки. Экран enable-notifications — Stage 7 п.2 (фича
 * screen-enable-notifications): живёт в окне после входа ([PostSignInHost],
 * RN-шлюз show-key login.tsx:41-45/88), НЕ в этом графе — VAL-INTRO-003.
 * Логин — полноценный порт Stage 7 п.3 (фича screen-login, [LoginScreen]):
 * режимы choose/create/login; create открывает шлюз показа ключа
 * (onAccountCreated → окно после входа, где
 * [site.xmpp.greenthumb.ui.screens.login.ShowKeySurface]). Старт —
 * [resolveStartRoute]; смена состояния сессии
 * (вход/выход) пересоздаёт граф с новым стартовым маршрутом — как RN-редирект
 * с index-экрана.
 *
 * Локализованное поддерево — [AppLocalizedContent] вокруг контента маршрутов и
 * панели вкладок (исправление VAL-I18N-006, архитектура §9): key(locale)
 * пересоздаёт только экраны, подписи перечитываются немедленно (VAL-I18N-004),
 * а rememberNavController + back stack живут СНАРУЖИ ключа — смена языка на
 * выбранной вкладке сохраняет маршрут (RN-паритет: редиректа на дашборд нет).
 * Плейсхолдерные заголовки читают [AppEnvironment]'овскую локаль на каждом
 * проходе — им ключ не нужен, но экраны Stage 7 с локальным remember-состоянием
 * обязаны быть под одним ключом с вкладками.
 */
@Composable
public fun GtAppNavGraph(
    startDestination: String,
    sessionUserId: String?,
    onlineSession: Boolean,
    session: SessionManager,
    connectivity: Connectivity,
    plants: PlantRepositoryOpener,
    settings: AppPreferencesStore,
    /**
     * Шов логина (M7 screen-login): создание аккаунта из режима create
     * сообщает свежий recovery key наверх — App() открывает окно после входа
     * (RN-шлюз show-key → enable-notifications, login.tsx:41-45/88). Ветка
     * графа Welcome/Login передаёт его, вкладки — нет (создание аккаунта там
     * не бывает).
     */
    onAccountCreated: (recoveryKey: String) -> Unit = {},
    /**
     * Стартовый режим логина (screen-login, VAL-HANDOFF-IMP-002): из экрана
     * «ключ не найден» кнопки ведут СРАЗУ в режимы login/create (RN setMode
     * login.tsx:82-85/89-90 — мимо choose). Ветка welcome — default.
     */
    initialLoginMode: LoginMode = LoginMode.Choose,
    /**
     * Push-подсистема (Stage 7 п.4, экран профиля; каркас — тумблер/тест
     * активной реализации ждут M9). Ветка welcome/login — dev-заглушка.
     */
    push: PushTokens = PushTokens(),
    /**
     * Язык подписки пушей (RN `i18n.language`, 'ru'/'en'): wire-значение
     * AppLanguage, при системной локали — RN-фолбэк 'ru'
     * (subscribeToExpoNotifications). Лямбда: значение на момент действия.
     */
    pushLanguage: () -> String = { "ru" },
    /**
     * Ошибка удаления растения (screen-plant-detail, RN-паритет onError
     * deleteMutation: экран уже ушел на дашборд из onMutate, Alert — поверх
     * нового экрана). Владелец-помощник здесь, remember — наружу
     * [PlantDeleteErrorState]; вкладка дашборда не обязана знать.
     */
    deleteErrorState: PlantDeleteErrorState = remember { PlantDeleteErrorState() },
) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = startDestination) {
        composable(NavRoutes.WELCOME) {
            AppLocalizedContent {
                WelcomeScreen(
                    settings = settings,
                    onFinish = {
                        navController.navigate(NavRoutes.LOGIN) {
                            // Единственное законное завершение карусели — вход:
                            // интро не возвращается по Back после завершения
                            // (RN router.replace — не push).
                            popUpTo(NavRoutes.WELCOME) { inclusive = true }
                        }
                    },
                )
            }
        }
        composable(NavRoutes.LOGIN) {
            AppLocalizedContent {
                LoginScreen(
                    session = session,
                    onAccountCreated = onAccountCreated,
                    initialMode = initialLoginMode,
                )
            }
        }
        composable(NavRoutes.TABS_DASHBOARD) {
            AppLocalizedContent {
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
        }
        composable(NavRoutes.TABS_PROFILE) {
            AppLocalizedContent {
                TabShell(selected = GtTab.Profile, onSelect = { navController.navigateTab(it.route) }) {
                    ProfileScreen(
                        session = session,
                        settings = settings,
                        push = push,
                        pushLanguage = { pushLanguage() },
                        onAddPlant = { navController.navigate(NavRoutes.ADD_PLANT) },
                    )
                }
            }
        }
        composable(NavRoutes.ADD_PLANT) {
            AppLocalizedContent {
                if (sessionUserId != null) {
                    AddPlantScreen(
                        userId = sessionUserId,
                        opener = plants,
                        onBack = { navController.popBackStack() },
                    )
                }
            }
        }
        composable(NavRoutes.PLANT) { entry ->
            // SavedState в navigation 2.9.2 мультиплатформенный: строковые
            // аргументы читаются SavedStateReader'ом (на Android SavedState —
            // Bundle, читатель тот же API).
            val plantId = entry.arguments?.read { getStringOrNull(NavRoutes.PLANT_ARG) }.orEmpty()
            AppLocalizedContent {
                PlantDetailScreen(
                    userId = sessionUserId ?: "",
                    plantId = plantId,
                    opener = plants,
                    onBack = { navController.popBackStack() },
                    // RN onMutate: router.replace('/') сразу после подтверждения
                    // — экран исчезает до ответа сети (репозиторий уже убрал
                    // строку из Room). popBackStack — дашборд с сохранённым
                    // состоянием вкладки.
                    onDeleted = { navController.popBackStack() },
                    deleteErrorState = deleteErrorState,
                )
            }
        }
    }
    // Алерт ошибки удаления — НАД графом (RN onError deleteMutation: мутация
    // уже увела экран на дашборд, Alert показывается поверх нового экрана).
    // message != null → алерт; закрытие чистит состояние.
    val deleteMessage = deleteErrorState.message
    if (deleteMessage != null) {
        val errorTitle = stringResource(Res.string.common_error)
        GtAlertDialog(
            title = errorTitle,
            message = deleteMessage,
            buttons = listOf(GtAlertButton(text = "OK")),
            onDismissRequest = { deleteErrorState.message = null },
        )
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
 * Промежуточный профиль (Stage 6) заменён полноценным экраном
 * [ProfileScreen] (Stage 7 п.4, фича screen-profile): пикеры языка/темы,
 * recovery key, время уведомления, пуши-секция, выход.
 */
