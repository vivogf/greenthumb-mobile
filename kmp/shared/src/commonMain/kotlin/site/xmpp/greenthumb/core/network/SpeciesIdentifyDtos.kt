package site.xmpp.greenthumb.core.network

import kotlinx.serialization.Serializable

/** Body of `POST /api/species/identify` (architecture §8.4): JPEG/PNG data URLs and `ru`|`en`. */
@Serializable
data class IdentifyRequest(
    val photos: List<String>,
    val lang: String,
)

/** One Pl@ntNet candidate as the server returns it (score 0..1, best first). */
@Serializable
data class SpeciesCandidateDto(
    val scientificName: String,
    val commonNames: List<String> = emptyList(),
    val score: Double = 0.0,
    val gbifId: String? = null,
)

/** 200 body of `POST /api/species/identify`. */
@Serializable
data class IdentifyResponse(
    val results: List<SpeciesCandidateDto> = emptyList(),
    val remainingToday: Int? = null,
)

/**
 * Client deadline for identify only (architecture §8.4). The server gives
 * Pl@ntNet 15 s; every other call keeps [REQUEST_TIMEOUT_MILLIS].
 */
const val IDENTIFY_TIMEOUT_MILLIS: Long = 25_000L
