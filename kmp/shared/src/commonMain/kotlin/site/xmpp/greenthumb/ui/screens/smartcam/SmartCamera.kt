package site.xmpp.greenthumb.ui.screens.smartcam

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import site.xmpp.greenthumb.catalog.CatalogRepository
import site.xmpp.greenthumb.core.network.ApiError
import site.xmpp.greenthumb.core.network.DiagnosisResponse
import site.xmpp.greenthumb.core.platform.CameraStatus
import site.xmpp.greenthumb.core.platform.Haptics
import site.xmpp.greenthumb.core.platform.PickResult
import site.xmpp.greenthumb.core.platform.PickSource
import site.xmpp.greenthumb.core.platform.photoDataUri
import site.xmpp.greenthumb.core.platform.resizeJpeg
import site.xmpp.greenthumb.ui.screens.addplant.AddPlantDraft
import site.xmpp.greenthumb.ui.screens.diagnosis.DiagnosisError
import site.xmpp.greenthumb.ui.screens.diagnosis.DiagnosisJournalSaver
import site.xmpp.greenthumb.ui.screens.diagnosis.DiagnosisLogic
import site.xmpp.greenthumb.ui.screens.diagnosis.DiagnosisResult
import site.xmpp.greenthumb.ui.screens.diagnosis.JournalSaveState
import site.xmpp.greenthumb.ui.screens.diagnosis.PlantDiagnoser
import site.xmpp.greenthumb.ui.screens.species.IdentifyCandidate
import site.xmpp.greenthumb.ui.screens.species.IdentifyLogic
import site.xmpp.greenthumb.ui.screens.species.SpeciesIdentifier

/** What the smart camera does with the photo; [wire] is the `mode` route argument. */
public enum class SmartCameraMode(public val wire: String) {
    Plant("plant"),
    Disease("disease"),
    ;

    public companion object {
        /** Unknown or missing values open the plant mode. */
        public fun fromWire(value: String?): SmartCameraMode = entries.firstOrNull { it.wire == value } ?: Plant
    }
}

/**
 * The last mode chosen on the camera (owner item 1: «последний режим запоминается»). It lives as long as the
 * app process; an explicit mode from the caller wins over it.
 */
public class SmartCameraModeMemory {
    private var last: SmartCameraMode = SmartCameraMode.Plant

    public fun resolve(requested: SmartCameraMode?): SmartCameraMode = requested ?: last

    public fun remember(mode: SmartCameraMode) {
        last = mode
    }

    public companion object {
        /** The app's memory; tests create their own instance. */
        public val App: SmartCameraModeMemory = SmartCameraModeMemory()
    }
}

/**
 * The one photo of the smart camera (M11c item 4): a shot keeps the 3:4 sensor frame and a gallery pick its own
 * aspect, the long side is [MAX_SIDE] (a portrait shot is 600×800), upright, re-encoded without EXIF. The same bytes
 * go to identify, both diagnosis routes, the journal and the Add Plant cover.
 */
public object SmartCameraPhoto {
    public const val MAX_SIDE: Int = 800
    private const val QUALITY: Double = 0.85

    public fun prepare(raw: ByteArray): ByteArray = prepare(raw) { bytes, side, quality -> resizeJpeg(bytes, side, quality) }

    /** A photo still above [DiagnosisLogic.MAX_PHOTO_BYTES] is shrunk further, so no request exceeds the server limit. */
    internal fun prepare(raw: ByteArray, resize: (ByteArray, Int, Double) -> ByteArray): ByteArray {
        var side = MAX_SIDE
        var bytes = resize(raw, side, QUALITY)
        while (bytes.size > DiagnosisLogic.MAX_PHOTO_BYTES && side > 320) {
            side = side * 3 / 4
            bytes = resize(raw, side, QUALITY)
        }
        return bytes
    }
}

/** Diagnosis without a plant (`POST /api/diagnose`, architecture §9.1). */
public fun interface StandaloneDiagnoser {
    public suspend fun diagnose(photoDataUri: String, note: String?, language: String): DiagnosisResponse
}

/** One of the user's plants in the «Какое из ваших растений?» picker. */
public data class SmartCameraPlant(
    public val id: String,
    public val name: String,
    public val photoUrl: String,
)

public sealed interface SmartCameraFailure {
    /** Pl@ntNet found nothing. */
    public data object NoMatch : SmartCameraFailure

    /** 429 from identify: today's identifications are used up. */
    public data object IdentifyQuota : SmartCameraFailure

    /** Identify failed for any other reason. */
    public data object IdentifyUnavailable : SmartCameraFailure

    public data class Diagnosis(public val error: DiagnosisError) : SmartCameraFailure

    /**
     * The request did not get through (no connection, timeout, service down), so the same photo is worth sending
     * again. A used-up limit, no match or a photo the service rejected needs another day or another photo instead.
     */
    public val transient: Boolean
        get() = when (this) {
            IdentifyUnavailable -> true
            is Diagnosis -> error == DiagnosisError.Network || error == DiagnosisError.Unavailable
            NoMatch, IdentifyQuota -> false
        }
}

public sealed interface SmartCameraPhase {
    /** The live camera with the mode switch, the shutter and the gallery button (or the camera fallback). */
    public data object Viewfinder : SmartCameraPhase

    /** The plant cover of «Проверить на болезни» is being loaded; no camera is open. */
    public data object PhotoLoading : SmartCameraPhase

    /** The photo is shown and stays on the phone until «Узнать вид» / «Проверить растение». */
    public data object Captured : SmartCameraPhase
    public data object Loading : SmartCameraPhase
    public data class PlantResult(public val candidates: List<IdentifyCandidate>) : SmartCameraPhase
    public data class DiseaseResult(public val result: DiagnosisResult) : SmartCameraPhase
    public data class Failed(public val failure: SmartCameraFailure) : SmartCameraPhase
}

/**
 * State of the in-app smart camera (M11 owner item 1, architecture §10.1). It opens on the viewfinder;
 * a shot ([shutter]), a gallery pick or the system-camera fallback only shows the photo ([SmartCameraPhase.Captured]).
 * Nothing leaves the phone until [send], and one [send] makes exactly one request in the current mode.
 * The photo lives in memory; a kept capture file is deleted on [retake] and [close], and a shot that
 * arrives after the attempt ended is deleted at once. A cancelled request never publishes its answer.
 * A disease result can be saved to one of the user's plants through [journal] («Это моё растение?»)
 * without another diagnosis request.
 *
 * Opened from a plant (owner item 6, variant B1), [plant] is the chip of the «Болезнь» mode: the check goes
 * through [plantDiagnoser] with that plant's care history, and its result is saved to that plant's journal
 * directly. ✕ on the chip ([clearPlant]) makes it a plant-less check. [initialPhoto] (the plant cover of
 * «Проверить на болезни») starts on the after-shot screen; if it cannot be loaded, the viewfinder opens instead.
 */
public class SmartCameraController(
    initialMode: SmartCameraMode,
    private val identifier: SpeciesIdentifier?,
    private val diagnoser: StandaloneDiagnoser?,
    /** Gallery and the system-camera fallback. */
    private val pick: suspend (PickSource) -> PickResult,
    /** JPEG for identify (and the add-plant cover); the app passes [SmartCameraPhoto.prepare]. */
    private val preparePlant: (ByteArray) -> ByteArray,
    /** JPEG for diagnosis; the app passes [SmartCameraPhoto.prepare] as well. */
    private val prepareDisease: (ByteArray) -> ByteArray,
    private val catalog: () -> CatalogRepository?,
    /** The in-app camera shot with the flash on or off. */
    private val capture: suspend (flash: Boolean) -> PickResult = { PickResult.Cancelled },
    private val workContext: CoroutineContext = EmptyCoroutineContext,
    private val journal: DiagnosisJournalSaver? = null,
    private val deleteCapture: (String) -> Unit = {},
    /** Called on every mode change, so the next camera opens in the same mode. */
    private val onModeChange: (SmartCameraMode) -> Unit = {},
    /** Diagnosis of [plant] with its care history (`POST /api/plants/:id/diagnose`). */
    private val plantDiagnoser: PlantDiagnoser? = null,
    plant: SmartCameraPlant? = null,
    private val initialPhoto: (suspend () -> ByteArray)? = null,
    /** Sends the zoom ratio to the camera; the app passes it to [site.xmpp.greenthumb.core.platform.CameraShutter.zoomRatio]. */
    private val applyZoom: (Float) -> Unit = {},
) {
    /** The plant of the chip; null for a plant-less check. */
    public var plant: SmartCameraPlant? by mutableStateOf(plant)
        private set

    /** The chip belongs to «Болезнь»; identification never uses it. */
    public val activePlant: SmartCameraPlant? get() = plant.takeIf { mode == SmartCameraMode.Disease }

    /** The plant the shown result was checked for; its journal save needs no «Это моё растение?». */
    public var diagnosedPlant: SmartCameraPlant? by mutableStateOf(null)
        private set

    /** The preset photo could not be loaded; the viewfinder shows a message until the next photo. */
    public var photoLoadFailed: Boolean by mutableStateOf(false)
        private set

    public var mode: SmartCameraMode by mutableStateOf(initialMode)
        private set

    public var phase: SmartCameraPhase by mutableStateOf(
        if (initialPhoto != null) SmartCameraPhase.PhotoLoading else SmartCameraPhase.Viewfinder,
    )
        private set

    public var cameraStatus: CameraStatus by mutableStateOf(CameraStatus.Starting)
        private set

    /** The flash toggle; it stays while the mode changes and goes with the next shot. */
    public var flash: Boolean by mutableStateOf(false)
        private set

    public val hasFlash: Boolean get() = (cameraStatus as? CameraStatus.Ready)?.hasFlash == true

    /** Prepared JPEG of the current attempt: upright, resized, without EXIF. */
    public var photo: ByteArray? by mutableStateOf(null)
        private set

    /**
     * «Что вас беспокоит?» of the disease mode, at most [DiagnosisLogic.MAX_NOTE_LENGTH] characters. It stays across
     * «Переснять» of one camera session and is cleared when a result is shown and when the camera closes.
     */
    public var note: String by mutableStateOf("")
        private set

    /** Index of the candidate «Добавить к моим растениям» uses. */
    public var selectedCandidate: Int by mutableStateOf(0)
        private set

    /** A shot or a pick is running. */
    public var picking: Boolean by mutableStateOf(false)
        private set

    /**
     * An accepted shutter tap waits for its photo. It turns on in the tap itself, so the screen reacts in the next
     * frame, long before the camera delivers the JPEG.
     */
    public var shotPending: Boolean by mutableStateOf(false)
        private set

    /**
     * Zoom of the viewfinder and the shot, 1× .. [zoomLimit]. It stays across mode switches and «Переснять» and
     * starts at 1× with every new camera screen.
     */
    public var zoom: Float by mutableStateOf(1f)
        private set

    /** The smaller of the camera's maximum and [MAX_ZOOM]; 1 while the camera is not ready or cannot zoom. */
    public val zoomLimit: Float
        get() = (cameraStatus as? CameraStatus.Ready)?.maxZoom?.coerceIn(1f, MAX_ZOOM) ?: 1f

    /** «2.0×» while the zoom is not 1× (same format in every language), null at 1×. */
    public val zoomLabel: String?
        get() {
            val tenths = (zoom * 10).roundToInt()
            return if (tenths == 10) null else "${tenths / 10}.${tenths % 10}×"
        }

    public var deniedSource: PickSource? by mutableStateOf(null)
        private set

    public var pickErrorMessage: String? by mutableStateOf(null)
        private set

    /** The plant chosen with «Это моё растение?»; null until the user picks one. */
    public var journalPlantId: String? by mutableStateOf(null)
        private set

    public var journalSave: JournalSaveState by mutableStateOf(JournalSaveState.Idle)
        private set

    /** The «Какое из ваших растений?» sheet is open. */
    public var plantPickerOpen: Boolean by mutableStateOf(false)
        private set

    public val canSaveToJournal: Boolean get() = journal != null

    /** «Это моё растение?» is offered on a plant-less disease result until a plant is chosen. */
    public val canChooseOwnPlant: Boolean
        get() = journal != null && diagnosedPlant == null && journalPlantId == null &&
            phase is SmartCameraPhase.DiseaseResult && !savingOrSaved

    private var job: Job? = null
    private var journalJob: Job? = null
    private var capturePath: String? = null
    private var attempt = 0
    private var closed = false

    private val onViewfinder: Boolean get() = !closed && phase == SmartCameraPhase.Viewfinder && !picking

    /** Loads [initialPhoto] once and shows it on the after-shot screen; nothing is sent. */
    public fun loadInitialPhoto(scope: CoroutineScope) {
        val load = initialPhoto ?: return
        if (closed || phase != SmartCameraPhase.PhotoLoading || job != null) return
        val id = ++attempt
        val current = mode
        job = scope.launch {
            val prepared = try {
                val raw = load()
                withContext(workContext) {
                    if (current == SmartCameraMode.Plant) preparePlant(raw) else prepareDisease(raw)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                null
            }
            if (id != attempt || closed) return@launch
            if (prepared == null) {
                photoLoadFailed = true
                phase = SmartCameraPhase.Viewfinder
            } else {
                photo = prepared
                phase = SmartCameraPhase.Captured
            }
        }
    }

    /** ✕ on the plant chip: the check becomes plant-less; only before the photo is sent. */
    public fun clearPlant() {
        if (closed || (phase != SmartCameraPhase.Viewfinder && phase != SmartCameraPhase.Captured)) return
        plant = null
    }

    public fun onCameraStatus(status: CameraStatus) {
        cameraStatus = status
        if (status is CameraStatus.Ready) {
            if (!status.hasFlash) flash = false
            changeZoom(zoom.coerceIn(1f, zoomLimit))
        }
    }

    private val canZoom: Boolean get() = onViewfinder && cameraStatus is CameraStatus.Ready

    /** One step of a two-finger pinch: [factor] > 1 zooms in. */
    public fun zoomBy(factor: Float) {
        if (!canZoom || factor <= 0f || factor.isNaN()) return
        changeZoom((zoom * factor).coerceIn(1f, zoomLimit))
    }

    /** Tap on the zoom label or a double tap on the preview: back to 1×. */
    public fun resetZoom() {
        if (canZoom) changeZoom(1f)
    }

    private fun changeZoom(value: Float) {
        zoom = value
        applyZoom(value)
    }

    public fun selectMode(value: SmartCameraMode) {
        if (!onViewfinder || value == mode) return
        mode = value
        onModeChange(value)
    }

    /** A horizontal swipe on the viewfinder: to the left goes to the next mode, to the right to the previous one. */
    public fun swipeMode(dragX: Float) {
        if (abs(dragX) < SWIPE_MIN_DRAG) return
        val step = if (dragX < 0f) 1 else -1
        val next = SmartCameraMode.entries.getOrNull(mode.ordinal + step) ?: return
        selectMode(next)
    }

    public fun toggleFlash() {
        if (onViewfinder && hasFlash) flash = !flash
    }

    /** The shutter of the in-app camera: one short haptic and the shot feedback on the tap, then the shot. */
    public fun shutter(scope: CoroutineScope) {
        if (cameraStatus !is CameraStatus.Ready || !onViewfinder) return
        val withFlash = flash && hasFlash
        shotPending = true
        Haptics.medium()
        acquire(scope) { capture(withFlash) }
    }

    public fun chooseFromGallery(scope: CoroutineScope) {
        acquire(scope) { pick(PickSource.Gallery) }
    }

    /** The system camera, only when the in-app camera cannot start. */
    public fun useSystemCamera(scope: CoroutineScope) {
        if (cameraStatus != CameraStatus.Unavailable) return
        acquire(scope) { pick(PickSource.Camera) }
    }

    /** Gets a photo and shows it; nothing is sent. A cancelled shot or pick stays on the viewfinder. */
    private fun acquire(scope: CoroutineScope, source: suspend () -> PickResult) {
        if (!onViewfinder) return
        val id = ++attempt
        val current = mode
        picking = true
        // `picking` stays set until the photo is prepared, so a second shutter, pick or mode change
        // cannot start while the first photo is still being resized.
        job = scope.launch {
            try {
                val picked = try {
                    source()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Throwable) {
                    if (id == attempt) pickErrorMessage = failure.message ?: failure.toString()
                    null
                }
                if (picked is PickResult.Picked && picked.capturePath != null) keepCapture(id, picked.capturePath)
                if (id != attempt) return@launch
                when (picked) {
                    null, PickResult.Cancelled -> Unit
                    is PickResult.PermissionDenied -> deniedSource = picked.source
                    is PickResult.Picked -> {
                        photoLoadFailed = false
                        show(id, current, picked.bytes)
                    }
                }
            } finally {
                if (id == attempt) {
                    picking = false
                    shotPending = false
                }
            }
        }
    }

    private suspend fun show(id: Int, current: SmartCameraMode, raw: ByteArray) {
        val prepared = try {
            withContext(workContext) {
                if (current == SmartCameraMode.Plant) preparePlant(raw) else prepareDisease(raw)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            if (id == attempt) {
                pickErrorMessage = failure.message ?: failure.toString()
                dropCapture()
            }
            return
        }
        if (id != attempt) return
        photo = prepared
        selectedCandidate = 0
        phase = SmartCameraPhase.Captured
    }

    /** The newest shot replaces the kept one; a shot of a stale attempt or a closed screen is deleted at once. */
    private fun keepCapture(id: Int, path: String) {
        if (closed || id != attempt) {
            deleteCapture(path)
            return
        }
        capturePath?.takeIf { it != path }?.let(deleteCapture)
        capturePath = path
    }

    private fun dropCapture() {
        capturePath?.let(deleteCapture)
        capturePath = null
    }

    public fun updateNote(value: String) {
        // The IME may commit the text again after the field has left the screen (retake, send); only the
        // after-shot screen edits the note.
        if (closed || (phase != SmartCameraPhase.Captured && phase != SmartCameraPhase.PhotoLoading)) return
        note = DiagnosisLogic.clampNote(value)
    }

    /** «Узнать вид» / «Проверить растение»: the one request of this photo. */
    public fun send(scope: CoroutineScope, language: String) {
        val prepared = photo ?: return
        if (closed || phase != SmartCameraPhase.Captured) return
        val id = ++attempt
        val current = mode
        val checked = activePlant
        val wireNote = DiagnosisLogic.wireNote(note)
        phase = SmartCameraPhase.Loading
        job = scope.launch {
            val outcome: SmartCameraPhase = try {
                val dataUri = withContext(workContext) { photoDataUri(prepared) }
                when (current) {
                    SmartCameraMode.Plant -> identify(dataUri, language)
                    SmartCameraMode.Disease -> diagnose(checked, dataUri, wireNote, language)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                SmartCameraPhase.Failed(failureFor(current, failure))
            }
            if (id != attempt) return@launch
            if (outcome is SmartCameraPhase.DiseaseResult && checked != null) {
                diagnosedPlant = checked
                journalPlantId = checked.id
            }
            if (outcome !is SmartCameraPhase.Failed) note = ""
            phase = outcome
        }
    }

    private suspend fun identify(dataUri: String, language: String): SmartCameraPhase {
        val seam = identifier ?: return SmartCameraPhase.Failed(SmartCameraFailure.IdentifyUnavailable)
        val candidates = IdentifyLogic.toCandidates(seam.identify(dataUri, language), catalog())
        return if (candidates.isEmpty()) {
            SmartCameraPhase.Failed(SmartCameraFailure.NoMatch)
        } else {
            SmartCameraPhase.PlantResult(candidates)
        }
    }

    private suspend fun diagnose(checked: SmartCameraPlant?, dataUri: String, note: String?, language: String): SmartCameraPhase {
        val unavailable = SmartCameraPhase.Failed(SmartCameraFailure.Diagnosis(DiagnosisError.Unavailable))
        val response = if (checked != null) {
            // A plant check never falls back to the plant-less route: that would drop the care history silently.
            val seam = plantDiagnoser ?: return unavailable
            seam.diagnose(checked.id, dataUri, note, language)
        } else {
            val seam = diagnoser ?: return unavailable
            seam.diagnose(dataUri, note, language)
        }
        val result = DiagnosisLogic.toResult(response)
            ?: return SmartCameraPhase.Failed(SmartCameraFailure.Diagnosis(DiagnosisError.Unavailable))
        return SmartCameraPhase.DiseaseResult(result)
    }

    private fun failureFor(current: SmartCameraMode, failure: Throwable): SmartCameraFailure = when (current) {
        SmartCameraMode.Plant ->
            if (failure is ApiError.Client && failure.status == 429) {
                SmartCameraFailure.IdentifyQuota
            } else {
                SmartCameraFailure.IdentifyUnavailable
            }
        SmartCameraMode.Disease -> SmartCameraFailure.Diagnosis(DiagnosisLogic.errorFor(failure))
    }

    public fun selectCandidate(index: Int) {
        val result = phase as? SmartCameraPhase.PlantResult ?: return
        if (index in result.candidates.indices) selectedCandidate = index
    }

    public fun chosenCandidate(): IdentifyCandidate? =
        (phase as? SmartCameraPhase.PlantResult)?.candidates?.getOrNull(selectedCandidate)

    /** «Добавить к моим растениям»: the chosen species and the identified photo, unchanged, as the cover. */
    public fun addPlantDraft(): AddPlantDraft? {
        val candidate = chosenCandidate() ?: return null
        val jpeg = photo ?: return null
        return AddPlantDraft(candidate, jpeg)
    }

    private val savingOrSaved: Boolean
        get() = journalSave == JournalSaveState.Saving || journalSave == JournalSaveState.Saved

    /** «Это моё растение?»: only on a plant-less disease result that is not saved yet. */
    public fun openPlantPicker() {
        if (journal != null && diagnosedPlant == null && phase is SmartCameraPhase.DiseaseResult && !savingOrSaved) {
            plantPickerOpen = true
        }
    }

    public fun dismissPlantPicker() {
        plantPickerOpen = false
    }

    /** Sets the plant the result is saved to; a failed save is reset, a saved result keeps its plant. */
    public fun choosePlant(plantId: String) {
        plantPickerOpen = false
        if (journal == null || diagnosedPlant != null || phase !is SmartCameraPhase.DiseaseResult || savingOrSaved) return
        journalPlantId = plantId
        journalSave = JournalSaveState.Idle
    }

    /**
     * «Сохранить в журнал растения» for the chosen plant: one upload of the diagnosed photo with
     * [caption]. Further taps while saving or after success do nothing; a failure can be retried.
     */
    public fun saveToJournal(scope: CoroutineScope, caption: String) {
        val saver = journal ?: return
        val plantId = journalPlantId ?: return
        val jpeg = photo ?: return
        if (closed || phase !is SmartCameraPhase.DiseaseResult || savingOrSaved) return
        journalSave = JournalSaveState.Saving
        journalJob = scope.launch {
            journalSave = try {
                val dataUri = withContext(workContext) { photoDataUri(jpeg) }
                saver.save(plantId, dataUri, caption)
                JournalSaveState.Saved
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                JournalSaveState.Failed
            }
        }
    }

    /** «Вернуться к фото» is offered: a transient failure of a photo that is still here. */
    public val canReturnToPhoto: Boolean
        get() = !closed && photo != null && (phase as? SmartCameraPhase.Failed)?.failure?.transient == true

    /**
     * «Вернуться к фото» after a transient failure: the after-shot screen with the same photo, note, mode, chip
     * and capture file. Nothing is sent; only the next explicit [send] asks again.
     */
    public fun backToPhoto() {
        if (canReturnToPhoto) phase = SmartCameraPhase.Captured
    }

    /** Stops a running request; the photo and the note stay on the after-shot screen. */
    public fun cancel() {
        if (phase != SmartCameraPhase.Loading) return
        attempt++
        job?.cancel()
        job = null
        phase = SmartCameraPhase.Captured
    }

    /** «Переснять» / «Снять ещё раз»: back to the viewfinder in the same mode and with the same chip; nothing is sent. */
    public fun retake() {
        reset()
        phase = SmartCameraPhase.Viewfinder
    }

    private fun reset() {
        attempt++
        job?.cancel()
        job = null
        journalJob?.cancel()
        journalJob = null
        picking = false
        shotPending = false
        photo = null
        journalPlantId = null
        diagnosedPlant = null
        journalSave = JournalSaveState.Idle
        plantPickerOpen = false
        dropCapture()
    }

    public fun dismissDenied() {
        deniedSource = null
    }

    public fun dismissPickError() {
        pickErrorMessage = null
    }

    /** The screen is gone: stop work and drop the photo. */
    public fun close() {
        closed = true
        note = ""
        retake()
    }

    private companion object {
        /** Horizontal drag (px) that counts as a mode swipe rather than a slip of the finger. */
        const val SWIPE_MIN_DRAG = 48f

        /** Owner choice (M11c): pinch zoom up to 4× where the camera allows it, no further. */
        const val MAX_ZOOM = 4f
    }
}
