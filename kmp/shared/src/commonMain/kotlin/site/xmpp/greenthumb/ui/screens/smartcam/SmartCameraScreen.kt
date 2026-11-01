package site.xmpp.greenthumb.ui.screens.smartcam

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.Dispatchers
import org.jetbrains.compose.resources.decodeToImageBitmap
import org.jetbrains.compose.resources.stringResource
import site.xmpp.greenthumb.core.platform.CameraCapture
import site.xmpp.greenthumb.core.platform.CameraShutter
import site.xmpp.greenthumb.core.platform.CameraStatus
import site.xmpp.greenthumb.core.platform.LocalAppLocale
import site.xmpp.greenthumb.core.platform.PickResult
import site.xmpp.greenthumb.core.platform.PickSource
import site.xmpp.greenthumb.core.platform.deleteCaptureFile
import site.xmpp.greenthumb.core.platform.deleteStaleCaptures
import site.xmpp.greenthumb.core.platform.openAppSettings
import site.xmpp.greenthumb.core.platform.pickImageKeepingCapture
import site.xmpp.greenthumb.ui.components.GtAlertDialog
import site.xmpp.greenthumb.ui.components.GtCard
import site.xmpp.greenthumb.ui.components.GtChevronDirection
import site.xmpp.greenthumb.ui.components.GtChevronMark
import site.xmpp.greenthumb.ui.components.GtCloseMark
import site.xmpp.greenthumb.ui.components.GtDiagnosisMark
import site.xmpp.greenthumb.ui.components.GtFlashMark
import site.xmpp.greenthumb.ui.components.GtGalleryMark
import site.xmpp.greenthumb.ui.components.GtInputField
import site.xmpp.greenthumb.ui.components.GtModal
import site.xmpp.greenthumb.ui.components.GtPlantPhoto
import site.xmpp.greenthumb.ui.components.GtSheetRow
import site.xmpp.greenthumb.ui.components.GtSheetRowRole
import site.xmpp.greenthumb.ui.components.GtSheetTitle
import site.xmpp.greenthumb.ui.components.GtSmartCameraMark
import site.xmpp.greenthumb.ui.components.GtSpeciesMark
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
import site.xmpp.greenthumb.ui.res.diagnosis_notePlaceholder
import site.xmpp.greenthumb.ui.res.diagnosis_photoLoadFailed
import site.xmpp.greenthumb.ui.res.diagnosis_photoLoading
import site.xmpp.greenthumb.ui.res.diagnosis_photoPreview
import site.xmpp.greenthumb.ui.res.smartcam_addToMine
import site.xmpp.greenthumb.ui.res.smartcam_backToPhoto
import site.xmpp.greenthumb.ui.res.smartcam_cameraDenied
import site.xmpp.greenthumb.ui.res.smartcam_cameraUnavailable
import site.xmpp.greenthumb.ui.res.smartcam_choosePhoto
import site.xmpp.greenthumb.ui.res.smartcam_chosenPlant
import site.xmpp.greenthumb.ui.res.smartcam_close
import site.xmpp.greenthumb.ui.res.smartcam_flashOff
import site.xmpp.greenthumb.ui.res.smartcam_flashOn
import site.xmpp.greenthumb.ui.res.smartcam_hintDisease
import site.xmpp.greenthumb.ui.res.smartcam_hintPlant
import site.xmpp.greenthumb.ui.res.smartcam_isMine
import site.xmpp.greenthumb.ui.res.smartcam_justLook
import site.xmpp.greenthumb.ui.res.smartcam_modeDisease
import site.xmpp.greenthumb.ui.res.smartcam_modePlant
import site.xmpp.greenthumb.ui.res.smartcam_noMatch
import site.xmpp.greenthumb.ui.res.smartcam_notInList
import site.xmpp.greenthumb.ui.res.smartcam_openSettings
import site.xmpp.greenthumb.ui.res.smartcam_pickPlant
import site.xmpp.greenthumb.ui.res.smartcam_plantCare
import site.xmpp.greenthumb.ui.res.smartcam_plantChipClear
import site.xmpp.greenthumb.ui.res.smartcam_reshoot
import site.xmpp.greenthumb.ui.res.smartcam_retake
import site.xmpp.greenthumb.ui.res.smartcam_sendDisease
import site.xmpp.greenthumb.ui.res.smartcam_sendNoteDisease
import site.xmpp.greenthumb.ui.res.smartcam_sendNotePlant
import site.xmpp.greenthumb.ui.res.smartcam_sendPlant
import site.xmpp.greenthumb.ui.res.smartcam_shutterDisease
import site.xmpp.greenthumb.ui.res.smartcam_shutterPlant
import site.xmpp.greenthumb.ui.res.smartcam_systemCamera
import site.xmpp.greenthumb.ui.res.smartcam_title
import site.xmpp.greenthumb.ui.res.smartcam_zoomReset
import site.xmpp.greenthumb.ui.res.species_identifyConfidence
import site.xmpp.greenthumb.ui.res.species_identifyLoading
import site.xmpp.greenthumb.ui.res.species_identifyQuota
import site.xmpp.greenthumb.ui.res.species_identifyUnavailable
import site.xmpp.greenthumb.ui.screens.addplant.AddPlantDraft
import site.xmpp.greenthumb.ui.screens.diagnosis.DiagnosisJournal
import site.xmpp.greenthumb.ui.screens.diagnosis.DiagnosisJournalSaver
import site.xmpp.greenthumb.ui.screens.diagnosis.DiagnosisResultView
import site.xmpp.greenthumb.ui.screens.diagnosis.Likelihood
import site.xmpp.greenthumb.ui.screens.diagnosis.PlantDiagnoser
import site.xmpp.greenthumb.ui.screens.diagnosis.diagnosisErrorText
import site.xmpp.greenthumb.ui.screens.diagnosis.likelihoodWord
import site.xmpp.greenthumb.ui.screens.species.IdentifyCandidate
import site.xmpp.greenthumb.ui.screens.species.IdentifyLogic
import site.xmpp.greenthumb.ui.screens.species.PlantNetAttribution
import site.xmpp.greenthumb.ui.screens.species.ScoreLevel
import site.xmpp.greenthumb.ui.screens.species.SpeciesIdentifier
import site.xmpp.greenthumb.ui.screens.species.confidenceWord
import site.xmpp.greenthumb.ui.screens.species.rememberSpeciesCatalog
import site.xmpp.greenthumb.ui.theme.CameraChrome
import site.xmpp.greenthumb.ui.theme.GtParagraph
import site.xmpp.greenthumb.ui.theme.Radii
import site.xmpp.greenthumb.ui.theme.Sizes
import site.xmpp.greenthumb.ui.theme.Spacing

/** The camera frame on screen: the sensor's 4:3 held in portrait. */
private const val CAMERA_FRAME_ASPECT = 3f / 4f

/** A gallery photo keeps its aspect on screen within these bounds; outside them it is fitted, never stretched. */
private const val PREVIEW_ASPECT_MIN = 9f / 16f
private const val PREVIEW_ASPECT_MAX = 16f / 9f

/** Width of the aiming frame as a share of the camera frame: the whole plant, or one sick spot up close. */
private const val PLANT_FRAME_SHARE = 0.8f
private const val DISEASE_FRAME_SHARE = 0.5f

/** The dark blink of the shutter fades into the waiting veil in this time. */
private const val SHOT_BLINK_MS = 150

/** The camera of the platform: CameraX on Android, a stub that reports [CameraStatus.Unavailable] on the JVM. */
public typealias SmartCameraView = @Composable (CameraShutter, (CameraStatus) -> Unit, Modifier) -> Unit

/**
 * «Умная камера» (M11 item 1): a full-screen live viewfinder with ✕, the flash toggle, the
 * «Растение · Болезнь» switch above the shutter row and the gallery button. A shot or a gallery
 * photo is shown first; only «Узнать вид» / «Проверить растение» sends it. Then the plant
 * candidates with Pl@ntNet attribution or the diagnosis. «Добавить к моим растениям» hands the photo
 * and species to Add Plant through [onAddToMine]; «Просто узнать» and back save nothing. A disease
 * result is for a plant outside the list; «Это моё растение?» picks one of [myPlants] and saves the
 * result to its journal through [journal].
 *
 * From a plant («Что с растением?», «Проверить на болезни») [plant] puts that plant's chip on top of «Болезнь»:
 * the check goes through [plantDiagnoser] and its result is saved to that plant's journal directly. [initialPhoto]
 * (the plant cover) starts on the after-shot screen instead of the viewfinder.
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
    camera: SmartCameraView = { shutter, onStatus, modifier -> CameraCapture(shutter, onStatus, modifier) },
    openSettings: () -> Unit = ::openAppSettings,
    onModeChange: (SmartCameraMode) -> Unit = {},
    plant: SmartCameraPlant? = null,
    plantDiagnoser: PlantDiagnoser? = null,
    initialPhoto: (suspend () -> ByteArray)? = null,
) {
    val catalog = rememberSpeciesCatalog()
    val currentCatalog by rememberUpdatedState(catalog)
    val currentModeChange by rememberUpdatedState(onModeChange)
    val shutter = remember { CameraShutter() }
    val controller = remember(identifier, diagnoser, journal, plantDiagnoser, plant?.id) {
        // Shots left behind by a killed process; this screen does not own any yet.
        deleteStaleCaptures()
        SmartCameraController(
            initialMode = initialMode,
            identifier = identifier,
            diagnoser = diagnoser,
            pick = pick,
            preparePlant = SmartCameraPhoto::prepare,
            prepareDisease = SmartCameraPhoto::prepare,
            catalog = { currentCatalog },
            capture = { flash -> shutter.take(flash) },
            workContext = Dispatchers.Default,
            journal = journal,
            deleteCapture = deleteCapture,
            onModeChange = { currentModeChange(it) },
            plantDiagnoser = plantDiagnoser,
            plant = plant,
            initialPhoto = initialPhoto,
            applyZoom = { shutter.zoomRatio = it },
        )
    }
    DisposableEffect(controller) { onDispose { controller.close() } }
    val loadScope = rememberCoroutineScope()
    LaunchedEffect(controller) { controller.loadInitialPhoto(loadScope) }
    SmartCameraContent(
        controller = controller,
        onClose = onClose,
        onAddToMine = onAddToMine,
        myPlants = myPlants,
        viewfinder = {
            Viewfinder(
                controller = controller,
                shutter = shutter,
                camera = camera,
                onClose = onClose,
                openSettings = openSettings,
            )
        },
    )
}

@Composable
internal fun SmartCameraContent(
    controller: SmartCameraController,
    onClose: () -> Unit,
    onAddToMine: (AddPlantDraft) -> Unit,
    myPlants: List<SmartCameraPlant> = emptyList(),
    viewfinder: @Composable () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val language = IdentifyLogic.wireLanguage(LocalAppLocale.current)
    when (controller.phase) {
        // The camera is in composition only on the viewfinder, so it is released as soon as a photo is shown.
        SmartCameraPhase.Viewfinder -> viewfinder()
        SmartCameraPhase.Captured, SmartCameraPhase.PhotoLoading ->
            AfterShot(controller, onClose, onSend = { controller.send(scope, language) })
        else -> ResultContent(controller, onClose, onAddToMine, myPlants)
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

@Composable
private fun Viewfinder(
    controller: SmartCameraController,
    shutter: CameraShutter,
    camera: SmartCameraView,
    onClose: () -> Unit,
    openSettings: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val status = controller.cameraStatus
    val blocked = status is CameraStatus.Denied || status == CameraStatus.Unavailable
    val layout = remember { PreviewLayout() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CameraChrome.background)
            .onGloballyPositioned { layout.root = it }
            .viewfinderGestures(
                previewBounds = layout::previewBounds,
                onSwipe = controller::swipeMode,
                onZoom = controller::zoomBy,
                onDoubleTap = controller::resetZoom,
            )
            .testTag("smartcam_viewfinder"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                ChromeButton(
                    label = stringResource(Res.string.smartcam_close),
                    onClick = onClose,
                    modifier = Modifier.testTag("smartcam_close"),
                ) { GtCloseMark(tint = CameraChrome.content, modifier = Modifier.size(Sizes.iconSlot)) }
                // The chip is centred between two equal 48 dp slots, so a missing flash does not shift it.
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    controller.activePlant?.let { PlantChip(it, onClear = controller::clearPlant) }
                }
                Box(modifier = Modifier.size(Sizes.touch)) {
                    if (controller.hasFlash) FlashToggle(controller)
                }
            }
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                // The whole sensor frame, as large as fits: what the box shows is what the shot keeps.
                Box(
                    modifier = Modifier
                        .aspectRatio(CAMERA_FRAME_ASPECT)
                        .clip(RoundedCornerShape(Radii.md))
                        .onGloballyPositioned { layout.preview = it }
                        .testTag("smartcam_preview"),
                    contentAlignment = Alignment.Center,
                ) {
                    // Stays composed while blocked: after «Открыть настройки» it reports the new permission state itself.
                    camera(shutter, controller::onCameraStatus, Modifier.fillMaxSize())
                    if (!blocked) {
                        AimingFrame(controller.mode, photoLoadFailed = controller.photoLoadFailed)
                        ShotFeedback(pending = controller.shotPending)
                        controller.zoomLabel?.let { label ->
                            ZoomLabel(label, onReset = controller::resetZoom, modifier = Modifier.align(Alignment.BottomCenter))
                        }
                    }
                }
                if (blocked) {
                    Box(modifier = Modifier.padding(horizontal = Spacing.lg)) {
                        CameraBlocked(
                            status = status,
                            enabled = !controller.picking,
                            onGallery = { controller.chooseFromGallery(scope) },
                            onSettings = openSettings,
                            onSystemCamera = { controller.useSystemCamera(scope) },
                        )
                    }
                }
            }
            Column(
                modifier = Modifier.fillMaxWidth().padding(Spacing.lg),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ModeSelector(selected = controller.mode, onSelect = controller::selectMode)
                if (!blocked) {
                    Spacer(Modifier.size(Spacing.lg))
                    ShutterRow(
                        mode = controller.mode,
                        busy = controller.shotPending,
                        shutterEnabled = status is CameraStatus.Ready && !controller.picking,
                        galleryEnabled = !controller.picking,
                        onShutter = { controller.shutter(scope) },
                        onGallery = { controller.chooseFromGallery(scope) },
                    )
                }
            }
        }
    }
}

/** Where the preview sits inside the viewfinder, for the double tap; read in the gesture, never in composition. */
private class PreviewLayout {
    var root: LayoutCoordinates? = null
    var preview: LayoutCoordinates? = null

    fun previewBounds(): Rect? {
        val outer = root?.takeIf { it.isAttached } ?: return null
        val inner = preview?.takeIf { it.isAttached } ?: return null
        return outer.localBoundingBoxOf(inner)
    }
}

/**
 * The shutter reaction (M11c item 2): on the tap the preview goes dark at once and lifts to a dim veil that stays
 * until the photo is ready, like the blink of a system camera.
 */
@Composable
private fun ShotFeedback(pending: Boolean) {
    val veil = remember { Animatable(0f) }
    LaunchedEffect(pending) {
        if (pending) {
            veil.snapTo(CameraChrome.shotBlink)
            veil.animateTo(CameraChrome.shotVeil, tween(SHOT_BLINK_MS))
        } else {
            veil.snapTo(0f)
        }
    }
    if (pending) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = veil.value }
                .background(CameraChrome.background)
                .testTag("smartcam_shotFeedback"),
        )
    }
}

/** «2.0×» on the preview while zoomed; the whole 48 dp target returns to 1×. */
@Composable
private fun ZoomLabel(label: String, onReset: () -> Unit, modifier: Modifier = Modifier) {
    val description = stringResource(Res.string.smartcam_zoomReset, label)
    Box(
        modifier = modifier
            .padding(bottom = Spacing.xs)
            // Grows with the text: at font scale 1.3 «4.0×» is wider than 48 dp.
            .defaultMinSize(minWidth = Sizes.touch, minHeight = Sizes.touch)
            .clip(RoundedCornerShape(Radii.md))
            .clickable(role = Role.Button, onClick = onReset)
            .semantics { contentDescription = description }
            .testTag("smartcam_zoomLabel"),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = CameraChrome.content,
            maxLines = 1,
            modifier = Modifier
                .clip(RoundedCornerShape(Radii.md))
                .background(CameraChrome.scrim)
                .padding(horizontal = Spacing.xs, vertical = Spacing.xxs)
                .testTag("smartcam_zoomLabelText"),
        )
    }
}

/** A round 48 dp control on the dark viewfinder. */
@Composable
private fun ChromeButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    mark: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .size(Sizes.touch)
            .clip(CircleShape)
            .background(CameraChrome.scrim)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { mark() }
}

@Composable
private fun FlashToggle(controller: SmartCameraController) {
    val on = controller.flash
    val label = stringResource(if (on) Res.string.smartcam_flashOn else Res.string.smartcam_flashOff)
    Box(
        modifier = Modifier
            .size(Sizes.touch)
            .clip(CircleShape)
            .background(CameraChrome.scrim)
            .toggleable(value = on, role = Role.Switch, onValueChange = { controller.toggleFlash() })
            .semantics { contentDescription = label }
            .testTag("smartcam_flash"),
        contentAlignment = Alignment.Center,
    ) {
        GtFlashMark(tint = CameraChrome.content, on = on, modifier = Modifier.size(Sizes.iconSlot))
    }
}

/**
 * Corners of the aim and the hint under them; «Болезнь» uses a smaller frame for a close-up. After a failed
 * plant cover download the hint says to take a new photo instead.
 */
@Composable
private fun AimingFrame(mode: SmartCameraMode, photoLoadFailed: Boolean) {
    val share = if (mode == SmartCameraMode.Plant) PLANT_FRAME_SHARE else DISEASE_FRAME_SHARE
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Box(
            modifier = Modifier
                .fillMaxWidth(share)
                .aspectRatio(1f)
                .border(Sizes.cameraRing, CameraChrome.frame, RoundedCornerShape(Radii.xl))
                .testTag("smartcam_frame"),
        )
        Text(
            text = stringResource(
                when {
                    photoLoadFailed -> Res.string.diagnosis_photoLoadFailed
                    mode == SmartCameraMode.Plant -> Res.string.smartcam_hintPlant
                    else -> Res.string.smartcam_hintDisease
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = CameraChrome.content,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .clip(RoundedCornerShape(Radii.md))
                .background(CameraChrome.scrim)
                .padding(horizontal = Spacing.sm, vertical = Spacing.xxs)
                .testTag(if (photoLoadFailed) "smartcam_photoLoadError" else "smartcam_hint"),
        )
    }
}

/**
 * The plant of a check from plant detail: a round thumbnail, the name and its own ✕ (a 48 dp target) that turns
 * the check into a plant-less one. A long name ends with «…» so the chip never pushes ✕ or the flash away.
 */
@Composable
private fun PlantChip(plant: SmartCameraPlant, onClear: () -> Unit, onCamera: Boolean = true) {
    val pill = RoundedCornerShape(Radii.xl)
    val scheme = MaterialTheme.colorScheme
    val fill = if (onCamera) CameraChrome.scrim else scheme.surfaceVariant
    val content = if (onCamera) CameraChrome.content else scheme.onSurface
    val clearLabel = stringResource(Res.string.smartcam_plantChipClear, plant.name)
    Row(
        modifier = Modifier
            .heightIn(min = Sizes.touch)
            .clip(pill)
            .background(fill)
            .padding(start = Spacing.xs)
            .testTag("smartcam_plantChip"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        GtPlantPhoto(
            photoUrl = plant.photoUrl,
            leafSize = Spacing.lg,
            modifier = Modifier
                .size(Sizes.iconSlot)
                .clip(CircleShape)
                .background(if (onCamera) CameraChrome.scrim else scheme.surface),
        )
        Text(
            text = plant.name,
            style = MaterialTheme.typography.labelLarge,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).testTag("smartcam_plantChipName"),
        )
        Box(
            modifier = Modifier
                .size(Sizes.touch)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = onClear)
                .semantics { contentDescription = clearLabel }
                .testTag("smartcam_plantChipClear"),
            contentAlignment = Alignment.Center,
        ) { GtCloseMark(tint = content, modifier = Modifier.size(Spacing.lg)) }
    }
}

/** «Растение · Болезнь» on the viewfinder; a horizontal swipe on the preview switches it too. */
@Composable
private fun ModeSelector(selected: SmartCameraMode, onSelect: (SmartCameraMode) -> Unit) {
    val pill = RoundedCornerShape(Radii.xl)
    Row(
        modifier = Modifier
            .clip(pill)
            .background(CameraChrome.scrim)
            .padding(Spacing.xxs)
            .testTag("smartcam_modes"),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        for (mode in SmartCameraMode.entries) {
            val on = mode == selected
            Box(
                modifier = Modifier
                    .heightIn(min = Sizes.touch)
                    .widthIn(min = Sizes.touch)
                    .clip(pill)
                    .then(if (on) Modifier.background(CameraChrome.content, pill) else Modifier)
                    .clickable(role = Role.Tab) { onSelect(mode) }
                    .semantics { this.selected = on }
                    .padding(horizontal = Spacing.lg)
                    .testTag("smartcam_mode_${mode.wire}"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(
                        when (mode) {
                            SmartCameraMode.Plant -> Res.string.smartcam_modePlant
                            SmartCameraMode.Disease -> Res.string.smartcam_modeDisease
                        },
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (on) FontWeight.SemiBold else null,
                    color = if (on) CameraChrome.onContent else CameraChrome.content,
                )
            }
        }
    }
}

/** Gallery on the left, the shutter in the centre; the right third stays empty so the shutter is centred. */
@Composable
private fun ShutterRow(
    mode: SmartCameraMode,
    busy: Boolean,
    shutterEnabled: Boolean,
    galleryEnabled: Boolean,
    onShutter: () -> Unit,
    onGallery: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            ChromeButton(
                label = stringResource(Res.string.smartcam_choosePhoto),
                onClick = onGallery,
                enabled = galleryEnabled,
                modifier = Modifier.testTag("smartcam_gallery"),
            ) { GtGalleryMark(tint = CameraChrome.content, modifier = Modifier.size(Sizes.iconSlot)) }
        }
        val label = stringResource(
            when (mode) {
                SmartCameraMode.Plant -> Res.string.smartcam_shutterPlant
                SmartCameraMode.Disease -> Res.string.smartcam_shutterDisease
            },
        )
        Box(
            modifier = Modifier
                .size(Sizes.cameraShutter)
                .border(Sizes.cameraRing, CameraChrome.content, CircleShape)
                .padding(Sizes.cameraRing * 2)
                .clip(CircleShape)
                .background(CameraChrome.content)
                .clickable(enabled = shutterEnabled, role = Role.Button, onClick = onShutter)
                .semantics { contentDescription = label }
                .testTag("smartcam_shutter"),
            contentAlignment = Alignment.Center,
        ) {
            val markModifier = Modifier.size(Sizes.iconSlot)
            when {
                busy -> CircularProgressIndicator(
                    color = CameraChrome.onContent,
                    strokeWidth = Sizes.cameraRing,
                    modifier = markModifier.testTag("smartcam_shutterBusy"),
                )
                mode == SmartCameraMode.Plant -> GtSpeciesMark(tint = CameraChrome.onContent, modifier = markModifier)
                else -> GtDiagnosisMark(tint = CameraChrome.onContent, modifier = markModifier)
            }
        }
        Spacer(Modifier.weight(1f))
    }
}

/** No camera permission, or no working camera: the gallery and settings / the system camera instead. */
@Composable
private fun CameraBlocked(
    status: CameraStatus,
    enabled: Boolean,
    onGallery: () -> Unit,
    onSettings: () -> Unit,
    onSystemCamera: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val denied = status is CameraStatus.Denied
    GtCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(
                text = stringResource(if (denied) Res.string.smartcam_cameraDenied else Res.string.smartcam_cameraUnavailable),
                // Two lines: start-aligned; justification is only for paragraphs of three lines or more.
                style = MaterialTheme.typography.bodyLarge,
                color = scheme.onSurface,
                modifier = Modifier.testTag("smartcam_cameraMessage"),
            )
            PrimaryButton(
                text = stringResource(Res.string.smartcam_choosePhoto),
                onClick = onGallery,
                enabled = enabled,
                modifier = Modifier.gtButtonWidth().testTag("smartcam_fallbackGallery"),
            )
            if (denied) {
                SecondaryButton(
                    text = stringResource(Res.string.smartcam_openSettings),
                    onClick = onSettings,
                    modifier = Modifier.gtButtonWidth().testTag("smartcam_openSettings"),
                )
            } else {
                SecondaryButton(
                    text = stringResource(Res.string.smartcam_systemCamera),
                    onClick = onSystemCamera,
                    enabled = enabled,
                    modifier = Modifier.gtButtonWidth().testTag("smartcam_systemCamera"),
                )
            }
        }
    }
}

/**
 * The photo stays on the phone here; the one primary button sends it, «Переснять» goes back to the camera.
 * A check from plant detail keeps the plant chip and says that the plant's care is taken into account. While the
 * plant cover is loading, the button waits.
 */
@Composable
private fun AfterShot(controller: SmartCameraController, onClose: () -> Unit, onSend: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val plant = controller.mode == SmartCameraMode.Plant
    val loadingPhoto = controller.phase == SmartCameraPhase.PhotoLoading
    val checked = controller.activePlant
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.lg)
            .testTag("smartcam_aftershot"),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Header(onClose = onClose, closeTag = "smartcam_close")
        if (checked != null) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                PlantChip(checked, onClear = controller::clearPlant, onCamera = false)
                Text(
                    text = stringResource(Res.string.smartcam_plantCare, checked.name),
                    style = GtParagraph.of(MaterialTheme.typography.bodyMedium),
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.testTag("smartcam_plantCare"),
                )
            }
        }
        if (loadingPhoto) {
            Row(
                modifier = Modifier.fillMaxWidth().testTag("smartcam_photoLoading"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                CircularProgressIndicator(color = scheme.secondary, modifier = Modifier.size(Spacing.xxl))
                Text(
                    text = stringResource(Res.string.diagnosis_photoLoading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
        PhotoPreview(controller.photo)
        if (!plant) {
            GtInputField(
                value = controller.note,
                onValueChange = controller::updateNote,
                placeholder = stringResource(Res.string.diagnosis_notePlaceholder),
                singleLine = false,
                modifier = Modifier.fillMaxWidth().testTag("smartcam_note"),
            )
        }
        PrimaryButton(
            text = stringResource(if (plant) Res.string.smartcam_sendPlant else Res.string.smartcam_sendDisease),
            onClick = onSend,
            enabled = !loadingPhoto,
            modifier = Modifier.gtButtonWidth().testTag("smartcam_send"),
        )
        Text(
            text = stringResource(if (plant) Res.string.smartcam_sendNotePlant else Res.string.smartcam_sendNoteDisease),
            style = GtParagraph.of(MaterialTheme.typography.bodyMedium),
            color = scheme.onSurfaceVariant,
            modifier = Modifier.testTag("smartcam_sendNote"),
        )
        SecondaryButton(
            text = stringResource(Res.string.smartcam_reshoot),
            onClick = controller::retake,
            enabled = !loadingPhoto,
            modifier = Modifier.gtButtonWidth().testTag("smartcam_reshoot"),
        )
    }
}

/** Back (or ✕ on the after-shot screen), the smart camera mark and the title. */
@Composable
private fun Header(onClose: () -> Unit, closeTag: String) {
    val scheme = MaterialTheme.colorScheme
    val closing = closeTag == "smartcam_close"
    val label = stringResource(if (closing) Res.string.smartcam_close else Res.string.a11y_back)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Box(
            modifier = Modifier
                .size(Sizes.touch)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                ) { onClose() }
                .semantics { contentDescription = label }
                .testTag(closeTag),
            contentAlignment = Alignment.Center,
        ) {
            if (closing) {
                GtCloseMark(tint = scheme.onSurface, modifier = Modifier.size(Sizes.iconSlot))
            } else {
                GtChevronMark(tint = scheme.onSurface, modifier = Modifier.size(Spacing.lg), direction = GtChevronDirection.Left)
            }
        }
        GtSmartCameraMark(tint = scheme.secondary, modifier = Modifier.size(Sizes.iconSlot))
        Text(
            text = stringResource(Res.string.smartcam_title),
            style = MaterialTheme.typography.headlineMedium,
            color = scheme.onSurface,
            modifier = Modifier.weight(1f).testTag("smartcam_title"),
        )
    }
}

/** Loading, the species candidates, the diagnosis or a failure. */
@Composable
private fun ResultContent(
    controller: SmartCameraController,
    onClose: () -> Unit,
    onAddToMine: (AddPlantDraft) -> Unit,
    myPlants: List<SmartCameraPlant>,
) {
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val captionPrefix = stringResource(Res.string.diagnosis_journalCaptionPrefix)
    val words = Likelihood.entries.associateWith { likelihoodWord(it) }
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
        Header(onClose = onClose, closeTag = "smartcam_back")

        when (phase) {
            SmartCameraPhase.Viewfinder, SmartCameraPhase.Captured, SmartCameraPhase.PhotoLoading -> Unit
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
                    onClick = { controller.addPlantDraft()?.let(onAddToMine) },
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
                val diagnosed = controller.diagnosedPlant
                val chosen = diagnosed ?: myPlants.firstOrNull { it.id == controller.journalPlantId }
                DiagnosisResultView(
                    result = phase.result,
                    photo = controller.photo,
                    saveState = if (chosen != null && controller.canSaveToJournal) controller.journalSave else null,
                    onSave = {
                        controller.saveToJournal(
                            scope,
                            DiagnosisJournal.caption(captionPrefix, phase.result.causes) { words.getValue(it) },
                        )
                    },
                    onDone = onClose,
                    onAgain = controller::retake,
                    beforeActions = {
                        // A plant check without a journal has nothing to say about saving.
                        if (diagnosed == null || controller.canSaveToJournal) OwnPlantChoice(
                            chosen = chosen,
                            canChoose = controller.canChooseOwnPlant && myPlants.isNotEmpty(),
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
                if (controller.canReturnToPhoto) {
                    PrimaryButton(
                        text = stringResource(Res.string.smartcam_backToPhoto),
                        onClick = controller::backToPhoto,
                        modifier = Modifier.gtButtonWidth().testTag("smartcam_backToPhoto"),
                    )
                    SecondaryButton(
                        text = stringResource(Res.string.smartcam_retake),
                        onClick = controller::retake,
                        modifier = Modifier.gtButtonWidth().testTag("smartcam_retake"),
                    )
                } else {
                    PrimaryButton(
                        text = stringResource(Res.string.smartcam_retake),
                        onClick = controller::retake,
                        modifier = Modifier.gtButtonWidth().testTag("smartcam_retake"),
                    )
                }
            }
        }
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
    if (bitmap != null && bitmap.width > 0 && bitmap.height > 0) {
        // A shot is shown in its 3:4 frame and a gallery photo in its own aspect, whole.
        val aspect = (bitmap.width.toFloat() / bitmap.height).coerceIn(PREVIEW_ASPECT_MIN, PREVIEW_ASPECT_MAX)
        Image(
            bitmap = bitmap,
            contentDescription = stringResource(Res.string.diagnosis_photoPreview),
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspect)
                .clip(RoundedCornerShape(Radii.lg))
                .testTag("smartcam_photo"),
        )
    }
}
