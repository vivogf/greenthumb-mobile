package site.xmpp.greenthumb.ui.screens.smartcam

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import site.xmpp.greenthumb.core.platform.CameraShutter
import site.xmpp.greenthumb.core.platform.CameraStatus
import site.xmpp.greenthumb.core.platform.PickResult

/**
 * VAL-CAM12-006/007 without UI: pinch zoom runs from 1× to min(device maximum, 4×), the «N.N×» label shows while
 * zoom ≠ 1×, the label and a double tap return to 1×, zoom stays across mode switch and «Переснять», a reopened
 * camera starts at 1×, and the selected ratio is the one the camera has when the shot is taken.
 */
class SmartCameraZoomTest {

    private class Fakes {
        val applied = mutableListOf<Float>()
        val shutter = CameraShutter()
        val zoomAtShot = mutableListOf<Float>()
        var gate: CompletableDeferred<Unit>? = null

        init {
            shutter.attach { _ ->
                zoomAtShot += shutter.zoomRatio
                gate?.await()
                PickResult.Picked(byteArrayOf(1, 2), capturePath = "/cache/gt-pick/gt-diag-${zoomAtShot.size}.jpg")
            }
        }

        fun controller(mode: SmartCameraMode = SmartCameraMode.Plant) = SmartCameraController(
            initialMode = mode,
            identifier = null,
            diagnoser = null,
            pick = { PickResult.Cancelled },
            preparePlant = { it },
            prepareDisease = { it },
            catalog = { null },
            capture = { flash -> shutter.take(flash) },
            applyZoom = { ratio ->
                applied += ratio
                shutter.zoomRatio = ratio
            },
        )
    }

    private fun SmartCameraController.ready(maxZoom: Float) = onCameraStatus(CameraStatus.Ready(hasFlash = false, maxZoom = maxZoom))

    @Test
    fun zoomIsClampedToOneAndTheSmallerOfTheDeviceMaximumAndFour() {
        val f = Fakes()
        val c = f.controller()
        c.ready(maxZoom = 10f)
        assertEquals(4f, c.zoomLimit)
        c.zoomBy(10f)
        assertEquals(4f, c.zoom, "never above 4×")
        c.zoomBy(0.01f)
        assertEquals(1f, c.zoom, "never below 1×")
        c.ready(maxZoom = 2.5f)
        assertEquals(2.5f, c.zoomLimit, "a weaker camera caps at its own maximum")
        c.zoomBy(3f)
        assertEquals(2.5f, c.zoom)
        // The camera reports a smaller maximum later (another lens): the zoom follows it down.
        c.ready(maxZoom = 2f)
        assertEquals(2f, c.zoom)
        assertEquals(2f, f.applied.last(), "the clamped ratio reaches the camera")
    }

    @Test
    fun aCameraWithoutZoomOrNotReadyIgnoresPinches() {
        val f = Fakes()
        val c = f.controller()
        c.zoomBy(2f)
        assertEquals(1f, c.zoom, "no zoom before the camera is ready")
        c.ready(maxZoom = 1f)
        assertEquals(1f, c.zoomLimit)
        c.zoomBy(2f)
        assertEquals(1f, c.zoom)
        assertNull(c.zoomLabel)
        c.onCameraStatus(CameraStatus.Unavailable)
        c.zoomBy(2f)
        assertEquals(1f, c.zoom)
    }

    @Test
    fun pinchStepsMultiplyAndTheLabelShowsOneDecimalWhileNotAtOne() {
        val f = Fakes()
        val c = f.controller()
        c.ready(maxZoom = 8f)
        assertNull(c.zoomLabel, "no label at 1×")
        c.zoomBy(1.2f)
        c.zoomBy(1.25f)
        assertEquals(1.5f, c.zoom, 0.0001f)
        assertEquals("1.5×", c.zoomLabel)
        c.zoomBy(2f)
        assertEquals("3.0×", c.zoomLabel)
        c.zoomBy(1.0f / 3f)
        assertNull(c.zoomLabel, "back at 1× the label goes away")
    }

    @Test
    fun theLabelTapAndADoubleTapResetToOne() {
        val f = Fakes()
        val c = f.controller()
        c.ready(maxZoom = 4f)
        c.zoomBy(2f)
        assertEquals("2.0×", c.zoomLabel)
        c.resetZoom()
        assertEquals(1f, c.zoom)
        assertNull(c.zoomLabel)
        assertEquals(1f, f.shutter.zoomRatio)
    }

    @Test
    fun pinchDoesNotSwitchTheMode() {
        val f = Fakes()
        val c = f.controller()
        c.ready(maxZoom = 4f)
        c.zoomBy(2f)
        c.zoomBy(0.7f)
        assertEquals(SmartCameraMode.Plant, c.mode)
    }

    @Test
    fun zoomStaysAcrossModeSwitchAndRetakeAndAReopenedCameraStartsAtOne() = runTest {
        val f = Fakes()
        val c = f.controller()
        c.ready(maxZoom = 4f)
        c.zoomBy(2f)
        c.selectMode(SmartCameraMode.Disease)
        assertEquals(2f, c.zoom, "mode switch keeps the zoom")
        c.shutter(this)
        advanceUntilIdle()
        assertEquals(SmartCameraPhase.Captured, c.phase)
        c.retake()
        assertEquals(2f, c.zoom, "«Переснять» keeps the zoom")
        // The viewfinder binds the camera again and reports it; the kept ratio is applied once more.
        c.onCameraStatus(CameraStatus.Starting)
        c.ready(maxZoom = 4f)
        assertEquals(2f, f.shutter.zoomRatio)
        c.close()

        val reopened = Fakes()
        val again = reopened.controller(SmartCameraMode.Disease)
        again.ready(maxZoom = 4f)
        assertEquals(1f, again.zoom, "a reopened camera starts at 1×")
        assertEquals(1f, reopened.shutter.zoomRatio)
    }

    @Test
    fun theSelectedRatioIsTheOneTheCameraHasWhenTheShotIsTaken() = runTest {
        val f = Fakes()
        val c = f.controller()
        c.ready(maxZoom = 4f)
        c.shutter(this)
        advanceUntilIdle()
        c.retake()
        c.zoomBy(2f)
        c.shutter(this)
        advanceUntilIdle()
        assertEquals(listOf(1f, 2f), f.zoomAtShot)
    }

    @Test
    fun zoomDoesNotChangeWhileAShotIsPendingOrOnTheAfterShotScreen() = runTest {
        val f = Fakes()
        val c = f.controller()
        c.ready(maxZoom = 4f)
        c.zoomBy(1.5f)
        f.gate = CompletableDeferred()
        c.shutter(this)
        runCurrent()
        c.zoomBy(2f)
        c.resetZoom()
        assertEquals(1.5f, c.zoom, "the pending shot keeps its ratio")
        f.gate!!.complete(Unit)
        advanceUntilIdle()
        c.zoomBy(2f)
        assertEquals(1.5f, c.zoom, "no zoom on the after-shot screen")
        assertEquals(listOf(1.5f), f.zoomAtShot)
    }
}
