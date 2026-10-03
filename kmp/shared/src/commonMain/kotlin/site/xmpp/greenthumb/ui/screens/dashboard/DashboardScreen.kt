package site.xmpp.greenthumb.ui.screens.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import coil3.compose.AsyncImage
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import site.xmpp.greenthumb.core.network.PlantDto
import site.xmpp.greenthumb.core.platform.AppForeground
import site.xmpp.greenthumb.core.platform.Connectivity
import site.xmpp.greenthumb.core.storage.AppPreferencesStore
import site.xmpp.greenthumb.core.storage.LayoutMode
import site.xmpp.greenthumb.core.storage.SessionManager
import site.xmpp.greenthumb.core.storage.SessionState
import site.xmpp.greenthumb.data.PlantRepositoryOpener
import site.xmpp.greenthumb.data.RefreshCoordinator
import site.xmpp.greenthumb.data.RefreshOutcome
import site.xmpp.greenthumb.data.SyncBanner
import site.xmpp.greenthumb.data.SyncMetaSource
import site.xmpp.greenthumb.data.UnsavedMutation
import site.xmpp.greenthumb.data.WateringStatus
import site.xmpp.greenthumb.data.daysUntilWatering
import site.xmpp.greenthumb.data.wateringStatus
import site.xmpp.greenthumb.ui.components.GtAlertDialog
import site.xmpp.greenthumb.ui.components.GtAlertButton
import site.xmpp.greenthumb.ui.components.GtCheckMark
import site.xmpp.greenthumb.ui.components.GtChip
import site.xmpp.greenthumb.ui.components.GtClockMark
import site.xmpp.greenthumb.ui.components.GtCloudOffMark
import site.xmpp.greenthumb.ui.components.GtCloseMark
import site.xmpp.greenthumb.ui.components.GtEmptyState
import site.xmpp.greenthumb.ui.components.GtFunnelMark
import site.xmpp.greenthumb.ui.components.GtGridViewMark
import site.xmpp.greenthumb.ui.components.GtIconButton
import site.xmpp.greenthumb.ui.components.GtLeafMark
import site.xmpp.greenthumb.ui.components.GtListViewMark
import site.xmpp.greenthumb.ui.components.GtLocationMark
import site.xmpp.greenthumb.ui.components.GtCardViewMark
import site.xmpp.greenthumb.ui.components.GtPlusMark
import site.xmpp.greenthumb.ui.components.GtSearchMark
import site.xmpp.greenthumb.ui.components.GtSkeleton
import site.xmpp.greenthumb.ui.components.GtSkeletonMode
import site.xmpp.greenthumb.ui.components.GtThanosSnap
import site.xmpp.greenthumb.ui.components.GtTextField
import site.xmpp.greenthumb.ui.components.GtWaterButton
import site.xmpp.greenthumb.ui.components.GtWaterDropMark
import site.xmpp.greenthumb.ui.components.borderHairline
import site.xmpp.greenthumb.ui.components.pickerToday
import site.xmpp.greenthumb.ui.res.Res
import site.xmpp.greenthumb.ui.res.a11y_addPlant
import site.xmpp.greenthumb.ui.res.a11y_clearSearch
import site.xmpp.greenthumb.ui.res.a11y_openPlant
import site.xmpp.greenthumb.ui.res.a11y_postponeAll
import site.xmpp.greenthumb.ui.res.a11y_viewModeCard
import site.xmpp.greenthumb.ui.res.a11y_viewModeGrid
import site.xmpp.greenthumb.ui.res.a11y_viewModeList
import site.xmpp.greenthumb.ui.res.a11y_waterAll
import site.xmpp.greenthumb.ui.res.a11y_waterPlant
import site.xmpp.greenthumb.ui.res.common_error
import site.xmpp.greenthumb.ui.res.dashboard_addPlant
import site.xmpp.greenthumb.ui.res.dashboard_all
import site.xmpp.greenthumb.ui.res.dashboard_databaseError
import site.xmpp.greenthumb.ui.res.dashboard_healthy
import site.xmpp.greenthumb.ui.res.dashboard_lastSynced
import site.xmpp.greenthumb.ui.res.dashboard_myPlants
import site.xmpp.greenthumb.ui.res.dashboard_needsWater
import site.xmpp.greenthumb.ui.res.dashboard_noPlants
import site.xmpp.greenthumb.ui.res.dashboard_noPlantsFound
import site.xmpp.greenthumb.ui.res.dashboard_plantsWatered
import site.xmpp.greenthumb.ui.res.dashboard_postponeAll
import site.xmpp.greenthumb.ui.res.dashboard_refreshFailed
import site.xmpp.greenthumb.ui.res.dashboard_search
import site.xmpp.greenthumb.ui.res.dashboard_startTracking
import site.xmpp.greenthumb.ui.res.dashboard_tryDifferentFilter
import site.xmpp.greenthumb.ui.res.dashboard_tryDifferentSearch
import site.xmpp.greenthumb.ui.res.dashboard_waterAll
import site.xmpp.greenthumb.ui.res.dashboard_wateringAllPending
import site.xmpp.greenthumb.ui.res.plant_overdue
import site.xmpp.greenthumb.ui.res.plant_daysLeft
import site.xmpp.greenthumb.ui.res.plant_water
import site.xmpp.greenthumb.ui.res.plant_watered
import site.xmpp.greenthumb.ui.res.plant_waterToday
import site.xmpp.greenthumb.ui.theme.Motion
import site.xmpp.greenthumb.ui.theme.Radii
import site.xmpp.greenthumb.ui.theme.Spacing
import site.xmpp.greenthumb.ui.theme.greenThumbExtendedColors

/**
 * Альфа-хвосты статуса и баннера из RN (`colors.primary + '18'` и т.п.):
 * 0x18 = 24/255, 0x12 = 18/255, 0x24 = 36/255.
 */
private const val StatusPillAlpha: Float = 24f / 255f
private const val BulkPendingAlpha: Float = 18f / 255f
private const val BulkBannerBorderAlpha: Float = 36f / 255f

/**
 * Экран дашборда (Stage 7 п.7, фича screen-dashboard) — порт
 * `app/(tabs)/index.tsx` (1221 строка, прочитана целиком).
 *
 * Секции 1:1:
 * - шапка: заголовок, имя пользователя, баннер «обновлено в HH:mm»
 *   (возраст ≥60 с — [BANNER_AFTER_MILLIS]; иконка офлайна ≥300 с —
 *   [OFFLINE_ICON_AFTER_MILLIS]; тик 30 с — RN пересчитывал возраст на
 *   каждом рендере) и переключатель трёх режимов ([LayoutMode], персист
 *   через [AppPreferencesStore.setLayoutMode] — RN LAYOUT_MODE_STORE_KEY);
 * - поиск по имени и локации + кнопка очистки ([a11y_clearSearch]);
 * - фильтр-чипы all/needsWater/healthy со счётчиком needsWater
 *   ([GtChip] — порт FilterTab);
 * - кнопки «полить все»/«перенести все» — только при needsWaterCount>0 и
 *   фильтре needsWater (RN);
 * - баннер массового полива: «Поливаем N растений…» на время запроса →
 *   «N полито» на [Motion.BulkWaterSuccessBannerMs];
 * - список/карточки/сетка 3 колонки, статусы из [wateringStatus];
 * - pull-to-refresh ([PullToRefreshBox] — отдельный ручной стейт, не
 *   общий флаг);
 * - скелетон первой загрузки ([GtSkeleton]);
 * - два empty-state: «нет растений вообще» ([GtEmptyState] с кнопкой) и
 *   «ничего не найдено поиском/фильтром» (отдельный текст, RN
 *   tryDifferentSearch/tryDifferentFilter);
 * - FAB добавления.
 *
 * Данные и мутации — [site.xmpp.greenthumb.data.PlantRepository] (Room —
 * источник истины): refresh при ошибке таблицу не трогает, поэтому ошибка
 * обновления при живом кэше показывает список + полоску ошибки, а НЕ
 * полноэкранную ошибку поверх данных (VAL-DASH-009; полноэкранная ветка
 * RN — баг, не переносится). Поливы оптимистичны с откатом и журналом;
 * postponeAll — без оптимистичности, список меняется только после ответа
 * (VAL-DATA-004), алерта на ошибку переноса нет (RN-паритет).
 *
 * Отличия от RN, осознанные:
 * - анимации отвязаны от данных (architecture.md §7): репозиторий пишет
 *   Room немедленно, поэтому per-card «Watering…» массового полива RN
 *   (артефакт «кэш ещё не обновлён») не переносится — состояние несёт
 *   баннер; карточка при незакрытом журнале помечена [UnsavedMutation.LABEL].
 *   ThanosSnap — эффект одноразовым набором снап-id (M10); кнопки полива —
 *   [GtWaterButton] (частицы + Medium-гаптика внутри, RN-паритет);
 * -Ionicons → Canvas-метки (прецедент GtBottomTabs); «#ff6b6b»/«#fbbf24»
 *   сетки → scheme.error/extended.amber (литералы вне палитры не переносятся);
 * - RN-ошибка чтения кэша (полноэкранная) и полоса «не удалось обновить» —
 *   ключи `dashboard.databaseError`/`dashboard.refreshFailed` (KMP-only,
 *   KDoc i18n_check.py).
 *
 * Экран живёт под [AppLocalizedContent] (GtAppNavGraph): remember-состояние
 * (поиск, фильтр) перекомпоновывается вместе с подписями (правило Stage 7).
 * Репозиторий экран НЕ закрывает (владелец — opener; правило M6).
 */
@Composable
public fun DashboardScreen(
    userId: String,
    session: SessionManager,
    onlineSession: Boolean,
    connectivity: Connectivity,
    opener: PlantRepositoryOpener,
    settings: AppPreferencesStore,
    onOpenPlant: (String) -> Unit,
    onAddPlant: () -> Unit,
) {
    val repo = remember(userId) { opener.open(userId) }
    // Источник ON_RESUME (VAL-DASH-010): подписка живёт, пока открыт дашборд.
    // Android подписывается на lifecycle Activity; закрытие снимает колбэк.
    val foreground = remember(repo) { AppForeground() }
    DisposableEffect(foreground) { onDispose { foreground.close() } }
    val scheme = MaterialTheme.colorScheme
    val extended = greenThumbExtendedColors()
    val scope = rememberCoroutineScope()
    val today: LocalDate = pickerToday()

    // ── Наблюдение данных (жизненный цикл — правило M6: Flow кэшируется
    // remember(repo), обращение к Room при коллекции; экран базу не закрывает).
    val observationFailed = remember(repo) { mutableStateOf(false) }
    val plants by remember(repo) {
        repo.observePlants()
            .onEach { observationFailed.value = false }
            .catch { observationFailed.value = true }
    }.collectAsState(initial = emptyList())
    val unsavedIds by remember(repo) {
        repo.observeUnsavedPlantIds().catch { emit(emptySet()) }
    }.collectAsState(initial = emptySet())
    val online by connectivity.isOnline.collectAsState()

    // ── Состояние обновления/баннера (механика интерим-экрана Stage 6).
    var refreshFailed by remember(userId) { mutableStateOf(false) }
    var banner by remember(userId) { mutableStateOf<SyncBanner?>(null) }
    var sawOffline by remember(userId) { mutableStateOf(false) }
    var firstLoadSettled by remember(userId) { mutableStateOf(false) }
    var everSynced by remember(userId) { mutableStateOf(false) }

    // ── Локальные состояния RN index.tsx (порядок как у RN).
    var searchQuery by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(DashboardFilter.All) }
    val layoutPref by settings.layoutMode.collectAsState(initial = null)
    val viewMode = layoutPref ?: LayoutMode.List
    var isManualRefreshing by remember { mutableStateOf(false) }
    var bulkPendingCount by remember { mutableStateOf<Int?>(null) }
    var bulkSuccessCount by remember { mutableStateOf<Int?>(null) }
    var postponePending by remember { mutableStateOf(false) }
    var wateringIds by remember { mutableStateOf(emptySet<String>()) }
    // Распад при массовом поливе (M10): id карточек с эффектом ThanosSnap.
    // Данные не ждут эффекта — Room обновлён оптимистикой репозитория;
    // snapHeldIds держит карточки-призраки в списке с клика (запись их
    // мгновенно выводит из фильтра — эффект владеет их видимостью),
    // snappingIds включает распад по успеху запроса (RN onSuccess);
    // оба снимаются через [Motion.BulkWaterSnapMs] (RN-ритм снятия снапа).
    var snappingIds by remember { mutableStateOf(emptySet<String>()) }
    var snapHeldIds by remember { mutableStateOf(emptySet<String>()) }
    // Порядок рендера на момент клика «полить все» (до M4-записи): held-
    // карточки держат эти позиции весь распад — иначе пост-мутационная
    // сортировка прыгает им в хвост (M10-фикс F1; RN удерживал их на
    // прежних местах). Снимается вместе с snapHeldIds.
    var snapOrderIds by remember { mutableStateOf(emptyList<String>()) }
    var errorDialogMessage by remember { mutableStateOf<String?>(null) }

    // Имя пользователя в шапке (RN user?.name; офлайн-кэш тоже пользователь).
    val sessionState by session.state.collectAsState()
    val userName = when (val state = sessionState) {
        is SessionState.SignedIn -> state.user.name
        is SessionState.Offline -> state.user.name
        else -> null
    }

    val hasPlants = plants.isNotEmpty()
    val dueCount = needsWaterCount(plants, today)
    val filteredPlants = filterAndSortPlants(plants, searchQuery, filter, today)

    // ── Обновления: replay журнала → координатор (первый показ, сеть) —
    // механика интерим-экрана Stage 6 (VAL-OFF-004).
    LaunchedEffect(repo, onlineSession) {
        if (!onlineSession) {
            banner = repo.syncBanner()
            firstLoadSettled = true
            return@LaunchedEffect
        }
        if (online) {
            val replay = runCatching { repo.replayPending() }
            if (replay.isFailure) refreshFailed = true
        }
        everSynced = repo.lastSyncedAtMillis() != null
        val coordinator = RefreshCoordinator(
            syncMeta = SyncMetaSource { repo.lastSyncedAtMillis() },
            nowMillis = { Clock.System.now().toEpochMilliseconds() },
            // Успешный фоновый refresh (первый показ/восстановление сети) гасит
            // полоску: при свежих sync_meta обещание «показаны сохранённые
            // данные» стало бы ложью. RN держал ошибку до успешного refetch.
            refresh = {
                repo.refresh()
                refreshFailed = false
            },
        )
        when (coordinator.onFirstShow()) {
            is RefreshOutcome.Failed -> refreshFailed = true
            is RefreshOutcome.Refreshed -> everSynced = true
            is RefreshOutcome.SkippedFresh -> everSynced = true
            else -> {}
        }
        firstLoadSettled = true
        banner = repo.syncBanner()
        coordinator.attachConnectivity(this, connectivity.isOnline)
        // ON_RESUME (VAL-DASH-010): возврат на открытый дашборд после >60 с
        // на фоне refresh принимает серверную правду — удалённые на сервере
        // растения уходят из Room без перезапуска и без смены аккаунта.
        // Дедуп соседних триггеров (первый показ, восстановление сети, полёт
        // запроса) — внутри координатора; здесь гасим полоску ошибки при
        // неуспехе и перечитываем баннер при успехе.
        coordinator.attachForeground(this, foreground.isResumed) { outcome ->
            when (outcome) {
                is RefreshOutcome.Failed -> refreshFailed = true
                is RefreshOutcome.Refreshed -> {
                    everSynced = true
                    banner = repo.syncBanner()
                }
                else -> {}
            }
        }
    }
    LaunchedEffect(online) {
        if (!online) {
            sawOffline = true
            return@LaunchedEffect
        }
        if (!sawOffline) return@LaunchedEffect
        val replay = runCatching { repo.replayPending() }
        if (replay.isFailure) refreshFailed = true
        banner = repo.syncBanner()
    }
    // Возраст sync_meta растёт сам по себе; RN пересчитывал возраст на каждом
    // рендере — здесь тик в полпорога, баннер появляется без других событий.
    LaunchedEffect(repo) {
        while (true) {
            banner = repo.syncBanner()
            delay(30_000L)
        }
    }

    // ── Действия (мутации — репозиторий; откат/журнал внутри него).

    /** Ручной refresh (pull-to-refresh): RN handleRefresh, ошибки — без алерта. */
    fun refreshManual() {
        if (isManualRefreshing) return
        isManualRefreshing = true
        scope.launch {
            try {
                repo.refresh()
                refreshFailed = false
                everSynced = true
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                // Полоска ошибки над живым кэшем, НЕ полноэкранная (VAL-DASH-009).
                refreshFailed = true
            }
            isManualRefreshing = false
            banner = repo.syncBanner()
        }
    }

    /** Одиночный полив (RN waterMutation: onError — откат без алерта). */
    fun water(plantId: String) {
        if (plantId in wateringIds) return
        wateringIds = wateringIds + plantId
        scope.launch {
            try {
                repo.water(plantId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                // RN onError: rollback снимком без алерта; сеть оставляет
                // журнал («не сохранено» — метка карточки).
            } finally {
                wateringIds = wateringIds - plantId
            }
        }
    }

    /**
     * Массовый полив (RN waterAllMutation): pending-баннер «Поливаем N…» на
     * время запроса → success-баннер «N полито» на 2400 мс. Данные репозиторий
     * обновляет немедленно (до конца анимации — вкладочный чек VAL-DASH-006);
     * N фиксируется ДО записи (после оптимистичной записи все healthy).
     * Ошибка — алерт (RN onError showAlert).
     *
     * Эффект отвязан от данных (M4-механизм, architecture.md §7): RN держал
     * запись кэша 1150 мс ([Motion.BulkWaterSnapMs]) ради ThanosSnap — здесь
     * запись уже сделана репозиторием, распад ([GtThanosSnap]) — одноразовое
     * событие по id «полить» до записи (RN context.ids из onMutate); набор
     * снапа живёт те же 1150 мс (RN тоже снимает его на BULK_WATER_SNAP_MS).
     */
    fun waterAll() {
        if (bulkPendingCount != null) return
        val due = dueCount
        val dueIds = plants
            .filter {
                wateringStatus(it.lastWateredDate, it.waterFrequencyDays, today) !=
                    WateringStatus.Healthy
            }
            .map { it.id }
        bulkPendingCount = due
        if (dueIds.isNotEmpty()) {
            snapHeldIds = dueIds.toSet()
            snapOrderIds = filteredPlants.map { it.id }
        }
        scope.launch {
            try {
                repo.waterAll()
                // Запрос закончился — pending-баннер сменяется success-баннером
                // («N полито» на 2400 мс). Снятие pending только в finally
                // держало спиннер «Поливаем N…» весь success-фазу: текст
                // «N полито» не показывался никогда.
                bulkPendingCount = null
                bulkSuccessCount = due
                if (dueIds.isNotEmpty()) {
                    snappingIds = dueIds.toSet()
                    launch {
                        delay(Motion.BulkWaterSnapMs.toLong())
                        snappingIds = emptySet()
                        snapHeldIds = emptySet()
                        snapOrderIds = emptyList()
                    }
                }
                delay(Motion.BulkWaterSuccessBannerMs.toLong())
                bulkSuccessCount = null
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                errorDialogMessage = error.message ?: error.toString()
                snapHeldIds = emptySet()
                snapOrderIds = emptyList()
            } finally {
                bulkPendingCount = null
            }
        }
    }

    /**
     * Массовый перенос (RN postponeAllMutation): без оптимистичности —
     * список меняется только после ответа (репозиторий: вызов → refresh).
     * Алерта на ошибку НЕТ (RN-паритет, VAL-DATA-004).
     */
    fun postponeAll() {
        if (postponePending) return
        postponePending = true
        scope.launch {
            try {
                repo.postponeAll()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                // RN onError нет — ошибка молча (onSettled только invalidate).
            } finally {
                postponePending = false
            }
        }
    }

    fun changeViewMode(mode: LayoutMode) {
        // RN: setViewMode + AsyncStorage.setItem; здесь Flow перечитается сам.
        scope.launch { settings.setLayoutMode(mode) }
    }

    // ── Поверхность ──
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background)
            .statusBarsPadding(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── Шапка: заголовок + имя + баннер синхронизации + режимы ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = Spacing.xl,
                        end = Spacing.xl,
                        top = Spacing.lg,
                        bottom = Spacing.sm,
                    ),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(Res.string.dashboard_myPlants),
                        style = MaterialTheme.typography.titleLarge,
                        color = scheme.onSurface,
                    )
                    if (!userName.isNullOrBlank()) {
                        Text(
                            text = userName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    val shown = banner
                    if (shown != null && shown.visible && hasPlants && shown.syncedAtLabel != null) {
                        Row(
                            modifier = Modifier.padding(top = Spacing.xxs),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
                        ) {
                            if (shown.showOfflineIcon) {
                                GtCloudOffMark(
                                    tint = scheme.onSurfaceVariant,
                                    modifier = Modifier.size(Spacing.sm),
                                )
                            }
                            Text(
                                text = stringResource(Res.string.dashboard_lastSynced, shown.syncedAtLabel),
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (hasPlants) {
                    Row(
                        modifier = Modifier.padding(top = Spacing.xs),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
                    ) {
                        // RN ViewModeButton: активная — primary, неактивная —
                        // mutedForeground.
                        ViewModeButton(
                            selected = viewMode == LayoutMode.List,
                            label = stringResource(Res.string.a11y_viewModeList),
                            mark = { GtListViewMark(tint = it, modifier = Modifier.size(Spacing.lg)) },
                            onClick = { changeViewMode(LayoutMode.List) },
                        )
                        ViewModeButton(
                            selected = viewMode == LayoutMode.Card,
                            label = stringResource(Res.string.a11y_viewModeCard),
                            mark = { GtCardViewMark(tint = it, modifier = Modifier.size(Spacing.lg)) },
                            onClick = { changeViewMode(LayoutMode.Card) },
                        )
                        ViewModeButton(
                            selected = viewMode == LayoutMode.Grid,
                            label = stringResource(Res.string.a11y_viewModeGrid),
                            mark = { GtGridViewMark(tint = it, modifier = Modifier.size(Spacing.lg)) },
                            onClick = { changeViewMode(LayoutMode.Grid) },
                        )
                    }
                }
            }

            // ── Полоска ошибки чтения локальной базы (KMP: живая база).
            if (observationFailed.value) {
                Text(
                    text = stringResource(Res.string.dashboard_databaseError),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.error,
                    modifier = Modifier.padding(horizontal = Spacing.xl),
                )
                Spacer(modifier = Modifier.height(Spacing.xs))
            }

            when {
                // ── Первая загрузка: скелетон (RN isLoading → SkeletonLoader);
                // хедер уже виден, кости по текущему режиму.
                plants.isEmpty() && !firstLoadSettled -> {
                    GtSkeleton(
                        mode = when (viewMode) {
                            LayoutMode.List -> GtSkeletonMode.List
                            LayoutMode.Card -> GtSkeletonMode.Card
                            LayoutMode.Grid -> GtSkeletonMode.Grid
                        },
                        modifier = Modifier.weight(1f),
                    )
                }

                // ── Ошибка первой загрузки при пустых данных: RN error-ветка
                // (сообщение об ошибке вместо списка).
                plants.isEmpty() && refreshFailed && !everSynced -> {
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(Res.string.dashboard_refreshFailed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.error,
                            modifier = Modifier.padding(Spacing.xl),
                        )
                    }
                }

                // ── Нет растений вообще: пустой сад с кнопкой (RN empty state).
                plants.isEmpty() -> {
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        GtEmptyState(
                            title = stringResource(Res.string.dashboard_noPlants),
                            message = stringResource(Res.string.dashboard_startTracking),
                            actionLabel = stringResource(Res.string.dashboard_addPlant),
                            onAction = onAddPlant,
                        )
                    }
                }

                // ── Есть растения: поиск/фильтры/действия/список ──
                else -> {
                    // Поиск (имя + локация) с кнопкой очистки.
                    GtTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = "",
                        placeholder = stringResource(Res.string.dashboard_search),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.xl),
                        leadingIcon = {
                            GtSearchMark(
                                tint = scheme.onSurfaceVariant,
                                modifier = Modifier.size(Spacing.lg),
                            )
                        },
                        trailingIcon = if (searchQuery.isEmpty()) {
                            null
                        } else {
                            {
                                val clearLabel = stringResource(Res.string.a11y_clearSearch)
                                Box(
                                    modifier = Modifier
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                            role = Role.Button,
                                        ) { searchQuery = "" }
                                        .semantics { contentDescription = clearLabel }
                                        .padding(Spacing.xxs),
                                ) {
                                    GtCloseMark(
                                        tint = scheme.onSurfaceVariant,
                                        modifier = Modifier.size(Spacing.lg),
                                    )
                                }
                            }
                        },
                    )
                    Spacer(modifier = Modifier.height(Spacing.sm))

                    // Фильтр-чипы (FilterTab): счётчик — только у needsWater.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.xl),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    ) {
                        GtChip(
                            label = stringResource(Res.string.dashboard_all),
                            selected = filter == DashboardFilter.All,
                            onClick = { filter = DashboardFilter.All },
                        )
                        GtChip(
                            label = needsWaterChipLabel(
                                baseLabel = stringResource(Res.string.dashboard_needsWater),
                                dueCount = dueCount,
                            ),
                            selected = filter == DashboardFilter.NeedsWater,
                            onClick = { filter = DashboardFilter.NeedsWater },
                        )
                        GtChip(
                            label = stringResource(Res.string.dashboard_healthy),
                            selected = filter == DashboardFilter.Healthy,
                            onClick = { filter = DashboardFilter.Healthy },
                        )
                    }
                    Spacer(modifier = Modifier.height(Spacing.sm))

                    // Кнопки массовых действий: только needsWaterCount>0 И
                    // фильтр needsWater (RN).
                    if (dueCount > 0 && filter == DashboardFilter.NeedsWater) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Spacing.xl),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                        ) {
                            BulkActionButton(
                                text = stringResource(Res.string.dashboard_waterAll),
                                contentDescription = stringResource(Res.string.a11y_waterAll),
                                icon = { GtWaterDropMark(tint = it, modifier = Modifier.size(Spacing.md + Spacing.xxs)) },
                                container = scheme.primary,
                                contentColor = scheme.onPrimary,
                                enabled = bulkPendingCount == null,
                                modifier = Modifier.weight(1f),
                                onClick = { waterAll() },
                            )
                            BulkActionButton(
                                text = stringResource(Res.string.dashboard_postponeAll),
                                contentDescription = stringResource(Res.string.a11y_postponeAll),
                                icon = { GtClockMark(tint = it, modifier = Modifier.size(Spacing.md + Spacing.xxs)) },
                                container = scheme.surfaceVariant,
                                contentColor = scheme.onSurfaceVariant,
                                enabled = !postponePending,
                                modifier = Modifier.weight(1f),
                                onClick = { postponeAll() },
                            )
                        }
                        Spacer(modifier = Modifier.height(Spacing.sm))
                    }

                    // Баннер массового полива: pending → success (2400 мс).
                    val pendingCount = bulkPendingCount
                    val successCount = bulkSuccessCount
                    if (pendingCount != null || successCount != null) {
                        BulkWaterBanner(
                            pendingCount = pendingCount,
                            successCount = successCount,
                            modifier = Modifier.padding(horizontal = Spacing.xl),
                        )
                        Spacer(modifier = Modifier.height(Spacing.sm))
                    }

                    // Полоска ошибки ручного refresh над живым списком
                    // (VAL-DASH-009; полноэкранной ошибки поверх данных нет).
                    if (refreshFailed) {
                        Text(
                            text = stringResource(Res.string.dashboard_refreshFailed),
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.error,
                            modifier = Modifier.padding(horizontal = Spacing.xl),
                        )
                        Spacer(modifier = Modifier.height(Spacing.xs))
                    }

                    if (filteredPlants.isEmpty() && snapHeldIds.isEmpty()) {
                        // Пустой результат поиска/фильтра — ОТДЕЛЬНЫЙ empty-state
                        // (RN noPlantsFound + tryDifferentSearch/tryDifferentFilter).
                        // Во время снапа (данные уже обновлены, карточки-призраки
                        // досиживают) пустое состояние не показываем.
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                                modifier = Modifier.padding(Spacing.xxl + Spacing.xs),
                            ) {
                                if (searchQuery.isNotEmpty()) {
                                    GtSearchMark(
                                        tint = scheme.onSurfaceVariant,
                                        modifier = Modifier.size(Spacing.xxl + Spacing.lg),
                                    )
                                } else {
                                    GtFunnelMark(
                                        tint = scheme.onSurfaceVariant,
                                        modifier = Modifier.size(Spacing.xxl + Spacing.lg),
                                    )
                                }
                                Text(
                                    text = stringResource(Res.string.dashboard_noPlantsFound),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = scheme.onSurface,
                                )
                                Text(
                                    text = if (searchQuery.isEmpty()) {
                                        stringResource(Res.string.dashboard_tryDifferentFilter)
                                    } else {
                                        stringResource(Res.string.dashboard_tryDifferentSearch)
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = scheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else {
                        // Список/сетка с pull-to-refresh (RN RefreshControl на
                        // FlatList; хедер и фильтры вне зоны тяги — как у RN).
                        PullToRefreshBox(
                            isRefreshing = isManualRefreshing,
                            onRefresh = { refreshManual() },
                            modifier = Modifier.weight(1f),
                        ) {
                            // Карточки в снап-наборе досиживают в списке, даже когда
                            // данные уже обновлены и фильтр их больше не отдаёт
                            // (M4-механизм: запись не ждёт анимацию — видимость
                            // карточек держит сам эффект; RN достигал того же тем,
                            // что держал запись кэша 1150 мс). Порядок —
                            // order-preserving по [snapOrderIds]: карточки держат
                            // исходные позиции, stagger распада идёт в исходном
                            // порядке (M10-фикс F1; раньше held дописывались в
                            // хвост и визуально прыгали вниз).
                            val renderPlants = renderPlantsDuringSnap(
                                filteredPlants = filteredPlants,
                                allPlants = plants,
                                snapHeldIds = snapHeldIds,
                                snapOrderIds = snapOrderIds,
                            )
                            when (viewMode) {
                                LayoutMode.Grid -> PlantsGrid(
                                    plants = renderPlants,
                                    unsavedIds = unsavedIds,
                                    wateringIds = wateringIds,
                                    snappingIds = snappingIds,
                                    today = today,
                                    onOpenPlant = onOpenPlant,
                                    onWater = ::water,
                                )
                                else -> PlantsList(
                                    plants = renderPlants,
                                    unsavedIds = unsavedIds,
                                    wateringIds = wateringIds,
                                    snappingIds = snappingIds,
                                    today = today,
                                    cardMode = viewMode == LayoutMode.Card,
                                    onOpenPlant = onOpenPlant,
                                    onWater = ::water,
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── FAB (RN: только когда растения есть).
        if (hasPlants) {
            val addLabel = stringResource(Res.string.a11y_addPlant)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(Spacing.xl)
                    .size(Spacing.xxl * 2 + Spacing.xs)
                    .background(scheme.primary, CircleShape)
                    .clickable(role = Role.Button, onClick = onAddPlant)
                    .semantics { contentDescription = addLabel },
                contentAlignment = Alignment.Center,
            ) {
                GtPlusMark(
                    tint = scheme.onPrimary,
                    modifier = Modifier.size(Spacing.xxl + Spacing.xs),
                )
            }
        }
    }

    // Ошибка массового полива (RN onError waterAllMutation: showAlert).
    val dialogMessage = errorDialogMessage
    if (dialogMessage != null) {
        GtAlertDialog(
            title = stringResource(Res.string.common_error),
            message = dialogMessage,
            buttons = listOf(GtAlertButton(text = "OK")),
            onDismissRequest = { errorDialogMessage = null },
        )
    }
}

/** Подпись needsWater-чипа: RN `{label} (${count})` при count > 0. */
private fun needsWaterChipLabel(baseLabel: String, dueCount: Int): String =
    if (dueCount > 0) "$baseLabel ($dueCount)" else baseLabel

/**
 * Кнопка переключателя вида (RN ViewModeButton 34×34): фон primary-альфа при
 * выборе; GtIconButton даёт рамку и side 38 (компонент M5 «переключатель
 * вида»); tint — primary при выборе, иначе mutedForeground.
 */
@Composable
private fun ViewModeButton(
    selected: Boolean,
    label: String,
    mark: @Composable (Color) -> Unit,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    GtIconButton(
        onClick = onClick,
        contentDescription = label,
        selected = selected,
    ) {
        mark(if (selected) scheme.primary else scheme.onSurfaceVariant)
    }
}

/** Кнопка массового действия (RN Water All / Postpone All). */
@Composable
private fun BulkActionButton(
    text: String,
    contentDescription: String,
    icon: @Composable (Color) -> Unit,
    container: Color,
    contentColor: Color,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(Radii.md)
    Row(
        modifier = modifier
            .background(if (enabled) container else scheme.surfaceVariant, shape)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { this.contentDescription = contentDescription }
            .padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs, Alignment.CenterHorizontally),
    ) {
        icon(contentColor)
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
        )
    }
}

/**
 * Баннер массового полива: pending «Поливаем N растений/растения/растение…» /
 * success «N полито» (RN dashboard.wateringAllPending / plantsWatered).
 *
 * Success-фраза склоняется по числу политых, pending-глагольная — тоже
 * (VAL-DASH-006; см. тексты в ветках ниже); сам счётчик всегда отдельным
 * числом перед фразой в success — структура строки
 * RN `"{count} {plantsWatered}"`. internal — для регрессионного jvmTest
 * плюрализма (BulkWaterBannerPluralTest).
 */
@Composable
internal fun BulkWaterBanner(
    pendingCount: Int?,
    successCount: Int?,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val pending = pendingCount != null
    val background = if (pending) {
        scheme.primary.copy(alpha = BulkPendingAlpha)
    } else {
        scheme.primary.copy(alpha = StatusPillAlpha)
    }
    val shape = RoundedCornerShape(Radii.lg)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(background, shape)
            .borderHairline(scheme.primary.copy(alpha = BulkBannerBorderAlpha), shape)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        if (pending) {
            CircularProgressIndicator(
                color = scheme.primary,
                modifier = Modifier.size(Spacing.lg + Spacing.xxs),
            )
        } else {
            GtCheckMark(tint = scheme.primary, modifier = Modifier.size(Spacing.lg + Spacing.xxs))
        }
        val text = if (pending && pendingCount != null) {
            // Плюрализм pending-баннера (VAL-DASH-006): ru 1/21 → «растение»,
            // 2/22 → «растения», 5/25 → «растений»; en во всех количествах —
            // «plants», как было.
            pluralStringResource(
                Res.plurals.dashboard_wateringAllPending,
                pendingCount,
                pendingCount,
            )
        } else if (successCount != null) {
            // Плюрализм существительного по числу политых (VAL-DASH-006):
            // ru 1/21 → «растение», 2/22 → «растения», 5/25 → «растений»;
            // en во всех количествах — «plants watered», как было.
            "$successCount " + pluralStringResource(
                Res.plurals.dashboard_plantsWatered,
                successCount,
            )
        } else {
            ""
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = scheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Список и карточки (RN FlatList numColumns=1; card — градиент на фото).
 * Карточка в снап-наборе завёрнута в [GtThanosSnap] (RN renderItemFinal:
 * `snappingIds.has(id)` → `<ThanosSnap snap delay={index * 70}>`).
 */
@Composable
private fun PlantsList(
    plants: List<PlantDto>,
    unsavedIds: Set<String>,
    wateringIds: Set<String>,
    snappingIds: Set<String>,
    today: LocalDate,
    cardMode: Boolean,
    onOpenPlant: (String) -> Unit,
    onWater: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Spacing.xl,
            end = Spacing.xl,
            bottom = Spacing.xxl * 4 + Spacing.xs,
        ),
        verticalArrangement = Arrangement.spacedBy(if (cardMode) Spacing.lg else Spacing.sm),
    ) {
        itemsIndexed(plants, key = { _, plant -> plant.id }) { index, plant ->
            val waterColor = statusAccent(plant, today)
            val pill = statusPill(plant, today)
            val item: @Composable (Modifier) -> Unit = { mod ->
                if (cardMode) {
                    PlantCard(
                        plant = plant,
                        today = today,
                        statusText = pill.text,
                        statusColor = pill.color,
                        statusBg = pill.bg,
                        isWatering = plant.id in wateringIds,
                        unsaved = plant.id in unsavedIds,
                        onOpenPlant = onOpenPlant,
                        onWater = onWater,
                        modifier = mod,
                    )
                } else {
                    PlantListItem(
                        plant = plant,
                        today = today,
                        statusText = pill.text,
                        statusColor = pill.color,
                        statusBg = pill.bg,
                        accentColor = waterColor,
                        isWatering = plant.id in wateringIds,
                        unsaved = plant.id in unsavedIds,
                        onOpenPlant = onOpenPlant,
                        onWater = onWater,
                        modifier = mod,
                    )
                }
            }
            if (plant.id in snappingIds) {
                GtThanosSnap(
                    snap = true,
                    delayMillis = index * Motion.ThanosStaggerMs,
                    modifier = Modifier.animateItem(),
                ) {
                    item(Modifier)
                }
            } else {
                item(Modifier.animateItem())
            }
        }
    }
}

/** Сетка 3 колонки (RN numColumns=3, GRID_GAP=10 → [Spacing.sm]). */
@Composable
private fun PlantsGrid(
    plants: List<PlantDto>,
    unsavedIds: Set<String>,
    wateringIds: Set<String>,
    snappingIds: Set<String>,
    today: LocalDate,
    onOpenPlant: (String) -> Unit,
    onWater: (String) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(GRID_COLS),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Spacing.lg,
            end = Spacing.lg,
            bottom = Spacing.xxl * 4 + Spacing.xs,
        ),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        itemsIndexed(plants, key = { _, plant -> plant.id }) { index, plant ->
            val cell: @Composable (Modifier) -> Unit = { mod ->
                PlantGridCell(
                    plant = plant,
                    today = today,
                    isWatering = plant.id in wateringIds,
                    unsaved = plant.id in unsavedIds,
                    onOpenPlant = onOpenPlant,
                    onWater = onWater,
                    modifier = mod,
                )
            }
            if (plant.id in snappingIds) {
                GtThanosSnap(
                    snap = true,
                    delayMillis = index * Motion.ThanosStaggerMs,
                    modifier = Modifier.animateItem(),
                ) {
                    cell(Modifier)
                }
            } else {
                cell(Modifier.animateItem())
            }
        }
    }
}

/** Колонки сетки (RN GRID_COLS). */
private const val GRID_COLS: Int = 3

/** Цвет-акцент статуса (RN accentColor: overdue→destructive, today→amber, иначе primary). */
@Composable
private fun statusAccent(plant: PlantDto, today: LocalDate): Color {
    val scheme = MaterialTheme.colorScheme
    return when (wateringStatus(plant.lastWateredDate, plant.waterFrequencyDays, today)) {
        WateringStatus.Overdue -> scheme.error
        WateringStatus.Today -> greenThumbExtendedColors().amber
        WateringStatus.Healthy -> scheme.primary
    }
}

/** Текст/цвет/фон пилюли статуса (RN getStatusInfo; today → amberBg-токен). */
private data class StatusPillInfo(val text: String, val color: Color, val bg: Color)

@Composable
private fun statusPill(plant: PlantDto, today: LocalDate): StatusPillInfo {
    val scheme = MaterialTheme.colorScheme
    val extended = greenThumbExtendedColors()
    val days = daysUntilWatering(plant.lastWateredDate, plant.waterFrequencyDays, today)
    return when (wateringStatus(plant.lastWateredDate, plant.waterFrequencyDays, today)) {
        WateringStatus.Overdue -> StatusPillInfo(
            text = pluralStringResource(Res.plurals.plant_overdue, -days, -days),
            color = scheme.error,
            bg = scheme.error.copy(alpha = StatusPillAlpha),
        )
        WateringStatus.Today -> StatusPillInfo(
            text = stringResource(Res.string.plant_waterToday),
            color = extended.amber,
            bg = extended.amberBg,
        )
        WateringStatus.Healthy -> StatusPillInfo(
            text = pluralStringResource(Res.plurals.plant_daysLeft, days, days),
            color = scheme.primary,
            bg = scheme.primary.copy(alpha = StatusPillAlpha),
        )
    }
}

/** Пилюля статуса: иконка-капля + текст (RN water-outline + text 11/600). */
@Composable
private fun StatusPill(
    text: String,
    color: Color,
    bg: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .background(bg, RoundedCornerShape(Radii.sm))
            .padding(horizontal = Spacing.xs, vertical = Spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        GtWaterDropMark(tint = color, modifier = Modifier.size(Spacing.sm))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

/**
 * Строка списка (RN renderListItem): фото 56, имя, локация, пилюля статуса,
 * справа — полив (не-healthy) / галочка (healthy) / спиннер (поливается).
 */
@Composable
private fun PlantListItem(
    plant: PlantDto,
    today: LocalDate,
    statusText: String,
    statusColor: Color,
    statusBg: Color,
    accentColor: Color,
    isWatering: Boolean,
    unsaved: Boolean,
    onOpenPlant: (String) -> Unit,
    onWater: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val extended = greenThumbExtendedColors()
    val openLabel = stringResource(Res.string.a11y_openPlant, plant.name)
    val waterLabel = stringResource(Res.string.a11y_waterPlant, plant.name)
    val shape = RoundedCornerShape(Radii.lg)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(scheme.surface)
            .borderHairline(extended.cardBorder, shape)
            .semantics { contentDescription = openLabel }
            .clickable(role = Role.Button, onClick = { onOpenPlant(plant.id) })
            // Левый акцент статуса (RN borderLeftWidth 3): поверх фона и
            // рамки, под контентом.
            .drawBehind {
                drawRect(
                    color = accentColor,
                    size = Size((Spacing.xxs * 3f / 4).toPx(), size.height),
                )
            }
            .padding(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        PlantPhoto(plant = plant, side = Spacing.xxl * 2 + Spacing.xs, iconSize = Spacing.xl)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            Text(
                text = plant.name,
                style = MaterialTheme.typography.bodyLarge,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
            ) {
                GtLocationMark(
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(Spacing.sm),
                )
                Text(
                    text = plant.location,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            StatusPill(text = statusText, color = statusColor, bg = statusBg)
            if (unsaved) {
                Text(
                    text = UnsavedMutation.LABEL,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
        // RN WaterButtonWithParticles compact: спиннер живёт ВНУТРИ кнопки
        // (isWatering), всплеск частиц поверх — в компоненте.
        if (wateringStatus(plant.lastWateredDate, plant.waterFrequencyDays, today) !=
            WateringStatus.Healthy
        ) {
            GtWaterButton(
                onWater = { onWater(plant.id) },
                isWatering = isWatering,
                compact = true,
                contentDescription = waterLabel,
            )
        } else {
            GtCheckMark(
                tint = scheme.primary.copy(alpha = 0.4f),
                modifier = Modifier.size(Spacing.lg + Spacing.xxs),
            )
        }
    }
}

/**
 * Карточка (RN renderCardItem): квадратное фото с градиентом и именем,
 * под ним локация + пилюля, полная ширина кнопки полива.
 */
@Composable
private fun PlantCard(
    plant: PlantDto,
    today: LocalDate,
    statusText: String,
    statusColor: Color,
    statusBg: Color,
    isWatering: Boolean,
    unsaved: Boolean,
    onOpenPlant: (String) -> Unit,
    onWater: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val extended = greenThumbExtendedColors()
    val openLabel = stringResource(Res.string.a11y_openPlant, plant.name)
    val waterLabel = stringResource(Res.string.a11y_waterPlant, plant.name)
    val shape = RoundedCornerShape(Radii.xl)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(scheme.surface)
            .borderHairline(extended.cardBorder, shape)
            .clickable(role = Role.Button, onClick = { onOpenPlant(plant.id) })
            .semantics { contentDescription = openLabel },
    ) {
        // Фото с градиентом и именем (RN LinearGradient нижние 45%).
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(scheme.surfaceVariant),
        ) {
            PlantPhoto(
                plant = plant,
                side = null,
                iconSize = Spacing.xxl + Spacing.lg,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.45f)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.7f),
                        ),
                    )
                    .padding(start = Spacing.md, end = Spacing.md, bottom = Spacing.sm),
                contentAlignment = Alignment.BottomStart,
            ) {
                Text(
                    text = plant.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Column(
            modifier = Modifier.padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
                ) {
                    GtLocationMark(
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(Spacing.md),
                    )
                    Text(
                        text = plant.location,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                StatusPill(text = statusText, color = statusColor, bg = statusBg)
            }
            if (unsaved) {
                Text(
                    text = UnsavedMutation.LABEL,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
            }
            if (wateringStatus(plant.lastWateredDate, plant.waterFrequencyDays, today) !=
                WateringStatus.Healthy
            ) {
                // RN WaterButtonWithParticles fullWidth (спиннер/частицы внутри).
                GtWaterButton(
                    onWater = { onWater(plant.id) },
                    isWatering = isWatering,
                    label = stringResource(Res.string.plant_water),
                    successLabel = stringResource(Res.string.plant_watered),
                    contentDescription = waterLabel,
                )
            }
        }
    }
}

/**
 * Ячейка сетки (RN renderGridItem): квадрат, фото, градиент на всю высоту
 * (locations 0/0.45/1), имя внизу слева, счётчик дней внизу справа, кнопка
 * полива в правом верхнем углу (не-healthy).
 */
@Composable
private fun PlantGridCell(
    plant: PlantDto,
    today: LocalDate,
    isWatering: Boolean,
    unsaved: Boolean,
    onOpenPlant: (String) -> Unit,
    onWater: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val extended = greenThumbExtendedColors()
    val openLabel = stringResource(Res.string.a11y_openPlant, plant.name)
    val waterLabel = stringResource(Res.string.a11y_waterPlant, plant.name)
    val status = wateringStatus(plant.lastWateredDate, plant.waterFrequencyDays, today)
    val days = daysUntilWatering(plant.lastWateredDate, plant.waterFrequencyDays, today)
    val needsWater = status != WateringStatus.Healthy
    val accent = statusAccent(plant, today)
    val numberColor = when (status) {
        WateringStatus.Overdue -> scheme.error
        WateringStatus.Today -> extended.amber
        WateringStatus.Healthy -> Color.White
    }
    val shape = RoundedCornerShape(Radii.sm)
    Box(
        modifier = modifier
            .aspectRatio(1f),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(shape)
                .background(Color.Black)
                .semantics { contentDescription = openLabel }
                .clickable(role = Role.Button, onClick = { onOpenPlant(plant.id) })
                // Левый акцент статуса — RN borderLeftWidth 3.
                .drawBehind {
                    drawRect(
                        color = accent,
                        size = Size((Spacing.xxs * 3f / 4).toPx(), size.height),
                    )
                },
        ) {
            PlantPhoto(
                plant = plant,
                side = null,
                iconSize = Spacing.xxl + Spacing.xs,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.45f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.85f),
                        ),
                    )
                    .padding(
                        start = Spacing.xxs / 2 + Spacing.xxs,
                        end = Spacing.xxs / 2 + Spacing.xxs,
                        bottom = Spacing.xxs,
                    ),
                contentAlignment = Alignment.BottomStart,
            ) {
                Text(
                    text = plant.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    maxLines = 2,
                )
            }
            if (unsaved) {
                Text(
                    text = UnsavedMutation.LABEL,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(Spacing.xxs),
                )
            }
            // Счётчик дней — правый нижний угол (RN bottom-right badge).
            Text(
                text = days.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = numberColor,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = Spacing.xxs / 2 + Spacing.xxs, bottom = Spacing.xxs)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(Spacing.xxs))
                    .padding(horizontal = Spacing.xxs, vertical = Spacing.xxs / 2),
            )
            // Кнопка полива — правый верхний угол (только не-healthy; RN
            // WaterButtonWithParticles compact: спиннер/частицы внутри).
            if (needsWater) {
                GtWaterButton(
                    onWater = { onWater(plant.id) },
                    isWatering = isWatering,
                    compact = true,
                    contentDescription = waterLabel,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(Spacing.xxs),
                )
            }
        }
    }
}

/**
 * Фото растения: [side] задан — квадрат (список), null — размеры родителя
 * (карточка/сетка). Пустой photo_url → плейсхолдер-лист (RN Ionicons leaf).
 */
@Composable
private fun PlantPhoto(
    plant: PlantDto,
    side: Dp?,
    iconSize: Dp,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val sized = if (side != null) modifier.size(side) else modifier.fillMaxSize()
    if (plant.photoUrl.isBlank()) {
        Box(
            modifier = sized
                .background(scheme.surfaceVariant, RoundedCornerShape(Radii.md)),
            contentAlignment = Alignment.Center,
        ) {
            GtLeafMark(
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(iconSize),
            )
        }
    } else {
        AsyncImage(
            model = plant.photoUrl,
            contentDescription = null,
            modifier = sized
                .background(scheme.surfaceVariant, RoundedCornerShape(Radii.md))
                .clip(RoundedCornerShape(Radii.md)),
            contentScale = ContentScale.Crop,
        )
    }
}
