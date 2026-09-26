package site.xmpp.greenthumb

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import site.xmpp.greenthumb.core.platform.AppEnvironment
import site.xmpp.greenthumb.core.platform.AppLocaleSyncRoot
import site.xmpp.greenthumb.core.platform.Connectivity
import site.xmpp.greenthumb.core.platform.PushTokens
import site.xmpp.greenthumb.core.platform.isSystemDarkTheme
import site.xmpp.greenthumb.core.storage.AppPreferencesStore
import site.xmpp.greenthumb.core.storage.SessionManager
import site.xmpp.greenthumb.core.storage.SessionState
import site.xmpp.greenthumb.data.PlantRepositoryOpener
import site.xmpp.greenthumb.ui.nav.GtAppNavGraph
import site.xmpp.greenthumb.ui.nav.GtHandoffImportFailedScreen
import site.xmpp.greenthumb.ui.nav.GtKeyNotFoundScreen
import site.xmpp.greenthumb.ui.nav.GtSplash
import site.xmpp.greenthumb.ui.nav.NavRoutes
import site.xmpp.greenthumb.ui.nav.PostSignInHost
import site.xmpp.greenthumb.ui.nav.PostSignInSurface
import site.xmpp.greenthumb.ui.nav.StartRoute
import site.xmpp.greenthumb.ui.nav.resolveStartRoute
import site.xmpp.greenthumb.ui.nav.sessionUserIdOrNull
import site.xmpp.greenthumb.ui.theme.GreenThumbTheme
import site.xmpp.greenthumb.ui.theme.resolveDarkTheme

/**
 * Корень приложения (Stage 6 п.1–2): тема + стартовая маршрутизация + граф
 * навигации. Заменяет скелет Stage 3/4: статус-строки сессии и dev-форма
 * входа переехали в маршруты ([site.xmpp.greenthumb.ui.nav.GtAppNavGraph]),
 * временная строка «Offline…» НЕ перенесена (наблюдение M4 user-testing —
 * полоса офлайна и replay живут на вкладке дашборда и обновляются без
 * рестарта).
 *
 * Стартовая маршрутизация — [resolveStartRoute] (порт RN `app/index.tsx`):
 * гейт готовности → индикатор без редиректа; пользователь → дашборд;
 * иначе интро; иначе логин. Поверхности [SessionState.KeyNotFound] и
 * [SessionState.HandoffImportFailed] — вне графа (KMP-состояния Stage 3,
 * кнопки — Stage 7).
 *
 * Окно ПОСЛЕ входа (Stage 7 п.2–3): RN подавлял автопереход на вкладки в
 * режиме show-key (`login.tsx:41-45`) и после подтверждения вёл на
 * enable-notifications (`login.tsx:88`). KMP-эквивалент: [postSignIn] —
 * remember-состояние корня, переживает пересоздание графа при смене состояния
 * сессии (вход/выход); поверхности — [PostSignInSurface] (show-key — интерим
 * до screen-login, enable-notifications — Stage 7 п.2). Окно активно, только
 * пока сессия жива (SignedIn): выход/401 закрывает его. Вне окна
 * enable-notifications недостижима — VAL-INTRO-003 (маршрут `auth/
 * enable-notifications` включается только в этой ветке, из логина напрямую
 * не открыть).
 *
 * Тема (Stage 6 п.3, VAL-THEME-001/002): предпочтение light/dark/auto из
 * AppSettings (`greenthumb_theme`; null = не задано = auto, дефолт RN) +
 * системная схема через [isSystemDarkTheme] (androidMain —
 * LocalConfiguration/UI_MODE_NIGHT_MASK; jvmMain — LocalSystemTheme) —
 * [resolveDarkTheme]. Auto в реальном времени следует за системой: на Android
 * смена uimode обновляет LocalConfiguration без пересоздания Activity
 * (`android:configChanges="uiMode"` в манифесте androidApp), на desktop
 * LocalSystemTheme обновляется опросом.
 *
 * Язык (Stage 6 п.4–5): [AppEnvironment] вокруг всего контента — preference из
 * AppSettings (`greenthumb_language`; null = системная) + CompositionLocal.
 * Платформенная синхронизация языка — вне Compose (M6b VAL-I18N-007):
 * androidMain вписывает выбор в Configuration/LocaleList каждой доставки
 * конфигурации (MainActivity/AppLocale), поэтому uiMode-переключения при
 * невидимом Profile не возвращают подписи к системному языку (r2-anomaly).
 * key() на смену языка НЕ здесь, а в [AppLocalizedContent] вокруг локализованного
 * UI ([site.xmpp.greenthumb.ui.nav.GtAppNavGraph]): NavHost и back stack живут
 * вне ключа, смена языка не сбрасывает выбранную вкладку и не перезапускает
 * сессию (VAL-I18N-006). Старт сессии — вне [AppEnvironment] так же по другой
 * причине: она запускается один раз на жизнь процесса.
 */
@Composable
fun App(
    session: SessionManager,
    connectivity: Connectivity,
    plants: PlantRepositoryOpener,
    settings: AppPreferencesStore,
    push: PushTokens,
) {
    // M6b (VAL-I18N-007): синхронизация выбранной локали с платформой в корне
    // тела — до детей. Android: чтение LocalConfiguration делает корень
    // реактивным к каждой доставке конфигурации (в т.ч. display-ступеням
    // мимо activity-колбэка) и переприменяет выбор ДО композиции подписей;
    // desktop — no-op. Детали — [AppLocaleSyncRoot].
    AppLocaleSyncRoot()
    val themePreference by settings.theme.collectAsState(initial = null)
    val systemDark = isSystemDarkTheme()
    // Локаль (Stage 6 п.4): preference из AppSettings (greenthumb_language) или
    // null = системная (RN-дефолт: язык устройства вне en/ru → фолбэк ресурсов en).
    // key(customAppLocale) внутри — перекомпоновка всех подписей при смене языка.
    val languagePreference by settings.language.collectAsState(initial = null)
    // Язык подписки пушей (M7): wire-значение выбора; null (системная) —
    // RN-фолбэк subscribeToExpoNotifications ('ru'). Экран enable-notifications
    // передаёт его в [PushTokens.requestSubscribe] (активная реализация M9).
    val pushLanguage = languagePreference?.wire ?: "ru"
    // Окно после входа (Stage 7 п.2–3): RN-подавление автоперехода из show-key.
    // remember (не rememberSaveable): после смерти процесса RN-эквивалент тоже
    // потерян (mode в useState) — пользователь повторяет вход.
    var postSignIn by remember { mutableStateOf<PostSignInSurface?>(null) }
    // Потеря сессии (выход/явный 401) закрывает окно и сбрасывает поверхность:
    // ключ нового входа (если пользователь войдёт снова) обязан быть свежим —
    // stale-ключ старого аккаунта не показывается. RN-паритет: после потери
    // сессии continue из show-key всё равно уводил в auth gate (вкладки
    // недостижимы), здесь то же состояние достигается сразу.
    LaunchedEffect(postSignIn != null) {
        if (postSignIn != null && session.state.value !is SessionState.SignedIn) {
            postSignIn = null
        }
    }
    // Единственный запуск стартовой последовательности на жизнь процесса —
    // ВНЕ [AppEnvironment]: поддерево под key(customAppLocale) в
    // [AppLocalizedContent] пересоздаётся при смене языка, и LaunchedEffect
    // внутри перезапустил бы startup() (сплеш-вспышка + повторный me-запрос;
    // транзиентный сбой выкинул бы на логин). Перекомпоновка подписей сессию
    // не трогает (Stage 6 п.5, VAL-I18N-004/006). startupIfNeeded, а не
    // startup(): на Android пересоздание Activity (поворот, масштаб шрифта)
    // монтирует новый состав UI поверх того же менеджера (граф — на процесс,
    // architecture.md §9) — инициализированная сессия не перезапускается
    // (фикс M6 VAL-SHELL-003).
    LaunchedEffect(Unit) { session.startupIfNeeded() }
    AppEnvironment(customAppLocale = languagePreference?.wire) {
        GreenThumbTheme(darkTheme = resolveDarkTheme(themePreference, systemDark)) {
            val state by session.state.collectAsState()
            val introSeen by settings.introSeen.collectAsState(initial = null)
            val route = resolveStartRoute(state, introSeen)
            // Окно после входа активно, только пока пользователь действительно
            // вошёл и маршрутизация ведёт на вкладки (RN: user && mode ==
            // 'show-key' — подавление; выход/401 закрывают окно).
            val windowActive = state is SessionState.SignedIn && route == StartRoute.Dashboard
            if (windowActive && postSignIn != null) {
                // Дашборд отложен до завершения окна (RN: вкладки не показываются
                // из show-key). Смена языка пересчитывает route от того же
                // состояния — окно не теряется.
                PostSignInHost(
                    surface = postSignIn!!,
                    push = push,
                    pushLanguage = pushLanguage,
                    onDone = { postSignIn = null },
                )
            } else {
                when (route) {
                    StartRoute.Loading -> GtSplash()
                    is StartRoute.HandoffImportFailed ->
                        GtHandoffImportFailedScreen(recoveryKey = route.recoveryKey)
                    StartRoute.KeyNotFound -> GtKeyNotFoundScreen()
                    StartRoute.Dashboard -> GtAppNavGraph(
                        startDestination = NavRoutes.TABS_DASHBOARD,
                        sessionUserId = state.sessionUserIdOrNull(),
                        onlineSession = state is SessionState.SignedIn,
                        session = session,
                        connectivity = connectivity,
                        plants = plants,
                        settings = settings,
                    )
                    StartRoute.Welcome -> GtAppNavGraph(
                        startDestination = NavRoutes.WELCOME,
                        sessionUserId = null,
                        onlineSession = false,
                        session = session,
                        connectivity = connectivity,
                        plants = plants,
                        settings = settings,
                    )
                    // Ветка логина — единственный производитель ключа (RN
                    // handleCreateAccount → setMode('show-key')); колбэк открывает
                    // окно после входа, подавляя автопереход на вкладки.
                    StartRoute.Login -> GtAppNavGraph(
                        startDestination = NavRoutes.LOGIN,
                        sessionUserId = null,
                        onlineSession = false,
                        session = session,
                        connectivity = connectivity,
                        plants = plants,
                        settings = settings,
                        onAccountCreated = { recoveryKey ->
                            postSignIn = PostSignInSurface.ShowKey(recoveryKey)
                        },
                    )
                }
            }
        }
    }
}
