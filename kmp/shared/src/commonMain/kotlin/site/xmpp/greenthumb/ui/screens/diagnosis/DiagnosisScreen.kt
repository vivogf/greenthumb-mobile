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
import androidx.compose.ui.text.font.FontWeight
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
import site.xmpp.greenthumb.ui.components.GtCard
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
import site.xmpp.greenthumb.ui.res.diagnosis_advice
import site.xmpp.greenthumb.ui.res.diagnosis_backToPlant
import site.xmpp.greenthumb.ui.res.diagnosis_cancel
import site.xmpp.greenthumb.ui.res.diagnosis_choosePhoto
import site.xmpp.greenthumb.ui.res.diagnosis_dailyLimit
import site.xmpp.greenthumb.ui.res.diagnosis_disclaimer
import site.xmpp.greenthumb.ui.res.diagnosis_failed
import site.xmpp.greenthumb.ui.res.diagnosis_intro
import site.xmpp.greenthumb.ui.res.diagnosis_likelihood
import site.xmpp.greenthumb.ui.res.diagnosis_likelihoodHigh
import site.xmpp.greenthumb.ui.res.diagnosis_likelihoodLow
import site.xmpp.greenthumb.ui.res.diagnosis_likelihoodMedium
import site.xmpp.greenthumb.ui.res.diagnosis_loading
import site.xmpp.greenthumb.ui.res.diagnosis_networkError
import site.xmpp.greenthumb.ui.res.diagnosis_newDiagnosis
import site.xmpp.greenthumb.ui.res.diagnosis_notePlaceholder
import site.xmpp.greenthumb.ui.res.diagnosis_photoMissing
import site.xmpp.greenthumb.ui.res.diagnosis_photoPreview
import site.xmpp.greenthumb.ui.res.diagnosis_resultTitle
import site.xmpp.greenthumb.ui.res.diagnosis_retry
import site.xmpp.greenthumb.ui.res.diagnosis_submit
import site.xmpp.greenthumb.ui.res.diagnosis_summary
import site.xmpp.greenthumb.ui.res.diagnosis_takePhoto
import site.xmpp.greenthumb.ui.res.diagnosis_title
import site.xmpp.greenthumb.ui.res.diagnosis_unavailable
import site.xmpp.greenthumb.ui.theme.Radii
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
        DiagnosisError.Network -> Res.string.diagnosis_networkError
        DiagnosisError.Failed -> Res.string.diagnosis_failed
    },
)

/**
 * Diagnosis of one plant (architecture §5.7): photo from camera or gallery,
 * optional note, loading with cancel, result card or a message with retry.
 * The screen owns all of its state; leaving it cancels the request and
 * deletes the kept camera shot.
 */
@Composable
public fun DiagnosisScreen(
    plantId: String,
    diagnoser: PlantDiagnoser,
    onBack: () -> Unit,
    pick: suspend (PickSource) -> PickResult = { pickImageKeepingCapture(it) },
    deleteCapture: (String) -> Unit = ::deleteCaptureFile,
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
        )
    }
    DisposableEffect(controller) { onDispose { controller.close() } }
    DiagnosisContent(controller = controller, onBack = onBack)
}

@Composable
internal fun DiagnosisContent(controller: DiagnosisController, onBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
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
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Box(
                modifier = Modifier
                    .size(Spacing.xxl)
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
            Text(
                text = stringResource(Res.string.diagnosis_title),
                style = MaterialTheme.typography.headlineMedium,
                color = scheme.onSurface,
            )
        }

        when (phase) {
            is DiagnosisPhase.Result -> ResultContent(
                result = phase.result,
                onNew = controller::startOver,
                onBack = onBack,
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
                        enabled = controller.hasPhoto && !controller.picking,
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

@Composable
private fun ResultContent(result: DiagnosisResult, onNew: () -> Unit, onBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Text(
        text = stringResource(Res.string.diagnosis_resultTitle),
        style = MaterialTheme.typography.titleMedium,
        color = scheme.onSurface,
        modifier = Modifier.testTag("diagnosis_result"),
    )
    result.causes.forEachIndexed { index, cause ->
        GtCard(modifier = Modifier.fillMaxWidth().testTag("diagnosis_cause_$index")) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(
                    text = cause.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurface,
                )
                Text(
                    text = stringResource(Res.string.diagnosis_likelihood, likelihoodWord(cause.likelihood)),
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.tertiary,
                    modifier = Modifier.testTag("diagnosis_likelihood_$index"),
                )
                Text(
                    text = cause.explanation,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurface,
                )
                Text(
                    text = stringResource(Res.string.diagnosis_advice),
                    style = MaterialTheme.typography.titleSmall,
                    color = scheme.onSurface,
                )
                Text(
                    text = cause.advice,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurface,
                )
            }
        }
    }
    if (result.summary.isNotEmpty()) {
        GtCard(modifier = Modifier.fillMaxWidth().testTag("diagnosis_summary")) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(
                    text = stringResource(Res.string.diagnosis_summary),
                    style = MaterialTheme.typography.titleSmall,
                    color = scheme.onSurface,
                )
                Text(
                    text = result.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurface,
                )
            }
        }
    }
    Text(
        text = stringResource(Res.string.diagnosis_disclaimer),
        style = MaterialTheme.typography.bodySmall,
        color = scheme.onSurfaceVariant,
        modifier = Modifier.testTag("diagnosis_disclaimer"),
    )
    SecondaryButton(
        text = stringResource(Res.string.diagnosis_newDiagnosis),
        onClick = onNew,
        modifier = Modifier.gtButtonWidth().testTag("diagnosis_new"),
    )
    PrimaryButton(
        text = stringResource(Res.string.diagnosis_backToPlant),
        onClick = onBack,
        modifier = Modifier.gtButtonWidth().testTag("diagnosis_done"),
    )
}
