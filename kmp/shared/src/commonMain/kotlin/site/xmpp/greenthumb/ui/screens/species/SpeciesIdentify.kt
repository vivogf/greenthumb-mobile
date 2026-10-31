package site.xmpp.greenthumb.ui.screens.species

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import site.xmpp.greenthumb.catalog.CatalogRepository
import site.xmpp.greenthumb.catalog.SpeciesEntry
import site.xmpp.greenthumb.core.network.ApiError
import site.xmpp.greenthumb.core.network.GreenThumbApi
import site.xmpp.greenthumb.core.network.SpeciesCandidateDto
import site.xmpp.greenthumb.core.platform.PickResult
import site.xmpp.greenthumb.core.platform.PickSource
import site.xmpp.greenthumb.core.platform.photoDataUri

/** Confidence shown as a word, never as a number (architecture §8.4). */
public enum class ScoreLevel { High, Medium, Low }

/** ≥ 0.50 high, 0.20–0.49 medium, < 0.20 low. */
public fun scoreLevel(score: Double): ScoreLevel = when {
    score >= 0.50 -> ScoreLevel.High
    score >= 0.20 -> ScoreLevel.Medium
    else -> ScoreLevel.Low
}

/** One candidate ready for display; [catalogEntry] is set when the catalog knows the species. */
public data class IdentifyCandidate(
    public val scientificName: String,
    public val commonName: String?,
    public val level: ScoreLevel,
    public val catalogEntry: SpeciesEntry?,
)

public sealed interface IdentifyState {
    public data object Idle : IdentifyState
    public data object Loading : IdentifyState
    public data class Results(public val candidates: List<IdentifyCandidate>) : IdentifyState
    public data object Empty : IdentifyState
    public data object QuotaExceeded : IdentifyState
    public data object Unavailable : IdentifyState
}

/** Network seam of identification; production uses [ApiSpeciesIdentifier]. */
public fun interface SpeciesIdentifier {
    public suspend fun identify(photoDataUri: String, lang: String): List<SpeciesCandidateDto>
}

public class ApiSpeciesIdentifier(private val api: GreenThumbApi) : SpeciesIdentifier {
    override suspend fun identify(photoDataUri: String, lang: String): List<SpeciesCandidateDto> =
        api.identifySpecies(photoDataUri, lang).results
}

public object IdentifyLogic {
    public const val MAX_CANDIDATES: Int = 3

    /** Longest side of the photo sent to the server, px. */
    public const val PHOTO_MAX_SIDE: Int = 1280

    /** Server order is kept; at most [MAX_CANDIDATES]. */
    public fun toCandidates(results: List<SpeciesCandidateDto>, catalog: CatalogRepository?): List<IdentifyCandidate> =
        results.take(MAX_CANDIDATES).map { dto ->
            IdentifyCandidate(
                scientificName = dto.scientificName,
                commonName = dto.commonNames.firstOrNull { it.isNotBlank() },
                level = scoreLevel(dto.score),
                catalogEntry = catalog?.byScientificName(dto.scientificName),
            )
        }

    /** 429 is the daily quota; every other failure means the service cannot answer now. */
    public fun stateForError(error: Throwable): IdentifyState =
        if (error is ApiError.Client && error.status == 429) IdentifyState.QuotaExceeded else IdentifyState.Unavailable

    /** Wire language of the request: `ru` for a Russian UI, otherwise `en`. */
    public fun wireLanguage(locale: String): String =
        if (locale.lowercase().startsWith("ru")) "ru" else "en"
}

/**
 * Identification flow of the add-plant screen: pick a photo, shrink it to
 * [IdentifyLogic.PHOTO_MAX_SIDE], send it, and settle in a final state.
 * Every path leaves [IdentifyState.Loading]; the 60 s client deadline bounds
 * the wait.
 */
public class IdentifyController(
    private val identifier: SpeciesIdentifier,
    private val pick: suspend (PickSource) -> PickResult,
    private val resize: (ByteArray) -> ByteArray,
) {
    public var state: IdentifyState by mutableStateOf(IdentifyState.Idle)
        private set

    public var deniedSource: PickSource? by mutableStateOf(null)
        private set

    public var pickErrorMessage: String? by mutableStateOf(null)
        private set

    private var job: Job? = null

    public fun start(scope: CoroutineScope, source: PickSource, lang: String, catalog: () -> CatalogRepository?) {
        if (job?.isActive == true) return
        job = scope.launch {
            val picked = try {
                pick(source)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                pickErrorMessage = error.message ?: error.toString()
                return@launch
            }
            when (picked) {
                is PickResult.Cancelled -> Unit
                is PickResult.PermissionDenied -> deniedSource = picked.source
                is PickResult.Picked -> prepareAndIdentify(picked.bytes, lang, catalog)
            }
        }
    }

    /**
     * Identifies a photo the screen already has (the chosen cover): no picker opens.
     * Returns the running job, or null when another identification is still running.
     */
    public fun identifyPhoto(scope: CoroutineScope, photo: ByteArray, lang: String, catalog: () -> CatalogRepository?): Job? {
        if (job?.isActive == true) return null
        return scope.launch { prepareAndIdentify(photo, lang, catalog) }.also { job = it }
    }

    private suspend fun prepareAndIdentify(bytes: ByteArray, lang: String, catalog: () -> CatalogRepository?) {
        val photo = try {
            withContext(Dispatchers.Default) { photoDataUri(resize(bytes)) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            pickErrorMessage = error.message ?: error.toString()
            return
        }
        identify(photo, lang, catalog)
    }

    /** Sends one photo and settles the state. */
    public suspend fun identify(photoDataUri: String, lang: String, catalog: () -> CatalogRepository?) {
        state = IdentifyState.Loading
        state = try {
            val candidates = IdentifyLogic.toCandidates(identifier.identify(photoDataUri, lang), catalog())
            if (candidates.isEmpty()) IdentifyState.Empty else IdentifyState.Results(candidates)
        } catch (cancellation: CancellationException) {
            state = IdentifyState.Idle
            throw cancellation
        } catch (error: Throwable) {
            IdentifyLogic.stateForError(error)
        }
    }

    /** Closes the result or stops a running request. */
    public fun dismiss() {
        job?.cancel()
        job = null
        state = IdentifyState.Idle
    }

    public fun dismissDenied() {
        deniedSource = null
    }

    public fun dismissPickError() {
        pickErrorMessage = null
    }
}
