package site.xmpp.greenthumb.ui.screens.species

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import site.xmpp.greenthumb.core.platform.PickSource
import site.xmpp.greenthumb.ui.components.GtModal
import site.xmpp.greenthumb.ui.components.SecondaryButton
import site.xmpp.greenthumb.ui.components.gtButtonWidth
import site.xmpp.greenthumb.ui.res.Res
import site.xmpp.greenthumb.ui.res.common_camera
import site.xmpp.greenthumb.ui.res.common_cancel
import site.xmpp.greenthumb.ui.res.common_gallery
import site.xmpp.greenthumb.ui.res.powered_by_plantnet
import site.xmpp.greenthumb.ui.res.species_confidenceHigh
import site.xmpp.greenthumb.ui.res.species_confidenceLow
import site.xmpp.greenthumb.ui.res.species_confidenceMedium
import site.xmpp.greenthumb.ui.res.species_identifyChooseHint
import site.xmpp.greenthumb.ui.res.species_identifyConfidence
import site.xmpp.greenthumb.ui.res.species_identifyEmpty
import site.xmpp.greenthumb.ui.res.species_identifyLoading
import site.xmpp.greenthumb.ui.res.species_identifyQuota
import site.xmpp.greenthumb.ui.res.species_identifySourceTitle
import site.xmpp.greenthumb.ui.res.species_identifyTitle
import site.xmpp.greenthumb.ui.res.species_identifyUnavailable
import site.xmpp.greenthumb.ui.res.species_inCatalog
import site.xmpp.greenthumb.ui.res.species_plantNetLogo
import site.xmpp.greenthumb.ui.res.species_poweredBy
import site.xmpp.greenthumb.ui.res.species_searchCatalog
import site.xmpp.greenthumb.ui.theme.Spacing

@Composable
public fun confidenceWord(level: ScoreLevel): String = stringResource(
    when (level) {
        ScoreLevel.High -> Res.string.species_confidenceHigh
        ScoreLevel.Medium -> Res.string.species_confidenceMedium
        ScoreLevel.Low -> Res.string.species_confidenceLow
    },
)

/** Camera or gallery for the identification photo. */
@Composable
public fun IdentifySourceDialog(onPick: (PickSource) -> Unit, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    GtModal(onDismissRequest = onDismiss) {
        Text(
            text = stringResource(Res.string.species_identifySourceTitle),
            style = MaterialTheme.typography.titleMedium,
            color = scheme.onSurface,
            modifier = Modifier.fillMaxWidth().padding(start = Spacing.xl, top = Spacing.xl, bottom = Spacing.xs),
        )
        DialogRow(stringResource(Res.string.common_camera), "identify_camera") { onPick(PickSource.Camera) }
        DialogRow(stringResource(Res.string.common_gallery), "identify_gallery") { onPick(PickSource.Gallery) }
        DialogRow(stringResource(Res.string.common_cancel), "identify_sourceCancel", muted = true, onClick = onDismiss)
    }
}

/**
 * Identification result: loading with cancel, up to three candidates with
 * confidence words and the catalog mark, or a clear message with a catalog
 * search action. Pl@ntNet attribution is always visible.
 */
@Composable
public fun SpeciesIdentifyDialog(
    state: IdentifyState,
    language: String,
    onPick: (IdentifyCandidate) -> Unit,
    onSearchCatalog: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    GtModal(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(Spacing.xl).testTag("identify_dialog"),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(
                text = stringResource(Res.string.species_identifyTitle),
                style = MaterialTheme.typography.titleMedium,
                color = scheme.onSurface,
            )
            when (state) {
                IdentifyState.Idle, IdentifyState.Loading -> Row(
                    modifier = Modifier.fillMaxWidth().testTag("identify_loading"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    CircularProgressIndicator(color = scheme.primary, modifier = Modifier.size(Spacing.xl))
                    Text(
                        text = stringResource(Res.string.species_identifyLoading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                    )
                }
                is IdentifyState.Results -> {
                    Text(
                        text = stringResource(Res.string.species_identifyChooseHint),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                    state.candidates.forEachIndexed { index, candidate ->
                        CandidateRow(candidate, language, index) { onPick(candidate) }
                    }
                }
                IdentifyState.Empty -> Message(stringResource(Res.string.species_identifyEmpty), onSearchCatalog)
                IdentifyState.QuotaExceeded -> Message(stringResource(Res.string.species_identifyQuota), onSearchCatalog)
                IdentifyState.Unavailable -> Message(stringResource(Res.string.species_identifyUnavailable), onSearchCatalog)
            }
            PlantNetAttribution()
            Text(
                text = stringResource(Res.string.common_cancel),
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.End)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        onClick = onDismiss,
                    )
                    .padding(Spacing.xs)
                    .testTag("identify_close"),
            )
        }
    }
}

@Composable
private fun CandidateRow(candidate: IdentifyCandidate, language: String, index: Int, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val catalogName = candidate.catalogEntry?.names?.forLanguage(language)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = Spacing.xs)
            .testTag("identify_candidate_$index"),
        verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        val title = catalogName ?: candidate.commonName
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
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(Res.string.species_identifyConfidence, confidenceWord(candidate.level)),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
            if (candidate.catalogEntry != null) {
                Text(
                    text = stringResource(Res.string.species_inCatalog),
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.primary,
                    modifier = Modifier.testTag("identify_inCatalog_$index"),
                )
            }
        }
    }
}

@Composable
private fun Message(text: String, onSearchCatalog: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.testTag("identify_message"),
    )
    SecondaryButton(
        text = stringResource(Res.string.species_searchCatalog),
        onClick = onSearchCatalog,
        modifier = Modifier.gtButtonWidth().testTag("identify_searchCatalog"),
    )
}

/** Pl@ntNet logo and "Powered by Pl@ntNet" (free-tier condition). */
@Composable
public fun PlantNetAttribution() {
    Row(
        modifier = Modifier.fillMaxWidth().testTag("identify_poweredBy"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Image(
            painter = painterResource(Res.drawable.powered_by_plantnet),
            contentDescription = stringResource(Res.string.species_plantNetLogo),
            contentScale = ContentScale.Fit,
            modifier = Modifier.height(Spacing.xl),
        )
        Text(
            text = stringResource(Res.string.species_poweredBy),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DialogRow(label: String, tag: String, muted: Boolean = false, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Spacing.xl, vertical = Spacing.md)
            .testTag(tag),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (muted) scheme.onSurfaceVariant else scheme.primary,
        )
    }
}
