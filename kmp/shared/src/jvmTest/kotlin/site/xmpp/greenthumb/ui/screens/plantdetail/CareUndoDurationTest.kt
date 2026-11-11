package site.xmpp.greenthumb.ui.screens.plantdetail

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The «Отменить» bar of a care mark: 10 s for everyone (the tester could not reach 5 s), 30 s with a screen
 * reader on. It stays finite on purpose: an indefinite bar has no close button.
 */
class CareUndoDurationTest {

    @Test
    fun theBarStaysTenSecondsWithoutAScreenReader() {
        assertEquals(10_000L, CARE_UNDO_MILLIS)
        assertEquals(CARE_UNDO_MILLIS, careUndoMillis(screenReaderOn = false))
    }

    @Test
    fun aScreenReaderGetsALongerBar_butStillFinite() {
        assertEquals(CARE_UNDO_SCREEN_READER_MILLIS, careUndoMillis(screenReaderOn = true))
        assertTrue(CARE_UNDO_SCREEN_READER_MILLIS > CARE_UNDO_MILLIS)
        assertTrue(CARE_UNDO_SCREEN_READER_MILLIS <= 60_000L, "still closes on its own")
    }
}
