package site.xmpp.greenthumb.ui.screens.smartcam

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import site.xmpp.greenthumb.catalog.CatalogRepository
import site.xmpp.greenthumb.core.network.ApiError
import site.xmpp.greenthumb.core.network.DiagnosisResponse
import site.xmpp.greenthumb.core.platform.CameraStatus
import site.xmpp.greenthumb.core.platform.PickResult
import site.xmpp.greenthumb.core.platform.PickSource
import site.xmpp.greenthumb.core.platform.photoDataUri
import site.xmpp.greenthumb.ui.screens.diagnosis.DiagnosisError
import site.xmpp.greenthumb.ui.screens.diagnosis.DiagnosisJournalSaver
import site.xmpp.greenthumb.ui.screens.diagnosis.DiagnosisLogic
import site.xmpp.greenthumb.ui.screens.diagnosis.DiagnosisResult
import site.xmpp.greenthumb.ui.screens.diagnosis.JournalSaveState
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
}

public sealed interface SmartCameraPhase {
    /** The live camera with the mode switch, the shutter and the gallery button (or the camera fallback). */
    public data object Viewfinder : SmartCameraPhase

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
 */
public class SmartCameraController(
    initialMode: SmartCameraMode,
    private val identifier: SpeciesIdentifier?,
    private val diagnoser: StandaloneDiagnoser?,
    /** Gallery and the system-camera fallback. */
    private val pick: suspend (PickSource) -> PickResult,
    /** JPEG for identify (and for the add-plant draft). */
    private val preparePlant: (ByteArray) -> ByteArray,
    /** JPEG for diagnosis (size-capped by [DiagnosisLogic.preparePhoto]). */
    private val prepareDisease: (ByteArray) -> ByteArray,
    private val catalog: () -> CatalogRepository?,
    /** The in-app camera shot with the flash on or off. */
    private val capture: suspend (flash: Boolean) -> PickResult = { PickResult.Cancelled },
    private val workContext: CoroutineContext = EmptyCoroutineContext,
    private val journal: DiagnosisJournalSaver? = null,
    private val deleteCapture: (String) -> Unit = {},
    /** Called on every mode change, so the next camera opens in the same mode. */
    private val onModeChange: (SmartCameraMode) -> Unit = {},
) {
    public var mode: SmartCameraMode by mutableStateOf(initialMode)
        private set

    public var phase: SmartCameraPhase by mutableStateOf(SmartCameraPhase.Viewfinder)
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

    /** «Что вас беспокоит?» of the disease mode, at most [DiagnosisLogic.MAX_NOTE_LENGTH] characters. */
    public var note: String by mutableStateOf("")
        private set

    /** Index of the candidate «Добавить к моим растениям» uses. */
    public var selectedCandidate: Int by mutableStateOf(0)
        private set

    /** A shot or a pick is running. */
    public var picking: Boolean by mutableStateOf(false)
        private set

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

    private var job: Job? = null
    private var journalJob: Job? = null
    private var capturePath: String? = null
    private var attempt = 0
    private var closed = false

    private val onViewfinder: Boolean get() = !closed && phase == SmartCameraPhase.Viewfinder && !picking

    public fun onCameraStatus(status: CameraStatus) {
        cameraStatus = status
        if (status is CameraStatus.Ready && !status.hasFlash) flash = false
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

    /** The shutter of the in-app camera. */
    public fun shutter(scope: CoroutineScope) {
        if (cameraStatus !is CameraStatus.Ready) return
        val withFlash = flash && hasFlash
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
                    is PickResult.Picked -> show(id, current, picked.bytes)
                }
            } finally {
                if (id == attempt) picking = false
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
        note = DiagnosisLogic.clampNote(value)
    }

    /** «Узнать вид» / «Проверить растение»: the one request of this photo. */
    public fun send(scope: CoroutineScope, language: String) {
        val prepared = photo ?: return
        if (closed || phase != SmartCameraPhase.Captured) return
        val id = ++attempt
        val current = mode
        val wireNote = DiagnosisLogic.wireNote(note)
        phase = SmartCameraPhase.Loading
        job = scope.launch {
            val outcome: SmartCameraPhase = try {
                val dataUri = withContext(workContext) { photoDataUri(prepared) }
                when (current) {
                    SmartCameraMode.Plant -> identify(dataUri, language)
                    SmartCameraMode.Disease -> diagnose(dataUri, wireNote, language)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                SmartCameraPhase.Failed(failureFor(current, failure))
            }
            if (id == attempt) phase = outcome
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

    private suspend fun diagnose(dataUri: String, note: String?, language: String): SmartCameraPhase {
        val seam = diagnoser ?: return SmartCameraPhase.Failed(SmartCameraFailure.Diagnosis(DiagnosisError.Unavailable))
        val result = DiagnosisLogic.toResult(seam.diagnose(dataUri, note, language))
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

    private val savingOrSaved: Boolean
        get() = journalSave == JournalSaveState.Saving || journalSave == JournalSaveState.Saved

    /** «Это моё растение?»: only on a disease result that is not saved yet. */
    public fun openPlantPicker() {
        if (journal != null && phase is SmartCameraPhase.DiseaseResult && !savingOrSaved) plantPickerOpen = true
    }

    public fun dismissPlantPicker() {
        plantPickerOpen = false
    }

    /** Sets the plant the result is saved to; a failed save is reset, a saved result keeps its plant. */
    public fun choosePlant(plantId: String) {
        plantPickerOpen = false
        if (journal == null || phase !is SmartCameraPhase.DiseaseResult || savingOrSaved) return
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

    /** Stops a running request; the photo and the note stay on the after-shot screen. */
    public fun cancel() {
        if (phase != SmartCameraPhase.Loading) return
        attempt++
        job?.cancel()
        job = null
        phase = SmartCameraPhase.Captured
    }

    /** «Переснять» / «Снять ещё раз»: back to the viewfinder in the same mode; nothing is sent. */
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
        photo = null
        note = ""
        journalPlantId = null
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
        retake()
    }

    private companion object {
        /** Horizontal drag (px) that counts as a mode swipe rather than a slip of the finger. */
        const val SWIPE_MIN_DRAG = 48f
    }
}
