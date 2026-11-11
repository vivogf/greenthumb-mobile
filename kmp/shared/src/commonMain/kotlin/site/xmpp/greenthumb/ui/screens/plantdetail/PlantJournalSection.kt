package site.xmpp.greenthumb.ui.screens.plantdetail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import site.xmpp.greenthumb.core.network.CARE_NOTE_MAX_LENGTH
import site.xmpp.greenthumb.core.network.CareEventDto
import site.xmpp.greenthumb.core.network.CareEventType
import site.xmpp.greenthumb.core.network.TimelinePhotoDto
import site.xmpp.greenthumb.data.DELETABLE_CARE_ENTRY_TYPES
import site.xmpp.greenthumb.data.PlantJournal
import site.xmpp.greenthumb.data.PlantTimeline
import site.xmpp.greenthumb.data.SaveOutcome
import site.xmpp.greenthumb.data.isHeldForReplay
import site.xmpp.greenthumb.data.isOldServerRefusal
import site.xmpp.greenthumb.ui.components.GtAlertButton
import site.xmpp.greenthumb.ui.components.GtAlertButtonStyle
import site.xmpp.greenthumb.ui.components.GtAlertDialog
import site.xmpp.greenthumb.ui.components.GtCard
import site.xmpp.greenthumb.ui.components.GtFertilizeMark
import site.xmpp.greenthumb.ui.components.GtGalleryMark
import site.xmpp.greenthumb.ui.components.GtIconButton
import site.xmpp.greenthumb.ui.components.GtInputField
import site.xmpp.greenthumb.ui.components.GtJournalMark
import site.xmpp.greenthumb.ui.components.GtLeafMark
import site.xmpp.greenthumb.ui.components.GtPencilMark
import site.xmpp.greenthumb.ui.components.GtPlantPhoto
import site.xmpp.greenthumb.ui.components.GtPotMark
import site.xmpp.greenthumb.ui.components.GtScissorsMark
import site.xmpp.greenthumb.ui.components.GtTrashMark
import site.xmpp.greenthumb.ui.components.GtWaterDropMark
import site.xmpp.greenthumb.ui.components.PrimaryButton
import site.xmpp.greenthumb.ui.components.gtButtonWidth
import site.xmpp.greenthumb.ui.components.pickerToday
import site.xmpp.greenthumb.ui.res.Res
import site.xmpp.greenthumb.ui.res.dashboard_refreshFailed
import site.xmpp.greenthumb.ui.res.journal_addNote
import site.xmpp.greenthumb.ui.res.journal_deleteEntry
import site.xmpp.greenthumb.ui.res.journal_deleteEntryFailed
import site.xmpp.greenthumb.ui.res.journal_deleteEntryMessage
import site.xmpp.greenthumb.ui.res.journal_deleteEntryTitle
import site.xmpp.greenthumb.ui.res.journal_deleteFailed
import site.xmpp.greenthumb.ui.res.journal_deleteNeedsServer
import site.xmpp.greenthumb.ui.res.journal_deleteWaterMessage
import site.xmpp.greenthumb.ui.res.journal_deleteMessage
import site.xmpp.greenthumb.ui.res.journal_deleteNote
import site.xmpp.greenthumb.ui.res.journal_deleteTitle
import site.xmpp.greenthumb.ui.res.journal_empty
import site.xmpp.greenthumb.ui.res.journal_entryLabel
import site.xmpp.greenthumb.ui.res.journal_networkRequired
import site.xmpp.greenthumb.ui.res.journal_notePlaceholder
import site.xmpp.greenthumb.ui.res.journal_saveFailed
import site.xmpp.greenthumb.ui.res.journal_title
import site.xmpp.greenthumb.ui.res.journal_typeFertilize
import site.xmpp.greenthumb.ui.res.journal_typeNote
import site.xmpp.greenthumb.ui.res.journal_typeOther
import site.xmpp.greenthumb.ui.res.journal_typePhoto
import site.xmpp.greenthumb.ui.res.journal_typePrune
import site.xmpp.greenthumb.ui.res.journal_typeRepot
import site.xmpp.greenthumb.ui.res.journal_typeWater
import site.xmpp.greenthumb.ui.res.plantDetails_cancel
import site.xmpp.greenthumb.ui.res.plantDetails_delete
import site.xmpp.greenthumb.ui.theme.Radii
import site.xmpp.greenthumb.ui.theme.Sizes
import site.xmpp.greenthumb.ui.theme.Spacing

/** Label of a wire event type; unknown future types get a generic label. */
internal fun journalTypeLabel(type: String): StringResource = when (type) {
    CareEventType.WATER -> Res.string.journal_typeWater
    CareEventType.FERTILIZE -> Res.string.journal_typeFertilize
    CareEventType.REPOT -> Res.string.journal_typeRepot
    CareEventType.PRUNE -> Res.string.journal_typePrune
    CareEventType.NOTE -> Res.string.journal_typeNote
    CareEventType.PHOTO -> Res.string.journal_typePhoto
    else -> Res.string.journal_typeOther
}

/** Inline status under the note field. */
private enum class NoteMessage { NetworkRequired, SaveFailed, DeleteFailed, DeleteEntryFailed, DeleteNeedsServer }

/**
 * "Журнал" card of plant detail (architecture §5.5, §8.5).
 *
 * Entries come from the Room cache, so they stay readable offline; a failed
 * or impossible refresh shows the same saved-data strip as the dashboard.
 * A note is sent only by a tap on the button: an offline attempt keeps the
 * typed text and says that a network is required, and nothing is retried
 * on reconnect (VAL-JOURNAL-018).
 *
 * [refreshKey] changes when the screen knows the server journal moved
 * (a confirmed watering or care update, a replay after reconnect).
 *
 * A `photo` entry shows its timeline photo from [timeline] as a thumbnail with the caption
 * (a saved «Что с растением?» result, for example).
 *
 * With [deleteCareEntry], water, fertilize, repot and prune entries get the same delete button as notes.
 * The repository removes the row and moves the card date at once and queues the request offline,
 * so only a refusal shows a message here.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PlantJournalSection(
    journal: PlantJournal,
    plantId: String,
    online: Boolean,
    refreshKey: Int,
    locale: String,
    modifier: Modifier = Modifier,
    timeline: PlantTimeline? = null,
    deleteCareEntry: (suspend (eventId: String) -> SaveOutcome)? = null,
) {
    val entries by remember(journal, plantId) {
        journal.observe(plantId).catch { emit(emptyList()) }
    }.collectAsState(initial = null)
    val photos by remember(timeline, plantId) {
        timeline?.observe(plantId)?.catch { emit(emptyList()) } ?: flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    var refreshFailed by remember(plantId) { mutableStateOf(false) }
    var draft by remember(plantId) { mutableStateOf("") }
    var saving by remember(plantId) { mutableStateOf(false) }
    var message by remember(plantId) { mutableStateOf<NoteMessage?>(null) }
    var pendingDelete by remember(plantId) { mutableStateOf<CareEventDto?>(null) }
    val scope = rememberCoroutineScope()
    val composerRequester = remember { BringIntoViewRequester() }
    var inputFocused by remember { mutableStateOf(false) }
    var composerClipped by remember { mutableStateOf(false) }
    // The note field would keep focus behind the delete confirmation and get it back when it closes.
    val focusManager = LocalFocusManager.current

    // Focus alone scrolls only the field above the keyboard; keep the whole composer (field, message,
    // "Add note") on screen so the user sees the button and why a note was not sent. Clipping is the
    // signal because the keyboard shrinks the scroll viewport after focus, not at the same time.
    LaunchedEffect(message, inputFocused, composerClipped) {
        if (message != null || (inputFocused && composerClipped)) composerRequester.bringIntoView()
    }

    LaunchedEffect(journal, plantId, refreshKey, online) {
        if (!online) {
            refreshFailed = true
            return@LaunchedEffect
        }
        refreshFailed = try {
            journal.refresh(plantId)
            false
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            true
        }
    }

    fun submit() {
        if (saving || draft.isBlank()) return
        if (!online) {
            message = NoteMessage.NetworkRequired
            return
        }
        saving = true
        val text = draft
        scope.launch {
            try {
                journal.addNote(plantId, text)
                if (draft == text) draft = ""
                message = null
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                message = if (isHeldForReplay(error)) NoteMessage.NetworkRequired else NoteMessage.SaveFailed
            } finally {
                saving = false
            }
        }
    }

    fun delete(entry: CareEventDto) {
        val deleteCare = deleteCareEntry
        if (entry.type != CareEventType.NOTE && deleteCare != null) {
            scope.launch {
                try {
                    deleteCare(entry.id)
                    if (message == NoteMessage.DeleteEntryFailed || message == NoteMessage.DeleteNeedsServer) message = null
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    message = if (isOldServerRefusal(error)) NoteMessage.DeleteNeedsServer else NoteMessage.DeleteEntryFailed
                }
            }
            return
        }
        scope.launch {
            try {
                journal.deleteNote(plantId, entry.id)
                if (message == NoteMessage.DeleteFailed) message = null
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                message = NoteMessage.DeleteFailed
            }
        }
    }

    val scheme = MaterialTheme.colorScheme
    val rows = entries.orEmpty()
    val today = remember { pickerToday() }
    val dateStyle = MaterialTheme.typography.labelMedium
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val years = rows.mapNotNull { journalDate(it.occurredOn, locale, today).year }.distinct()
    // One width for every row's date column: the widest possible short date (and any year shown) at this font scale.
    val dateWidth = remember(locale, density, dateStyle, years, measurer) {
        val widest = (journalDateWidthSamples(locale) + years).maxOf { sample ->
            measurer.measure(sample, dateStyle, softWrap = false, maxLines = 1).size.width
        }
        with(density) { widest.toDp() }
    }
    GtCard(modifier = modifier.fillMaxWidth().testTag("journal_section")) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                GtJournalMark(tint = scheme.secondary, modifier = Modifier.size(Sizes.iconSlot))
                Text(
                    text = stringResource(Res.string.journal_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = scheme.onSurface,
                )
            }
            if (refreshFailed) {
                Text(
                    text = stringResource(Res.string.dashboard_refreshFailed),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.testTag("journal_offline"),
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .bringIntoViewRequester(composerRequester)
                    .onGloballyPositioned { coordinates ->
                        composerClipped = coordinates.boundsInWindow().height < coordinates.size.height - 1
                    },
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Text(
                    text = stringResource(Res.string.journal_entryLabel),
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.testTag("journal_entryLabel"),
                )
                GtInputField(
                    value = draft,
                    onValueChange = { draft = it.take(CARE_NOTE_MAX_LENGTH) },
                    placeholder = stringResource(Res.string.journal_notePlaceholder),
                    singleLine = false,
                    modifier = Modifier
                        .onFocusChanged { inputFocused = it.isFocused }
                        .testTag("journal_noteInput"),
                )
                message?.let { current ->
                    Text(
                        text = stringResource(
                            when (current) {
                                NoteMessage.NetworkRequired -> Res.string.journal_networkRequired
                                NoteMessage.SaveFailed -> Res.string.journal_saveFailed
                                NoteMessage.DeleteFailed -> Res.string.journal_deleteFailed
                                NoteMessage.DeleteEntryFailed -> Res.string.journal_deleteEntryFailed
                                NoteMessage.DeleteNeedsServer -> Res.string.journal_deleteNeedsServer
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.error,
                        modifier = Modifier.testTag("journal_message"),
                    )
                }
                PrimaryButton(
                    text = stringResource(Res.string.journal_addNote),
                    onClick = ::submit,
                    enabled = draft.isNotBlank() && !saving,
                    modifier = Modifier.gtButtonWidth().testTag("journal_addNote"),
                )
            }
            if (entries != null && rows.isEmpty()) {
                Text(
                    text = stringResource(Res.string.journal_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
            rows.forEachIndexed { index, entry ->
                if (index > 0) HorizontalDivider(color = scheme.outlineVariant)
                JournalRow(
                    entry = entry,
                    photo = JournalPhotoJoin.photoFor(entry, photos),
                    locale = locale,
                    today = today,
                    dateWidth = dateWidth,
                    deletable = entry.type == CareEventType.NOTE ||
                        (deleteCareEntry != null && entry.type in DELETABLE_CARE_ENTRY_TYPES),
                    onDelete = {
                        focusManager.clearFocus()
                        pendingDelete = entry
                    },
                )
            }
        }
    }

    pendingDelete?.let { entry ->
        val isNote = entry.type == CareEventType.NOTE
        GtAlertDialog(
            title = stringResource(if (isNote) Res.string.journal_deleteTitle else Res.string.journal_deleteEntryTitle),
            message = stringResource(
                when (entry.type) {
                    CareEventType.NOTE -> Res.string.journal_deleteMessage
                    CareEventType.WATER -> Res.string.journal_deleteWaterMessage
                    else -> Res.string.journal_deleteEntryMessage
                },
            ),
            buttons = listOf(
                GtAlertButton(
                    text = stringResource(Res.string.plantDetails_cancel),
                    style = GtAlertButtonStyle.Cancel,
                ),
                GtAlertButton(
                    text = stringResource(Res.string.plantDetails_delete),
                    style = GtAlertButtonStyle.Destructive,
                    onClick = { delete(entry) },
                ),
            ),
            onDismissRequest = { pendingDelete = null },
        )
    }
}

/** Icon of a non-photo entry type in the journal's leading slot. */
@Composable
private fun JournalTypeIcon(type: String, tint: Color, modifier: Modifier) = when (type) {
    CareEventType.WATER -> GtWaterDropMark(tint = tint, modifier = modifier)
    CareEventType.FERTILIZE -> GtFertilizeMark(tint = tint, modifier = modifier)
    CareEventType.REPOT -> GtPotMark(tint = tint, modifier = modifier)
    CareEventType.PRUNE -> GtScissorsMark(tint = tint, modifier = modifier)
    CareEventType.NOTE -> GtPencilMark(tint = tint, modifier = modifier)
    // A photo entry whose timeline photo is not cached (yet).
    CareEventType.PHOTO -> GtGalleryMark(tint = tint, modifier = modifier)
    else -> GtLeafMark(tint = tint, modifier = modifier)
}

/**
 * One journal row as a table row: date column of [dateWidth] (shared by all rows) · 48 dp leading slot
 * (photo thumbnail or type icon) · type label and text · 48 dp trailing slot (delete when [deletable], empty otherwise),
 * so every column starts at the same x in every row.
 */
@Composable
private fun JournalRow(
    entry: CareEventDto,
    photo: TimelinePhotoDto?,
    locale: String,
    today: LocalDate,
    dateWidth: Dp,
    deletable: Boolean,
    onDelete: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val date = journalDate(entry.occurredOn, locale, today)
    Row(
        modifier = Modifier.fillMaxWidth().testTag("journal_entry_${entry.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Column(modifier = Modifier.width(dateWidth).testTag("journal_date_${entry.id}")) {
            Text(
                text = date.dayMonth,
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
            )
            date.year?.let { year ->
                Text(
                    text = year,
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
        Box(
            modifier = Modifier.size(Sizes.journalThumb).testTag("journal_lead_${entry.id}"),
            contentAlignment = Alignment.Center,
        ) {
            if (photo != null) {
                GtPlantPhoto(
                    photoUrl = photo.url,
                    leafSize = Spacing.lg,
                    modifier = Modifier
                        .size(Sizes.journalThumb)
                        .clip(RoundedCornerShape(Radii.md))
                        .testTag("journal_thumb_${entry.id}"),
                )
            } else {
                // Decorative: the type label next to it says the same.
                JournalTypeIcon(
                    type = entry.type,
                    tint = scheme.secondary,
                    modifier = Modifier.size(Sizes.iconSlot).testTag("journal_icon_${entry.id}"),
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f).testTag("journal_text_${entry.id}"),
            verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
        ) {
            Text(
                text = stringResource(journalTypeLabel(entry.type)),
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onSurface,
            )
            val note = JournalPhotoJoin.captionFor(entry, photo)
            if (note != null) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurface,
                    maxLines = if (photo != null) 2 else Int.MAX_VALUE,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("journal_caption_${entry.id}"),
                )
            }
        }
        Box(
            modifier = Modifier.width(Sizes.touch).testTag("journal_trailing_${entry.id}"),
            contentAlignment = Alignment.Center,
        ) {
            if (deletable) {
                GtIconButton(
                    onClick = onDelete,
                    contentDescription = stringResource(
                        if (entry.type == CareEventType.NOTE) Res.string.journal_deleteNote else Res.string.journal_deleteEntry,
                    ),
                    modifier = Modifier.testTag("journal_delete_${entry.id}"),
                ) {
                    GtTrashMark(tint = scheme.error, modifier = Modifier.size(Spacing.lg))
                }
            }
        }
    }
}
