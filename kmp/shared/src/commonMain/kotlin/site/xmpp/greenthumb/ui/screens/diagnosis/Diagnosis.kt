package site.xmpp.greenthumb.ui.screens.diagnosis

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import site.xmpp.greenthumb.core.network.ApiError
import site.xmpp.greenthumb.core.network.DiagnosisResponse
import site.xmpp.greenthumb.core.network.GreenThumbApi
import site.xmpp.greenthumb.core.network.PHOTO_CAPTION_MAX_LENGTH
import site.xmpp.greenthumb.core.platform.PickResult
import site.xmpp.greenthumb.core.platform.PickSource
import site.xmpp.greenthumb.core.platform.photoDataUri
import site.xmpp.greenthumb.ui.screens.smartcam.StandaloneDiagnoser

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

    /** 429 `daily_limit` for this account (`scope: "user"`, or no scope). */
    DailyLimit,

    /** 429 `daily_limit` with `scope: "global"`: the whole service has used up today's checks. */
    ServiceLimit,

    /** No connection or the 75 s client deadline. */
    Network,

    /** The server refused the request (4xx other than 429). */
    Failed,
}

/** Network seam of diagnosis; production uses [ApiPlantDiagnoser]. */
public fun interface PlantDiagnoser {
    public suspend fun diagnose(plantId: String, photoDataUri: String, note: String?, language: String): DiagnosisResponse

    /** Bytes of a stored plant photo (the cover used by «Проверить на болезни»). */
    public suspend fun fetchPhoto(url: String): ByteArray = throw UnsupportedOperationException("no photo download")
}

/** Production diagnoser; it also serves the smart camera's plant-less diagnosis. */
public class ApiPlantDiagnoser(private val api: GreenThumbApi) : PlantDiagnoser, StandaloneDiagnoser {
    override suspend fun diagnose(plantId: String, photoDataUri: String, note: String?, language: String): DiagnosisResponse =
        api.diagnosePlant(plantId, photoDataUri, note, language)

    override suspend fun diagnose(photoDataUri: String, note: String?, language: String): DiagnosisResponse =
        api.diagnoseWithoutPlant(photoDataUri, note, language)

    override suspend fun fetchPhoto(url: String): ByteArray = api.downloadPhoto(url)
}

/**
 * Writes a diagnosis to the plant journal: one `POST /api/plants/:id/photos` with the
 * diagnosed photo and a caption. Production goes through `PlantTimeline.addPhoto`, so the
 * photo and its journal entry also land in the local cache.
 */
public fun interface DiagnosisJournalSaver {
    public suspend fun save(plantId: String, photoDataUri: String, caption: String)
}

/** «Сохранить в журнал растения» button state; a failure keeps the result and allows a retry. */
public enum class JournalSaveState { Idle, Saving, Saved, Failed }

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
        is ApiError.Client -> when {
            error.status != 429 -> DiagnosisError.Failed
            limitScope(error.body) == "global" -> DiagnosisError.ServiceLimit
            else -> DiagnosisError.DailyLimit
        }
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

    /** `scope` of a 429 `daily_limit` body, or null when the body has none. */
    private fun limitScope(body: String): String? = try {
        Json.parseToJsonElement(body).jsonObject["scope"]?.jsonPrimitive?.content
    } catch (_: Exception) {
        null
    }

    /**
     * Raw bytes of the plant cover: a legacy `data:` URI is decoded here, any other URL is
     * downloaded with [download] (the API client).
     */
    @OptIn(ExperimentalEncodingApi::class)
    public suspend fun coverPhoto(photoUrl: String, download: suspend (String) -> ByteArray): ByteArray {
        val url = photoUrl.trim()
        require(url.isNotEmpty()) { "the plant has no photo" }
        if (url.startsWith("data:", ignoreCase = true)) {
            val comma = url.indexOf(',')
            require(comma > 0 && url.substring(0, comma).endsWith(";base64", ignoreCase = true)) { "unsupported data URI" }
            return Base64.decode(url.substring(comma + 1))
        }
        return download(url)
    }
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
    private val journal: DiagnosisJournalSaver? = null,
    private val initialPhoto: (suspend () -> ByteArray)? = null,
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

    /** The cover photo of «Проверить на болезни» is being downloaded and prepared. */
    public var photoLoading: Boolean by mutableStateOf(false)
        private set

    /** The cover photo could not be loaded; the normal input is shown instead. */
    public var photoLoadFailed: Boolean by mutableStateOf(false)
        private set

    public var journalSave: JournalSaveState by mutableStateOf(JournalSaveState.Idle)
        private set

    public val hasPhoto: Boolean get() = photo != null

    /** Only a screen opened from a plant of this account gets a journal. */
    public val canSaveToJournal: Boolean get() = journal != null

    public val canRetry: Boolean
        get() = error != null && error != DiagnosisError.DailyLimit && error != DiagnosisError.ServiceLimit && photo != null

    /** The prepared JPEG of the shown result; it is the photo saved to the journal. */
    private var resultPhoto: ByteArray? = null
    private var journalJob: Job? = null
    private var initialJob: Job? = null

    private var capturePath: String? = null
    private var pickJob: Job? = null
    private var requestJob: Job? = null
    private var requestId = 0
    private var closed = false

    public fun updateNote(value: String) {
        note = DiagnosisLogic.clampNote(value)
    }

    /** Loads the preset photo once; without one, or on failure, the input works as usual. */
    public fun loadInitialPhoto(scope: CoroutineScope) {
        val load = initialPhoto ?: return
        if (closed || initialJob != null || photo != null) return
        photoLoading = true
        initialJob = scope.launch {
            try {
                val prepared = withContext(workContext) { prepare(load()) }
                if (!closed && photo == null) {
                    photo = prepared
                    photoLoadFailed = false
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                if (photo == null) photoLoadFailed = true
            } finally {
                photoLoading = false
            }
        }
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
        photoLoadFailed = false
        error = null
        if (phase is DiagnosisPhase.Result) phase = DiagnosisPhase.Input
    }

    public fun submit(scope: CoroutineScope, language: String) {
        val current = photo ?: return
        if (closed || phase == DiagnosisPhase.Loading || photoLoading) return
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
                onSuccess = {
                    resultPhoto = current
                    journalJob?.cancel()
                    journalJob = null
                    journalSave = JournalSaveState.Idle
                    phase = DiagnosisPhase.Result(it)
                },
                onFailure = {
                    error = DiagnosisLogic.errorFor(it)
                    phase = DiagnosisPhase.Input
                },
            )
        }
    }

    /**
     * Saves the shown result to the plant journal: one upload of the diagnosed photo with
     * [caption]. Saved or in-flight saves ignore further taps; a failure can be retried and
     * sends the same photo and caption again. No diagnosis request is made.
     */
    public fun saveToJournal(scope: CoroutineScope, caption: String) {
        val saver = journal ?: return
        val jpeg = resultPhoto ?: return
        if (closed || phase !is DiagnosisPhase.Result) return
        if (journalSave == JournalSaveState.Saving || journalSave == JournalSaveState.Saved) return
        journalSave = JournalSaveState.Saving
        journalJob = scope.launch {
            journalSave = try {
                val dataUri = withContext(workContext) { DiagnosisLogic.photoDataUrl(jpeg) }
                saver.save(plantId, dataUri, caption)
                JournalSaveState.Saved
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                JournalSaveState.Failed
            }
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
        initialJob?.cancel()
        journalJob?.cancel()
        journalJob = null
        resultPhoto = null
        capturePath?.let(deleteCapture)
        capturePath = null
        photo = null
        note = ""
        error = null
        phase = DiagnosisPhase.Input
    }
}

/** Caption of a diagnosis saved to the plant journal (VAL-CARD-014). */
public object DiagnosisJournal {
    private val spaces = Regex("\\s+")

    /**
     * «<prefix>: <title> (<word>), …» for each shown cause, cut to [PHOTO_CAPTION_MAX_LENGTH]
     * with «…» and never ending in half of a surrogate pair.
     */
    public fun caption(prefix: String, causes: List<DiagnosisCause>, word: (Likelihood) -> String): String {
        val full = prefix.trim() + ": " + causes.joinToString(", ") { cause ->
            "${cause.title.replace(spaces, " ").trim()} (${word(cause.likelihood)})"
        }
        if (full.length <= PHOTO_CAPTION_MAX_LENGTH) return full
        var cut = full.take(PHOTO_CAPTION_MAX_LENGTH - 1)
        if (cut.last().isHighSurrogate()) cut = cut.dropLast(1)
        return cut.trimEnd() + "…"
    }
}
