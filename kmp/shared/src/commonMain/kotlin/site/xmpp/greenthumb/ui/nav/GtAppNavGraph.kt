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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.savedstate.read
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.stringResource
import site.xmpp.greenthumb.core.platform.AppLocalizedContent
import site.xmpp.greenthumb.core.platform.Connectivity
import site.xmpp.greenthumb.core.platform.PushTokens
import site.xmpp.greenthumb.core.storage.AppPreferencesStore
import site.xmpp.greenthumb.core.storage.SessionManager
import site.xmpp.greenthumb.core.storage.SessionState
import site.xmpp.greenthumb.core.network.PlantDto
import site.xmpp.greenthumb.data.PlantRepositoryOpener
import site.xmpp.greenthumb.season.rememberSeasonContext
import site.xmpp.greenthumb.ui.components.GtAlertDialog
import site.xmpp.greenthumb.ui.components.GtAlertButton
import site.xmpp.greenthumb.ui.res.Res
import site.xmpp.greenthumb.ui.res.common_error
import site.xmpp.greenthumb.ui.res.plantDetails_deleteQueuedMessage
import site.xmpp.greenthumb.ui.res.plantDetails_deleteQueuedTitle
import site.xmpp.greenthumb.ui.screens.addplant.AddPlantDraftStore
import site.xmpp.greenthumb.ui.screens.addplant.AddPlantScreen
import site.xmpp.greenthumb.ui.screens.dashboard.DashboardScreen
import site.xmpp.greenthumb.ui.screens.diagnosis.DiagnosisJournalSaver
import site.xmpp.greenthumb.ui.screens.diagnosis.DiagnosisLogic
import site.xmpp.greenthumb.ui.screens.diagnosis.DiagnosisScreen
import site.xmpp.greenthumb.ui.screens.diagnosis.PlantDiagnoser
import site.xmpp.greenthumb.ui.screens.login.LoginMode
import site.xmpp.greenthumb.ui.screens.login.LoginScreen
import site.xmpp.greenthumb.ui.screens.login.SavedKeyOutcome
import site.xmpp.greenthumb.ui.screens.plantdetail.PlantDeleteErrorState
import site.xmpp.greenthumb.ui.screens.plantdetail.PlantDeleteNotice
import site.xmpp.greenthumb.ui.screens.plantdetail.PlantDetailScreen
import site.xmpp.greenthumb.ui.screens.profile.DeleteAccountScreen
import site.xmpp.greenthumb.ui.screens.profile.ProfileScreen
import site.xmpp.greenthumb.ui.screens.profile.RecoveryKeyQrScreen
import site.xmpp.greenthumb.ui.screens.progress.ProgressScreen
import site.xmpp.greenthumb.ui.screens.addplant.AddPlantDraft
import site.xmpp.greenthumb.ui.screens.smartcam.SmartCameraMode
import site.xmpp.greenthumb.ui.screens.smartcam.SmartCameraPlant
import site.xmpp.greenthumb.ui.screens.smartcam.SmartCameraScreen
import site.xmpp.greenthumb.ui.screens.smartcam.StandaloneDiagnoser
import site.xmpp.greenthumb.ui.screens.species.SpeciesIdentifier
import site.xmpp.greenthumb.ui.screens.welcome.WelcomeScreen
import site.xmpp.greenthumb.ui.theme.LocalReduceMotion
import site.xmpp.greenthumb.ui.theme.Spacing
import site.xmpp.greenthumb.ui.theme.screenTransitions

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
    /** Diagnosis of one plant (architecture §5.7), opened from plant detail. */
    public const val PLANT_DIAGNOSIS: String = "plant/{id}/diagnosis"
    /** «Проверить на болезни» from the photo sheet: the same screen with the plant cover preset. */
    public const val PLANT_DIAGNOSIS_COVER: String = "plant/{id}/diagnosis/cover"
    /** Экран подтверждения удаления аккаунта (Stage 12 п.4; в RN нет). */
    public const val DELETE_ACCOUNT: String = "profile/delete-account"
    /** Recovery key as a QR code (architecture §5.3). */
    public const val KEY_QR: String = "profile/recovery-key-qr"
    /** Streaks and achievements (architecture §5.8). */
    public const val PROGRESS: String = "profile/progress"

    /** Smart camera (D§ item 9a); `mode` is `plant` (default) or `disease`. */
    public const val SMART_CAMERA: String = "smartcam?mode={mode}"
    public const val SMART_CAMERA_MODE_ARG: String = "mode"

    /** Add Plant prefilled by the smart camera; the token names the draft in [AddPlantDraftStore]. */
    public const val ADD_PLANT_DRAFT: String = "add-plant/draft/{draft}"
    public const val ADD_PLANT_DRAFT_ARG: String = "draft"

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
    /** Saved-key outcome from Key not found (e.g. stale key), shown in login mode. */
    initialLoginNotice: SavedKeyOutcome? = null,
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
     * Deep-link пуша (Stage 9 п.7, фича kmp-push-offline-routing):
     * `data.plant_id` из launch-intent. App() передаёт id ТОЛЬКО в
     * SignedIn-ветке (без сессии extra не открывает данные и не обходит
     * вход); до того значение живёт в держателе точки входа — переход
     * отложен до входа.
     */
    pendingPlantId: String? = null,
    /** id потреблён графом (переход выполнен или отклонён) — держатель чистит. */
    onPendingPlantIdConsumed: () -> Unit = {},
    /**
     * Ошибка удаления растения (screen-plant-detail, RN-паритет onError
     * deleteMutation: экран уже ушел на дашборд из onMutate, Alert — поверх
     * нового экрана). Владелец-помощник здесь, remember — наружу
     * [PlantDeleteErrorState]; вкладка дашборда не обязана знать.
     */
    deleteErrorState: PlantDeleteErrorState = remember { PlantDeleteErrorState() },
    /** Identification by photo in Add Plant (architecture §5.4). */
    speciesIdentifier: SpeciesIdentifier? = null,
    /** Plant diagnosis by photo (architecture §5.7); null hides the plant-detail entry. */
    plantDiagnoser: PlantDiagnoser? = null,
) {
    val navController = rememberNavController()
    // Null for the few milliseconds auto mode waits for the latitude table (preloaded in App());
    // season-dependent screens stay blank instead of showing unadjusted statuses that then flip.
    val season = rememberSeasonContext(session)
    val transitions = screenTransitions(LocalReduceMotion.current)
    val addPlantDrafts = remember { AddPlantDraftStore() }
    GtPageRoot {
        NavHost(
            navController = navController,
            startDestination = startDestination,
            enterTransition = { transitions.enter },
            exitTransition = { transitions.exit },
            popEnterTransition = { transitions.enter },
            popExitTransition = { transitions.exit },
        ) {
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
                        initialNotice = initialLoginNotice,
                    )
                }
            }
            composable(NavRoutes.TABS_DASHBOARD) {
                AppLocalizedContent {
                    TabShell(selected = GtTab.Plants, onSelect = { navController.navigateTab(it.route) }) {
                        if (sessionUserId != null && season != null) {
                            DashboardScreen(
                                userId = sessionUserId,
                                session = session,
                                onlineSession = onlineSession,
                                connectivity = connectivity,
                                opener = plants,
                                settings = settings,
                                onOpenPlant = { plantId -> navController.navigate("plant/$plantId") },
                                onAddPlant = { navController.navigate(NavRoutes.ADD_PLANT) },
                                season = season,
                                onOpenSmartCamera = { navController.navigate("smartcam") },
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
                            onDeleteAccount = { navController.navigate(NavRoutes.DELETE_ACCOUNT) },
                            onShowKeyQr = { navController.navigate(NavRoutes.KEY_QR) },
                            onOpenProgress = sessionUserId?.let { { navController.navigate(NavRoutes.PROGRESS) } },
                        )
                    }
                }
            }
            // Экран подтверждения удаления аккаунта (Stage 12 п.4, VAL-REL-003):
            // подтверждение → session.deleteAccount() → SignedOut пересобирает
            // граф на экран входа (App()); экрану самостоятельная навигация
            // после успеха не нужна. Back — popBackStack на профиль.
            composable(NavRoutes.DELETE_ACCOUNT) {
                AppLocalizedContent {
                    DeleteAccountScreen(
                        session = session,
                        onBack = { navController.popBackStack() },
                    )
                }
            }
            composable(NavRoutes.KEY_QR) {
                AppLocalizedContent {
                    RecoveryKeyQrScreen(
                        session = session,
                        onBack = { navController.popBackStack() },
                    )
                }
            }
            composable(NavRoutes.PROGRESS) {
                AppLocalizedContent {
                    if (sessionUserId != null) {
                        ProgressScreen(
                            userId = sessionUserId,
                            opener = plants,
                            isOnline = connectivity.isOnline,
                            onBack = { navController.popBackStack() },
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
                            identifier = speciesIdentifier,
                        )
                    }
                }
            }
            composable(
                NavRoutes.SMART_CAMERA,
                arguments = listOf(
                    navArgument(NavRoutes.SMART_CAMERA_MODE_ARG) {
                        type = NavType.StringType
                        defaultValue = SmartCameraMode.Plant.wire
                    },
                ),
            ) { entry ->
                val mode = SmartCameraMode.fromWire(entry.arguments?.read { getStringOrNull(NavRoutes.SMART_CAMERA_MODE_ARG) })
                AppLocalizedContent {
                    SmartCameraRoute(
                        userId = sessionUserId,
                        plants = plants,
                        initialMode = mode,
                        identifier = speciesIdentifier,
                        diagnoser = plantDiagnoser as? StandaloneDiagnoser,
                        onClose = { navController.popBackStack() },
                        onAddToMine = { draft ->
                            val token = addPlantDrafts.put(draft)
                            navController.navigate("add-plant/draft/$token") {
                                popUpTo(NavRoutes.SMART_CAMERA) { inclusive = true }
                            }
                        },
                    )
                }
            }
            composable(NavRoutes.ADD_PLANT_DRAFT) { entry ->
                val token = entry.arguments?.read { getStringOrNull(NavRoutes.ADD_PLANT_DRAFT_ARG) }.orEmpty()
                // Taken outside the localized subtree, so a language change keeps the prefilled form.
                val draft = remember(token) { addPlantDrafts.take(token) }
                AppLocalizedContent {
                    if (sessionUserId != null) {
                        AddPlantScreen(
                            userId = sessionUserId,
                            opener = plants,
                            onBack = { navController.popBackStack() },
                            identifier = speciesIdentifier,
                            draft = draft,
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
                    if (season != null) {
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
                            isOnline = connectivity.isOnline,
                            season = season,
                            onOpenDiagnosis = plantDiagnoser?.let { { navController.navigate("plant/$plantId/diagnosis") } },
                            onCheckPhoto = plantDiagnoser?.let { { navController.navigate("plant/$plantId/diagnosis/cover") } },
                        )
                    }
                }
            }
            for ((route, useCover) in listOf(NavRoutes.PLANT_DIAGNOSIS to false, NavRoutes.PLANT_DIAGNOSIS_COVER to true)) {
                composable(route) { entry ->
                    val plantId = entry.arguments?.read { getStringOrNull(NavRoutes.PLANT_ARG) }.orEmpty()
                    AppLocalizedContent {
                        if (plantDiagnoser != null && sessionUserId != null) {
                            PlantDiagnosisRoute(
                                userId = sessionUserId,
                                plantId = plantId,
                                plants = plants,
                                diagnoser = plantDiagnoser,
                                useCover = useCover,
                                onBack = { navController.popBackStack() },
                            )
                        }
                    }
                }
            }
        }
    }
    // Deep-link пуша (Stage 9 п.7): тап по FCM-пуши с `data.plant_id`
    // (number|string — RN-контракт app/_layout.tsx:50-54; FCM кладёт data
    // в launch-intent; тап по строке виджета — так же) открывает plant/{id}.
    // Переход только при аккаунте (SignedIn или Offline): без сессии (ветки
    // welcome/login) extra не читается и вход не обходится — id ждёт в
    // держателе точки входа до входа.
    // Пустой id — no-op (локальное тестовое уведомление extras не несёт).
    // Порядок: переход → refresh → consume (consume меняет ключи эффекта —
    // работа ДО него не должна ждать, работа ПОСЛЕ — не планироваться).
    LaunchedEffect(pendingPlantId, sessionUserId) {
        val plantId = pendingPlantId
        if (plantId.isNullOrEmpty() || sessionUserId == null) return@LaunchedEffect
        try {
            navController.navigate("plant/$plantId")
        } catch (malformed: IllegalArgumentException) {
            // id вне формата маршрута (`/`, `?` — произвол FCM data) —
            // не крашить: карточка не открывается, id потребляется.
        }
        // Холодный старт: кадр ушёл на карточку раньше первого sync дашборда —
        // Room пуст, и данные карточке доставляет refresh (наблюдение экрана
        // реактивно: Loading/NotFound → карточка, когда ответ ляжет в Room).
        // Тёплый тап по уже кэшированному растению — без лишнего GET (тяжёлые
        // photo_url в ответе; паритет RN — из кэша). Провал refresh — честный
        // NotFound экрана (RN error-ветка), ошибку сюда не поднимаем.
        val repo = plants.open(sessionUserId)
        val cached = try {
            repo.observePlants().first()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            null
        }
        if (cached == null || cached.none { it.id == plantId }) {
            try {
                repo.refresh()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                // Тихо: карточка уже показывает NotFound; при возврате на
                // дашборд его собственный sync повторит попытку.
            }
        }
        onPendingPlantIdConsumed()
    }
    // Алерт отказа удаления — НАД графом (RN onError deleteMutation: мутация
    // уже увела экран на дашборд, Alert показывается поверх нового экрана).
    // Две ветки VAL-DETAIL-004 (решение пользователя 2026-09-27): определённый
    // 4xx/5xx → «Ошибка» + текст сервера; Network/Timeout → честный статус
    // «удаление выполнится после подключения» (очередь M4 досошлёт удаление).
    // Закрытие чистит состояние.
    val deleteNotice = deleteErrorState.notice
    if (deleteNotice != null) {
        when (deleteNotice) {
            is PlantDeleteNotice.Failed -> GtAlertDialog(
                title = stringResource(Res.string.common_error),
                message = deleteNotice.message,
                buttons = listOf(GtAlertButton(text = "OK")),
                onDismissRequest = { deleteErrorState.notice = null },
            )
            PlantDeleteNotice.Queued -> GtAlertDialog(
                title = stringResource(Res.string.plantDetails_deleteQueuedTitle),
                message = stringResource(Res.string.plantDetails_deleteQueuedMessage),
                buttons = listOf(GtAlertButton(text = "OK")),
                onDismissRequest = { deleteErrorState.notice = null },
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
 * Промежуточный профиль (Stage 6) заменён полноценным экраном
 * [ProfileScreen] (Stage 7 п.4, фича screen-profile): пикеры языка/темы,
 * recovery key, время уведомления, пуши-секция, выход.
 */

/**
 * Smart camera with this account's plants for «Это моё растение?»: the list comes from the local
 * cache, and the save goes through the plant timeline like the plant diagnosis route.
 */
@Composable
private fun SmartCameraRoute(
    userId: String?,
    plants: PlantRepositoryOpener,
    initialMode: SmartCameraMode,
    identifier: SpeciesIdentifier?,
    diagnoser: StandaloneDiagnoser?,
    onClose: () -> Unit,
    onAddToMine: (AddPlantDraft) -> Unit,
) {
    val repo = remember(userId) { userId?.let { plants.open(it) } }
    val myPlants by remember(repo) {
        val source = repo?.observePlants() ?: flowOf(emptyList())
        source
            .map { list -> list.map { SmartCameraPlant(it.id, it.name, it.photoUrl) }.sortedBy { it.name.lowercase() } }
            .catch { emit(emptyList()) }
    }.collectAsState(initial = emptyList())
    val journal = remember(repo) {
        repo?.let { r -> DiagnosisJournalSaver { id, uri, caption -> r.timeline.addPhoto(id, uri, caption) } }
    }
    SmartCameraScreen(
        initialMode = initialMode,
        identifier = identifier,
        diagnoser = diagnoser,
        onClose = onClose,
        onAddToMine = onAddToMine,
        myPlants = myPlants,
        journal = journal,
    )
}

/** The diagnosed plant as found in this account's cache; null plant = not one of its plants. */
private data class DiagnosisPlantLookup(val plant: PlantDto?)

/**
 * «Что с растением?» route. The journal save is offered only when the plant is one of this
 * account's plants; [useCover] presets the plant cover instead of asking for a photo.
 */
@Composable
private fun PlantDiagnosisRoute(
    userId: String,
    plantId: String,
    plants: PlantRepositoryOpener,
    diagnoser: PlantDiagnoser,
    useCover: Boolean,
    onBack: () -> Unit,
) {
    val repo = remember(userId) { plants.open(userId) }
    // Looked up once: a later list change must not rebuild the open screen.
    var lookup by remember(repo, plantId) { mutableStateOf<DiagnosisPlantLookup?>(null) }
    LaunchedEffect(repo, plantId) {
        lookup = DiagnosisPlantLookup(
            try {
                repo.observePlants().first().firstOrNull { it.id == plantId }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                null
            },
        )
    }
    val plant = (lookup ?: return).plant
    val journal = remember(repo, plant?.id) {
        plant?.let { DiagnosisJournalSaver { id, uri, caption -> repo.timeline.addPhoto(id, uri, caption) } }
    }
    val cover = plant?.photoUrl?.takeIf { useCover && it.isNotBlank() }
    DiagnosisScreen(
        plantId = plantId,
        diagnoser = diagnoser,
        onBack = onBack,
        journal = journal,
        initialPhoto = cover?.let { url -> { DiagnosisLogic.coverPhoto(url, diagnoser::fetchPhoto) } },
        subtitle = plant?.let { listOf(it.name, it.location).filter { part -> part.isNotBlank() }.joinToString(" · ") },
    )
}
