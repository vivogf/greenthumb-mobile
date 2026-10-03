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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import site.xmpp.greenthumb.core.network.Patch
import site.xmpp.greenthumb.core.network.PatchPlantDto
import site.xmpp.greenthumb.core.network.PlantDto
import site.xmpp.greenthumb.core.platform.LocalAppLocale
import site.xmpp.greenthumb.core.platform.PickSource
import site.xmpp.greenthumb.core.platform.photoDataUri
import site.xmpp.greenthumb.data.PlantRepositoryOpener
import site.xmpp.greenthumb.data.WateringStatus
import site.xmpp.greenthumb.data.daysUntilWatering
import site.xmpp.greenthumb.data.wateringStatus
import site.xmpp.greenthumb.ui.components.DestructiveButton
import site.xmpp.greenthumb.ui.components.GtAlertDialog
import site.xmpp.greenthumb.ui.components.GtAlertButton
import site.xmpp.greenthumb.ui.components.GtAlertButtonStyle
import site.xmpp.greenthumb.ui.components.GtCameraMark
import site.xmpp.greenthumb.ui.components.GtCard
import site.xmpp.greenthumb.ui.components.GtChevronDirection
import site.xmpp.greenthumb.ui.components.GtChevronMark
import site.xmpp.greenthumb.ui.components.GtDatePickerField
import site.xmpp.greenthumb.ui.components.GtGearMark
import site.xmpp.greenthumb.ui.components.GtGalleryMark
import site.xmpp.greenthumb.ui.components.GtInputField
import site.xmpp.greenthumb.ui.components.GtLeafMark
import site.xmpp.greenthumb.ui.components.GtLocationMark
import site.xmpp.greenthumb.ui.components.GtModal
import site.xmpp.greenthumb.ui.components.GtPencilMark
import site.xmpp.greenthumb.ui.components.GtPotMark
import site.xmpp.greenthumb.ui.components.GtScissorsMark
import site.xmpp.greenthumb.ui.components.GtTextField
import site.xmpp.greenthumb.ui.components.GtWaterButton
import site.xmpp.greenthumb.ui.components.PhotoPickerHost
import site.xmpp.greenthumb.ui.components.borderHairline
import site.xmpp.greenthumb.ui.components.formatPickerDate
import site.xmpp.greenthumb.ui.components.gtButtonWidth
import site.xmpp.greenthumb.ui.components.pickerToday
import site.xmpp.greenthumb.ui.components.rememberPhotoPicker
import site.xmpp.greenthumb.ui.res.Res
import site.xmpp.greenthumb.ui.res.a11y_back
import site.xmpp.greenthumb.ui.res.a11y_changePhoto
import site.xmpp.greenthumb.ui.res.a11y_deletePlant
import site.xmpp.greenthumb.ui.res.a11y_waterPlant
import site.xmpp.greenthumb.ui.res.common_camera
import site.xmpp.greenthumb.ui.res.common_cancel
import site.xmpp.greenthumb.ui.res.common_done
import site.xmpp.greenthumb.ui.res.common_error
import site.xmpp.greenthumb.ui.res.common_gallery
import site.xmpp.greenthumb.ui.res.plant_daysLeft
import site.xmpp.greenthumb.ui.res.plant_delete
import site.xmpp.greenthumb.ui.res.plant_overdue
import site.xmpp.greenthumb.ui.res.plantDetails_advancedCare
import site.xmpp.greenthumb.ui.res.plantDetails_careNotSet
import site.xmpp.greenthumb.ui.res.plantDetails_careSettings
import site.xmpp.greenthumb.ui.res.plantDetails_cancel
import site.xmpp.greenthumb.ui.res.plantDetails_changePhoto
import site.xmpp.greenthumb.ui.res.plantDetails_daysAgo
import site.xmpp.greenthumb.ui.res.plantDetails_delete
import site.xmpp.greenthumb.ui.res.plantDetails_deleteDescription
import site.xmpp.greenthumb.ui.res.plantDetails_deleteTitle
import site.xmpp.greenthumb.ui.res.plantDetails_edit
import site.xmpp.greenthumb.ui.res.plantDetails_fertilize
import site.xmpp.greenthumb.ui.res.plantDetails_fertilizing
import site.xmpp.greenthumb.ui.res.plantDetails_fertilizingFrequency
import site.xmpp.greenthumb.ui.res.plantDetails_goHome
import site.xmpp.greenthumb.ui.res.plantDetails_lastTime
import site.xmpp.greenthumb.ui.res.plantDetails_lastWatered
import site.xmpp.greenthumb.ui.res.plantDetails_leaveEmpty
import site.xmpp.greenthumb.ui.res.plantDetails_noNotes
import site.xmpp.greenthumb.ui.res.plantDetails_notSpecified
import site.xmpp.greenthumb.ui.res.plantDetails_notes
import site.xmpp.greenthumb.ui.res.plantDetails_notesPlaceholder
import site.xmpp.greenthumb.ui.res.plantDetails_plantNotFound
import site.xmpp.greenthumb.ui.res.plantDetails_prune
import site.xmpp.greenthumb.ui.res.plantDetails_pruning
import site.xmpp.greenthumb.ui.res.plantDetails_pruningFrequency
import site.xmpp.greenthumb.ui.res.plantDetails_repot
import site.xmpp.greenthumb.ui.res.plantDetails_repotting
import site.xmpp.greenthumb.ui.res.plantDetails_repottingFrequency
import site.xmpp.greenthumb.ui.res.plantDetails_save
import site.xmpp.greenthumb.ui.res.plantDetails_today
import site.xmpp.greenthumb.ui.res.plantDetails_waterPlant
import site.xmpp.greenthumb.ui.res.plant_watered
import site.xmpp.greenthumb.ui.res.plantDetails_wateringFrequency
import site.xmpp.greenthumb.ui.res.plantDetails_yesterday
import site.xmpp.greenthumb.ui.theme.Radii
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
 * назад/камера), инлайн-редактирование имени (тап → поле → сохранение по
 * потере фокуса, как onBlur RN), карточка статуса полива с кнопкой «Полить»
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
 * - Ionicons/эмодзи → Canvas-метки ([GtLeafMark]/[GtPotMark]/[GtScissorsMark]…);
 * - WaterButtonWithParticles → [GtWaterButton] (частицы + Medium-гаптика — m10);
 * - LinearGradient → [Brush.verticalGradient] от [Color.Black]/[Color.Transparent]
 *   (литералы RN-экрана `#fff`/rgba — свойство-константа, K5 не матчит);
 * - фото: data-URI через Coil [AsyncImage] (Coil запинен миссией; Keyer —
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
) {
    val repo = remember(userId) { opener.open(userId) }
    // Наблюдение из кэша списка — pattern DashboardScreen: Flow один раз на
    // репозиторий, первый кадр — Loading (RN isLoading), пустой результат —
    // NotFound (RN error-ветка), ошибка живой базы — тоже NotFound-экран
    // (не краш; наблюдение закрытого репозитория тихо завершается —
    // защита в репозитории).
    val state by remember(repo) {
        repo.observePlants()
            .map<List<PlantDto>, PlantState> { rows ->
                rows.firstOrNull { it.id == plantId }
                    ?.let { PlantState.Found(it) }
                    ?: PlantState.NotFound
            }
            .catch { emit(PlantState.NotFound) }
    }.collectAsState(initial = PlantState.Loading)

    val locale = LocalAppLocale.current

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
    val lastWateredLabel = stringResource(Res.string.plantDetails_lastWatered)
    val waterPlantLabel = stringResource(Res.string.plantDetails_waterPlant)
    val wateredSuccessLabel = stringResource(Res.string.plant_watered)
    val advancedCareLabel = stringResource(Res.string.plantDetails_advancedCare)
    val notesLabel = stringResource(Res.string.plantDetails_notes)
    val notesPlaceholder = stringResource(Res.string.plantDetails_notesPlaceholder)
    val noNotesText = stringResource(Res.string.plantDetails_noNotes)
    val todayText = stringResource(Res.string.plantDetails_today)
    val yesterdayText = stringResource(Res.string.plantDetails_yesterday)
    val notSpecifiedText = stringResource(Res.string.plantDetails_notSpecified)
    val leaveEmptyHint = stringResource(Res.string.plantDetails_leaveEmpty)
    val lastTimeText = stringResource(Res.string.plantDetails_lastTime)
    val changePhotoTitle = stringResource(Res.string.plantDetails_changePhoto)
    val cameraText = stringResource(Res.string.common_camera)
    val galleryText = stringResource(Res.string.common_gallery)
    val waterFrequencyLabel = stringResource(Res.string.plantDetails_wateringFrequency)
    val fertilizingLabel = stringResource(Res.string.plantDetails_fertilizing)
    val repottingLabel = stringResource(Res.string.plantDetails_repotting)
    val pruningLabel = stringResource(Res.string.plantDetails_pruning)
    val fertilizeAction = stringResource(Res.string.plantDetails_fertilize)
    val repotAction = stringResource(Res.string.plantDetails_repot)
    val pruneAction = stringResource(Res.string.plantDetails_prune)
    val fertilizingFrequencyLabel = stringResource(Res.string.plantDetails_fertilizingFrequency)
    val repottingFrequencyLabel = stringResource(Res.string.plantDetails_repottingFrequency)
    val pruningFrequencyLabel = stringResource(Res.string.plantDetails_pruningFrequency)
    val doneText = stringResource(Res.string.common_done)

    // Scope удаления: переживает уход экрана (RN onMutate → router.replace,
    // мутация доезжает и с алертом ошибки — уже над дашбордом). Отдельный
    // SupervisorJob, не rememberCoroutineScope: тот отменяется при размонте.
    val deleteScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }

    // Локальное состояние RN (имя, заметки, модалки, алерт).
    var editingName by remember { mutableStateOf(false) }
    var editedName by remember { mutableStateOf("") }
    var editingNotes by remember { mutableStateOf(false) }
    var editedNotes by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var showPhotoSheet by remember { mutableStateOf(false) }
    var settingsForm by remember { mutableStateOf<CareSettingsForm?>(null) }
    var deleteConfirmOpen by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var errorDialogMessage by remember { mutableStateOf<String?>(null) }

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
                        repo.update(plant.id, patch)
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

            // Полив с детали (RN waterMutation через WaterButtonWithParticles):
            // pending только water-патча (RN isPending && 'last_watered_date'
            // в variables); записи/откаты — в репозитории, алерт — как у update.
            var waterPending by remember(plant.id) { mutableStateOf(false) }
            fun waterNow() {
                if (waterPending) return
                waterPending = true
                scope.launch {
                    try {
                        repo.update(plant.id, PatchPlantDto(lastWateredDate = Patch.Value(today.toString())))
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Throwable) {
                        errorDialogMessage = error.message ?: error.toString()
                    } finally {
                        waterPending = false
                    }
                }
            }
            val waterDays = daysUntilWatering(plant.lastWateredDate, plant.waterFrequencyDays, today)
            val waterStatus = wateringStatus(plant.lastWateredDate, plant.waterFrequencyDays, today)
            val extended = greenThumbExtendedColors()
            val waterColor = when (waterStatus) {
                WateringStatus.Overdue -> MaterialTheme.colorScheme.error
                WateringStatus.Today -> extended.amber
                WateringStatus.Healthy -> MaterialTheme.colorScheme.primary
            }
            val waterBadgeText = when (waterStatus) {
                WateringStatus.Overdue ->
                    pluralStringResource(Res.plurals.plant_overdue, -waterDays, -waterDays)
                WateringStatus.Today -> todayText
                WateringStatus.Healthy ->
                    pluralStringResource(Res.plurals.plant_daysLeft, waterDays, waterDays)
            }
            val wateredLabel = when (val label = wateredAgoLabel(plant.lastWateredDate, today)) {
                WateredAgoLabel.Today -> todayText
                WateredAgoLabel.Yesterday -> yesterdayText
                is WateredAgoLabel.DaysAgo -> stringResource(Res.string.plantDetails_daysAgo, label.days)
            }

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
                        if (plant.photoUrl.isBlank()) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                GtLeafMark(
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(Spacing.xxl * 2),
                                )
                            }
                        } else {
                            AsyncImage(
                                model = plant.photoUrl,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                        }
                        // Затемнение (LinearGradient RN: 0.35 black → transparent → 0.7 black).
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        0f to Color.Black.copy(alpha = 0.35f),
                                        0.5f to Color.Transparent,
                                        1f to Color.Black.copy(alpha = 0.7f),
                                    ),
                                ),
                        )

                        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(Spacing.lg),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                HeaderIconButton(label = backLabel, onClick = onBack) {
                                    GtChevronMark(
                                        tint = Color.White,
                                        modifier = Modifier.size(Spacing.lg),
                                        direction = GtChevronDirection.Left,
                                    )
                                }
                                HeaderIconButton(label = changePhotoLabel, onClick = { showPhotoSheet = true }) {
                                    GtCameraMark(tint = Color.White, modifier = Modifier.size(Spacing.lg))
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
                                if (editingName) {
                                    NameEditField(
                                        value = editedName,
                                        onValueChange = { editedName = it },
                                        onSave = {
                                            editingName = false
                                            val trimmed = editedName.trim()
                                            if (trimmed.isNotEmpty() && trimmed != plant.name) {
                                                update(PatchPlantDto(name = Patch.Value(trimmed)))
                                            }
                                        },
                                    )
                                } else {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null,
                                                role = Role.Button,
                                            ) {
                                                editedName = plant.name
                                                editingName = true
                                            },
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                                    ) {
                                        Text(
                                            text = plant.name,
                                            style = MaterialTheme.typography.headlineSmall
                                                .copy(fontWeight = FontWeight.Bold),
                                            color = Color.White,
                                            modifier = Modifier.weight(1f),
                                        )
                                        GtPencilMark(
                                            tint = Color.White.copy(alpha = 0.7f),
                                            modifier = Modifier.size(Spacing.lg),
                                        )
                                    }
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
                                ) {
                                    GtLocationMark(
                                        tint = Color.White.copy(alpha = 0.75f),
                                        modifier = Modifier.size(Spacing.sm),
                                    )
                                    Text(
                                        text = plant.location,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White.copy(alpha = 0.75f),
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
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                                    Text(
                                        text = lastWateredLabel.uppercase(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        text =
                                            "$wateredLabel · ${formatPickerDate(plant.lastWateredDate, locale)}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                                WaterBadge(text = waterBadgeText, color = waterColor)
                            }
                            Spacer(modifier = Modifier.height(Spacing.sm))
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
                                val cards = buildList {
                                    plant.fertilizeFrequencyDays?.let { freq ->
                                        add(
                                            CareCardData(
                                                title = fertilizingLabel,
                                                a11yLabel = fertilizeAction,
                                                days = daysUntilCare(plant.lastFertilizedDate, freq, today),
                                                mark = { tint -> GtLeafMark(tint = tint) },
                                                onAction = {
                                                    update(
                                                        PatchPlantDto(lastFertilizedDate = Patch.Value(today.toString())),
                                                    )
                                                },
                                            ),
                                        )
                                    }
                                    plant.repotFrequencyMonths?.let { freq ->
                                        add(
                                            CareCardData(
                                                title = repottingLabel,
                                                a11yLabel = repotAction,
                                                days = daysUntilMonthCare(plant.lastRepottedDate, freq, today),
                                                mark = { tint -> GtPotMark(tint = tint) },
                                                onAction = {
                                                    update(PatchPlantDto(lastRepottedDate = Patch.Value(today.toString())))
                                                },
                                            ),
                                        )
                                    }
                                    plant.pruneFrequencyMonths?.let { freq ->
                                        add(
                                            CareCardData(
                                                title = pruningLabel,
                                                a11yLabel = pruneAction,
                                                days = daysUntilMonthCare(plant.lastPrunedDate, freq, today),
                                                mark = { tint -> GtScissorsMark(tint = tint) },
                                                onAction = {
                                                    update(PatchPlantDto(lastPrunedDate = Patch.Value(today.toString())))
                                                },
                                            ),
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

                        // ── Кнопка настроек ухода ──
                        SettingsRow(
                            label = careSettingsLabel,
                            onClick = {
                                settingsForm = CareSettingsForm.of(plant)
                                showSettings = true
                            },
                        )

                        // ── Заметки ──
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
                                )
                                if (!editingNotes) {
                                    Text(
                                        text = editLabel,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.clickableNoIndication {
                                            editedNotes = plant.notes
                                            editingNotes = true
                                        },
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

                        // ── Удаление ──
                        DestructiveButton(
                            text = plantDelete,
                            onClick = { deleteConfirmOpen = true },
                            enabled = !deleting,
                            modifier = Modifier
                                .gtButtonWidth()
                                .semantics { contentDescription = deleteA11yLabel },
                        )
                    }
                }
                // Пикер фото поверх экрана (Stage 8 п.1).
                PhotoPickerHost(photoPicker)
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
                    onDismissRequest = { deleteConfirmOpen = false },
                )
            }

            // ── Модалка настроек ухода (RN Care Settings Modal) ──
            val openForm = settingsForm
            if (showSettings && openForm != null) {
                CareSettingsModal(
                    form = openForm,
                    title = careSettingsLabel,
                    waterFrequencyLabel = waterFrequencyLabel,
                    fertilizingLabel = fertilizingLabel,
                    repottingLabel = repottingLabel,
                    pruningLabel = pruningLabel,
                    fertilizingFrequencyLabel = fertilizingFrequencyLabel,
                    repottingFrequencyLabel = repottingFrequencyLabel,
                    pruningFrequencyLabel = pruningFrequencyLabel,
                    leaveEmptyHint = leaveEmptyHint,
                    lastTimeText = lastTimeText,
                    notSpecifiedText = notSpecifiedText,
                    lastWateredText = lastWateredLabel,
                    cancelText = cancelText,
                    saveText = saveText,
                    doneText = doneText,
                    locale = locale,
                    onFormChange = { settingsForm = it },
                    onSave = {
                        showSettings = false
                        update(CareSettingsPatch.build(plant, openForm))
                    },
                    onDismiss = { showSettings = false },
                )
            }

            // ── Модалка смены фото (RN bottom sheet) ──
            if (showPhotoSheet) {
                GtModal(onDismissRequest = { showPhotoSheet = false }) {
                    Text(
                        text = changePhotoTitle,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = Spacing.lg),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    PhotoSheetRow(
                        label = cameraText,
                        icon = { GtCameraMark(it) },
                        onClick = {
                            showPhotoSheet = false
                            photoPicker.launch(scope, PickSource.Camera)
                        },
                    )
                    PhotoSheetRow(
                        label = galleryText,
                        icon = { GtGalleryMark(it) },
                        onClick = {
                            showPhotoSheet = false
                            photoPicker.launch(scope, PickSource.Gallery)
                        },
                    )
                    PhotoSheetRow(
                        label = cancelText,
                        icon = null,
                        destructive = true,
                        onClick = { showPhotoSheet = false },
                    )
                }
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
                color = scheme.primary,
                modifier = Modifier.clickableNoIndication(onGoHome),
            )
        }
    }
}

/** Поле имени в шапке: сохранение по потере фокуса (RN onBlur) или Done (RN onSubmitEditing). */
@Composable
private fun NameEditField(
    value: String,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    val requester = remember { FocusRequester() }
    var wasFocused by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { requester.requestFocus() }
    GtTextField(
        value = value,
        onValueChange = onValueChange,
        label = "",
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onSave() }),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(requester)
            .onFocusChanged { focus ->
                if (focus.isFocused) {
                    wasFocused = true
                } else if (wasFocused) {
                    onSave()
                }
            },
    )
}

/** Круглая кнопка шапки: rgba(0,0,0,0.4) фон, 38×38 (RN header buttons). */
@Composable
private fun HeaderIconButton(
    label: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(Spacing.xxl + Spacing.md)
            .background(Color.Black.copy(alpha = 0.4f), CircleShape)
            .clickableNoIndication(onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

private fun Modifier.clickableNoIndication(onClick: () -> Unit): Modifier =
    clickable(
        interactionSource = MutableInteractionSource(),
        indication = null,
        onClick = onClick,
    )

/** Пилюля статуса полива: фон = тот же цвет с альфой (RN `+ '18'`). */
@Composable
private fun WaterBadge(text: String, color: Color) {
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.1f), RoundedCornerShape(Radii.xl))
            .padding(horizontal = Spacing.sm, vertical = Spacing.xxs),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = color,
        )
    }
}

/** Данные карточки действия ухода. */
private data class CareCardData(
    val title: String,
    val a11yLabel: String,
    val days: Int?,
    val mark: @Composable (Color) -> Unit,
    val onAction: () -> Unit,
)

/** Карточка действия ухода 2×N (RN CareActionCard): тап = дата сегодня. */
@Composable
private fun CareCard(card: CareCardData, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val extended = greenThumbExtendedColors()
    val urgencyColor = when (careUrgency(card.days)) {
        CareUrgency.NotSet -> scheme.onSurfaceVariant
        CareUrgency.Overdue -> scheme.error
        CareUrgency.Today -> extended.amber
        CareUrgency.Later -> scheme.primary
    }
    val daysText = when (val value = careDaysText(card.days)) {
        CareDaysText.NotSet -> stringResource(Res.string.plantDetails_careNotSet)
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
                role = Role.Button,
                onClick = card.onAction,
            )
            .padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        card.mark(scheme.onSurface)
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
private fun SettingsRow(label: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val extended = greenThumbExtendedColors()
    Row(
        modifier = Modifier
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
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            GtGearMark(tint = scheme.primary, modifier = Modifier.size(Spacing.xl))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = scheme.onSurface,
            )
        }
        GtChevronMark(tint = scheme.onSurfaceVariant, modifier = Modifier.size(Spacing.md))
    }
}

/** Строка модалки смены фото (RN bottom sheet rows). */
@Composable
private fun PhotoSheetRow(
    label: String,
    icon: (@Composable (Color) -> Unit)?,
    onClick: () -> Unit,
    destructive: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickableNoIndication(onClick)
            .padding(horizontal = Spacing.xl, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        icon?.invoke(scheme.primary)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (destructive) scheme.error else scheme.onSurface,
        )
    }
}

/**
 * Модалка настроек ухода (RN Care Settings Modal, pageSheet): частота полива
 * + дата последнего полива; частота/дата удобрения, пересадки, обрезки с
 * подсказкой «оставьте пустым» (эмодзи RN-лейблов KMP не повторяет — решение
 * фичи screen-add-plant). Сохранение — [CareSettingsPatch.build]: очищенная
 * частота при наличии у растения → явный null (VAL-DETAIL-003).
 */
@Composable
private fun CareSettingsModal(
    form: CareSettingsForm,
    title: String,
    waterFrequencyLabel: String,
    fertilizingLabel: String,
    repottingLabel: String,
    pruningLabel: String,
    fertilizingFrequencyLabel: String,
    repottingFrequencyLabel: String,
    pruningFrequencyLabel: String,
    leaveEmptyHint: String,
    lastTimeText: String,
    notSpecifiedText: String,
    lastWateredText: String,
    cancelText: String,
    saveText: String,
    doneText: String,
    locale: String,
    onFormChange: (CareSettingsForm) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    GtModal(onDismissRequest = onDismiss) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.xl, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = cancelText,
                style = MaterialTheme.typography.bodyLarge,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.clickableNoIndication(onDismiss),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = scheme.onSurface,
            )
            Text(
                text = saveText,
                style = MaterialTheme.typography.bodyLarge,
                color = scheme.primary,
                modifier = Modifier.clickableNoIndication(onSave),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.xl)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            SettingsFrequencyField(
                label = waterFrequencyLabel,
                hint = null,
                value = form.waterFrequencyText,
                onValueChange = { onFormChange(form.copy(waterFrequencyText = it)) },
            )
            SettingsDateField(
                label = lastWateredText,
                value = form.lastWateredDate,
                onValueChange = { onFormChange(form.copy(lastWateredDate = it)) },
                placeholder = notSpecifiedText,
                doneText = doneText,
                cancelText = cancelText,
                locale = locale,
            )

            SettingsFrequencyField(
                label = fertilizingFrequencyLabel,
                hint = leaveEmptyHint,
                value = form.fertilizeFrequencyText,
                onValueChange = { onFormChange(form.copy(fertilizeFrequencyText = it)) },
            )
            SettingsDateField(
                label = "$fertilizingLabel · $lastTimeText",
                value = form.lastFertilizedDate,
                onValueChange = { onFormChange(form.copy(lastFertilizedDate = it)) },
                placeholder = notSpecifiedText,
                doneText = doneText,
                cancelText = cancelText,
                locale = locale,
            )

            SettingsFrequencyField(
                label = repottingFrequencyLabel,
                hint = leaveEmptyHint,
                value = form.repotFrequencyText,
                onValueChange = { onFormChange(form.copy(repotFrequencyText = it)) },
            )
            SettingsDateField(
                label = "$repottingLabel · $lastTimeText",
                value = form.lastRepottedDate,
                onValueChange = { onFormChange(form.copy(lastRepottedDate = it)) },
                placeholder = notSpecifiedText,
                doneText = doneText,
                cancelText = cancelText,
                locale = locale,
            )

            SettingsFrequencyField(
                label = pruningFrequencyLabel,
                hint = leaveEmptyHint,
                value = form.pruneFrequencyText,
                onValueChange = { onFormChange(form.copy(pruneFrequencyText = it)) },
            )
            SettingsDateField(
                label = "$pruningLabel · $lastTimeText",
                value = form.lastPrunedDate,
                onValueChange = { onFormChange(form.copy(lastPrunedDate = it)) },
                placeholder = notSpecifiedText,
                doneText = doneText,
                cancelText = cancelText,
                locale = locale,
            )
        }
    }
}

/** Поле частоты модалки (RN SettingsField: label + numeric input). */
@Composable
private fun SettingsFrequencyField(
    label: String,
    hint: String?,
    value: String,
    onValueChange: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = scheme.onSurface,
        )
        if (hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
        GtInputField(
            value = value,
            onValueChange = onValueChange,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
    }
}

/** Дата модалки (RN DatePickerInput): верхняя граница — сегодня. */
@Composable
private fun SettingsDateField(
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
