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
import site.xmpp.greenthumb.ui.screens.diagnosis.DiagnosisLogic
import site.xmpp.greenthumb.ui.screens.diagnosis.DiagnosisResult
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
 * on [retake] and [close]; a cancelled request never publishes its answer.
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

    private var job: Job? = null
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
            if (id != attempt) return@launch
            when (picked) {
                null, PickResult.Cancelled -> Unit
                is PickResult.PermissionDenied -> deniedSource = picked.source
                is PickResult.Picked -> send(id, current, picked.bytes, language)
            }
        }
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

    /** Stops a running request and returns to the mode choice; the mode stays. */
    public fun cancel() {
        attempt++
        job?.cancel()
        job = null
        picking = false
        photo = null
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
