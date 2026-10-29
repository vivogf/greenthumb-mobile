package site.xmpp.greenthumb.ui.screens.plantdetail

import site.xmpp.greenthumb.core.network.CareEventDto
import site.xmpp.greenthumb.core.network.CareEventType
import site.xmpp.greenthumb.core.network.TimelinePhotoDto

/** A `photo` journal entry and the timeline photo it points to (thumbnail + caption). */
internal object JournalPhotoJoin {
    fun photoFor(entry: CareEventDto, photos: List<TimelinePhotoDto>): TimelinePhotoDto? {
        if (entry.type != CareEventType.PHOTO) return null
        val id = entry.photoId ?: return null
        return photos.firstOrNull { it.id == id }
    }

    /** The photo caption, else the entry note; null when neither has text. */
    fun captionFor(entry: CareEventDto, photo: TimelinePhotoDto?): String? =
        photo?.caption?.takeIf { it.isNotBlank() } ?: entry.note?.takeIf { it.isNotBlank() }
}
