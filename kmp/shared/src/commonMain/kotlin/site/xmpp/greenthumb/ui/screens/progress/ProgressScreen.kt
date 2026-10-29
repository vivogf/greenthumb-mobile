package site.xmpp.greenthumb.ui.screens.progress

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.datetime.TimeZone
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import site.xmpp.greenthumb.data.AchievementCell
import site.xmpp.greenthumb.data.AchievementId
import site.xmpp.greenthumb.data.CachedProgress
import site.xmpp.greenthumb.data.PlantRepositoryOpener
import site.xmpp.greenthumb.data.ProgressView
import site.xmpp.greenthumb.data.formatProgressTime
import site.xmpp.greenthumb.ui.components.GtCheckMark
import site.xmpp.greenthumb.ui.components.GtChevronDirection
import site.xmpp.greenthumb.ui.components.GtChevronMark
import site.xmpp.greenthumb.ui.components.GtCloudOffMark
import site.xmpp.greenthumb.ui.components.GtLockMark
import site.xmpp.greenthumb.ui.components.PrimaryButton
import site.xmpp.greenthumb.ui.components.borderHairline
import site.xmpp.greenthumb.ui.res.Res
import site.xmpp.greenthumb.ui.res.a11y_back
import site.xmpp.greenthumb.ui.res.achievement_first_note_description
import site.xmpp.greenthumb.ui.res.achievement_first_note_title
import site.xmpp.greenthumb.ui.res.achievement_first_photo_description
import site.xmpp.greenthumb.ui.res.achievement_first_photo_title
import site.xmpp.greenthumb.ui.res.achievement_first_plant_description
import site.xmpp.greenthumb.ui.res.achievement_first_plant_title
import site.xmpp.greenthumb.ui.res.achievement_first_water_description
import site.xmpp.greenthumb.ui.res.achievement_first_water_title
import site.xmpp.greenthumb.ui.res.achievement_plants_5_description
import site.xmpp.greenthumb.ui.res.achievement_plants_5_title
import site.xmpp.greenthumb.ui.res.achievement_streak_30_description
import site.xmpp.greenthumb.ui.res.achievement_streak_30_title
import site.xmpp.greenthumb.ui.res.achievement_streak_7_description
import site.xmpp.greenthumb.ui.res.achievement_streak_7_title
import site.xmpp.greenthumb.ui.res.achievement_waterings_100_description
import site.xmpp.greenthumb.ui.res.achievement_waterings_100_title
import site.xmpp.greenthumb.ui.res.achievement_waterings_25_description
import site.xmpp.greenthumb.ui.res.achievement_waterings_25_title
import site.xmpp.greenthumb.ui.res.progress_achievements
import site.xmpp.greenthumb.ui.res.progress_bestStreak
import site.xmpp.greenthumb.ui.res.progress_cellDescription
import site.xmpp.greenthumb.ui.res.progress_currentStreak
import site.xmpp.greenthumb.ui.res.progress_loading
import site.xmpp.greenthumb.ui.res.progress_locked
import site.xmpp.greenthumb.ui.res.progress_needsNetwork
import site.xmpp.greenthumb.ui.res.progress_offline
import site.xmpp.greenthumb.ui.res.progress_refreshFailed
import site.xmpp.greenthumb.ui.res.progress_retry
import site.xmpp.greenthumb.ui.res.progress_streakHint
import site.xmpp.greenthumb.ui.res.progress_title
import site.xmpp.greenthumb.ui.res.progress_unlocked
import site.xmpp.greenthumb.ui.theme.Radii
import site.xmpp.greenthumb.ui.theme.Spacing

/** What the per-user cache holds; [Unknown] only until the first Room read. */
private sealed interface CacheState {
    data object Unknown : CacheState
    data object Absent : CacheState
    data class Present(val cached: CachedProgress) : CacheState
}

/**
 * Streaks and achievements (architecture §5.8), opened from Profile. Shows the
 * cached copy at once and refreshes it from `GET /api/progress` while online.
 * Offline it keeps the cached copy with a no-network note; with nothing cached it
 * says that the network is needed instead of spinning.
 */
@Composable
public fun ProgressScreen(
    userId: String,
    opener: PlantRepositoryOpener,
    isOnline: StateFlow<Boolean>,
    onBack: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val repo = remember(userId) { opener.open(userId) }
    val cache by remember(repo) {
        repo.progress.observe()
            .map { cached -> if (cached == null) CacheState.Absent else CacheState.Present(cached) }
            .catch { emit(CacheState.Absent) }
    }.collectAsState(initial = CacheState.Unknown)
    val online by isOnline.collectAsState()
    var refreshing by remember(repo) { mutableStateOf(false) }
    var refreshFailed by remember(repo) { mutableStateOf(false) }
    var attempt by remember(repo) { mutableIntStateOf(0) }

    LaunchedEffect(repo, online, attempt) {
        if (!online) return@LaunchedEffect
        refreshing = true
        try {
            repo.progress.refresh()
            refreshFailed = false
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            refreshFailed = true
        } finally {
            refreshing = false
        }
    }

    val backLabel = stringResource(Res.string.a11y_back)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.xl)
            .testTag("progress_screen"),
    ) {
        Box(
            modifier = Modifier
                .size(Spacing.xxl)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                ) { onBack() }
                .semantics { contentDescription = backLabel }
                .testTag("progress_back"),
            contentAlignment = Alignment.Center,
        ) {
            GtChevronMark(
                tint = scheme.onSurface,
                modifier = Modifier.size(Spacing.lg),
                direction = GtChevronDirection.Left,
            )
        }
        Spacer(modifier = Modifier.height(Spacing.lg))
        Text(
            text = stringResource(Res.string.progress_title),
            style = MaterialTheme.typography.headlineMedium,
            color = scheme.onSurface,
        )
        Spacer(modifier = Modifier.height(Spacing.lg))

        when (val state = cache) {
            CacheState.Unknown -> Unit
            is CacheState.Present -> {
                val time = formatProgressTime(state.cached.updatedAtMillis, TimeZone.currentSystemDefault())
                if (!online) {
                    StatusNote(stringResource(Res.string.progress_offline, time), tag = "progress_offline")
                } else if (refreshFailed && !refreshing) {
                    StatusNote(stringResource(Res.string.progress_refreshFailed, time), tag = "progress_refreshFailed")
                }
                ProgressContent(state.cached.view)
            }
            CacheState.Absent -> {
                if (online && !refreshFailed) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xl).testTag("progress_loading"),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(color = scheme.secondary, modifier = Modifier.size(Spacing.xl))
                        Text(
                            text = stringResource(Res.string.progress_loading),
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth().testTag("progress_needsNetwork"),
                        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                        ) {
                            GtCloudOffMark(tint = scheme.onSurfaceVariant, modifier = Modifier.size(Spacing.lg))
                            Text(
                                text = stringResource(Res.string.progress_needsNetwork),
                                style = MaterialTheme.typography.bodyMedium,
                                color = scheme.onSurface,
                            )
                        }
                        PrimaryButton(
                            text = stringResource(Res.string.progress_retry),
                            onClick = { attempt++ },
                            enabled = online && !refreshing,
                            modifier = Modifier.fillMaxWidth().testTag("progress_retry"),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusNote(text: String, tag: String) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.sm).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        GtCloudOffMark(tint = scheme.onSurfaceVariant, modifier = Modifier.size(Spacing.lg))
        Text(text = text, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
    }
}

@Composable
private fun ProgressContent(view: ProgressView) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        StreakCard(
            label = stringResource(Res.string.progress_currentStreak),
            value = view.current,
            tag = "progress_current",
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        StreakCard(
            label = stringResource(Res.string.progress_bestStreak),
            value = view.best,
            tag = "progress_best",
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
    }
    Spacer(modifier = Modifier.height(Spacing.xs))
    Text(
        text = stringResource(Res.string.progress_streakHint),
        style = MaterialTheme.typography.bodySmall,
        color = scheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(Spacing.xl))
    Text(
        text = stringResource(Res.string.progress_achievements),
        style = MaterialTheme.typography.titleMedium,
        color = scheme.onSurface,
    )
    Spacer(modifier = Modifier.height(Spacing.sm))
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        view.cells.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                row.forEach { cell ->
                    AchievementTile(cell, modifier = Modifier.weight(1f).fillMaxHeight())
                }
                if (row.size == 1) Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StreakCard(label: String, value: Int, tag: String, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .background(scheme.surface, RoundedCornerShape(Radii.lg))
            .padding(Spacing.lg),
    ) {
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.headlineLarge,
            color = scheme.tertiary,
            modifier = Modifier.testTag(tag),
        )
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
    }
}

@Composable
private fun AchievementTile(cell: AchievementCell, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val title = stringResource(cell.id.titleRes())
    val description = stringResource(cell.id.descriptionRes())
    val stateLabel = stringResource(if (cell.unlocked) Res.string.progress_unlocked else Res.string.progress_locked)
    val a11y = stringResource(Res.string.progress_cellDescription, title, stateLabel, description)
    val shape = RoundedCornerShape(Radii.md)
    Column(
        modifier = modifier
            .background(if (cell.unlocked) scheme.primaryContainer else scheme.surfaceVariant, shape)
            .borderHairline(if (cell.unlocked) scheme.primary else scheme.surfaceVariant, shape)
            .semantics(mergeDescendants = true) { contentDescription = a11y }
            .testTag("progress_cell_${cell.id.wire}")
            .padding(Spacing.md)
            .alpha(if (cell.unlocked) 1f else LOCKED_ALPHA),
        verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            if (cell.unlocked) {
                GtCheckMark(tint = scheme.secondary, modifier = Modifier.size(Spacing.lg))
            } else {
                GtLockMark(tint = scheme.onSurfaceVariant, modifier = Modifier.size(Spacing.lg))
            }
            Text(
                text = stateLabel,
                style = MaterialTheme.typography.labelSmall,
                color = if (cell.unlocked) scheme.tertiary else scheme.onSurfaceVariant,
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = if (cell.unlocked) scheme.onPrimaryContainer else scheme.onSurface,
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = if (cell.unlocked) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
        )
    }
}

private const val LOCKED_ALPHA = 0.6f

private fun AchievementId.titleRes(): StringResource = when (this) {
    AchievementId.FirstPlant -> Res.string.achievement_first_plant_title
    AchievementId.Plants5 -> Res.string.achievement_plants_5_title
    AchievementId.FirstWater -> Res.string.achievement_first_water_title
    AchievementId.Waterings25 -> Res.string.achievement_waterings_25_title
    AchievementId.Waterings100 -> Res.string.achievement_waterings_100_title
    AchievementId.Streak7 -> Res.string.achievement_streak_7_title
    AchievementId.Streak30 -> Res.string.achievement_streak_30_title
    AchievementId.FirstNote -> Res.string.achievement_first_note_title
    AchievementId.FirstPhoto -> Res.string.achievement_first_photo_title
}

private fun AchievementId.descriptionRes(): StringResource = when (this) {
    AchievementId.FirstPlant -> Res.string.achievement_first_plant_description
    AchievementId.Plants5 -> Res.string.achievement_plants_5_description
    AchievementId.FirstWater -> Res.string.achievement_first_water_description
    AchievementId.Waterings25 -> Res.string.achievement_waterings_25_description
    AchievementId.Waterings100 -> Res.string.achievement_waterings_100_description
    AchievementId.Streak7 -> Res.string.achievement_streak_7_description
    AchievementId.Streak30 -> Res.string.achievement_streak_30_description
    AchievementId.FirstNote -> Res.string.achievement_first_note_description
    AchievementId.FirstPhoto -> Res.string.achievement_first_photo_description
}
