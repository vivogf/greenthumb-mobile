package site.xmpp.greenthumb.ui.screens.plantdetail

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The «Отменить» bar of a care mark: 10 s for everyone (the owner's tester could not reach 5 s), and no timer at
 * all with a screen reader on — the bar gains a close button and waits for the user.
 */
class CareUndoDurationTest {

    @Test
    fun withoutAScreenReaderTheBarClosesAfterTenSeconds() {
        assertEquals(10_000L, CARE_UNDO_MILLIS)
        assertEquals(CARE_UNDO_MILLIS, careUndoTimeoutMillis(screenReaderOn = false))
    }

    @Test
    fun withAScreenReaderTheBarWaitsForTheUser() {
        assertNull(careUndoTimeoutMillis(screenReaderOn = true), "no timer: it stays until the user acts or closes it")
        assertTrue(CARE_UNDO_MILLIS >= 10_000L, "5 s was too short for the owner's tester")
    }
}
