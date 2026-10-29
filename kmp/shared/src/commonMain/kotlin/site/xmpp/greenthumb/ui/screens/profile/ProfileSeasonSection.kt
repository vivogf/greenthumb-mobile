package site.xmpp.greenthumb.ui.screens.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import site.xmpp.greenthumb.core.platform.Haptics
import site.xmpp.greenthumb.core.storage.SessionManager
import site.xmpp.greenthumb.core.storage.SessionState
import site.xmpp.greenthumb.season.Hemisphere
import site.xmpp.greenthumb.season.SeasonMode
import site.xmpp.greenthumb.season.SeasonRule
import site.xmpp.greenthumb.season.TzLatitudes
import site.xmpp.greenthumb.season.rememberTzLatitudes
import site.xmpp.greenthumb.season.seasonModeOf
import site.xmpp.greenthumb.ui.components.GtWaterDropMark
import site.xmpp.greenthumb.ui.res.Res
import site.xmpp.greenthumb.ui.res.a11y_seasonOption
import site.xmpp.greenthumb.ui.res.profile_seasonAuto
import site.xmpp.greenthumb.ui.res.profile_seasonAutoNorth
import site.xmpp.greenthumb.ui.res.profile_seasonAutoSouth
import site.xmpp.greenthumb.ui.res.profile_seasonAutoTropical
import site.xmpp.greenthumb.ui.res.profile_seasonAutoUnknown
import site.xmpp.greenthumb.ui.res.profile_seasonHint
import site.xmpp.greenthumb.ui.res.profile_seasonNorth
import site.xmpp.greenthumb.ui.res.profile_seasonOff
import site.xmpp.greenthumb.ui.res.profile_seasonSaveFailed
import site.xmpp.greenthumb.ui.res.profile_seasonSouth
import site.xmpp.greenthumb.ui.res.profile_seasonTitle
import site.xmpp.greenthumb.ui.theme.Radii
import site.xmpp.greenthumb.ui.theme.Spacing

/** Option order of the switcher (architecture §2.9: Авто / Север / Юг / Выкл). */
private val SeasonOptions: List<Pair<SeasonMode, StringResource>> = listOf(
    SeasonMode.Auto to Res.string.profile_seasonAuto,
    SeasonMode.North to Res.string.profile_seasonNorth,
    SeasonMode.South to Res.string.profile_seasonSouth,
    SeasonMode.Off to Res.string.profile_seasonOff,
)

/** Auto explanation for the device zone (architecture §8.6). */
internal fun autoExplanation(hemisphere: Hemisphere): StringResource = when (hemisphere) {
    Hemisphere.North -> Res.string.profile_seasonAutoNorth
    Hemisphere.South -> Res.string.profile_seasonAutoSouth
    Hemisphere.Tropical -> Res.string.profile_seasonAutoTropical
    Hemisphere.None -> Res.string.profile_seasonAutoUnknown
}

/**
 * Seasonal watering control in Profile: four options for `season_mode`, saved optimistically through
 * [SessionManager.updateSeasonMode]. A failed save shows the previous mode again with an inline message.
 * [zoneId] is the device zone (the app computes the rule with it); [latitudes] is injected by tests.
 */
@Composable
internal fun SeasonModeSection(
    session: SessionManager,
    zoneId: String? = TimeZone.currentSystemDefault().id,
    latitudes: TzLatitudes? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val state by session.state.collectAsState()
    val user = when (val current = state) {
        is SessionState.SignedIn -> current.user
        is SessionState.Offline -> current.user
        else -> null
    } ?: return
    val loadedLatitudes = rememberTzLatitudes()
    val table = latitudes ?: loadedLatitudes
    val mode = seasonModeOf(user.seasonMode)
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            GtWaterDropMark(tint = scheme.secondary, modifier = Modifier.size(Spacing.xl))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(Res.string.profile_seasonTitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurface,
                )
                Text(
                    text = stringResource(Res.string.profile_seasonHint),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(scheme.surfaceVariant, RoundedCornerShape(Radii.md))
                .padding(Spacing.xxs),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
        ) {
            SeasonOptions.forEach { (option, label) ->
                val selected = option == mode
                val text = stringResource(label)
                val description = stringResource(Res.string.a11y_seasonOption, text)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(
                            if (selected) scheme.primary else scheme.surfaceVariant,
                            RoundedCornerShape(Radii.sm),
                        )
                        // Role.Tab: Compose reports `selected` to Android accessibility as isSelected only for tabs
                        // (other roles become "checked"), and Maestro/TalkBack read the segment as selected.
                        .selectable(
                            selected = selected,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Tab,
                            enabled = !saving,
                        ) {
                            if (selected || saving) return@selectable
                            failed = false
                            saving = true
                            Haptics.light()
                            scope.launch {
                                try {
                                    session.updateSeasonMode(option)
                                } catch (cancellation: CancellationException) {
                                    throw cancellation
                                } catch (error: Throwable) {
                                    failed = true
                                } finally {
                                    saving = false
                                }
                            }
                        }
                        .semantics { contentDescription = description }
                        .testTag("season_${option.wire}")
                        .padding(vertical = Spacing.sm, horizontal = Spacing.xxs),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) scheme.onPrimary else scheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }
        }
        if (mode == SeasonMode.Auto && table != null) {
            Text(
                text = stringResource(autoExplanation(SeasonRule(table).hemisphere(zoneId))),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.testTag("season_explanation"),
            )
        }
        if (failed) {
            Text(
                text = stringResource(Res.string.profile_seasonSaveFailed),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.error,
                modifier = Modifier.testTag("season_error"),
            )
        }
    }
}
