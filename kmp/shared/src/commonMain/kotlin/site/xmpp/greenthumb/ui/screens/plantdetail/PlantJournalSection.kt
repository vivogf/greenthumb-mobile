package site.xmpp.greenthumb.ui.screens.plantdetail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import site.xmpp.greenthumb.core.network.CARE_NOTE_MAX_LENGTH
import site.xmpp.greenthumb.core.network.CareEventDto
import site.xmpp.greenthumb.core.network.CareEventType
import site.xmpp.greenthumb.data.PlantJournal
import site.xmpp.greenthumb.data.isHeldForReplay
import site.xmpp.greenthumb.ui.components.GtAlertButton
import site.xmpp.greenthumb.ui.components.GtAlertButtonStyle
import site.xmpp.greenthumb.ui.components.GtAlertDialog
import site.xmpp.greenthumb.ui.components.GtCard
import site.xmpp.greenthumb.ui.components.GtIconButton
import site.xmpp.greenthumb.ui.components.GtInputField
import site.xmpp.greenthumb.ui.components.GtJournalMark
import site.xmpp.greenthumb.ui.components.GtTrashMark
import site.xmpp.greenthumb.ui.components.PrimaryButton
import site.xmpp.greenthumb.ui.components.formatPickerDate
import site.xmpp.greenthumb.ui.components.gtButtonWidth
import site.xmpp.greenthumb.ui.res.Res
import site.xmpp.greenthumb.ui.res.dashboard_refreshFailed
import site.xmpp.greenthumb.ui.res.journal_addNote
import site.xmpp.greenthumb.ui.res.journal_deleteFailed
import site.xmpp.greenthumb.ui.res.journal_deleteMessage
import site.xmpp.greenthumb.ui.res.journal_deleteNote
import site.xmpp.greenthumb.ui.res.journal_deleteTitle
import site.xmpp.greenthumb.ui.res.journal_empty
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
private enum class NoteMessage { NetworkRequired, SaveFailed, DeleteFailed }

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
) {
    val entries by remember(journal, plantId) {
        journal.observe(plantId).catch { emit(emptyList()) }
    }.collectAsState(initial = null)
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
            val rows = entries.orEmpty()
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
                    locale = locale,
                    onDelete = {
                        focusManager.clearFocus()
                        pendingDelete = entry
                    },
                )
            }
        }
    }

    pendingDelete?.let { entry ->
        GtAlertDialog(
            title = stringResource(Res.string.journal_deleteTitle),
            message = stringResource(Res.string.journal_deleteMessage),
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

@Composable
private fun JournalRow(entry: CareEventDto, locale: String, onDelete: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().testTag("journal_entry_${entry.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(
                    text = stringResource(journalTypeLabel(entry.type)),
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.onSurface,
                )
                Text(
                    text = formatPickerDate(entry.occurredOn, locale),
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.onSurfaceVariant,
                )
            }
            val note = entry.note
            if (!note.isNullOrEmpty()) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurface,
                )
            }
        }
        if (entry.type == CareEventType.NOTE) {
            GtIconButton(
                onClick = onDelete,
                contentDescription = stringResource(Res.string.journal_deleteNote),
            ) {
                GtTrashMark(tint = scheme.error, modifier = Modifier.size(Spacing.lg))
            }
        }
    }
}
