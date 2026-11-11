package site.xmpp.greenthumb.ui.screens.diagnosis

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import site.xmpp.greenthumb.core.network.ApiError
import site.xmpp.greenthumb.core.network.DiagnosisResponse
import site.xmpp.greenthumb.core.network.GreenThumbApi
import site.xmpp.greenthumb.core.network.PHOTO_CAPTION_MAX_LENGTH
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

public enum class DiagnosisError {
    /** 503 `diagnosis_unavailable`, other 5xx or an answer the app cannot show. */
    Unavailable,

    /**
     * 422 `no_plant`: the service worked and found no plant (or no sign of disease) in the photo, so another
     * photo helps and the attempt costs no daily check. Not a service failure.
     */
    NoPlant,

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
            error.status == 429 && limitScope(error.body) == "global" -> DiagnosisError.ServiceLimit
            error.status == 429 -> DiagnosisError.DailyLimit
            error.status == 422 && hasNoPlantCode(error.body) -> DiagnosisError.NoPlant
            else -> DiagnosisError.Failed
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

    /** `scope` of a 429 `daily_limit` body, or null when the body has none. */
    private fun limitScope(body: String): String? = field(body, "scope")

    /** The 422 body of a photo the service could not read as a plant carries `code: "no_plant"`. */
    private fun hasNoPlantCode(body: String): Boolean = field(body, "code") == NO_PLANT_CODE

    /** A string field of a JSON object body, or null when the body is not one or the field is missing. */
    private fun field(body: String, name: String): String? = try {
        Json.parseToJsonElement(body).jsonObject[name]?.jsonPrimitive?.content
    } catch (_: Exception) {
        null
    }

    private const val NO_PLANT_CODE: String = "no_plant"

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
