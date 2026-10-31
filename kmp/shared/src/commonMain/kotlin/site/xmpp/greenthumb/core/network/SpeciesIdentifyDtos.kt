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
 * Pl@ntNet 15 s; the rest covers the photo upload and the answer on a slow link
 * (answers took 11-20 s end to end over a 370 ms RTT path). Every other call
 * keeps [REQUEST_TIMEOUT_MILLIS].
 */
const val IDENTIFY_TIMEOUT_MILLIS: Long = 60_000L
