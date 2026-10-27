package site.xmpp.greenthumb.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One care journal entry (`CareEventRow`, `server/journal/eventsRoute.ts`).
 * `type` stays a wire string: an unknown future type must not break decoding;
 * the screen shows it under a generic label.
 */
@Serializable
public data class CareEventDto(
    val id: String,
    @SerialName("plant_id")
    val plantId: String,
    @SerialName("user_id")
    @Serializable(with = StringOrNumberAsStringSerializer::class)
    val userId: String,
    val type: String,
    @SerialName("occurred_on")
    val occurredOn: String,
    val note: String? = null,
    @SerialName("photo_id")
    val photoId: String? = null,
    @SerialName("created_at")
    val createdAt: String,
)

/** `GET /api/plants/:id/events` page, newest first. */
@Serializable
public data class CareEventsPage(
    val events: List<CareEventDto>,
    val nextCursor: String? = null,
)

/** Wire values of [CareEventDto.type]. Only [NOTE] is created or deleted by the client. */
public object CareEventType {
    public const val WATER: String = "water"
    public const val FERTILIZE: String = "fertilize"
    public const val REPOT: String = "repot"
    public const val PRUNE: String = "prune"
    public const val NOTE: String = "note"
    public const val PHOTO: String = "photo"
}

/** Server limit of a note (`careEvents.ts`). */
public const val CARE_NOTE_MAX_LENGTH: Int = 1000

/** Largest page the server accepts. */
public const val CARE_EVENTS_PAGE_LIMIT: Int = 100
