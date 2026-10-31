package site.xmpp.greenthumb.ui.screens.diagnosis

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import org.jetbrains.compose.resources.decodeToImageBitmap
import org.jetbrains.compose.resources.stringResource
import site.xmpp.greenthumb.ui.components.GtCard
import site.xmpp.greenthumb.ui.components.GtCheckMark
import site.xmpp.greenthumb.ui.components.PrimaryButton
import site.xmpp.greenthumb.ui.components.SecondaryButton
import site.xmpp.greenthumb.ui.components.gtButtonWidth
import site.xmpp.greenthumb.ui.res.Res
import site.xmpp.greenthumb.ui.res.diagnosis_advice
import site.xmpp.greenthumb.ui.res.diagnosis_dailyLimit
import site.xmpp.greenthumb.ui.res.diagnosis_disclaimer
import site.xmpp.greenthumb.ui.res.diagnosis_done
import site.xmpp.greenthumb.ui.res.diagnosis_failed
import site.xmpp.greenthumb.ui.res.diagnosis_likelihoodHigh
import site.xmpp.greenthumb.ui.res.diagnosis_likelihoodLow
import site.xmpp.greenthumb.ui.res.diagnosis_likelihoodMedium
import site.xmpp.greenthumb.ui.res.diagnosis_networkError
import site.xmpp.greenthumb.ui.res.diagnosis_newDiagnosis
import site.xmpp.greenthumb.ui.res.diagnosis_photoPreview
import site.xmpp.greenthumb.ui.res.diagnosis_resultTitle
import site.xmpp.greenthumb.ui.res.diagnosis_saveFailed
import site.xmpp.greenthumb.ui.res.diagnosis_saveToJournal
import site.xmpp.greenthumb.ui.res.diagnosis_savedToJournal
import site.xmpp.greenthumb.ui.res.diagnosis_serviceLimit
import site.xmpp.greenthumb.ui.res.diagnosis_unavailable
import site.xmpp.greenthumb.ui.theme.GreenThumbExtendedColors
import site.xmpp.greenthumb.ui.theme.GtParagraph
import site.xmpp.greenthumb.ui.theme.Radii
import site.xmpp.greenthumb.ui.theme.Sizes
import site.xmpp.greenthumb.ui.theme.Spacing
import site.xmpp.greenthumb.ui.theme.greenThumbExtendedColors

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

/** Filled dots of a likelihood: 3 for «Скорее всего», 2 for «Возможно», 1 for «Маловероятно». */
internal fun likelihoodDots(likelihood: Likelihood): Int = when (likelihood) {
    Likelihood.High -> 3
    Likelihood.Medium -> 2
    Likelihood.Low -> 1
}

internal fun likelihoodColor(likelihood: Likelihood, colors: GreenThumbExtendedColors): Color = when (likelihood) {
    Likelihood.High -> colors.causeHigh
    Likelihood.Medium -> colors.amber
    Likelihood.Low -> colors.causeLow
}

/** Likelihood word in its colour with three dots; the dots repeat the word, so only the word is read out. */
@Composable
internal fun LikelihoodBadge(likelihood: Likelihood, index: Int, modifier: Modifier = Modifier) {
    val color = likelihoodColor(likelihood, greenThumbExtendedColors())
    val filled = likelihoodDots(likelihood)
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = likelihoodWord(likelihood),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.weight(1f, fill = false).testTag("diagnosis_likelihood_$index"),
        )
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
                        .background(if (on) color else MaterialTheme.colorScheme.outlineVariant)
                        .testTag(if (on) "diagnosis_dotFilled_$index" else "diagnosis_dotEmpty_$index"),
                )
            }
        }
    }
}

/**
 * Result of «Что с растением?» (variant B): the diagnosed photo, up to three causes as a word with
 * dots, a «Что делать» card, the disclaimer and the actions. No numbers and no counters.
 * Shared with the smart camera.
 */
@Composable
internal fun DiagnosisResultView(
    result: DiagnosisResult,
    photo: ByteArray?,
    saveState: JournalSaveState?,
    onSave: () -> Unit,
    onDone: () -> Unit,
    onAgain: () -> Unit,
    /** Shown between the disclaimer and the actions (the smart camera's «Это моё растение?»). */
    beforeActions: @Composable () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    val bitmap: ImageBitmap? = remember(photo) { photo?.let { runCatching { it.decodeToImageBitmap() }.getOrNull() } }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = stringResource(Res.string.diagnosis_photoPreview),
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .height(Sizes.diagnosisPhoto)
                .clip(RoundedCornerShape(Radii.lg))
                .testTag("diagnosis_resultPhoto"),
        )
    }
    GtCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Text(
                text = stringResource(Res.string.diagnosis_resultTitle),
                style = MaterialTheme.typography.titleSmall,
                color = scheme.onSurface,
                modifier = Modifier.testTag("diagnosis_result"),
            )
            if (result.summary.isNotEmpty()) {
                Text(
                    text = result.summary,
                    style = GtParagraph.of(MaterialTheme.typography.bodyMedium),
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.testTag("diagnosis_summary"),
                )
            }
            result.causes.forEachIndexed { index, cause ->
                Column(
                    modifier = Modifier.fillMaxWidth().testTag("diagnosis_cause_$index"),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
                ) {
                    LikelihoodBadge(cause.likelihood, index, Modifier.fillMaxWidth())
                    Text(
                        text = cause.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = scheme.onSurface,
                    )
                    if (cause.explanation.isNotEmpty()) {
                        Text(
                            text = cause.explanation,
                            style = GtParagraph.of(MaterialTheme.typography.bodyMedium),
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
    val tips = result.causes.map { it.advice }.filter { it.isNotEmpty() }
    if (tips.isNotEmpty()) {
        GtCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(
                    text = stringResource(Res.string.diagnosis_advice),
                    style = MaterialTheme.typography.titleSmall,
                    color = scheme.onSurface,
                    modifier = Modifier.testTag("diagnosis_whatToDo"),
                )
                tips.forEach { tip ->
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        GtCheckMark(tint = scheme.secondary, modifier = Modifier.size(Spacing.lg).padding(top = Spacing.xxs))
                        Text(
                            text = tip,
                            style = GtParagraph.of(MaterialTheme.typography.bodyMedium),
                            color = scheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
    Text(
        text = stringResource(Res.string.diagnosis_disclaimer),
        style = GtParagraph.of(MaterialTheme.typography.bodySmall),
        color = scheme.onSurfaceVariant,
        modifier = Modifier.testTag("diagnosis_disclaimer"),
    )
    beforeActions()
    if (saveState != null) {
        if (saveState == JournalSaveState.Failed) {
            Text(
                text = stringResource(Res.string.diagnosis_saveFailed),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.error,
                modifier = Modifier.testTag("diagnosis_saveError"),
            )
        }
        PrimaryButton(
            text = stringResource(
                if (saveState == JournalSaveState.Saved) Res.string.diagnosis_savedToJournal else Res.string.diagnosis_saveToJournal,
            ),
            onClick = onSave,
            enabled = saveState == JournalSaveState.Idle || saveState == JournalSaveState.Failed,
            modifier = Modifier.gtButtonWidth().testTag("diagnosis_saveJournal"),
        )
    }
    SecondaryButton(
        text = stringResource(Res.string.diagnosis_done),
        onClick = onDone,
        modifier = Modifier.gtButtonWidth().testTag("diagnosis_done"),
    )
    SecondaryButton(
        text = stringResource(Res.string.diagnosis_newDiagnosis),
        onClick = onAgain,
        modifier = Modifier.gtButtonWidth().testTag("diagnosis_new"),
    )
}
