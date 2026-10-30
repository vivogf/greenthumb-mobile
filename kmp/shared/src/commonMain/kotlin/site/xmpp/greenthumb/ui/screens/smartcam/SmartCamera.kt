package site.xmpp.greenthumb.ui.screens.smartcam

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import site.xmpp.greenthumb.catalog.CatalogRepository
import site.xmpp.greenthumb.core.network.ApiError
import site.xmpp.greenthumb.core.network.DiagnosisResponse
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
    /** Mode choice with «Сделать фото» and «Выбрать из галереи». */
    public data object Choose : SmartCameraPhase
    public data object Loading : SmartCameraPhase
    public data class PlantResult(public val candidates: List<IdentifyCandidate>) : SmartCameraPhase
    public data class DiseaseResult(public val result: DiagnosisResult) : SmartCameraPhase
    public data class Failed(public val failure: SmartCameraFailure) : SmartCameraPhase
}

/**
 * State of the smart camera screen (D§ item 9a). Opening it and switching the mode send nothing;
 * one photo makes one request in the chosen mode. The photo lives only in memory and is dropped
 * on [retake] and [close]; a kept camera shot is deleted then too. A cancelled request never
 * publishes its answer. A disease result can be saved to one of the user's plants through
 * [journal] («Это моё растение?») without another diagnosis request.
 */
public class SmartCameraController(
    initialMode: SmartCameraMode,
    private val identifier: SpeciesIdentifier?,
    private val diagnoser: StandaloneDiagnoser?,
    private val pick: suspend (PickSource) -> PickResult,
    /** JPEG for identify (and for the add-plant draft). */
    private val preparePlant: (ByteArray) -> ByteArray,
    /** JPEG for diagnosis (size-capped by [DiagnosisLogic.preparePhoto]). */
    private val prepareDisease: (ByteArray) -> ByteArray,
    private val catalog: () -> CatalogRepository?,
    private val workContext: CoroutineContext = EmptyCoroutineContext,
    private val journal: DiagnosisJournalSaver? = null,
    private val deleteCapture: (String) -> Unit = {},
) {
    public var mode: SmartCameraMode by mutableStateOf(initialMode)
        private set

    public var phase: SmartCameraPhase by mutableStateOf(SmartCameraPhase.Choose)
        private set

    /** Prepared JPEG of the current attempt. */
    public var photo: ByteArray? by mutableStateOf(null)
        private set

    /** Index of the candidate «Добавить к моим растениям» uses. */
    public var selectedCandidate: Int by mutableStateOf(0)
        private set

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

    public fun selectMode(value: SmartCameraMode) {
        if (phase == SmartCameraPhase.Choose && !picking) mode = value
    }

    /** Picks a photo from [source] and sends it in the current mode. A cancelled pick stays on the choice. */
    public fun shoot(scope: CoroutineScope, source: PickSource, language: String) {
        if (closed || picking || phase != SmartCameraPhase.Choose) return
        val current = mode
        if (current == SmartCameraMode.Disease && diagnoser == null) {
            phase = SmartCameraPhase.Failed(SmartCameraFailure.Diagnosis(DiagnosisError.Unavailable))
            return
        }
        val id = ++attempt
        picking = true
        job = scope.launch {
            val picked = try {
                pick(source)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                pickErrorMessage = failure.message ?: failure.toString()
                null
            } finally {
                picking = false
            }
            if (picked is PickResult.Picked && picked.capturePath != null) keepCapture(id, picked.capturePath)
            if (id != attempt) return@launch
            when (picked) {
                null, PickResult.Cancelled -> Unit
                is PickResult.PermissionDenied -> deniedSource = picked.source
                is PickResult.Picked -> send(id, current, picked.bytes, language)
            }
        }
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

    private suspend fun send(id: Int, current: SmartCameraMode, raw: ByteArray, language: String) {
        val prepared = try {
            withContext(workContext) {
                if (current == SmartCameraMode.Plant) preparePlant(raw) else prepareDisease(raw)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            pickErrorMessage = failure.message ?: failure.toString()
            return
        }
        if (id != attempt) return
        photo = prepared
        selectedCandidate = 0
        phase = SmartCameraPhase.Loading
        val outcome: SmartCameraPhase = try {
            val dataUri = withContext(workContext) { photoDataUri(prepared) }
            when (current) {
                SmartCameraMode.Plant -> identify(dataUri, language)
                SmartCameraMode.Disease -> diagnose(dataUri, language)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            SmartCameraPhase.Failed(failureFor(current, failure))
        }
        if (id == attempt) phase = outcome
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

    private suspend fun diagnose(dataUri: String, language: String): SmartCameraPhase {
        val seam = diagnoser ?: return SmartCameraPhase.Failed(SmartCameraFailure.Diagnosis(DiagnosisError.Unavailable))
        val result = DiagnosisLogic.toResult(seam.diagnose(dataUri, null, language))
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

    /** Stops a running request and returns to the mode choice; the mode stays. */
    public fun cancel() {
        attempt++
        job?.cancel()
        job = null
        journalJob?.cancel()
        journalJob = null
        picking = false
        photo = null
        journalPlantId = null
        journalSave = JournalSaveState.Idle
        plantPickerOpen = false
        dropCapture()
        phase = SmartCameraPhase.Choose
    }

    /** «Снять ещё раз»: back to the mode choice with the same mode; nothing is sent. */
    public fun retake() {
        cancel()
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
        cancel()
    }
}
