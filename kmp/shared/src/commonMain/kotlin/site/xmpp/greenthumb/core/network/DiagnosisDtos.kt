package site.xmpp.greenthumb.core.network

import kotlinx.serialization.Serializable

/**
 * Body of `POST /api/plants/:id/diagnose` (architecture §2.10): one JPEG data URL,
 * an optional note (omitted when absent) and the answer language `ru`|`en`.
 */
@Serializable
data class DiagnoseRequest(
    val photo: String,
    val note: String? = null,
    val language: String,
)

/** One possible cause; `likelihood` is `low`|`medium`|`high`, never a number. */
@Serializable
data class DiagnosisCauseDto(
    val title: String,
    val likelihood: String,
    val explanation: String,
    val advice: String,
)

/** 200 body of `POST /api/plants/:id/diagnose`: 1–3 causes and a summary. */
@Serializable
data class DiagnosisResponse(
    val causes: List<DiagnosisCauseDto> = emptyList(),
    val summary: String = "",
)

/**
 * Client deadline for diagnose only (architecture §8.7). The server gives the
 * provider 60 s across its retries; every other call keeps [REQUEST_TIMEOUT_MILLIS].
 */
const val DIAGNOSE_TIMEOUT_MILLIS: Long = 75_000L
