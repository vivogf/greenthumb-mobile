package site.xmpp.greenthumb.ui.screens.plantdetail

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import site.xmpp.greenthumb.core.network.CareEventDto
import site.xmpp.greenthumb.core.network.CareEventType
import site.xmpp.greenthumb.core.network.TimelinePhotoDto

/** VAL-CARD-014: a `photo` journal entry finds its timeline photo (thumbnail + caption). */
class JournalPhotoJoinTest {
    private fun event(type: String, photoId: String?) = CareEventDto(
        id = "e-1",
        plantId = "p-1",
        userId = "73",
        type = type,
        occurredOn = "2026-10-30",
        photoId = photoId,
        createdAt = "2026-10-30T10:00:00.000Z",
    )

    private val photos = listOf(
        TimelinePhotoDto("ph-1", "https://example.test/1.jpg", "2026-10-30", "Plant check: Pests (Unlikely)", "2026-10-30T10:00:00.000Z"),
        TimelinePhotoDto("ph-2", "https://example.test/2.jpg", "2026-10-29", null, "2026-10-29T10:00:00.000Z"),
    )

    @Test
    fun photo_entry_gets_its_photo() {
        assertEquals(photos[0], JournalPhotoJoin.photoFor(event(CareEventType.PHOTO, "ph-1"), photos))
        assertEquals(photos[1], JournalPhotoJoin.photoFor(event(CareEventType.PHOTO, "ph-2"), photos))
    }

    @Test
    fun missing_or_foreign_photo_gives_null() {
        assertNull(JournalPhotoJoin.photoFor(event(CareEventType.PHOTO, "ph-9"), photos))
        assertNull(JournalPhotoJoin.photoFor(event(CareEventType.PHOTO, null), photos))
        assertNull(JournalPhotoJoin.photoFor(event(CareEventType.NOTE, "ph-1"), photos))
    }

    @Test
    fun caption_text_prefers_the_photo_caption_then_the_note() {
        assertEquals("Plant check: Pests (Unlikely)", JournalPhotoJoin.captionFor(event(CareEventType.PHOTO, "ph-1"), photos[0]))
        assertNull(JournalPhotoJoin.captionFor(event(CareEventType.PHOTO, "ph-2"), photos[1]))
        assertEquals("n", JournalPhotoJoin.captionFor(event(CareEventType.PHOTO, "ph-2").copy(note = "n"), photos[1]))
        assertEquals("n", JournalPhotoJoin.captionFor(event(CareEventType.NOTE, null).copy(note = "n"), null))
    }
}
