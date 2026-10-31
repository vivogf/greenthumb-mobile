package site.xmpp.greenthumb.ui.screens.smartcam

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import site.xmpp.greenthumb.core.platform.CameraStatus
import site.xmpp.greenthumb.core.platform.HapticEvent
import site.xmpp.greenthumb.core.platform.HapticSink
import site.xmpp.greenthumb.core.platform.Haptics
import site.xmpp.greenthumb.core.platform.PickResult

/**
 * VAL-CAM12-001/002 without UI: an accepted shutter tap gives exactly one short haptic through [Haptics] and turns
 * the shot feedback on at once, before the camera delivers the photo. Duplicate taps, mode switch, «Переснять»,
 * gallery picks and a camera that is not ready add no shutter haptic.
 */
class SmartCameraShutterFeedbackTest {

    private val events = mutableListOf<HapticEvent>()
    private lateinit var original: HapticSink

    @BeforeTest
    fun recordHaptics() {
        original = Haptics.sink
        Haptics.sink = HapticSink { events += it }
    }

    @AfterTest
    fun restoreHaptics() {
        Haptics.sink = original
    }

    private var gate: CompletableDeferred<PickResult>? = null
    private var shots = 0

    private fun controller(mode: SmartCameraMode) = SmartCameraController(
        initialMode = mode,
        identifier = null,
        diagnoser = null,
        pick = { PickResult.Picked(byteArrayOf(3)) },
        preparePlant = { it },
        prepareDisease = { it },
        catalog = { null },
        capture = { _ ->
            shots++
            gate?.await() ?: PickResult.Picked(byteArrayOf(1), capturePath = "/cache/gt-pick/gt-diag-$shots.jpg")
        },
    )

    @Test
    fun oneShortHapticPerAcceptedShotInEachMode() = runTest {
        for (mode in SmartCameraMode.entries) {
            events.clear()
            val c = controller(mode)
            c.onCameraStatus(CameraStatus.Ready(hasFlash = false))
            c.shutter(this)
            advanceUntilIdle()
            assertEquals(listOf(HapticEvent.Medium), events, "$mode: one shutter haptic")
            c.retake()
            c.shutter(this)
            advanceUntilIdle()
            assertEquals(listOf(HapticEvent.Medium, HapticEvent.Medium), events, "$mode: one more for the second shot")
        }
    }

    @Test
    fun duplicateTapsWhileTheShotIsPendingAddNoHaptic() = runTest {
        val c = controller(SmartCameraMode.Plant)
        c.onCameraStatus(CameraStatus.Ready(hasFlash = false))
        gate = CompletableDeferred()
        c.shutter(this)
        c.shutter(this)
        runCurrent()
        c.shutter(this)
        assertEquals(1, events.size, "one haptic for three taps")
        assertEquals(1, shots, "one capture")
        gate!!.complete(PickResult.Picked(byteArrayOf(1), capturePath = "/cache/gt-pick/gt-diag-1.jpg"))
        advanceUntilIdle()
        assertEquals(1, events.size)
    }

    @Test
    fun modeSwitchRetakeGalleryAndANotReadyCameraGiveNoShutterHaptic() = runTest {
        val c = controller(SmartCameraMode.Plant)
        c.shutter(this)
        advanceUntilIdle()
        assertTrue(events.isEmpty(), "camera not ready: no shot, no haptic")
        c.onCameraStatus(CameraStatus.Ready(hasFlash = false))
        c.selectMode(SmartCameraMode.Disease)
        c.swipeMode(dragX = 120f)
        c.chooseFromGallery(this)
        advanceUntilIdle()
        c.retake()
        assertTrue(events.isEmpty(), "no shutter haptic without a shot: $events")
    }

    @Test
    fun theFeedbackIsOnBeforeTheCaptureCompletesAndOffWithThePhoto() = runTest {
        val c = controller(SmartCameraMode.Disease)
        c.onCameraStatus(CameraStatus.Ready(hasFlash = false))
        assertFalse(c.shotPending)
        gate = CompletableDeferred()
        c.shutter(this)
        // Synchronously on the tap, before any coroutine runs: the screen can react in the same frame.
        assertTrue(c.shotPending, "feedback starts on the tap")
        assertEquals(1, events.size, "the haptic is on the tap too")
        runCurrent()
        assertTrue(c.shotPending, "still pending while the camera works")
        assertEquals(SmartCameraPhase.Viewfinder, c.phase)
        gate!!.complete(PickResult.Picked(byteArrayOf(1), capturePath = "/cache/gt-pick/gt-diag-1.jpg"))
        advanceUntilIdle()
        assertFalse(c.shotPending)
        assertEquals(SmartCameraPhase.Captured, c.phase)
    }

    @Test
    fun aCancelledOrFailedShotEndsTheFeedbackOnTheViewfinder() = runTest {
        val c = controller(SmartCameraMode.Plant)
        c.onCameraStatus(CameraStatus.Ready(hasFlash = false))
        gate = CompletableDeferred()
        c.shutter(this)
        runCurrent()
        gate!!.complete(PickResult.Cancelled)
        advanceUntilIdle()
        assertFalse(c.shotPending)
        assertEquals(SmartCameraPhase.Viewfinder, c.phase)
        gate = CompletableDeferred()
        c.shutter(this)
        runCurrent()
        gate!!.completeExceptionally(IllegalStateException("camera error"))
        advanceUntilIdle()
        assertFalse(c.shotPending)
        assertEquals(SmartCameraPhase.Viewfinder, c.phase)
        assertEquals(2, events.size, "each accepted tap had its haptic")
    }

    @Test
    fun closingDuringAPendingShotEndsTheFeedback() = runTest {
        val c = controller(SmartCameraMode.Plant)
        c.onCameraStatus(CameraStatus.Ready(hasFlash = false))
        gate = CompletableDeferred()
        c.shutter(this)
        runCurrent()
        c.close()
        assertFalse(c.shotPending)
        gate!!.complete(PickResult.Cancelled)
        advanceUntilIdle()
    }
}
