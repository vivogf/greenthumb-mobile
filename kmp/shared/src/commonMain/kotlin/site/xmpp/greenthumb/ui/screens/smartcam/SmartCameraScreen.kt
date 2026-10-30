package site.xmpp.greenthumb.ui.screens.smartcam

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.Dispatchers
import org.jetbrains.compose.resources.decodeToImageBitmap
import org.jetbrains.compose.resources.stringResource
import site.xmpp.greenthumb.core.platform.CropRect
import site.xmpp.greenthumb.core.platform.LocalAppLocale
import site.xmpp.greenthumb.core.platform.PickResult
import site.xmpp.greenthumb.core.platform.PickSource
import site.xmpp.greenthumb.core.platform.cropSquareJpeg
import site.xmpp.greenthumb.core.platform.deleteCaptureFile
import site.xmpp.greenthumb.core.platform.pickImageKeepingCapture
import site.xmpp.greenthumb.core.platform.resizeJpeg
import site.xmpp.greenthumb.ui.components.GtAlertDialog
import site.xmpp.greenthumb.ui.components.GtCard
import site.xmpp.greenthumb.ui.components.GtChevronDirection
import site.xmpp.greenthumb.ui.components.GtChevronMark
import site.xmpp.greenthumb.ui.components.GtModal
import site.xmpp.greenthumb.ui.components.GtPlantPhoto
import site.xmpp.greenthumb.ui.components.GtSheetRow
import site.xmpp.greenthumb.ui.components.GtSheetRowRole
import site.xmpp.greenthumb.ui.components.GtSheetTitle
import site.xmpp.greenthumb.ui.components.GtSmartCameraMark
import site.xmpp.greenthumb.ui.components.PrimaryButton
import site.xmpp.greenthumb.ui.components.SecondaryButton
import site.xmpp.greenthumb.ui.components.gtButtonWidth
import site.xmpp.greenthumb.ui.components.pickErrorText
import site.xmpp.greenthumb.ui.res.Res
import site.xmpp.greenthumb.ui.res.a11y_back
import site.xmpp.greenthumb.ui.res.common_cameraPermissionDenied
import site.xmpp.greenthumb.ui.res.common_cancel
import site.xmpp.greenthumb.ui.res.common_error
import site.xmpp.greenthumb.ui.res.common_galleryPermissionDenied
import site.xmpp.greenthumb.ui.res.common_permissionDeniedTitle
import site.xmpp.greenthumb.ui.res.diagnosis_journalCaptionPrefix
import site.xmpp.greenthumb.ui.res.diagnosis_loading
import site.xmpp.greenthumb.ui.res.diagnosis_photoPreview
import site.xmpp.greenthumb.ui.res.smartcam_addToMine
import site.xmpp.greenthumb.ui.res.smartcam_choosePhoto
import site.xmpp.greenthumb.ui.res.smartcam_chosenPlant
import site.xmpp.greenthumb.ui.res.smartcam_hintDisease
import site.xmpp.greenthumb.ui.res.smartcam_hintPlant
import site.xmpp.greenthumb.ui.res.smartcam_isMine
import site.xmpp.greenthumb.ui.res.smartcam_justLook
import site.xmpp.greenthumb.ui.res.smartcam_modeDisease
import site.xmpp.greenthumb.ui.res.smartcam_modePlant
import site.xmpp.greenthumb.ui.res.smartcam_noMatch
import site.xmpp.greenthumb.ui.res.smartcam_notInList
import site.xmpp.greenthumb.ui.res.smartcam_pickPlant
import site.xmpp.greenthumb.ui.res.smartcam_retake
import site.xmpp.greenthumb.ui.res.smartcam_takePhoto
import site.xmpp.greenthumb.ui.res.smartcam_title
import site.xmpp.greenthumb.ui.res.species_identifyConfidence
import site.xmpp.greenthumb.ui.res.species_identifyLoading
import site.xmpp.greenthumb.ui.res.species_identifyQuota
import site.xmpp.greenthumb.ui.res.species_identifyUnavailable
import site.xmpp.greenthumb.ui.screens.addplant.AddPlantDraft
import site.xmpp.greenthumb.ui.screens.diagnosis.DiagnosisJournal
import site.xmpp.greenthumb.ui.screens.diagnosis.DiagnosisJournalSaver
import site.xmpp.greenthumb.ui.screens.diagnosis.DiagnosisLogic
import site.xmpp.greenthumb.ui.screens.diagnosis.DiagnosisResultView
import site.xmpp.greenthumb.ui.screens.diagnosis.JournalSaveState
import site.xmpp.greenthumb.ui.screens.diagnosis.Likelihood
import site.xmpp.greenthumb.ui.screens.diagnosis.diagnosisErrorText
import site.xmpp.greenthumb.ui.screens.diagnosis.likelihoodWord
import site.xmpp.greenthumb.ui.screens.species.IdentifyCandidate
import site.xmpp.greenthumb.ui.screens.species.IdentifyLogic
import site.xmpp.greenthumb.ui.screens.species.PlantNetAttribution
import site.xmpp.greenthumb.ui.screens.species.ScoreLevel
import site.xmpp.greenthumb.ui.screens.species.SpeciesIdentifier
import site.xmpp.greenthumb.ui.screens.species.confidenceWord
import site.xmpp.greenthumb.ui.screens.species.rememberSpeciesCatalog
import site.xmpp.greenthumb.ui.theme.Radii
import site.xmpp.greenthumb.ui.theme.Sizes
import site.xmpp.greenthumb.ui.theme.Spacing
import site.xmpp.greenthumb.ui.theme.greenThumbExtendedColors

/** JPEG quality of the identify photo (the same as Add Plant identification). */
private const val PLANT_PHOTO_QUALITY = 0.85

/**
 * «Умная камера» (D§ item 9a, variant B): mode choice «Растение | Болезнь», a photo from the
 * system camera or the gallery, then the plant candidates with Pl@ntNet attribution or the
 * diagnosis. «Добавить к моим растениям» hands the photo and species to Add Plant through
 * [onAddToMine]; «Просто узнать» and back save nothing. A disease result is for a plant outside
 * the list; «Это моё растение?» picks one of [myPlants] and saves the result to its journal
 * through [journal].
 */
@Composable
public fun SmartCameraScreen(
    initialMode: SmartCameraMode,
    identifier: SpeciesIdentifier?,
    diagnoser: StandaloneDiagnoser?,
    onClose: () -> Unit,
    onAddToMine: (AddPlantDraft) -> Unit,
    myPlants: List<SmartCameraPlant> = emptyList(),
    journal: DiagnosisJournalSaver? = null,
    pick: suspend (PickSource) -> PickResult = { pickImageKeepingCapture(it) },
    deleteCapture: (String) -> Unit = ::deleteCaptureFile,
) {
    val catalog = rememberSpeciesCatalog()
    val currentCatalog by rememberUpdatedState(catalog)
    val controller = remember(identifier, diagnoser, journal) {
        SmartCameraController(
            initialMode = initialMode,
            identifier = identifier,
            diagnoser = diagnoser,
            pick = pick,
            preparePlant = { raw -> resizeJpeg(raw, maxSide = IdentifyLogic.PHOTO_MAX_SIDE, quality = PLANT_PHOTO_QUALITY) },
            prepareDisease = { raw -> DiagnosisLogic.preparePhoto(raw) { bytes, side, quality -> resizeJpeg(bytes, side, quality) } },
            catalog = { currentCatalog },
            workContext = Dispatchers.Default,
            journal = journal,
            deleteCapture = deleteCapture,
        )
    }
    DisposableEffect(controller) { onDispose { controller.close() } }
    SmartCameraContent(controller = controller, onClose = onClose, onAddToMine = onAddToMine, myPlants = myPlants)
}

/** Square cover of the identified photo for Add Plant; the full photo when it cannot be decoded. */
internal fun draftPhoto(photo: ByteArray): ByteArray {
    val bitmap = runCatching { photo.decodeToImageBitmap() }.getOrNull() ?: return photo
    return cropSquareJpeg(photo, CropRect.centerSquare(bitmap.width, bitmap.height))
}

@Composable
internal fun SmartCameraContent(
    controller: SmartCameraController,
    onClose: () -> Unit,
    onAddToMine: (AddPlantDraft) -> Unit,
    myPlants: List<SmartCameraPlant> = emptyList(),
) {
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val captionPrefix = stringResource(Res.string.diagnosis_journalCaptionPrefix)
    val words = Likelihood.entries.associateWith { likelihoodWord(it) }
    val language = IdentifyLogic.wireLanguage(LocalAppLocale.current)
    val backLabel = stringResource(Res.string.a11y_back)
    val phase = controller.phase

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.lg)
            .testTag("smartcam_screen"),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Box(
                modifier = Modifier
                    .size(Sizes.touch)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                    ) { onClose() }
                    .semantics { contentDescription = backLabel }
                    .testTag("smartcam_back"),
                contentAlignment = Alignment.Center,
            ) {
                GtChevronMark(tint = scheme.onSurface, modifier = Modifier.size(Spacing.lg), direction = GtChevronDirection.Left)
            }
            GtSmartCameraMark(tint = scheme.secondary, modifier = Modifier.size(Sizes.iconSlot))
            Text(
                text = stringResource(Res.string.smartcam_title),
                style = MaterialTheme.typography.headlineMedium,
                color = scheme.onSurface,
                modifier = Modifier.weight(1f).testTag("smartcam_title"),
            )
        }

        when (phase) {
            SmartCameraPhase.Choose -> ChooseContent(controller, onShoot = { source -> controller.shoot(scope, source, language) })
            SmartCameraPhase.Loading -> {
                PhotoPreview(controller.photo)
                Row(
                    modifier = Modifier.fillMaxWidth().testTag("smartcam_loading"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    CircularProgressIndicator(color = scheme.secondary, modifier = Modifier.size(Spacing.xxl))
                    Text(
                        text = stringResource(
                            if (controller.mode == SmartCameraMode.Plant) Res.string.species_identifyLoading else Res.string.diagnosis_loading,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                    )
                }
                SecondaryButton(
                    text = stringResource(Res.string.common_cancel),
                    onClick = controller::cancel,
                    modifier = Modifier.gtButtonWidth().testTag("smartcam_cancel"),
                )
            }
            is SmartCameraPhase.PlantResult -> {
                PhotoPreview(controller.photo)
                phase.candidates.forEachIndexed { index, candidate ->
                    CandidateCard(
                        candidate = candidate,
                        language = LocalAppLocale.current,
                        index = index,
                        selected = index == controller.selectedCandidate,
                        onClick = { controller.selectCandidate(index) },
                    )
                }
                PlantNetAttribution()
                PrimaryButton(
                    text = stringResource(Res.string.smartcam_addToMine),
                    onClick = {
                        val candidate = controller.chosenCandidate()
                        val photo = controller.photo
                        if (candidate != null && photo != null) onAddToMine(AddPlantDraft(candidate, draftPhoto(photo)))
                    },
                    modifier = Modifier.gtButtonWidth().testTag("smartcam_addToMine"),
                )
                SecondaryButton(
                    text = stringResource(Res.string.smartcam_justLook),
                    onClick = onClose,
                    modifier = Modifier.gtButtonWidth().testTag("smartcam_justLook"),
                )
                SecondaryButton(
                    text = stringResource(Res.string.smartcam_retake),
                    onClick = controller::retake,
                    modifier = Modifier.gtButtonWidth().testTag("smartcam_retake"),
                )
            }
            is SmartCameraPhase.DiseaseResult -> {
                val chosen = myPlants.firstOrNull { it.id == controller.journalPlantId }
                DiagnosisResultView(
                    result = phase.result,
                    photo = controller.photo,
                    saveState = if (chosen != null) controller.journalSave else null,
                    onSave = {
                        controller.saveToJournal(
                            scope,
                            DiagnosisJournal.caption(captionPrefix, phase.result.causes) { words.getValue(it) },
                        )
                    },
                    onDone = onClose,
                    onAgain = controller::retake,
                    beforeActions = {
                        OwnPlantChoice(
                            chosen = chosen,
                            canChoose = controller.canSaveToJournal && myPlants.isNotEmpty() &&
                                controller.journalSave != JournalSaveState.Saving &&
                                controller.journalSave != JournalSaveState.Saved,
                            onChoose = controller::openPlantPicker,
                        )
                    },
                )
            }
            is SmartCameraPhase.Failed -> {
                PhotoPreview(controller.photo)
                Text(
                    text = failureText(phase.failure),
                    style = MaterialTheme.typography.bodyLarge,
                    color = scheme.onSurface,
                    modifier = Modifier.testTag("smartcam_message"),
                )
                if (controller.mode == SmartCameraMode.Plant) PlantNetAttribution()
                PrimaryButton(
                    text = stringResource(Res.string.smartcam_retake),
                    onClick = controller::retake,
                    modifier = Modifier.gtButtonWidth().testTag("smartcam_retake"),
                )
            }
        }
    }

    if (controller.plantPickerOpen) {
        PlantPickerSheet(
            plants = myPlants,
            onPick = controller::choosePlant,
            onDismiss = controller::dismissPlantPicker,
        )
    }
    controller.deniedSource?.let { source ->
        GtAlertDialog(
            title = stringResource(Res.string.common_permissionDeniedTitle),
            message = stringResource(
                when (source) {
                    PickSource.Camera -> Res.string.common_cameraPermissionDenied
                    PickSource.Gallery -> Res.string.common_galleryPermissionDenied
                },
            ),
            onDismissRequest = controller::dismissDenied,
        )
    }
    controller.pickErrorMessage?.let { message ->
        GtAlertDialog(
            title = stringResource(Res.string.common_error),
            message = pickErrorText(message),
            onDismissRequest = controller::dismissPickError,
        )
    }
}

/** «Не из вашего списка» (or the chosen plant) and «Это моё растение?» on a disease result. */
@Composable
private fun OwnPlantChoice(chosen: SmartCameraPlant?, canChoose: Boolean, onChoose: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    if (chosen == null) {
        Text(
            text = stringResource(Res.string.smartcam_notInList),
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.testTag("smartcam_notInList"),
        )
    } else {
        Text(
            text = stringResource(Res.string.smartcam_chosenPlant, chosen.name),
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurface,
            modifier = Modifier.testTag("smartcam_chosenPlant"),
        )
    }
    if (canChoose) {
        SecondaryButton(
            text = stringResource(Res.string.smartcam_isMine),
            onClick = onChoose,
            modifier = Modifier.gtButtonWidth().testTag("smartcam_isMine"),
        )
    }
}

/** «Какое из ваших растений?»: one row per plant with its photo and name, then «Отмена». */
@Composable
private fun PlantPickerSheet(plants: List<SmartCameraPlant>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    GtModal(onDismissRequest = onDismiss) {
        Box(modifier = Modifier.testTag("smartcam_pickPlantTitle")) {
            GtSheetTitle(text = stringResource(Res.string.smartcam_pickPlant))
        }
        Column(modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            plants.forEach { plant ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = Sizes.sheetRow)
                        .clickable(role = Role.Button) { onPick(plant.id) }
                        .testTag("smartcam_plant_${plant.id}")
                        .padding(horizontal = Spacing.xxl, vertical = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
                ) {
                    GtPlantPhoto(
                        photoUrl = plant.photoUrl,
                        leafSize = Sizes.iconSlot,
                        modifier = Modifier
                            .size(Sizes.journalThumb)
                            .clip(RoundedCornerShape(Radii.md))
                            .background(scheme.surfaceVariant)
                            .testTag("smartcam_plantThumb_${plant.id}"),
                    )
                    Text(
                        text = plant.name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = scheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        GtSheetRow(
            label = stringResource(Res.string.common_cancel),
            icon = null,
            onClick = onDismiss,
            role = GtSheetRowRole.Cancel,
            modifier = Modifier.testTag("smartcam_pickPlantCancel"),
        )
    }
}

@Composable
private fun ChooseContent(controller: SmartCameraController, onShoot: (PickSource) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    ModeSwitch(selected = controller.mode, onSelect = controller::selectMode)
    Text(
        text = stringResource(
            when (controller.mode) {
                SmartCameraMode.Plant -> Res.string.smartcam_hintPlant
                SmartCameraMode.Disease -> Res.string.smartcam_hintDisease
            },
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = scheme.onSurfaceVariant,
        modifier = Modifier.testTag("smartcam_hint"),
    )
    PrimaryButton(
        text = stringResource(Res.string.smartcam_takePhoto),
        onClick = { onShoot(PickSource.Camera) },
        enabled = !controller.picking,
        modifier = Modifier.gtButtonWidth().testTag("smartcam_camera"),
    )
    SecondaryButton(
        text = stringResource(Res.string.smartcam_choosePhoto),
        onClick = { onShoot(PickSource.Gallery) },
        enabled = !controller.picking,
        modifier = Modifier.gtButtonWidth().testTag("smartcam_gallery"),
    )
}

/** Two-segment «Растение | Болезнь» control; the selected segment uses the chip-selected style. */
@Composable
private fun ModeSwitch(selected: SmartCameraMode, onSelect: (SmartCameraMode) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val extended = greenThumbExtendedColors()
    val outer = RoundedCornerShape(Radii.lg)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(scheme.surfaceVariant, outer)
            .padding(Spacing.xxs)
            .testTag("smartcam_modes"),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        for (mode in SmartCameraMode.entries) {
            val on = mode == selected
            val label = stringResource(
                when (mode) {
                    SmartCameraMode.Plant -> Res.string.smartcam_modePlant
                    SmartCameraMode.Disease -> Res.string.smartcam_modeDisease
                },
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = Sizes.touch)
                    .clip(outer)
                    .then(if (on) Modifier.background(extended.chipSelected, outer).border(Sizes.glowBorder / 2, scheme.secondary, outer) else Modifier)
                    .clickable(role = Role.Tab) { onSelect(mode) }
                    .semantics { this.selected = on }
                    .testTag("smartcam_mode_${mode.wire}"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (on) FontWeight.SemiBold else null,
                    color = if (on) extended.accentText else scheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun CandidateCard(
    candidate: IdentifyCandidate,
    language: String,
    index: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val title = candidate.catalogEntry?.names?.forLanguage(language) ?: candidate.commonName
    GtCard(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (selected) Modifier.border(Sizes.glowBorder / 2, scheme.secondary, RoundedCornerShape(Radii.lg)) else Modifier)
            .clip(RoundedCornerShape(Radii.lg))
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected }
            .testTag("smartcam_candidate_$index"),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurface,
                )
            }
            Text(
                text = candidate.scientificName,
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = FontStyle.Italic,
                color = if (title == null) scheme.onSurface else scheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(Res.string.species_identifyConfidence, confidenceWord(candidate.level)),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f, fill = false).testTag("smartcam_similarity_$index"),
                )
                SimilarityDots(candidate.level, index)
            }
        }
    }
}

/** Filled dots repeat the confidence word (3 high, 2 medium, 1 low), so they are not read out. */
@Composable
private fun SimilarityDots(level: ScoreLevel, index: Int) {
    val filled = when (level) {
        ScoreLevel.High -> 3
        ScoreLevel.Medium -> 2
        ScoreLevel.Low -> 1
    }
    Row(
        modifier = Modifier.padding(start = Spacing.xs).clearAndSetSemantics { },
        horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        repeat(3) { dot ->
            val on = dot < filled
            Box(
                modifier = Modifier
                    .size(Sizes.likelihoodDot)
                    .clip(CircleShape)
                    .background(if (on) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outlineVariant)
                    .testTag(if (on) "smartcam_dotFilled_$index" else "smartcam_dotEmpty_$index"),
            )
        }
    }
}

@Composable
private fun failureText(failure: SmartCameraFailure): String = when (failure) {
    SmartCameraFailure.NoMatch -> stringResource(Res.string.smartcam_noMatch)
    SmartCameraFailure.IdentifyQuota -> stringResource(Res.string.species_identifyQuota)
    SmartCameraFailure.IdentifyUnavailable -> stringResource(Res.string.species_identifyUnavailable)
    is SmartCameraFailure.Diagnosis -> diagnosisErrorText(failure.error)
}

@Composable
private fun PhotoPreview(photo: ByteArray?) {
    // Decoded in memory: the image loader would write the photo to its disk cache.
    val bitmap: ImageBitmap? = remember(photo) { photo?.let { runCatching { it.decodeToImageBitmap() }.getOrNull() } }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = stringResource(Res.string.diagnosis_photoPreview),
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
                .clip(RoundedCornerShape(Radii.lg))
                .testTag("smartcam_photo"),
        )
    }
}
