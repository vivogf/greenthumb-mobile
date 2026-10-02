package site.xmpp.greenthumb.ui.screens.addplant

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import site.xmpp.greenthumb.core.network.ApiError
import site.xmpp.greenthumb.core.platform.LocalAppLocale
import site.xmpp.greenthumb.core.platform.PickSource
import site.xmpp.greenthumb.core.platform.photoDataUri
import site.xmpp.greenthumb.data.PlantRepositoryOpener
import site.xmpp.greenthumb.ui.components.GtAlertDialog
import site.xmpp.greenthumb.ui.components.GtAlertButton
import site.xmpp.greenthumb.ui.components.GtCard
import site.xmpp.greenthumb.ui.components.GtChevronMark
import site.xmpp.greenthumb.ui.components.GtDatePickerField
import site.xmpp.greenthumb.ui.components.GtImagePickerField
import site.xmpp.greenthumb.ui.components.GtImageSource
import site.xmpp.greenthumb.ui.components.GtSectionHeader
import site.xmpp.greenthumb.ui.components.GtTextField
import site.xmpp.greenthumb.ui.components.PhotoPickerHost
import site.xmpp.greenthumb.ui.components.PrimaryButton
import site.xmpp.greenthumb.ui.components.gtButtonWidth
import site.xmpp.greenthumb.ui.components.pickerToday
import site.xmpp.greenthumb.ui.components.rememberPhotoPicker
import site.xmpp.greenthumb.ui.res.Res
import site.xmpp.greenthumb.ui.res.addPlant_additionalCare
import site.xmpp.greenthumb.ui.res.addPlant_additionalCareHint
import site.xmpp.greenthumb.ui.res.addPlant_add
import site.xmpp.greenthumb.ui.res.addPlant_adding
import site.xmpp.greenthumb.ui.res.addPlant_fertilizePlaceholder
import site.xmpp.greenthumb.ui.res.addPlant_fertilizing
import site.xmpp.greenthumb.ui.res.addPlant_lastFertilized
import site.xmpp.greenthumb.ui.res.addPlant_lastPruned
import site.xmpp.greenthumb.ui.res.addPlant_lastRepotted
import site.xmpp.greenthumb.ui.res.addPlant_lastWateredHint
import site.xmpp.greenthumb.ui.res.addPlant_lastWateredLabel
import site.xmpp.greenthumb.ui.res.addPlant_locationLabel
import site.xmpp.greenthumb.ui.res.addPlant_locationPlaceholder
import site.xmpp.greenthumb.ui.res.addPlant_nameLabel
import site.xmpp.greenthumb.ui.res.addPlant_namePlaceholder
import site.xmpp.greenthumb.ui.res.addPlant_notesLabel
import site.xmpp.greenthumb.ui.res.addPlant_notesPlaceholder
import site.xmpp.greenthumb.ui.res.addPlant_photoLabel
import site.xmpp.greenthumb.ui.res.addPlant_photoPlaceholder
import site.xmpp.greenthumb.ui.res.addPlant_photoSourceTitle
import site.xmpp.greenthumb.ui.res.addPlant_pickDate
import site.xmpp.greenthumb.ui.res.addPlant_prunePlaceholder
import site.xmpp.greenthumb.ui.res.addPlant_pruning
import site.xmpp.greenthumb.ui.res.addPlant_removePhoto
import site.xmpp.greenthumb.ui.res.addPlant_repotPlaceholder
import site.xmpp.greenthumb.ui.res.addPlant_repotting
import site.xmpp.greenthumb.ui.res.addPlant_title
import site.xmpp.greenthumb.ui.res.addPlant_uploadFailed
import site.xmpp.greenthumb.ui.res.addPlant_validation_dateRequired
import site.xmpp.greenthumb.ui.res.addPlant_validation_frequencyInvalidNumber
import site.xmpp.greenthumb.ui.res.addPlant_validation_frequencyMinimum
import site.xmpp.greenthumb.ui.res.addPlant_validation_nameRequired
import site.xmpp.greenthumb.ui.res.addPlant_wateringLabel
import site.xmpp.greenthumb.ui.res.common_camera
import site.xmpp.greenthumb.ui.res.common_cancel
import site.xmpp.greenthumb.ui.res.common_done
import site.xmpp.greenthumb.ui.res.common_gallery
import site.xmpp.greenthumb.ui.theme.Spacing

/**
 * Экран добавления растения (Stage 7 п.5, фича screen-add-plant) — порт
 * `app/add-plant.tsx` (549 строк, прочитана целиком).
 *
 * Секции 1:1: фото ([GtImagePickerField] — каркас M8: квадрат + модалка
 * источников; активный пикер/кроп/resize — M8, колбэк сюда не приходит),
 * имя (обязательное), локация (опционально), частота полива (число) + дата
 * последнего полива (обязательная, [GtDatePickerField], дефолт — сегодня),
 * заметки, раскрывающийся расширенный уход (удобрение/пересадка/обрезка:
 * частота + дата), кнопка submit.
 *
 * Валидация — ВРУЧНУЮ по правилам insertPlantSchema ([AddPlantForm.validate])
 * без отдельной библиотеки; ошибки показываются под полями (GtTextField
 * error / подпись под пикером), submit блокируется (VAL-ADDPLANT-001).
 * Submit: валидная форма → [PlantRepository.add] (оптимистичная запись +
 * POST /api/plants; invalidate дашборда не нужен — Room сам рекомпонирует
 * список; VAL-ADDPLANT-002 — карточка появляется немедленно) → назад.
 * Ошибка сети/сервера — локализованный алерт (RN showAlert uploadFailed).
 *
 * Отличия от RN, осознанные по parity-файлу:
 * - react-hook-form+zod → ручная [AddPlantForm.validate] (план Stage 7 п.5);
 *   RN-хардкод русских текстов ошибок → ключи `addPlant.validation.*`;
 * - RN invalidateQueries + router.back → [PlantRepository.add] уже пишет
 *   Room; экран только возвращается назад (architecture.md §7);
 * - photoUri/photoBase64 RN-состояния → единый [AddPlantFields.photoUrl]
 *   data-URI (контракт бэкенда тот же; пикер/кроп — Stage 8 п.1);
 * - KeyboardAvoidingView → navigationBars/statusBars-пэддинги; RN-гаптика —
 *   expect-слой M10, desktop no-op;
 * - Ionicons leaf/arrow-back → Canvas-метки (material-icons в пинах нет,
 *   прецедент GtBottomTabs);
 * - RN-заголовки секций с эмодзи («💧 Полив», «🌱 Удобрение») захардкожены
 *   в RN-исходнике вне i18n; KMP рисует hairline-разделители без заголовков
 *   ([GtSectionHeader] с пустым title) и подписи полей из ключей.
 *
 * Экран живёт под [AppLocalizedContent] (GtAppNavGraph): смена языка
 * перекомпоновывает remember-состояние формы вместе с подписями (правило
 * parity-файла для Stage 7 экранов). Репозиторий открывает opener
 * ([AccountPlantGate] — один инстанс на пользователя); экран его НЕ закрывает
 * (правило DashboardScreen: смена вкладки/темы не закрывает базу).
 */
@Composable
public fun AddPlantScreen(
    userId: String,
    opener: PlantRepositoryOpener,
    onBack: () -> Unit,
) {
    val repo = remember(userId) { opener.open(userId) }
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val locale = LocalAppLocale.current

    // Локальное состояние формы RN add-plant.tsx (поля + advancedOpen +
    // submitting). Дефолт даты — todayString() (RN defaultValues).
    var fields by remember { mutableStateOf(AddPlantFields(lastWateredDate = pickerToday().toString())) }
    var advancedOpen by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var errors by remember { mutableStateOf<AddPlantErrors?>(null) }
    var errorDialogMessage by remember { mutableStateOf<String?>(null) }

    // Пикер фото (Stage 8 п.1): пикер → квадратный кроп → data-URI в форму.
    // rememberUpdatedState внутри не даёт рекомпозиции потерять открытый кроп.
    val photoPicker = rememberPhotoPicker { bytes ->
        fields = fields.copy(photoUrl = photoDataUri(bytes))
    }

    // Тексты — до корутин (stringResource композабелен, catch — нет).
    val uploadFailed = stringResource(Res.string.addPlant_uploadFailed)
    val cancelText = stringResource(Res.string.common_cancel)
    val photoLabel = stringResource(Res.string.addPlant_photoLabel)
    val photoPlaceholder = stringResource(Res.string.addPlant_photoPlaceholder)
    val photoSourceTitle = stringResource(Res.string.addPlant_photoSourceTitle)
    val removePhoto = stringResource(Res.string.addPlant_removePhoto)
    val pickDate = stringResource(Res.string.addPlant_pickDate)
    val doneText = stringResource(Res.string.common_done)
    val validation = AddPlantErrorTexts(
        nameRequired = stringResource(Res.string.addPlant_validation_nameRequired),
        frequencyInvalidNumber = stringResource(Res.string.addPlant_validation_frequencyInvalidNumber),
        frequencyMinimum = stringResource(Res.string.addPlant_validation_frequencyMinimum),
        dateRequired = stringResource(Res.string.addPlant_validation_dateRequired),
    )

    fun submit() {
        val current = AddPlantForm.validate(fields, validation)
        errors = current
        if (!current.canSubmit || submitting) return
        submitting = true
        scope.launch {
            try {
                repo.add(AddPlantForm.toInsertPlantDto(fields))
                onBack()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                // RN showAlert(t('addPlant.uploadFailed'), err?.message).
                errorDialogMessage = error.message ?: error.toString()
            } finally {
                submitting = false
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(scheme.background)
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            // Шапка: назад + заголовок (RN header row, gap 12 → Spacing.sm).
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Box(
                    modifier = Modifier
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Button,
                        ) { onBack() }
                        .padding(Spacing.xs),
                ) {
                    GtChevronMark(tint = scheme.onSurface, modifier = Modifier.size(Spacing.xl))
                }
                Text(
                    text = stringResource(Res.string.addPlant_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = scheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }

            // Фото (квадрат; пикер — Stage 8 п.1: галерея/камера + свой кроп).
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                GtImagePickerField(
                    hasImage = fields.photoUrl.isNotEmpty(),
                    label = if (fields.photoUrl.isEmpty()) photoPlaceholder else photoLabel,
                    sourceTitle = photoSourceTitle,
                    cameraLabel = stringResource(Res.string.common_camera),
                    galleryLabel = stringResource(Res.string.common_gallery),
                    removeLabel = removePhoto,
                    cancelLabel = cancelText,
                    photoUrl = fields.photoUrl,
                    onPickRequested = { source ->
                        photoPicker.launch(
                            scope,
                            when (source) {
                                GtImageSource.Camera -> PickSource.Camera
                                GtImageSource.Gallery -> PickSource.Gallery
                            },
                        )
                    },
                    onRemove = { fields = fields.copy(photoUrl = "") },
                )
            }

            // Имя (обязательное; подсветка ошибки — рамка error).
            FormLabel(text = stringResource(Res.string.addPlant_nameLabel))
            GtTextField(
                value = fields.name,
                onValueChange = { fields = fields.copy(name = it) },
                label = "",
                placeholder = stringResource(Res.string.addPlant_namePlaceholder),
                error = errors?.name,
                modifier = Modifier.fillMaxWidth(),
            )

            // Локация (опциональная).
            FormLabel(text = stringResource(Res.string.addPlant_locationLabel))
            GtTextField(
                value = fields.location,
                onValueChange = { fields = fields.copy(location = it) },
                label = "",
                placeholder = stringResource(Res.string.addPlant_locationPlaceholder),
                modifier = Modifier.fillMaxWidth(),
            )

            // Полив (RN SectionDivider «💧 Полив» — разделитель без заголовка).
            GtSectionHeader(title = "")
            FormLabel(text = stringResource(Res.string.addPlant_wateringLabel))
            GtTextField(
                value = fields.waterFrequencyText,
                onValueChange = { fields = fields.copy(waterFrequencyText = it) },
                label = "",
                placeholder = "7",
                error = errors?.waterFrequency,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                FormLabel(text = stringResource(Res.string.addPlant_lastWateredLabel))
                Text(
                    text = stringResource(Res.string.addPlant_lastWateredHint),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
            GtDatePickerField(
                value = fields.lastWateredDate,
                onValueChange = { date -> fields = fields.copy(lastWateredDate = date) },
                placeholder = pickDate,
                confirmLabel = doneText,
                dismissLabel = cancelText,
                languageTag = locale,
                errorText = errors?.lastWateredDate,
            )

            // Расширенный уход (раскрывающийся блок; RN Pressable chevron).
            GtSectionHeader(title = "")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                    ) { advancedOpen = !advancedOpen }
                    .padding(vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                    Text(
                        text = stringResource(Res.string.addPlant_additionalCare),
                        style = MaterialTheme.typography.titleSmall,
                        color = scheme.onSurface,
                    )
                    Text(
                        text = stringResource(Res.string.addPlant_additionalCareHint),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                GtChevronMark(tint = scheme.onSurfaceVariant, modifier = Modifier.size(Spacing.lg))
            }

            if (advancedOpen) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                    CareSubSection(title = stringResource(Res.string.addPlant_fertilizing)) {
                        FrequencyField(
                            value = fields.fertilizeFrequencyText,
                            onValueChange = { fields = fields.copy(fertilizeFrequencyText = it) },
                            label = stringResource(Res.string.addPlant_fertilizing),
                            placeholder = stringResource(Res.string.addPlant_fertilizePlaceholder),
                        )
                        DateField(
                            value = fields.lastFertilizedDate,
                            onValueChange = { fields = fields.copy(lastFertilizedDate = it) },
                            label = stringResource(Res.string.addPlant_lastFertilized),
                            locale = locale,
                            placeholder = pickDate,
                            confirmLabel = doneText,
                            dismissLabel = cancelText,
                        )
                    }
                    CareSubSection(title = stringResource(Res.string.addPlant_repotting)) {
                        FrequencyField(
                            value = fields.repotFrequencyText,
                            onValueChange = { fields = fields.copy(repotFrequencyText = it) },
                            label = stringResource(Res.string.addPlant_repotting),
                            placeholder = stringResource(Res.string.addPlant_repotPlaceholder),
                        )
                        DateField(
                            value = fields.lastRepottedDate,
                            onValueChange = { fields = fields.copy(lastRepottedDate = it) },
                            label = stringResource(Res.string.addPlant_lastRepotted),
                            locale = locale,
                            placeholder = pickDate,
                            confirmLabel = doneText,
                            dismissLabel = cancelText,
                        )
                    }
                    CareSubSection(title = stringResource(Res.string.addPlant_pruning)) {
                        FrequencyField(
                            value = fields.pruneFrequencyText,
                            onValueChange = { fields = fields.copy(pruneFrequencyText = it) },
                            label = stringResource(Res.string.addPlant_pruning),
                            placeholder = stringResource(Res.string.addPlant_prunePlaceholder),
                        )
                        DateField(
                            value = fields.lastPrunedDate,
                            onValueChange = { fields = fields.copy(lastPrunedDate = it) },
                            label = stringResource(Res.string.addPlant_lastPruned),
                            locale = locale,
                            placeholder = pickDate,
                            confirmLabel = doneText,
                            dismissLabel = cancelText,
                        )
                    }
                }
            }

            // Заметки (многострочные, опциональные).
            FormLabel(text = stringResource(Res.string.addPlant_notesLabel))
            GtTextField(
                value = fields.notes,
                onValueChange = { fields = fields.copy(notes = it) },
                label = "",
                placeholder = stringResource(Res.string.addPlant_notesPlaceholder),
                singleLine = false,
                modifier = Modifier.fillMaxWidth(),
            )

            // Submit (disabled при отправке; RN opacity 0.75 → disabled-цвета).
            PrimaryButton(
                text = if (submitting) stringResource(Res.string.addPlant_adding) else stringResource(Res.string.addPlant_add),
                onClick = { submit() },
                enabled = !submitting,
                modifier = Modifier.gtButtonWidth(),
            )
            if (submitting) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = scheme.primary, modifier = Modifier.size(Spacing.xl))
                }
            }
        }
        // Пикер фото поверх экрана (Stage 8 п.1): файловый диалог (jvm),
        // кроп-экран, алерты разрешения/ошибки.
        PhotoPickerHost(photoPicker)
    }

    // Ошибка загрузки (RN showAlert(t('addPlant.uploadFailed'), err.message)).
    val dialogMessage = errorDialogMessage
    if (dialogMessage != null) {
        GtAlertDialog(
            title = uploadFailed,
            message = dialogMessage,
            buttons = listOf(GtAlertButton(text = "OK")),
            onDismissRequest = { errorDialogMessage = null },
        )
    }
}

/** Подпись поля формы (RN labelStyle: 12/600 uppercase mutedForeground). */
@Composable
private fun FormLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Карточка подраздела расширенного ухода (RN CareSubSection). */
@Composable
private fun CareSubSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        content()
    }
}

/** Поле частоты продвинутого ухода (число, RN keyboardType numeric). */
@Composable
private fun FrequencyField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        GtTextField(
            value = value,
            onValueChange = onValueChange,
            label = "",
            placeholder = placeholder,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Дата продвинутого ухода: подпись + пикер (без обязательности). */
@Composable
private fun DateField(
    value: String?,
    onValueChange: (String) -> Unit,
    label: String,
    locale: String,
    placeholder: String,
    confirmLabel: String,
    dismissLabel: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        GtDatePickerField(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            confirmLabel = confirmLabel,
            dismissLabel = dismissLabel,
            languageTag = locale,
        )
    }
}
