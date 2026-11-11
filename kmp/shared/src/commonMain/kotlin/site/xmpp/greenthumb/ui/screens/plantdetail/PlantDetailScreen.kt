package site.xmpp.greenthumb.ui.screens.plantdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import site.xmpp.greenthumb.core.network.Patch
import site.xmpp.greenthumb.core.network.PatchPlantDto
import site.xmpp.greenthumb.core.network.PlantDto
import site.xmpp.greenthumb.core.platform.LocalAppLocale
import site.xmpp.greenthumb.core.platform.PickSource
import site.xmpp.greenthumb.core.platform.ScreenReader
import site.xmpp.greenthumb.core.platform.photoDataUri
import site.xmpp.greenthumb.data.PlantRepositoryOpener
import site.xmpp.greenthumb.data.SaveOutcome
import site.xmpp.greenthumb.data.waterDue
import site.xmpp.greenthumb.ui.components.DestructiveButton
import site.xmpp.greenthumb.ui.components.GtAlertDialog
import site.xmpp.greenthumb.ui.components.GtAlertButton
import site.xmpp.greenthumb.ui.components.GtAlertButtonStyle
import site.xmpp.greenthumb.ui.components.GtCameraMark
import site.xmpp.greenthumb.ui.components.GtCard
import site.xmpp.greenthumb.ui.components.GtChevronDirection
import site.xmpp.greenthumb.ui.components.GtChevronMark
import site.xmpp.greenthumb.ui.components.GtDatePickerDialog
import site.xmpp.greenthumb.ui.components.GtDatePickerField
import site.xmpp.greenthumb.ui.components.GtDiagnosisMark
import site.xmpp.greenthumb.ui.components.GtFertilizeMark
import site.xmpp.greenthumb.ui.components.GtGearMark
import site.xmpp.greenthumb.ui.components.GtGalleryMark
import site.xmpp.greenthumb.ui.components.GtInputField
import site.xmpp.greenthumb.ui.components.GtLocationMark
import site.xmpp.greenthumb.ui.components.GtModal
import site.xmpp.greenthumb.ui.components.GtPencilMark
import site.xmpp.greenthumb.ui.components.GtPlantPhoto
import site.xmpp.greenthumb.ui.components.GtPotMark
import site.xmpp.greenthumb.ui.components.GtScissorsMark
import site.xmpp.greenthumb.ui.components.GtSheetRow
import site.xmpp.greenthumb.ui.components.GtSheetRowRole
import site.xmpp.greenthumb.ui.components.GtSheetTitle
import site.xmpp.greenthumb.ui.components.GtSpeciesMark
import site.xmpp.greenthumb.ui.components.GtWaterButton
import site.xmpp.greenthumb.ui.components.GtWaterDueLine
import site.xmpp.greenthumb.ui.components.PhotoPickerHost
import site.xmpp.greenthumb.ui.components.borderHairline
import site.xmpp.greenthumb.ui.components.formatPickerDate
import site.xmpp.greenthumb.ui.components.gtButtonWidth
import site.xmpp.greenthumb.ui.components.pickerToday
import site.xmpp.greenthumb.ui.components.rememberPhotoPicker
import site.xmpp.greenthumb.ui.res.Res
import site.xmpp.greenthumb.ui.res.a11y_back
import site.xmpp.greenthumb.ui.res.a11y_changePhoto
import site.xmpp.greenthumb.ui.res.diagnosis_entry
import site.xmpp.greenthumb.ui.res.diagnosis_entryDescription
import site.xmpp.greenthumb.ui.res.a11y_deletePlant
import site.xmpp.greenthumb.ui.res.a11y_waterPlant
import site.xmpp.greenthumb.ui.res.common_cancel
import site.xmpp.greenthumb.ui.res.common_done
import site.xmpp.greenthumb.ui.res.common_error
import site.xmpp.greenthumb.ui.res.plant_daysLeft
import site.xmpp.greenthumb.ui.res.plant_delete
import site.xmpp.greenthumb.ui.res.plant_overdue
import site.xmpp.greenthumb.ui.res.plantDetails_advancedCare
import site.xmpp.greenthumb.ui.res.plantDetails_careLastDoneTitle
import site.xmpp.greenthumb.ui.res.plantDetails_careMarked
import site.xmpp.greenthumb.ui.res.plantDetails_careSetDate
import site.xmpp.greenthumb.ui.res.plantDetails_careUndo
import site.xmpp.greenthumb.ui.res.plantDetails_careQueuedBody
import site.xmpp.greenthumb.ui.res.plantDetails_careQueuedTitle
import site.xmpp.greenthumb.ui.res.plantDetails_careSettings
import site.xmpp.greenthumb.ui.res.plantDetails_cancel
import site.xmpp.greenthumb.ui.res.plantDetails_photoGallery
import site.xmpp.greenthumb.ui.res.plantDetails_photoNew
import site.xmpp.greenthumb.ui.res.plantDetails_photoCheck
import site.xmpp.greenthumb.ui.res.plantDetails_photoCheckHint
import site.xmpp.greenthumb.ui.res.plantDetails_photoSheetTitle
import site.xmpp.greenthumb.ui.res.plantDetails_daysAgo
import site.xmpp.greenthumb.ui.res.plantDetails_delete
import site.xmpp.greenthumb.ui.res.plantDetails_deleteDescription
import site.xmpp.greenthumb.ui.res.plantDetails_deleteTitle
import site.xmpp.greenthumb.ui.res.plantDetails_edit
import site.xmpp.greenthumb.ui.res.plantDetails_fertilize
import site.xmpp.greenthumb.ui.res.plantDetails_fertilizing
import site.xmpp.greenthumb.ui.res.plantDetails_goHome
import site.xmpp.greenthumb.ui.res.plantDetails_lastWateredLine
import site.xmpp.greenthumb.ui.res.plantDetails_aboutPlant
import site.xmpp.greenthumb.ui.res.plantDetails_noNotes
import site.xmpp.greenthumb.ui.res.plantDetails_namePlaceTitle
import site.xmpp.greenthumb.ui.res.plantDetails_placeBalcony
import site.xmpp.greenthumb.ui.res.plantDetails_placeBathroom
import site.xmpp.greenthumb.ui.res.plantDetails_placeBedroom
import site.xmpp.greenthumb.ui.res.plantDetails_placeKitchen
import site.xmpp.greenthumb.ui.res.plantDetails_placeLiving
import site.xmpp.greenthumb.ui.res.plantDetails_placeOffice
import site.xmpp.greenthumb.ui.res.plantDetails_notesPlaceholder
import site.xmpp.greenthumb.ui.res.plantDetails_plantNotFound
import site.xmpp.greenthumb.ui.res.plantDetails_prune
import site.xmpp.greenthumb.ui.res.plantDetails_pruning
import site.xmpp.greenthumb.ui.res.plantDetails_repot
import site.xmpp.greenthumb.ui.res.plantDetails_repotting
import site.xmpp.greenthumb.ui.res.plantDetails_save
import site.xmpp.greenthumb.ui.res.plantDetails_today
import site.xmpp.greenthumb.ui.res.plantDetails_waterPlant
import site.xmpp.greenthumb.ui.res.plant_watered
import site.xmpp.greenthumb.ui.res.plantDetails_winterMode
import site.xmpp.greenthumb.season.SeasonContext
import site.xmpp.greenthumb.ui.res.plantDetails_yesterday
import site.xmpp.greenthumb.ui.res.species_change
import site.xmpp.greenthumb.ui.res.species_choose
import site.xmpp.greenthumb.ui.res.species_clear
import site.xmpp.greenthumb.ui.res.species_light
import site.xmpp.greenthumb.ui.res.species_notSet
import site.xmpp.greenthumb.ui.res.species_tip
import site.xmpp.greenthumb.ui.res.species_title
import site.xmpp.greenthumb.ui.screens.species.SpeciesPickerDialog
import site.xmpp.greenthumb.ui.screens.species.rememberSpeciesCatalog
import site.xmpp.greenthumb.ui.screens.species.speciesLightLabel
import site.xmpp.greenthumb.catalog.CatalogRepository
import site.xmpp.greenthumb.ui.theme.Sizes
import site.xmpp.greenthumb.ui.theme.Spacing
import site.xmpp.greenthumb.ui.theme.greenThumbExtendedColors

/**
 * Ветка отказа удаления, ПЕРЕЖИВАЮЩАЯ экран деталей (RN-паритет
 * `app/plant/[id].tsx` deleteMutation: onMutate мгновенно делает
 * router.replace('/'), onError показывает Alert уже ПОВЕРХ дашборда —
 * алерт обязан жить не на экране деталей).
 *
 * Две ветки (VAL-DETAIL-004, решение пользователя 2026-09-27):
 * [Failed] — определённый 4xx/5xx (откат уже сделал репозиторий);
 * [Queued] — Network/Timeout: удаление стоит в очереди M4 и досылается
 * при восстановлении сети — сообщение честное, не «ошибка».
 */
public sealed interface PlantDeleteNotice {
    /** Определённый отказ сервера: карточка возвращена откатом, журнал пуст. */
    public data class Failed(public val message: String) : PlantDeleteNotice

    /** Network/Timeout: карточка исчезла, удаление выполнится после подключения. */
    public data object Queued : PlantDeleteNotice
}

/**
 * Состояние отказа удаления — владелец GtAppNavGraph (см. [PlantDeleteNotice]).
 */
public class PlantDeleteErrorState {
    public var notice: PlantDeleteNotice? by mutableStateOf(null)
}

/**
 * Экран деталей растения (Stage 7 п.6, фича screen-plant-detail) — порт
 * `app/plant/[id].tsx` (1178 строк, прочитана целиком).
 *
 * Секции 1:1: фото-шапка (высота = 0.75 ширины экрана, градиент-затемнение,
 * назад/камера), имя и место — окно «Название и место» по тапу на имя
 * (✕/Back отменяют, ✓ сохраняет оба поля одним PATCH; M10 items 5-6), карточка статуса полива с кнопкой «Полить»
 * (частицы — M10), карточки продвинутого ухода 2×N (только настроенные
 * частоты; тап = дата сегодня), строка настроек ухода, заметки (инлайн
 * save/cancel), удаление с подтверждением.
 *
 * Данные — ТОЛЬКО из репозитория (наблюдение таблицы по id): GET
 * /api/plants/:id на бэке нет (parity-файл). Пока наблюдение не отдало
 * первый кадр — спиннер (RN isLoading); отсутствие растения в кэше —
 * экран «не найдено» с кнопкой домой (VAL-DETAIL-006), не краш.
 *
 * Мутации — репозиторий (M4): оптимистичная запись + откат снимком на
 * HTTP-отказ — дело репозитория; экран показывает алерт ошибки. Удаление —
 * как RN onMutate: репозиторий убирает строку мгновенно (Room + эффект до
 * сети), экран СРАЗУ уходит на дашборд ([onDeleted] без ожидания сети);
 * корутина удаления живёт в отдельном [CoroutineScope] (не
 * rememberCoroutineScope — тот отменяется при уходе экрана), отказ
 * попадает в [PlantDeleteErrorState] → алерт над графом. Ветка отказа —
 * [deleteFailureBranch] (VAL-DETAIL-004): Network/Timeout → честный
 * статус очереди [PlantDeleteNotice.Queued], определённый 4xx/5xx →
 * [PlantDeleteNotice.Failed] поверх дашборда (RN onError-паритет).
 *
 * Осознанные отличия от RN (parity-файл):
 * - react-query optimistic-кэш → Room-наблюдение (architecture.md §7);
 *   invalidate не нужен — Room рекомпонирует сам (прецедент VAL-ADDPLANT-002);
 * - Ionicons/эмодзи → метки Retro-cosmos ([GtFertilizeMark]/[GtPotMark]/[GtScissorsMark]…);
 * - WaterButtonWithParticles → [GtWaterButton] (частицы + Medium-гаптика — m10);
 * - LinearGradient → [Brush.verticalGradient] от [Color.Black]/[Color.Transparent]
 *   (литералы RN-экрана `#fff`/rgba — свойство-константа, K5 не матчит);
 * - фото: https (R2) или data-URI через Coil [GtPlantPhoto] (Coil запинен миссией; Keyer —
 *   m8-coil-keyer); без фото — контур листа (Ionicons leaf RN);
 * - смена фото: модалка камера/галерея → [photoPicker] (Stage 8 п.1:
 *   platform-пикер + свой квадратный кроп + алерт отказа, молчание отмены);
 * - дата последнего полива — [formatPickerDate] (числовой en/ru-формат);
   локализованные месячные имена («d MMM yyyy» RN) — поверхность фичи
   screen-dashboard;
 * - подсветка «не сохранено» интерим-дашборда на шапке не повторяется
 *   (RN её не имеет — журнал виден в списке).
 */
@Composable
public fun PlantDetailScreen(
    userId: String,
    plantId: String,
    opener: PlantRepositoryOpener,
    onBack: () -> Unit,
    /** Возврат на дашборд (RN router.replace('/')). */
    onDeleted: () -> Unit,
    /** Алерт ошибки удаления — переживает экран (см. [PlantDeleteErrorState]). */
    deleteErrorState: PlantDeleteErrorState,
    /** Network state; on reconnect the screen replays the queue and refreshes the journal. */
    isOnline: StateFlow<Boolean> = AlwaysOnline,
    /** Seasonal rule of the signed-in user: the watering countdown and the winter-mode hint. */
    season: SeasonContext = SeasonContext.Off,
    /** Opens the diagnosis screen (architecture §5.7); null hides the entry. */
    onOpenDiagnosis: (() -> Unit)? = null,
    /** «Проверить на болезни» in the photo sheet: diagnosis of the current cover; null hides the row. */
    onCheckPhoto: (() -> Unit)? = null,
) {
    val repo = remember(userId) { opener.open(userId) }
    // Экран может быть открыт на локальном id растения, добавленного офлайн: когда очередь дошла и сервер выдал
    // свой id, карточка следует за растением по карте переименований — иначе она станет «Растение не найдено».
    val renames by remember(repo) { repo.renamedPlantIds }.collectAsState()
    val liveId = remember(renames, plantId) { repo.liveId(plantId) }
    // Наблюдение из кэша списка — pattern DashboardScreen: Flow один раз на
    // репозиторий, первый кадр — Loading (RN isLoading), пустой результат —
    // NotFound (RN error-ветка), ошибка живой базы — тоже NotFound-экран
    // (не краш; наблюдение закрытого репозитория тихо завершается —
    // защита в репозитории).
    val state by remember(repo, liveId) {
        repo.observePlants()
            .map<List<PlantDto>, PlantState> { rows ->
                rows.firstOrNull { it.id == liveId }
                    ?.let { PlantState.Found(it) }
                    ?: PlantState.NotFound
            }
            .catch { emit(PlantState.NotFound) }
    }.collectAsState(initial = PlantState.Loading)

    val locale = LocalAppLocale.current
    val online by isOnline.collectAsState()
    // Bumped when the server journal moved: confirmed care update or replay.
    var journalRefreshKey by remember(plantId) { mutableIntStateOf(0) }
    var sawOffline by remember(plantId) { mutableStateOf(false) }
    LaunchedEffect(repo, online) {
        if (!online) {
            sawOffline = true
            return@LaunchedEffect
        }
        if (!sawOffline) return@LaunchedEffect
        // The dashboard is not composed under this screen: replay here so an
        // offline watering reaches the server (one water event) before the
        // journal refresh reads it back.
        runCatching { repo.replayPending() }
        journalRefreshKey++
    }

    // Тексты — до корутин (stringResource композабелен, catch — нет).
    val errorTitle = stringResource(Res.string.common_error)
    val backLabel = stringResource(Res.string.a11y_back)
    val changePhotoLabel = stringResource(Res.string.a11y_changePhoto)
    val deleteA11yLabel = stringResource(Res.string.a11y_deletePlant)
    val deleteTitle = stringResource(Res.string.plantDetails_deleteTitle)
    val deleteButton = stringResource(Res.string.plantDetails_delete)
    val plantDelete = stringResource(Res.string.plant_delete)
    val cancelText = stringResource(Res.string.plantDetails_cancel)
    val saveText = stringResource(Res.string.plantDetails_save)
    val editLabel = stringResource(Res.string.plantDetails_edit)
    val notFoundText = stringResource(Res.string.plantDetails_plantNotFound)
    val goHomeText = stringResource(Res.string.plantDetails_goHome)
    val careSettingsLabel = stringResource(Res.string.plantDetails_careSettings)
    val waterPlantLabel = stringResource(Res.string.plantDetails_waterPlant)
    val wateredSuccessLabel = stringResource(Res.string.plant_watered)
    val advancedCareLabel = stringResource(Res.string.plantDetails_advancedCare)
    val notesLabel = stringResource(Res.string.plantDetails_aboutPlant)
    val notesPlaceholder = stringResource(Res.string.plantDetails_notesPlaceholder)
    val noNotesText = stringResource(Res.string.plantDetails_noNotes)
    val todayText = stringResource(Res.string.plantDetails_today)
    val yesterdayText = stringResource(Res.string.plantDetails_yesterday)
    val photoSheetTitle = stringResource(Res.string.plantDetails_photoSheetTitle)
    val photoNewText = stringResource(Res.string.plantDetails_photoNew)
    val photoGalleryText = stringResource(Res.string.plantDetails_photoGallery)
    val fertilizingLabel = stringResource(Res.string.plantDetails_fertilizing)
    val repottingLabel = stringResource(Res.string.plantDetails_repotting)
    val pruningLabel = stringResource(Res.string.plantDetails_pruning)
    val fertilizeAction = stringResource(Res.string.plantDetails_fertilize)
    val repotAction = stringResource(Res.string.plantDetails_repot)
    val pruneAction = stringResource(Res.string.plantDetails_prune)
    val doneText = stringResource(Res.string.common_done)
    val careLastDoneTitle = stringResource(Res.string.plantDetails_careLastDoneTitle)
    val careUndoLabel = stringResource(Res.string.plantDetails_careUndo)
    val namePlaceTitle = stringResource(Res.string.plantDetails_namePlaceTitle)
    val placeLabels = listOf(
        stringResource(Res.string.plantDetails_placeLiving),
        stringResource(Res.string.plantDetails_placeKitchen),
        stringResource(Res.string.plantDetails_placeBedroom),
        stringResource(Res.string.plantDetails_placeBalcony),
        stringResource(Res.string.plantDetails_placeBathroom),
        stringResource(Res.string.plantDetails_placeOffice),
    )

    // Scope удаления: переживает уход экрана (RN onMutate → router.replace,
    // мутация доезжает и с алертом ошибки — уже над дашбордом). Отдельный
    // SupervisorJob, не rememberCoroutineScope: тот отменяется при размонте.
    val deleteScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }

    // Локальное состояние RN (имя, заметки, модалки, алерт).
    // Open «Название и место» sheet; null when closed. Nothing is saved until its ✓.
    var namePlaceForm by remember { mutableStateOf<NamePlaceForm?>(null) }
    var editingNotes by remember { mutableStateOf(false) }
    var editedNotes by remember { mutableStateOf("") }
    // Every dialog below clears focus on open and close; a focused note field would otherwise get
    // focus back with the input connection open and no keyboard drawn (VAL-TIMELINE-017).
    val focusManager = LocalFocusManager.current
    var showPhotoSheet by remember { mutableStateOf(false) }
    // Open care settings sheet; null when closed. The sheet stays open on a failed save.
    var settingsForm by remember { mutableStateOf<CareSettingsForm?>(null) }
    var careSaving by remember { mutableStateOf(false) }
    var careError by remember { mutableStateOf<CareSaveError?>(null) }
    var careQueuedNotice by remember { mutableStateOf(false) }
    var deleteConfirmOpen by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var errorDialogMessage by remember { mutableStateOf<String?>(null) }
    var speciesPickerOpen by remember { mutableStateOf(false) }
    // Care whose last date is being picked (card without a date); null when the picker is closed.
    var careDatePick by remember { mutableStateOf<AdvancedCare?>(null) }
    val careSnackbar = remember { SnackbarHostState() }
    val careUndo = remember { CareUndoSlot() }
    val catalog = rememberSpeciesCatalog()

    when (val current = state) {
        PlantState.Loading -> LoadingScreen()
        PlantState.NotFound -> NotFoundScreen(
            plantNotFoundText = notFoundText,
            goHomeText = goHomeText,
            onGoHome = onDeleted,
        )
        is PlantState.Found -> {
            val plant = current.plant
            val scope = rememberCoroutineScope()
            val waterPlantA11yLabel = stringResource(Res.string.a11y_waterPlant, plant.name)

            fun update(patch: PatchPlantDto) {
                scope.launch {
                    try {
                        if (repo.updateOrQueue(plant.id, patch) is SaveOutcome.Saved) journalRefreshKey++
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Throwable) {
                        // RN showAlert(t('common.error'), err.message).
                        errorDialogMessage = error.message ?: error.toString()
                    }
                }
            }

            // Пикер фото (Stage 8 п.1): пикер → квадратный кроп → PATCH
            // photo_url data-URI (контракт RN processPhoto, plant/[id].tsx).
            val photoPicker = rememberPhotoPicker { bytes ->
                update(PatchPlantDto(photoUrl = Patch.Value(photoDataUri(bytes))))
            }
            // Timeline uploads use their own picker: the crop result goes to a caption dialog, not the cover.
            val timelinePick = remember(plant.id) { TimelinePick() }
            val timelinePicker = rememberPhotoPicker { bytes -> timelinePick.bytes = bytes }

            fun performDelete() {
                if (deleting) return
                deleting = true
                deleteScope.launch {
                    try {
                        repo.delete(plant.id)
                    } catch (cancellation: CancellationException) {
                        // Журнал остаётся (ответ не подтверждён), досылка —
                        // на следующем replay.
                        throw cancellation
                    } catch (error: Throwable) {
                        // Две ветки VAL-DETAIL-004 (решение 2026-09-27):
                        // Network/Timeout — репозиторий оставил удаление в
                        // очереди M4 (досошлёт при подключении) → честный
                        // статус «удаление выполнится после подключения»;
                        // определённый 4xx/5xx — откат снимком уже сделан →
                        // RN onError: Alert поверх дашборда.
                        deleteErrorState.notice = when (deleteFailureBranch(error)) {
                            DeleteFailureBranch.Queued -> PlantDeleteNotice.Queued
                            DeleteFailureBranch.Rejected ->
                                PlantDeleteNotice.Failed(error.message ?: error.toString())
                        }
                    } finally {
                        deleting = false
                    }
                }
                onDeleted()
            }

            val today = pickerToday()
            val careMarkedText = mapOf(
                AdvancedCare.Fertilize to stringResource(Res.string.plantDetails_careMarked, fertilizingLabel),
                AdvancedCare.Repot to stringResource(Res.string.plantDetails_careMarked, repottingLabel),
                AdvancedCare.Prune to stringResource(Res.string.plantDetails_careMarked, pruningLabel),
            )

            // A mis-tap on a care card writes a journal entry; «Отменить» sends the previous date back and
            // the server drops the entry. Watering has no undo: the server keeps water entries on a move back.
            fun markCare(care: AdvancedCare, date: String) {
                val previous = careUndo.previousFor(care) ?: CareUndoSlot.Previous(lastCareDate(plant, care))
                update(carePatch(care, date))
                careUndo.job?.cancel()
                careUndo.care = care
                careUndo.previous = previous
                careUndo.job = scope.launch {
                    val result = withTimeoutOrNull(careUndoMillis(ScreenReader.isOn())) {
                        careSnackbar.showSnackbar(
                            message = careMarkedText.getValue(care),
                            actionLabel = careUndoLabel,
                            duration = SnackbarDuration.Indefinite,
                        )
                    }
                    careUndo.care = null
                    careUndo.previous = null
                    if (result == SnackbarResult.ActionPerformed) update(carePatch(care, previous.date))
                }
            }

            // Полив с детали (RN waterMutation через WaterButtonWithParticles):
            // pending только water-патча (RN isPending && 'last_watered_date'
            // в variables); записи/откаты — в репозитории, алерт — как у update.
            var waterPending by remember(plant.id) { mutableStateOf(false) }
            fun waterNow() {
                if (waterPending) return
                waterPending = true
                scope.launch {
                    try {
                        val outcome =
                            repo.updateOrQueue(plant.id, PatchPlantDto(lastWateredDate = Patch.Value(today.toString())))
                        if (outcome is SaveOutcome.Saved) journalRefreshKey++
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Throwable) {
                        errorDialogMessage = error.message ?: error.toString()
                    } finally {
                        waterPending = false
                    }
                }
            }
            val countdowns = plantCareCountdowns(plant, today, season)
            val waterDueState = waterDue(plant.lastWateredDate, plant.waterFrequencyDays, today, season)
            val winterMode = season.isAdjusted(plant.lastWateredDate)
            val wateredLabel = when (val label = wateredAgoLabel(plant.lastWateredDate, today)) {
                WateredAgoLabel.Today -> todayText
                WateredAgoLabel.Yesterday -> yesterdayText
                is WateredAgoLabel.DaysAgo -> stringResource(Res.string.plantDetails_daysAgo, label.days)
            }

            val headerScrim = headerNeedsScrim(
                photoUrl = plant.photoUrl,
                darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f,
            )
            val headerInk = if (headerScrim) Color.White else MaterialTheme.colorScheme.onSurface
            val headerPencil =
                if (headerScrim) Color.White.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant
            val headerMuted =
                if (headerScrim) Color.White.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurfaceVariant
            val headerButtonFill =
                if (headerScrim) Color.Black.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surface

            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .imePadding(),
                ) {
                    // ── Фото-шапка (RN photoHeight = round(width * 0.75)) ──
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f / 0.75f)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        GtPlantPhoto(
                            photoUrl = plant.photoUrl,
                            leafSize = Spacing.xxl * 2,
                            modifier = Modifier.fillMaxSize(),
                        )
                        // Затемнение (LinearGradient RN: 0.35 black → transparent → 0.7 black).
                        // The gradient layer takes the photo tap; back, camera and name sit above it and keep theirs.
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .then(
                                    if (headerScrim) {
                                        Modifier.background(
                                            Brush.verticalGradient(
                                                0f to Color.Black.copy(alpha = 0.35f),
                                                0.5f to Color.Transparent,
                                                1f to Color.Black.copy(alpha = 0.7f),
                                            ),
                                        )
                                    } else {
                                        Modifier
                                    },
                                )
                                .testTag("plantDetail_headerPhoto")
                                .clickable(role = Role.Image, onClickLabel = photoSheetTitle) {
                                    focusManager.clearFocus()
                                    showPhotoSheet = true
                                }
                                .semantics { contentDescription = photoSheetTitle },
                        )

                        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(Spacing.lg),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                HeaderIconButton(label = backLabel, fill = headerButtonFill, onClick = onBack) {
                                    GtChevronMark(
                                        tint = headerInk,
                                        modifier = Modifier.size(Spacing.lg),
                                        direction = GtChevronDirection.Left,
                                    )
                                }
                                HeaderIconButton(
                                    label = changePhotoLabel,
                                    fill = headerButtonFill,
                                    onClick = {
                                        focusManager.clearFocus()
                                        showPhotoSheet = true
                                    },
                                ) {
                                    GtCameraMark(tint = headerInk, modifier = Modifier.size(Spacing.lg))
                                }
                            }
                            Spacer(modifier = Modifier.weight(1f))

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = Spacing.xl)
                                    .padding(bottom = Spacing.lg),
                                verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = Sizes.touch)
                                        .testTag("plantDetail_name")
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                            role = Role.Button,
                                            onClickLabel = namePlaceTitle,
                                        ) {
                                            focusManager.clearFocus()
                                            namePlaceForm = NamePlaceForm.of(plant.name, plant.location, placeLabels)
                                        },
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                                ) {
                                    Text(
                                        text = plant.name,
                                        style = MaterialTheme.typography.headlineSmall
                                            .copy(fontWeight = FontWeight.Bold),
                                        color = headerInk,
                                        modifier = Modifier.weight(1f),
                                    )
                                    GtPencilMark(
                                        tint = headerPencil,
                                        modifier = Modifier.size(Spacing.xl),
                                    )
                                }
                                if (plant.location.isNotBlank()) Row(
                                    modifier = Modifier.testTag("plantDetail_location"),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
                                ) {
                                    GtLocationMark(
                                        tint = headerMuted,
                                        modifier = Modifier.size(Spacing.sm),
                                    )
                                    Text(
                                        text = plant.location,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = headerMuted,
                                    )
                                }
                            }
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .navigationBarsPadding()
                            .padding(Spacing.xl),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xl),
                    ) {
                        // ── Статус полива ──
                        GtCard {
                            GtWaterDueLine(
                                due = waterDueState.due,
                                progress = waterDueState.progress,
                                lastWateredText = stringResource(
                                    Res.string.plantDetails_lastWateredLine,
                                    "$wateredLabel · ${formatPickerDate(plant.lastWateredDate, locale)}",
                                ),
                                winterText = if (winterMode) stringResource(Res.string.plantDetails_winterMode) else null,
                            )
                            Spacer(modifier = Modifier.height(Spacing.lg))
                            // RN WaterButtonWithParticles fullWidth: частицы +
                            // Medium-гаптика внутри компонента (M10); isWatering —
                            // pending water-патча (RN patchMutation.isPending с
                            // last_watered_date в variables).
                            GtWaterButton(
                                onWater = ::waterNow,
                                isWatering = waterPending,
                                label = waterPlantLabel,
                                successLabel = wateredSuccessLabel,
                                contentDescription = waterPlantA11yLabel,
                            )
                        }

                        // ── Продвинутый уход 2×N (только настроенные частоты) ──
                        if (plant.fertilizeFrequencyDays != null ||
                            plant.repotFrequencyMonths != null ||
                            plant.pruneFrequencyMonths != null
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                Text(
                                    text = advancedCareLabel,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                val cards = advancedCareCards(plant, countdowns).map { card ->
                                    // Without a last date the tap asks for it: "today" would add a care event.
                                    val onAction = {
                                        if (card.days == null) {
                                            focusManager.clearFocus()
                                            careDatePick = card.care
                                        } else {
                                            markCare(card.care, today.toString())
                                        }
                                    }
                                    when (card.care) {
                                        AdvancedCare.Fertilize -> CareCardData(
                                            title = fertilizingLabel,
                                            a11yLabel = fertilizeAction,
                                            days = card.days,
                                            mark = { tint -> GtFertilizeMark(tint = tint) },
                                            onAction = onAction,
                                        )
                                        AdvancedCare.Repot -> CareCardData(
                                            title = repottingLabel,
                                            a11yLabel = repotAction,
                                            days = card.days,
                                            mark = { tint -> GtPotMark(tint = tint) },
                                            onAction = onAction,
                                        )
                                        AdvancedCare.Prune -> CareCardData(
                                            title = pruningLabel,
                                            a11yLabel = pruneAction,
                                            days = card.days,
                                            mark = { tint -> GtScissorsMark(tint = tint) },
                                            onAction = onAction,
                                        )
                                    }
                                }
                                cards.chunked(2).forEach { rowCards ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                                    ) {
                                        rowCards.forEach { card ->
                                            CareCard(card = card, modifier = Modifier.weight(1f))
                                        }
                                        if (rowCards.size == 1) {
                                            Spacer(modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                        }

                        careDatePick?.let { care ->
                            GtDatePickerDialog(
                                initialDate = null,
                                onConfirm = { date ->
                                    careDatePick = null
                                    markCare(care, date)
                                },
                                onDismiss = { careDatePick = null },
                                confirmLabel = doneText,
                                dismissLabel = cancelText,
                                languageTag = locale,
                                title = careLastDoneTitle,
                            )
                        }

                        // ── Species (shown only when the plant has one) ──
                        val speciesInfo = plantSpeciesInfo(plant, catalog)
                        if (speciesInfo != PlantSpeciesInfo.None) {
                            SpeciesCard(info = speciesInfo, language = locale)
                        }

                        // ── Кнопка настроек ухода ──
                        SettingsRow(
                            label = careSettingsLabel,
                            onClick = {
                                focusManager.clearFocus()
                                careError = null
                                settingsForm = CareSettingsForm.of(plant)
                            },
                        )

                        if (onOpenDiagnosis != null) {
                            SettingsRow(
                                label = stringResource(Res.string.diagnosis_entry),
                                onClick = {
                                    focusManager.clearFocus()
                                    onOpenDiagnosis()
                                },
                                icon = { tint -> GtDiagnosisMark(tint = tint, modifier = Modifier.size(Spacing.xl)) },
                                description = stringResource(Res.string.diagnosis_entryDescription),
                                modifier = Modifier.testTag("plantDetail_diagnosis"),
                            )
                        }

                        // ── О растении: persistent plant notes (PATCH notes), not the dated journal ──
                        GtCard {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = notesLabel,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.testTag("plantDetail_notesTitle"),
                                )
                                if (!editingNotes) {
                                    Text(
                                        text = editLabel,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.tertiary,
                                        modifier = Modifier
                                            .touchTextLink {
                                                editedNotes = plant.notes
                                                editingNotes = true
                                            }
                                            .testTag("plantDetail_notesEdit"),
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(Spacing.xs))
                            if (editingNotes) {
                                GtInputField(
                                    value = editedNotes,
                                    onValueChange = { editedNotes = it },
                                    placeholder = notesPlaceholder,
                                    singleLine = false,
                                    modifier = Modifier.testTag("plantDetail_notesInput"),
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.End),
                                ) {
                                    Button(
                                        onClick = { editingNotes = false },
                                        shape = MaterialTheme.shapes.small,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        ),
                                    ) {
                                        Text(text = cancelText, style = MaterialTheme.typography.labelLarge)
                                    }
                                    Button(
                                        onClick = {
                                            editingNotes = false
                                            if (editedNotes != plant.notes) {
                                                update(PatchPlantDto(notes = Patch.Value(editedNotes)))
                                            }
                                        },
                                        shape = MaterialTheme.shapes.small,
                                        modifier = Modifier.testTag("plantDetail_notesSave"),
                                    ) {
                                        Text(text = saveText, style = MaterialTheme.typography.labelLarge)
                                    }
                                }
                            } else {
                                Text(
                                    text = plant.notes.ifEmpty { noNotesText },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (plant.notes.isEmpty()) {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                )
                            }
                        }

                        // ── Photo timeline (architecture §5.5) ──
                        PlantTimelineSection(
                            timeline = repo.timeline,
                            plantId = plant.id,
                            online = online,
                            refreshKey = journalRefreshKey,
                            locale = locale,
                            picker = timelinePicker,
                            pick = timelinePick,
                        )

                        // ── Journal (architecture §5.5) ──
                        PlantJournalSection(
                            journal = repo.journal,
                            timeline = repo.timeline,
                            plantId = plant.id,
                            online = online,
                            refreshKey = journalRefreshKey,
                            locale = locale,
                            // A journal refresh already in flight fetched the list before the delete; a new one follows it.
                            deleteCareEntry = { eventId ->
                                repo.deleteCareEntry(plant.id, eventId).also {
                                    if (it is SaveOutcome.Saved) journalRefreshKey++
                                }
                            },
                        )

                        // ── Удаление ──
                        DestructiveButton(
                            text = plantDelete,
                            onClick = {
                                focusManager.clearFocus()
                                deleteConfirmOpen = true
                            },
                            enabled = !deleting,
                            modifier = Modifier
                                .gtButtonWidth()
                                .semantics { contentDescription = deleteA11yLabel },
                        )
                    }
                }
                // Пикер фото поверх экрана (Stage 8 п.1).
                PhotoPickerHost(photoPicker)
                PhotoPickerHost(timelinePicker)
                SnackbarHost(
                    hostState = careSnackbar,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(Spacing.lg),
                )
            }

            // ── Подтверждение удаления (RN confirmDelete showAlert) ──
            if (deleteConfirmOpen) {
                GtAlertDialog(
                    title = deleteTitle,
                    message = stringResource(Res.string.plantDetails_deleteDescription, plant.name),
                    buttons = listOf(
                        GtAlertButton(text = cancelText, style = GtAlertButtonStyle.Cancel),
                        GtAlertButton(
                            text = deleteButton,
                            style = GtAlertButtonStyle.Destructive,
                            onClick = {
                                deleteConfirmOpen = false
                                performDelete()
                            },
                        ),
                    ),
                    onDismissRequest = {
                        deleteConfirmOpen = false
                        focusManager.clearFocus()
                    },
                )
            }

            // ── Care settings sheet (M10 item 7a) ──
            val openForm = settingsForm
            if (openForm != null) {
                PlantCareSettingsSheet(
                    form = openForm,
                    plantName = plant.name,
                    catalog = catalog,
                    locale = locale,
                    saving = careSaving,
                    error = careError,
                    onFormChange = { settingsForm = it },
                    onChooseSpecies = {
                        focusManager.clearFocus()
                        speciesPickerOpen = true
                    },
                    onCancel = {
                        if (!careSaving) {
                            settingsForm = null
                            careError = null
                            focusManager.clearFocus()
                        }
                    },
                    onSave = save@{
                        if (careSaving) return@save
                        val patch = CareSettingsPatch.build(plant, openForm)
                        if (patch.isEmpty()) {
                            settingsForm = null
                            careError = null
                            return@save
                        }
                        careSaving = true
                        careError = null
                        scope.launch {
                            try {
                                when (repo.updateOrQueue(plant.id, patch)) {
                                    is SaveOutcome.Saved -> journalRefreshKey++
                                    is SaveOutcome.Queued -> careQueuedNotice = true
                                }
                                settingsForm = null
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (error: Throwable) {
                                careError = careSaveError(error)
                            } finally {
                                careSaving = false
                            }
                        }
                    },
                )
            }

            if (speciesPickerOpen) {
                SpeciesPickerDialog(
                    catalog = catalog,
                    language = locale,
                    onPick = { entry ->
                        speciesPickerOpen = false
                        focusManager.clearFocus()
                        settingsForm = settingsForm?.withSpecies(entry)
                    },
                    onDismiss = {
                        speciesPickerOpen = false
                        focusManager.clearFocus()
                    },
                )
            }

            val openNamePlace = namePlaceForm
            if (openNamePlace != null) {
                PlantNamePlaceSheet(
                    form = openNamePlace,
                    onFormChange = { namePlaceForm = it },
                    onCancel = {
                        namePlaceForm = null
                        focusManager.clearFocus()
                    },
                    onSave = {
                        namePlaceForm = null
                        focusManager.clearFocus()
                        openNamePlace.toPatch()?.let(::update)
                    },
                )
            }

            // ── Модалка смены фото (RN bottom sheet) ──
            if (showPhotoSheet) {
                val closePhotoSheet = {
                    showPhotoSheet = false
                    focusManager.clearFocus()
                }
                GtModal(onDismissRequest = closePhotoSheet) {
                    GtSheetTitle(photoSheetTitle)
                    GtSheetRow(
                        label = photoNewText,
                        icon = { GtCameraMark(it) },
                        onClick = {
                            closePhotoSheet()
                            photoPicker.launch(scope, PickSource.Camera)
                        },
                    )
                    GtSheetRow(
                        label = photoGalleryText,
                        icon = { GtGalleryMark(it) },
                        onClick = {
                            closePhotoSheet()
                            photoPicker.launch(scope, PickSource.Gallery)
                        },
                    )
                    if (onCheckPhoto != null && plant.photoUrl.isNotBlank()) {
                        GtSheetRow(
                            label = stringResource(Res.string.plantDetails_photoCheck),
                            icon = { GtDiagnosisMark(it) },
                            supporting = stringResource(Res.string.plantDetails_photoCheckHint),
                            onClick = {
                                closePhotoSheet()
                                onCheckPhoto()
                            },
                            modifier = Modifier.testTag("plantDetail_photoCheck"),
                        )
                    }
                    GtSheetRow(
                        label = cancelText,
                        icon = null,
                        role = GtSheetRowRole.Cancel,
                        onClick = closePhotoSheet,
                    )
                }
            }

            if (careQueuedNotice) {
                GtAlertDialog(
                    title = stringResource(Res.string.plantDetails_careQueuedTitle),
                    message = stringResource(Res.string.plantDetails_careQueuedBody),
                    buttons = listOf(GtAlertButton(text = "OK")),
                    onDismissRequest = { careQueuedNotice = false },
                )
            }

            // Ошибка мутации (RN showAlert(t('common.error'), err.message)).
            val dialogMessage = errorDialogMessage
            if (dialogMessage != null) {
                GtAlertDialog(
                    title = errorTitle,
                    message = dialogMessage,
                    buttons = listOf(GtAlertButton(text = "OK")),
                    onDismissRequest = { errorDialogMessage = null },
                )
            }
        }
    }
}

/** Первый кадр наблюдения — спиннер RN isLoading (здесь — пустой фон). */
@Composable
private fun LoadingScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    )
}

/** Экран «растение не найдено»: текст + кнопка домой (RN error-ветка). */
@Composable
private fun NotFoundScreen(
    plantNotFoundText: String,
    goHomeText: String,
    onGoHome: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background)
            .statusBarsPadding()
            .padding(Spacing.xxl),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            Text(
                text = plantNotFoundText,
                style = MaterialTheme.typography.bodyLarge,
                color = scheme.error,
                textAlign = TextAlign.Center,
            )
            Text(
                text = goHomeText,
                style = MaterialTheme.typography.bodyLarge,
                color = scheme.tertiary,
                modifier = Modifier.clickableNoIndication(onGoHome),
            )
        }
    }
}

/** Круглая кнопка шапки: 38×38 circle (RN header buttons) inside a 48 dp touch target; [fill] — rgba(0,0,0,0.4) над фото. */
@Composable
private fun HeaderIconButton(
    label: String,
    fill: Color,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(Sizes.touch)
            .clickableNoIndication(onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(Spacing.xxl + Spacing.md)
                .background(fill, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

private fun Modifier.clickableNoIndication(onClick: () -> Unit): Modifier =
    clickable(
        interactionSource = MutableInteractionSource(),
        indication = null,
        onClick = onClick,
    )

/** Text link with a 48 dp touch area; the click and the text stay on one semantics node. */
private fun Modifier.touchTextLink(onClick: () -> Unit): Modifier =
    clickableNoIndication(onClick)
        .sizeIn(minWidth = Sizes.touch, minHeight = Sizes.touch)
        .wrapContentSize()
        .padding(horizontal = Spacing.xs)


/** PATCH that sets the last date of [care]; null clears it. */
private fun carePatch(care: AdvancedCare, date: String?): PatchPlantDto {
    val value: Patch<String> = if (date == null) Patch.Null else Patch.Value(date)
    return when (care) {
        AdvancedCare.Fertilize -> PatchPlantDto(lastFertilizedDate = value)
        AdvancedCare.Repot -> PatchPlantDto(lastRepottedDate = value)
        AdvancedCare.Prune -> PatchPlantDto(lastPrunedDate = value)
    }
}

private fun lastCareDate(plant: PlantDto, care: AdvancedCare): String? = when (care) {
    AdvancedCare.Fertilize -> plant.lastFertilizedDate
    AdvancedCare.Repot -> plant.lastRepottedDate
    AdvancedCare.Prune -> plant.lastPrunedDate
}

/**
 * How long the «Отменить» bar stays: 10 s, which the owner's tester still missed at 5 s, and 30 s with a screen
 * reader on — a spoken message plus an action takes longer to reach than a glance. The bar always closes by
 * itself: an indefinite one has no close button and would hold the bottom of the screen forever.
 */
internal const val CARE_UNDO_MILLIS: Long = 10_000L

/** The same offer with a screen reader on. */
internal const val CARE_UNDO_SCREEN_READER_MILLIS: Long = 30_000L

internal fun careUndoMillis(screenReaderOn: Boolean): Long =
    if (screenReaderOn) CARE_UNDO_SCREEN_READER_MILLIS else CARE_UNDO_MILLIS

/**
 * The open «Отменить» offer. A second tap on the same card while the offer is open keeps the date from
 * before the first tap, so one undo returns to where the user started.
 */
private class CareUndoSlot {
    class Previous(val date: String?)

    var care: AdvancedCare? = null
    var previous: Previous? = null
    var job: Job? = null

    fun previousFor(care: AdvancedCare): Previous? = if (this.care == care) previous else null
}

/** Данные карточки действия ухода. */
private data class CareCardData(
    val title: String,
    val a11yLabel: String,
    val days: Int?,
    val mark: @Composable (Color) -> Unit,
    val onAction: () -> Unit,
)

/** Карточка действия ухода 2×N (RN CareActionCard): тап = дата сегодня, без даты — выбор даты. */
@Composable
private fun CareCard(card: CareCardData, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val extended = greenThumbExtendedColors()
    val urgencyColor = when (careUrgency(card.days)) {
        CareUrgency.NotSet -> scheme.onSurfaceVariant
        CareUrgency.Overdue -> scheme.error
        CareUrgency.Today -> extended.amber
        CareUrgency.Later -> scheme.tertiary
    }
    val daysText = when (val value = careDaysText(card.days)) {
        CareDaysText.NeedsDate -> stringResource(Res.string.plantDetails_careSetDate)
        is CareDaysText.Overdue -> pluralStringResource(Res.plurals.plant_overdue, value.days, value.days)
        CareDaysText.Today -> stringResource(Res.string.plantDetails_today)
        is CareDaysText.DaysLeft -> pluralStringResource(Res.plurals.plant_daysLeft, value.days, value.days)
    }
    Column(
        modifier = modifier
            .background(scheme.surface, MaterialTheme.shapes.medium)
            .borderHairline(extended.cardBorder, MaterialTheme.shapes.medium)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                // TalkBack names what the tap does, not only the card title.
                onClickLabel = if (card.days == null) daysText else card.a11yLabel,
                role = Role.Button,
                onClick = card.onAction,
            )
            .padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        card.mark(scheme.secondary)
        Text(
            text = card.title,
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            text = daysText,
            style = MaterialTheme.typography.labelSmall,
            color = urgencyColor,
            textAlign = TextAlign.Center,
        )
    }
}

/** Строка «Настройки ухода» (RN Pressable с settings-outline + chevron). */
@Composable
private fun SettingsRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable (Color) -> Unit = { tint -> GtGearMark(tint = tint, modifier = Modifier.size(Spacing.xl)) },
    description: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val extended = greenThumbExtendedColors()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(scheme.surface, MaterialTheme.shapes.medium)
            .borderHairline(extended.cardBorder, MaterialTheme.shapes.medium)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            icon(scheme.secondary)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = scheme.onSurface,
                )
                if (description != null) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        }
        GtChevronMark(
            tint = scheme.onSurfaceVariant,
            modifier = Modifier.padding(start = Spacing.xs).size(Spacing.md).testTag("settingsRow_chevron"),
            direction = GtChevronDirection.Right,
        )
    }
}

/** Species line of the care settings sheet: current species, change and remove. */
@Composable
internal fun SettingsSpeciesRow(
    form: CareSettingsForm,
    catalog: CatalogRepository?,
    locale: String,
    onChoose: () -> Unit,
    onClear: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val name = catalog?.byId(form.speciesId)?.names?.forLanguage(locale) ?: form.speciesName
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
        Text(
            text = stringResource(Res.string.species_title),
            style = MaterialTheme.typography.labelLarge,
            color = scheme.onSurface,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(
                text = name ?: stringResource(Res.string.species_notSet),
                style = MaterialTheme.typography.bodyLarge,
                color = if (name == null) scheme.onSurfaceVariant else scheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(if (form.speciesId == null) Res.string.species_choose else Res.string.species_change),
                style = MaterialTheme.typography.labelLarge,
                color = scheme.tertiary,
                modifier = Modifier.touchTextLink(onChoose),
            )
            if (form.speciesId != null || form.speciesName != null) {
                Text(
                    text = stringResource(Res.string.species_clear),
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.error,
                    modifier = Modifier.touchTextLink(onClear),
                )
            }
        }
    }
}

/** Species card on the detail screen: name, scientific name, light and tip. */
@Composable
private fun SpeciesCard(info: PlantSpeciesInfo, language: String) {
    val scheme = MaterialTheme.colorScheme
    GtCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            GtSpeciesMark(tint = scheme.secondary, modifier = Modifier.size(Sizes.iconSlot))
            Text(
                text = stringResource(Res.string.species_title),
                style = MaterialTheme.typography.titleSmall,
                color = scheme.onSurface,
            )
        }
        Spacer(modifier = Modifier.height(Spacing.xs))
        when (info) {
            is PlantSpeciesInfo.Catalog -> {
                val entry = info.entry
                Text(
                    text = entry.names.forLanguage(language),
                    style = MaterialTheme.typography.bodyLarge,
                    color = scheme.onSurface,
                )
                Text(
                    text = entry.scientificName,
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = scheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(Spacing.sm))
                Text(
                    text = stringResource(Res.string.species_light) + ": " + speciesLightLabel(entry.light),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurface,
                )
                Spacer(modifier = Modifier.height(Spacing.xs))
                Text(
                    text = stringResource(Res.string.species_tip),
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                )
                Text(
                    text = entry.tip.forLanguage(language),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurface,
                )
            }
            is PlantSpeciesInfo.NameOnly -> Text(
                text = info.scientificName,
                style = MaterialTheme.typography.bodyLarge,
                fontStyle = FontStyle.Italic,
                color = scheme.onSurface,
            )
            PlantSpeciesInfo.None -> Unit
        }
    }
}

/** Дата в настройках ухода (RN DatePickerInput): верхняя граница — сегодня. */
@Composable
internal fun SettingsDateField(
    label: String,
    value: String?,
    onValueChange: (String) -> Unit,
    placeholder: String,
    doneText: String,
    cancelText: String,
    locale: String,
) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant,
        )
        GtDatePickerField(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            confirmLabel = doneText,
            dismissLabel = cancelText,
            languageTag = locale,
        )
    }
}

/** Default network state of direct compositions (tests, previews). */
private val AlwaysOnline: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()
