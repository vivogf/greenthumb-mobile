package site.xmpp.greenthumb.ui.screens.plantdetail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import site.xmpp.greenthumb.core.network.PHOTO_CAPTION_MAX_LENGTH
import site.xmpp.greenthumb.core.network.TimelinePhotoDto
import site.xmpp.greenthumb.core.platform.PickSource
import site.xmpp.greenthumb.core.platform.photoDataUri
import site.xmpp.greenthumb.data.PlantTimeline
import site.xmpp.greenthumb.data.isHeldForReplay
import site.xmpp.greenthumb.ui.components.GtAlertButton
import site.xmpp.greenthumb.ui.components.GtAlertButtonStyle
import site.xmpp.greenthumb.ui.components.GtAlertDialog
import site.xmpp.greenthumb.ui.components.GtCameraMark
import site.xmpp.greenthumb.ui.components.GtCard
import site.xmpp.greenthumb.ui.components.GtGalleryMark
import site.xmpp.greenthumb.ui.components.GtInputField
import site.xmpp.greenthumb.ui.components.GtModal
import site.xmpp.greenthumb.ui.components.GtPlantPhoto
import site.xmpp.greenthumb.ui.components.PhotoPickerController
import site.xmpp.greenthumb.ui.components.PrimaryButton
import site.xmpp.greenthumb.ui.components.formatPickerDate
import site.xmpp.greenthumb.ui.components.gtButtonWidth
import site.xmpp.greenthumb.ui.res.Res
import site.xmpp.greenthumb.ui.res.common_camera
import site.xmpp.greenthumb.ui.res.common_gallery
import site.xmpp.greenthumb.ui.res.dashboard_refreshFailed
import site.xmpp.greenthumb.ui.res.plantDetails_cancel
import site.xmpp.greenthumb.ui.res.plantDetails_delete
import site.xmpp.greenthumb.ui.res.timeline_addPhoto
import site.xmpp.greenthumb.ui.res.timeline_captionPlaceholder
import site.xmpp.greenthumb.ui.res.timeline_captionTitle
import site.xmpp.greenthumb.ui.res.timeline_close
import site.xmpp.greenthumb.ui.res.timeline_deleteFailed
import site.xmpp.greenthumb.ui.res.timeline_deleteMessage
import site.xmpp.greenthumb.ui.res.timeline_deletePhoto
import site.xmpp.greenthumb.ui.res.timeline_deleteTitle
import site.xmpp.greenthumb.ui.res.timeline_empty
import site.xmpp.greenthumb.ui.res.timeline_networkRequired
import site.xmpp.greenthumb.ui.res.timeline_openPhoto
import site.xmpp.greenthumb.ui.res.timeline_save
import site.xmpp.greenthumb.ui.res.timeline_title
import site.xmpp.greenthumb.ui.res.timeline_uploadFailed
import site.xmpp.greenthumb.ui.res.timeline_uploading
import site.xmpp.greenthumb.ui.theme.Radii
import site.xmpp.greenthumb.ui.theme.Spacing

/**
 * Bytes picked and cropped for the timeline, waiting for a caption.
 * The picker and its crop screen live at the screen root (the crop is a
 * full-screen overlay), so the hand-over goes through this holder.
 */
internal class TimelinePick {
    var bytes: ByteArray? by mutableStateOf(null)
}

private enum class UploadMessage { NetworkRequired, UploadFailed }

private const val TIMELINE_COLUMNS = 3

/**
 * "Фото" card of plant detail (architecture §5.5): the plant's photos newest
 * first from the Room cache (readable offline), add from camera or gallery
 * with an optional caption, full-screen view with delete after confirmation.
 *
 * Uploads need a network: offline the add button says so next to itself and
 * opens nothing; a failed upload keeps the caption dialog and its text.
 * Nothing is queued, so reconnecting alone uploads nothing (VAL-TIMELINE-014).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PlantTimelineSection(
    timeline: PlantTimeline,
    plantId: String,
    online: Boolean,
    refreshKey: Int,
    locale: String,
    picker: PhotoPickerController,
    pick: TimelinePick,
    modifier: Modifier = Modifier,
) {
    val photos by remember(timeline, plantId) {
        timeline.observe(plantId).catch { emit(emptyList()) }
    }.collectAsState(initial = null)
    var refreshFailed by remember(plantId) { mutableStateOf(false) }
    var offlineMessage by remember(plantId) { mutableStateOf(false) }
    var sourceSheet by remember(plantId) { mutableStateOf(false) }
    var viewing by remember(plantId) { mutableStateOf<TimelinePhotoDto?>(null) }
    val scope = rememberCoroutineScope()
    val messageRequester = remember { BringIntoViewRequester() }
    // Tapping a button does not take focus from the journal note field. If it stays focused behind
    // a dialog or the picker, Android restarts its input when the window regains focus: the field
    // ends up focused with the keyboard state out of sync.
    val focusManager = LocalFocusManager.current

    LaunchedEffect(timeline, plantId, refreshKey, online) {
        if (!online) {
            refreshFailed = true
            return@LaunchedEffect
        }
        offlineMessage = false
        refreshFailed = try {
            timeline.refresh(plantId)
            false
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            true
        }
    }
    LaunchedEffect(offlineMessage) {
        if (offlineMessage) messageRequester.bringIntoView()
    }

    fun requestAdd() {
        focusManager.clearFocus()
        if (!online) {
            offlineMessage = true
            return
        }
        offlineMessage = false
        sourceSheet = true
    }

    val scheme = MaterialTheme.colorScheme
    GtCard(modifier = modifier.fillMaxWidth().testTag("timeline_section")) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(
                text = stringResource(Res.string.timeline_title),
                style = MaterialTheme.typography.titleSmall,
                color = scheme.onSurface,
            )
            if (refreshFailed) {
                Text(
                    text = stringResource(Res.string.dashboard_refreshFailed),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.testTag("timeline_offline"),
                )
            }
            PrimaryButton(
                text = stringResource(Res.string.timeline_addPhoto),
                onClick = ::requestAdd,
                modifier = Modifier.gtButtonWidth().testTag("timeline_addPhoto"),
            )
            if (offlineMessage) {
                Text(
                    text = stringResource(Res.string.timeline_networkRequired),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.error,
                    modifier = Modifier.bringIntoViewRequester(messageRequester).testTag("timeline_message"),
                )
            }
            val rows = photos.orEmpty()
            if (photos != null && rows.isEmpty()) {
                Text(
                    text = stringResource(Res.string.timeline_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
            rows.chunked(TIMELINE_COLUMNS).forEach { chunk ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    chunk.forEach { photo ->
                        TimelineTile(
                            photo = photo,
                            locale = locale,
                            onOpen = {
                                focusManager.clearFocus()
                                viewing = photo
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(TIMELINE_COLUMNS - chunk.size) { Spacer(modifier = Modifier.weight(1f)) }
                }
            }
        }
    }

    if (sourceSheet) {
        GtModal(onDismissRequest = { sourceSheet = false }) {
            Text(
                text = stringResource(Res.string.timeline_addPhoto),
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.lg),
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            SourceRow(stringResource(Res.string.common_camera), { GtCameraMark(it) }) {
                sourceSheet = false
                picker.launch(scope, PickSource.Camera)
            }
            SourceRow(stringResource(Res.string.common_gallery), { GtGalleryMark(it) }) {
                sourceSheet = false
                picker.launch(scope, PickSource.Gallery)
            }
            SourceRow(stringResource(Res.string.plantDetails_cancel), null, destructive = true) {
                sourceSheet = false
            }
        }
    }

    pick.bytes?.let { bytes ->
        CaptionDialog(
            bytes = bytes,
            online = online,
            upload = { caption -> timeline.addPhoto(plantId, photoDataUri(bytes), caption) },
            onClose = {
                pick.bytes = null
                focusManager.clearFocus()
            },
        )
    }

    viewing?.let { photo ->
        TimelineViewer(
            photo = photo,
            locale = locale,
            delete = { timeline.deletePhoto(plantId, photo.id) },
            onClose = { viewing = null },
        )
    }
}

@Composable
private fun TimelineTile(
    photo: TimelinePhotoDto,
    locale: String,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val date = formatPickerDate(photo.takenOn, locale)
    val openLabel = stringResource(Res.string.timeline_openPhoto, date)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
        GtPlantPhoto(
            photoUrl = photo.url,
            leafSize = Spacing.xl,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(Radii.md))
                .background(scheme.surfaceVariant)
                .testTag("timeline_photo_${photo.id}")
                .clickable(role = Role.Image, onClick = onOpen)
                .semantics { contentDescription = openLabel },
        )
        Text(
            text = date,
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
        )
        val caption = photo.caption
        if (!caption.isNullOrEmpty()) {
            Text(
                text = caption,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SourceRow(
    label: String,
    icon: (@Composable (Color) -> Unit)?,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Spacing.xl, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        icon?.invoke(scheme.secondary)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (destructive) scheme.error else scheme.onSurface,
        )
    }
}

/** Preview + optional caption; an upload error stays inside the dialog with the typed caption. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CaptionDialog(
    bytes: ByteArray,
    online: Boolean,
    upload: suspend (String) -> Unit,
    onClose: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val preview = remember(bytes) { photoDataUri(bytes) }
    var caption by remember(bytes) { mutableStateOf("") }
    var uploading by remember(bytes) { mutableStateOf(false) }
    var message by remember(bytes) { mutableStateOf<UploadMessage?>(null) }
    val actionsRequester = remember { BringIntoViewRequester() }

    LaunchedEffect(message) {
        if (message != null) actionsRequester.bringIntoView()
    }

    fun save() {
        if (uploading) return
        if (!online) {
            message = UploadMessage.NetworkRequired
            return
        }
        uploading = true
        message = null
        scope.launch {
            try {
                upload(caption)
                onClose()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                message = if (isHeldForReplay(error)) UploadMessage.NetworkRequired else UploadMessage.UploadFailed
            } finally {
                uploading = false
            }
        }
    }

    GtModal(onDismissRequest = { if (!uploading) onClose() }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(Spacing.xl)
                .testTag("timeline_captionDialog"),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(
                text = stringResource(Res.string.timeline_captionTitle),
                style = MaterialTheme.typography.titleMedium,
                color = scheme.onSurface,
            )
            GtPlantPhoto(
                photoUrl = preview,
                leafSize = Spacing.xxl,
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(Radii.md))
                    .align(Alignment.CenterHorizontally),
            )
            GtInputField(
                value = caption,
                onValueChange = { caption = it.take(PHOTO_CAPTION_MAX_LENGTH) },
                placeholder = stringResource(Res.string.timeline_captionPlaceholder),
                singleLine = false,
                modifier = Modifier.testTag("timeline_captionInput"),
            )
            message?.let { current ->
                Text(
                    text = stringResource(
                        when (current) {
                            UploadMessage.NetworkRequired -> Res.string.timeline_networkRequired
                            UploadMessage.UploadFailed -> Res.string.timeline_uploadFailed
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.error,
                    modifier = Modifier.testTag("timeline_uploadMessage"),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().bringIntoViewRequester(actionsRequester),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(Res.string.plantDetails_cancel),
                    style = MaterialTheme.typography.bodyLarge,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier
                        .clickable(enabled = !uploading, role = Role.Button, onClick = onClose)
                        .padding(Spacing.sm),
                )
                PrimaryButton(
                    text = stringResource(if (uploading) Res.string.timeline_uploading else Res.string.timeline_save),
                    onClick = ::save,
                    enabled = !uploading,
                    modifier = Modifier.testTag("timeline_save"),
                )
            }
        }
    }
}

/** Full-screen photo with date and caption; Back or Close returns to the timeline. */
@Composable
private fun TimelineViewer(
    photo: TimelinePhotoDto,
    locale: String,
    delete: suspend () -> Unit,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var confirm by remember(photo.id) { mutableStateOf(false) }
    var deleting by remember(photo.id) { mutableStateOf(false) }
    var failed by remember(photo.id) { mutableStateOf(false) }
    val date = formatPickerDate(photo.takenOn, locale)

    fun performDelete() {
        if (deleting) return
        deleting = true
        failed = false
        scope.launch {
            try {
                delete()
                onClose()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                failed = true
            } finally {
                deleting = false
            }
        }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .statusBarsPadding()
                .navigationBarsPadding()
                .testTag("timeline_viewer"),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.md),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ViewerAction(stringResource(Res.string.timeline_close), Color.White, enabled = true, onClick = onClose)
                ViewerAction(
                    stringResource(Res.string.timeline_deletePhoto),
                    MaterialTheme.colorScheme.error,
                    enabled = !deleting,
                    onClick = { confirm = true },
                )
            }
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                if (photo.url.isNotBlank()) {
                    AsyncImage(
                        model = photo.url,
                        contentDescription = photo.caption ?: date,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                }
            }
            Column(
                modifier = Modifier.fillMaxWidth().padding(Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
            ) {
                Text(text = date, style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.75f))
                val caption = photo.caption
                if (!caption.isNullOrEmpty()) {
                    Text(
                        text = caption,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White,
                        modifier = Modifier.testTag("timeline_viewer_caption"),
                    )
                }
                if (failed) {
                    Text(
                        text = stringResource(Res.string.timeline_deleteFailed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("timeline_deleteMessage"),
                    )
                }
            }
        }

        if (confirm) {
            GtAlertDialog(
                title = stringResource(Res.string.timeline_deleteTitle),
                message = stringResource(Res.string.timeline_deleteMessage),
                buttons = listOf(
                    GtAlertButton(
                        text = stringResource(Res.string.plantDetails_cancel),
                        style = GtAlertButtonStyle.Cancel,
                    ),
                    GtAlertButton(
                        text = stringResource(Res.string.plantDetails_delete),
                        style = GtAlertButtonStyle.Destructive,
                        onClick = ::performDelete,
                    ),
                ),
                onDismissRequest = { confirm = false },
            )
        }
    }
}

@Composable
private fun ViewerAction(label: String, color: Color, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = color,
        modifier = Modifier
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(Spacing.sm),
    )
}
