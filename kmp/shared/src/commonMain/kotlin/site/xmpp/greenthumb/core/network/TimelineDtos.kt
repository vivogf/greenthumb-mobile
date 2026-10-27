package site.xmpp.greenthumb.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One photo of a plant's timeline (`TimelinePhotoRow`, `server/journal/photosRoute.ts`). */
@Serializable
public data class TimelinePhotoDto(
    val id: String,
    val url: String,
    @SerialName("taken_on")
    val takenOn: String,
    val caption: String? = null,
    @SerialName("created_at")
    val createdAt: String,
)

/** `GET /api/plants/:id/photos`, newest first. */
@Serializable
public data class TimelinePhotosPage(
    val photos: List<TimelinePhotoDto>,
)

/** `POST /api/plants/:id/photos` → 201: the photo row plus its `photo` care event. */
@Serializable
public data class CreatedTimelinePhotoDto(
    val id: String,
    val url: String,
    @SerialName("taken_on")
    val takenOn: String,
    val caption: String? = null,
    @SerialName("created_at")
    val createdAt: String,
    val event: CareEventDto? = null,
) {
    public fun photo(): TimelinePhotoDto = TimelinePhotoDto(id, url, takenOn, caption, createdAt)
}

/** Server limit of a caption (`photoInput.ts`). */
public const val PHOTO_CAPTION_MAX_LENGTH: Int = 300

/** A photo upload carries up to a few hundred KB; the default 10 s deadline is too short on slow links. */
public const val PHOTO_UPLOAD_TIMEOUT_MILLIS: Long = 30_000L
