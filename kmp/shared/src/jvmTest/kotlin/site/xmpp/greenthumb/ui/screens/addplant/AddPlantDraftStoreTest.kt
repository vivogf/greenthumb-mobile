package site.xmpp.greenthumb.ui.screens.addplant

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import site.xmpp.greenthumb.ui.screens.species.IdentifyCandidate
import site.xmpp.greenthumb.ui.screens.species.ScoreLevel

/** The one-slot hand-off from the smart camera to Add Plant (no bytes in navigation arguments). */
class AddPlantDraftStoreTest {
    private fun draft(name: String) = AddPlantDraft(
        candidate = IdentifyCandidate(name, null, ScoreLevel.High, null),
        photoJpeg = byteArrayOf(1, 2),
    )

    @Test
    fun takeReturnsTheDraftOnce() {
        val store = AddPlantDraftStore()
        val d = draft("Monstera deliciosa")
        val token = store.put(d)
        assertSame(d, store.take(token))
        assertNull(store.take(token))
    }

    @Test
    fun unknownTokenGivesNull() {
        val store = AddPlantDraftStore()
        store.put(draft("A a"))
        assertNull(store.take("nope"))
    }

    @Test
    fun aNewDraftReplacesTheOldOne() {
        val store = AddPlantDraftStore()
        val first = store.put(draft("A a"))
        val second = store.put(draft("B b"))
        assertNotEquals(first, second)
        assertNull(store.take(first))
        assertEquals("B b", store.take(second)?.candidate?.scientificName)
    }

    @Test
    fun draftFieldsPrefillSpeciesAndPhoto() {
        val fields = AddPlantDraft.initialFields(draft("Monstera deliciosa"), lastWateredDate = "2026-10-30", language = "en")
        assertEquals("Monstera deliciosa", fields.speciesName)
        assertEquals("Monstera deliciosa", fields.name)
        assertEquals("2026-10-30", fields.lastWateredDate)
        assertEquals(true, fields.photoUrl.startsWith("data:image/jpeg;base64,"))
    }
}
