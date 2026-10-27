package site.xmpp.greenthumb.ui.screens.diagnosis

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
import site.xmpp.greenthumb.core.network.ApiError
import site.xmpp.greenthumb.core.network.DiagnosisResponse
import site.xmpp.greenthumb.core.network.GreenThumbApi
import site.xmpp.greenthumb.core.platform.PickResult
import site.xmpp.greenthumb.core.platform.PickSource
import site.xmpp.greenthumb.core.platform.photoDataUri

/** Likelihood of a cause, shown only as a word (architecture §8.7: no numbers). */
public enum class Likelihood { Low, Medium, High }

public data class DiagnosisCause(
    public val title: String,
    public val likelihood: Likelihood,
    public val explanation: String,
    public val advice: String,
)

public data class DiagnosisResult(
    public val causes: List<DiagnosisCause>,
    public val summary: String,
)

public sealed interface DiagnosisPhase {
    /** Photo and note can be edited; [DiagnosisController.error] may describe the last failed attempt. */
    public data object Input : DiagnosisPhase
    public data object Loading : DiagnosisPhase
    public data class Result(public val result: DiagnosisResult) : DiagnosisPhase
}

public enum class DiagnosisError {
    /** 503 `diagnosis_unavailable`, other 5xx or an answer the app cannot show. */
    Unavailable,

    /** 429 `daily_limit`. */
    DailyLimit,

    /** No connection or the 75 s client deadline. */
    Network,

    /** The server refused the request (4xx other than 429). */
    Failed,
}

/** Network seam of diagnosis; production uses [ApiPlantDiagnoser]. */
public fun interface PlantDiagnoser {
    public suspend fun diagnose(plantId: String, photoDataUri: String, note: String?, language: String): DiagnosisResponse
}

public class ApiPlantDiagnoser(private val api: GreenThumbApi) : PlantDiagnoser {
    override suspend fun diagnose(plantId: String, photoDataUri: String, note: String?, language: String): DiagnosisResponse =
        api.diagnosePlant(plantId, photoDataUri, note, language)
}

public object DiagnosisLogic {
    public const val MAX_CAUSES: Int = 3
    public const val MAX_NOTE_LENGTH: Int = 500

    /** Longest side of the photo sent to the server, px (same as identification). */
    public const val PHOTO_MAX_SIDE: Int = 1280

    /** Server limit for the decoded photo. */
    public const val MAX_PHOTO_BYTES: Int = 5 * 1024 * 1024

    private const val PHOTO_QUALITY: Double = 0.8

    /** Wire language of the request: `ru` for a Russian UI, otherwise `en`. */
    public fun wireLanguage(locale: String): String =
        if (locale.lowercase().startsWith("ru")) "ru" else "en"

    /** At most [MAX_NOTE_LENGTH] UTF-16 units, never ending in half of a surrogate pair. */
    public fun clampNote(note: String): String {
        if (note.length <= MAX_NOTE_LENGTH) return note
        val cut = note.take(MAX_NOTE_LENGTH)
        return if (cut.last().isHighSurrogate()) cut.dropLast(1) else cut
    }

    /** The note as sent: trimmed, or null (omitted from the body) when blank. */
    public fun wireNote(note: String): String? = note.trim().takeIf { it.isNotEmpty() }?.let(::clampNote)

    public fun likelihood(wire: String): Likelihood? = when (wire.trim().lowercase()) {
        "low" -> Likelihood.Low
        "medium" -> Likelihood.Medium
        "high" -> Likelihood.High
        else -> null
    }

    /** Causes with a known likelihood, server order, at most [MAX_CAUSES]; null when none is left. */
    public fun toResult(response: DiagnosisResponse): DiagnosisResult? {
        val causes = response.causes.mapNotNull { dto ->
            val level = likelihood(dto.likelihood) ?: return@mapNotNull null
            DiagnosisCause(dto.title.trim(), level, dto.explanation.trim(), dto.advice.trim())
        }.take(MAX_CAUSES)
        if (causes.isEmpty()) return null
        return DiagnosisResult(causes, response.summary.trim())
    }

    public fun errorFor(error: Throwable): DiagnosisError = when (error) {
        is ApiError.Network, is ApiError.Timeout -> DiagnosisError.Network
        is ApiError.Client -> if (error.status == 429) DiagnosisError.DailyLimit else DiagnosisError.Failed
        is ApiError.Server -> DiagnosisError.Unavailable
        is ApiError.Unauthorized -> DiagnosisError.Failed
        else -> DiagnosisError.Unavailable
    }

    /**
     * Shrinks the photo to [PHOTO_MAX_SIDE] JPEG; a photo that is still above
     * [MAX_PHOTO_BYTES] is shrunk further, so the request never exceeds the server limit.
     */
    public fun preparePhoto(raw: ByteArray, resize: (ByteArray, Int, Double) -> ByteArray): ByteArray {
        var side = PHOTO_MAX_SIDE
        var bytes = resize(raw, side, PHOTO_QUALITY)
        while (bytes.size > MAX_PHOTO_BYTES && side > 320) {
            side = side * 3 / 4
            bytes = resize(raw, side, PHOTO_QUALITY)
        }
        return bytes
    }

    public fun photoDataUrl(jpeg: ByteArray): String = photoDataUri(jpeg)
}

/**
 * State of one diagnosis screen. Nothing outlives the screen: the photo and
 * note live in memory, a kept camera shot is deleted on replace and on [close],
 * and a cancelled request can never publish its answer.
 */
public class DiagnosisController(
    private val plantId: String,
    private val diagnoser: PlantDiagnoser,
    private val pick: suspend (PickSource) -> PickResult,
    private val prepare: (ByteArray) -> ByteArray,
    private val deleteCapture: (String) -> Unit,
    private val workContext: CoroutineContext = EmptyCoroutineContext,
) {
    public var phase: DiagnosisPhase by mutableStateOf(DiagnosisPhase.Input)
        private set

    public var error: DiagnosisError? by mutableStateOf(null)
        private set

    public var note: String by mutableStateOf("")
        private set

    /** Prepared JPEG shown as the preview and sent as the photo. */
    public var photo: ByteArray? by mutableStateOf(null)
        private set

    public var picking: Boolean by mutableStateOf(false)
        private set

    public var deniedSource: PickSource? by mutableStateOf(null)
        private set

    public var pickErrorMessage: String? by mutableStateOf(null)
        private set

    public val hasPhoto: Boolean get() = photo != null

    public val canRetry: Boolean get() = error != null && error != DiagnosisError.DailyLimit && photo != null

    private var capturePath: String? = null
    private var pickJob: Job? = null
    private var requestJob: Job? = null
    private var requestId = 0
    private var closed = false

    public fun updateNote(value: String) {
        note = DiagnosisLogic.clampNote(value)
    }

    public fun pickPhoto(scope: CoroutineScope, source: PickSource) {
        if (closed || picking || phase == DiagnosisPhase.Loading) return
        picking = true
        pickJob = scope.launch {
            try {
                val picked = try {
                    pick(source)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Throwable) {
                    pickErrorMessage = failure.message ?: failure.toString()
                    return@launch
                }
                when (picked) {
                    PickResult.Cancelled -> Unit
                    is PickResult.PermissionDenied -> deniedSource = picked.source
                    is PickResult.Picked -> accept(picked)
                }
            } finally {
                picking = false
            }
        }
    }

    private suspend fun accept(picked: PickResult.Picked) {
        val prepared = try {
            withContext(workContext) { prepare(picked.bytes) }
        } catch (failure: Throwable) {
            picked.capturePath?.let(deleteCapture)
            if (failure is CancellationException) throw failure
            pickErrorMessage = failure.message ?: failure.toString()
            return
        }
        if (closed) {
            picked.capturePath?.let(deleteCapture)
            return
        }
        capturePath?.let(deleteCapture)
        capturePath = picked.capturePath
        photo = prepared
        error = null
        if (phase is DiagnosisPhase.Result) phase = DiagnosisPhase.Input
    }

    public fun submit(scope: CoroutineScope, language: String) {
        val current = photo ?: return
        if (closed || phase == DiagnosisPhase.Loading) return
        val id = ++requestId
        val noteToSend = DiagnosisLogic.wireNote(note)
        error = null
        phase = DiagnosisPhase.Loading
        requestJob = scope.launch {
            val outcome = try {
                val dataUrl = withContext(workContext) { DiagnosisLogic.photoDataUrl(current) }
                val response = diagnoser.diagnose(plantId, dataUrl, noteToSend, language)
                DiagnosisLogic.toResult(response)?.let { Result.success(it) }
                    ?: Result.failure(IllegalStateException("no displayable cause"))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                Result.failure(failure)
            }
            // A cancelled or replaced request never publishes, even if its transport finished anyway.
            if (id != requestId) return@launch
            requestJob = null
            outcome.fold(
                onSuccess = { phase = DiagnosisPhase.Result(it) },
                onFailure = {
                    error = DiagnosisLogic.errorFor(it)
                    phase = DiagnosisPhase.Input
                },
            )
        }
    }

    /** Sends the same photo and note again. */
    public fun retry(scope: CoroutineScope, language: String) {
        if (canRetry) submit(scope, language)
    }

    /** Stops a running request and returns to the input with the photo and note kept. */
    public fun cancel() {
        requestId++
        requestJob?.cancel()
        requestJob = null
        if (phase == DiagnosisPhase.Loading) phase = DiagnosisPhase.Input
    }

    /** From the result back to the input for a new diagnosis with another photo or note. */
    public fun startOver() {
        if (phase is DiagnosisPhase.Result) phase = DiagnosisPhase.Input
    }

    public fun dismissDenied() {
        deniedSource = null
    }

    public fun dismissPickError() {
        pickErrorMessage = null
    }

    /** The screen is gone: stop work and delete the kept camera shot. */
    public fun close() {
        closed = true
        cancel()
        pickJob?.cancel()
        pickJob = null
        capturePath?.let(deleteCapture)
        capturePath = null
        photo = null
        note = ""
        error = null
        phase = DiagnosisPhase.Input
    }
}
