package site.xmpp.greenthumb.ui.screens.plantdetail

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The «Отменить» bar of a care mark: 3 s for everyone, and no timer at all with a screen reader on — the bar
 * gains a close button and waits for the user.
 *
 * 3 s is the owner's explicit call (10 s covered the journal), and it is shorter than the 4 s one tester once
 * spent reaching the bar. The slow-user case rides on the screen-reader branch: with TalkBack on there is no
 * timer. A large-font or hesitant user without TalkBack does lose the offer — the owner accepted that trade.
 */
class CareUndoDurationTest {

    @Test
    fun withoutAScreenReaderTheBarClosesAfterThreeSeconds() {
        assertEquals(3_000L, CARE_UNDO_MILLIS)
        assertEquals(CARE_UNDO_MILLIS, careUndoTimeoutMillis(screenReaderOn = false))
    }

    @Test
    fun withAScreenReaderTheBarWaitsForTheUser() {
        assertNull(careUndoTimeoutMillis(screenReaderOn = true), "no timer: it stays until the user acts or closes it")
    }
}
