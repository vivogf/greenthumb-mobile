package site.xmpp.greenthumb.ui.screens.diagnosis

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.Dispatchers
import org.jetbrains.compose.resources.decodeToImageBitmap
import org.jetbrains.compose.resources.stringResource
import site.xmpp.greenthumb.core.platform.LocalAppLocale
import site.xmpp.greenthumb.core.platform.PickResult
import site.xmpp.greenthumb.core.platform.PickSource
import site.xmpp.greenthumb.core.platform.deleteCaptureFile
import site.xmpp.greenthumb.core.platform.deleteStaleCaptures
import site.xmpp.greenthumb.core.platform.pickImageKeepingCapture
import site.xmpp.greenthumb.core.platform.resizeJpeg
import site.xmpp.greenthumb.ui.components.GtAlertDialog
import site.xmpp.greenthumb.ui.components.GtChevronDirection
import site.xmpp.greenthumb.ui.components.GtChevronMark
import site.xmpp.greenthumb.ui.components.GtInputField
import site.xmpp.greenthumb.ui.components.PrimaryButton
import site.xmpp.greenthumb.ui.components.SecondaryButton
import site.xmpp.greenthumb.ui.components.gtButtonWidth
import site.xmpp.greenthumb.ui.components.pickErrorText
import site.xmpp.greenthumb.ui.res.Res
import site.xmpp.greenthumb.ui.res.a11y_back
import site.xmpp.greenthumb.ui.res.common_cameraPermissionDenied
import site.xmpp.greenthumb.ui.res.common_error
import site.xmpp.greenthumb.ui.res.common_galleryPermissionDenied
import site.xmpp.greenthumb.ui.res.common_permissionDeniedTitle
import site.xmpp.greenthumb.ui.res.diagnosis_backToPlant
import site.xmpp.greenthumb.ui.res.diagnosis_cancel
import site.xmpp.greenthumb.ui.res.diagnosis_choosePhoto
import site.xmpp.greenthumb.ui.res.diagnosis_dailyLimit
import site.xmpp.greenthumb.ui.res.diagnosis_failed
import site.xmpp.greenthumb.ui.res.diagnosis_intro
import site.xmpp.greenthumb.ui.res.diagnosis_journalCaptionPrefix
import site.xmpp.greenthumb.ui.res.diagnosis_likelihoodHigh
import site.xmpp.greenthumb.ui.res.diagnosis_likelihoodLow
import site.xmpp.greenthumb.ui.res.diagnosis_likelihoodMedium
import site.xmpp.greenthumb.ui.res.diagnosis_loading
import site.xmpp.greenthumb.ui.res.diagnosis_networkError
import site.xmpp.greenthumb.ui.res.diagnosis_notePlaceholder
import site.xmpp.greenthumb.ui.res.diagnosis_photoLoadFailed
import site.xmpp.greenthumb.ui.res.diagnosis_photoLoading
import site.xmpp.greenthumb.ui.res.diagnosis_photoMissing
import site.xmpp.greenthumb.ui.res.diagnosis_photoPreview
import site.xmpp.greenthumb.ui.res.diagnosis_retry
import site.xmpp.greenthumb.ui.res.diagnosis_serviceLimit
import site.xmpp.greenthumb.ui.res.diagnosis_submit
import site.xmpp.greenthumb.ui.res.diagnosis_takePhoto
import site.xmpp.greenthumb.ui.res.diagnosis_title
import site.xmpp.greenthumb.ui.res.diagnosis_unavailable
import site.xmpp.greenthumb.ui.theme.Radii
import site.xmpp.greenthumb.ui.theme.Sizes
import site.xmpp.greenthumb.ui.theme.Spacing

@Composable
public fun likelihoodWord(likelihood: Likelihood): String = stringResource(
    when (likelihood) {
        Likelihood.Low -> Res.string.diagnosis_likelihoodLow
        Likelihood.Medium -> Res.string.diagnosis_likelihoodMedium
        Likelihood.High -> Res.string.diagnosis_likelihoodHigh
    },
)

@Composable
public fun diagnosisErrorText(error: DiagnosisError): String = stringResource(
    when (error) {
        DiagnosisError.Unavailable -> Res.string.diagnosis_unavailable
        DiagnosisError.DailyLimit -> Res.string.diagnosis_dailyLimit
        DiagnosisError.ServiceLimit -> Res.string.diagnosis_serviceLimit
        DiagnosisError.Network -> Res.string.diagnosis_networkError
        DiagnosisError.Failed -> Res.string.diagnosis_failed
    },
)

/**
 * «Что с растением?» for one plant (architecture §5.7): photo from camera or gallery (or the
 * plant cover from [initialPhoto]), optional note, loading with cancel, then the result on its own
 * screen or a message with retry. [journal] is given only for a plant of this account and enables
 * «Сохранить в журнал растения». The screen owns all of its state; leaving it cancels the request
 * and deletes the kept camera shot.
 */
@Composable
public fun DiagnosisScreen(
    plantId: String,
    diagnoser: PlantDiagnoser,
    onBack: () -> Unit,
    pick: suspend (PickSource) -> PickResult = { pickImageKeepingCapture(it) },
    deleteCapture: (String) -> Unit = ::deleteCaptureFile,
    journal: DiagnosisJournalSaver? = null,
    initialPhoto: (suspend () -> ByteArray)? = null,
    subtitle: String? = null,
) {
    val controller = remember(plantId, diagnoser) {
        deleteStaleCaptures()
        DiagnosisController(
            plantId = plantId,
            diagnoser = diagnoser,
            pick = pick,
            prepare = { raw -> DiagnosisLogic.preparePhoto(raw) { bytes, side, quality -> resizeJpeg(bytes, side, quality) } },
            deleteCapture = deleteCapture,
            workContext = Dispatchers.Default,
            journal = journal,
            initialPhoto = initialPhoto,
        )
    }
    DisposableEffect(controller) { onDispose { controller.close() } }
    val scope = rememberCoroutineScope()
    LaunchedEffect(controller) { controller.loadInitialPhoto(scope) }
    DiagnosisContent(controller = controller, onBack = onBack, subtitle = subtitle)
}

@Composable
internal fun DiagnosisContent(controller: DiagnosisController, onBack: () -> Unit, subtitle: String? = null) {
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val captionPrefix = stringResource(Res.string.diagnosis_journalCaptionPrefix)
    val words = Likelihood.entries.associateWith { likelihoodWord(it) }
    val focusManager = LocalFocusManager.current
    val language = DiagnosisLogic.wireLanguage(LocalAppLocale.current)
    val backLabel = stringResource(Res.string.a11y_back)
    val phase = controller.phase

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.xl)
            .testTag("diagnosis_screen"),
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
                    ) { onBack() }
                    .semantics { contentDescription = backLabel }
                    .testTag("diagnosis_back"),
                contentAlignment = Alignment.Center,
            ) {
                GtChevronMark(
                    tint = scheme.onSurface,
                    modifier = Modifier.size(Spacing.lg),
                    direction = GtChevronDirection.Left,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(Res.string.diagnosis_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = scheme.onSurface,
                    modifier = Modifier.testTag("diagnosis_title"),
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.testTag("diagnosis_subtitle"),
                    )
                }
            }
        }

        when (phase) {
            is DiagnosisPhase.Result -> DiagnosisResultView(
                result = phase.result,
                photo = controller.photo,
                saveState = if (controller.canSaveToJournal) controller.journalSave else null,
                onSave = {
                    controller.saveToJournal(
                        scope,
                        DiagnosisJournal.caption(captionPrefix, phase.result.causes) { words.getValue(it) },
                    )
                },
                onDone = onBack,
                onAgain = controller::startOver,
            )
            DiagnosisPhase.Loading -> {
                PhotoPreview(controller.photo)
                Row(
                    modifier = Modifier.fillMaxWidth().testTag("diagnosis_loading"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    CircularProgressIndicator(color = scheme.secondary, modifier = Modifier.size(Spacing.xl))
                    Text(
                        text = stringResource(Res.string.diagnosis_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                    )
                }
                SecondaryButton(
                    text = stringResource(Res.string.diagnosis_cancel),
                    onClick = controller::cancel,
                    modifier = Modifier.gtButtonWidth().testTag("diagnosis_cancel"),
                )
            }
            DiagnosisPhase.Input -> {
                Text(
                    text = stringResource(Res.string.diagnosis_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
                PhotoPreview(controller.photo)
                if (controller.photoLoading) {
                    Row(
                        modifier = Modifier.fillMaxWidth().testTag("diagnosis_photoLoading"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        CircularProgressIndicator(color = scheme.secondary, modifier = Modifier.size(Spacing.xl))
                        Text(
                            text = stringResource(Res.string.diagnosis_photoLoading),
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
                if (controller.photoLoadFailed) {
                    Text(
                        text = stringResource(Res.string.diagnosis_photoLoadFailed),
                        style = MaterialTheme.typography.bodyLarge,
                        color = scheme.error,
                        modifier = Modifier.testTag("diagnosis_photoLoadError"),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.fillMaxWidth()) {
                    SecondaryButton(
                        text = stringResource(Res.string.diagnosis_takePhoto),
                        onClick = {
                            focusManager.clearFocus()
                            controller.pickPhoto(scope, PickSource.Camera)
                        },
                        enabled = !controller.picking,
                        modifier = Modifier.weight(1f).testTag("diagnosis_camera"),
                    )
                    SecondaryButton(
                        text = stringResource(Res.string.diagnosis_choosePhoto),
                        onClick = {
                            focusManager.clearFocus()
                            controller.pickPhoto(scope, PickSource.Gallery)
                        },
                        enabled = !controller.picking,
                        modifier = Modifier.weight(1f).testTag("diagnosis_gallery"),
                    )
                }
                GtInputField(
                    value = controller.note,
                    onValueChange = controller::updateNote,
                    placeholder = stringResource(Res.string.diagnosis_notePlaceholder),
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth().testTag("diagnosis_note"),
                )
                val error = controller.error
                if (error != null) {
                    Text(
                        text = diagnosisErrorText(error),
                        style = MaterialTheme.typography.bodyLarge,
                        color = scheme.error,
                        modifier = Modifier.testTag("diagnosis_error"),
                    )
                }
                if (controller.canRetry) {
                    PrimaryButton(
                        text = stringResource(Res.string.diagnosis_retry),
                        onClick = {
                            focusManager.clearFocus()
                            controller.retry(scope, language)
                        },
                        modifier = Modifier.gtButtonWidth().testTag("diagnosis_retry"),
                    )
                } else {
                    if (!controller.hasPhoto) {
                        Text(
                            text = stringResource(Res.string.diagnosis_photoMissing),
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    PrimaryButton(
                        text = stringResource(Res.string.diagnosis_submit),
                        onClick = {
                            focusManager.clearFocus()
                            controller.submit(scope, language)
                        },
                        enabled = controller.hasPhoto && !controller.picking && !controller.photoLoading,
                        modifier = Modifier.gtButtonWidth().testTag("diagnosis_submit"),
                    )
                }
                if (error != null) {
                    SecondaryButton(
                        text = stringResource(Res.string.diagnosis_backToPlant),
                        onClick = onBack,
                        modifier = Modifier.gtButtonWidth().testTag("diagnosis_errorBack"),
                    )
                }
            }
        }
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
private fun PhotoPreview(photo: ByteArray?) {
    // Decoded in memory: going through the image loader would write the photo to its disk cache.
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
                .testTag("diagnosis_photo"),
        )
    }
}
