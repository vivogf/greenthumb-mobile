package site.xmpp.greenthumb.ui.components

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import site.xmpp.greenthumb.core.platform.CropRect
import site.xmpp.greenthumb.core.platform.PickResult
import site.xmpp.greenthumb.core.platform.PickSource
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Обёртки результата пикера различаются (Stage 8 п.1, expectedBehavior #3):
 * Picked / Cancelled / PermissionDenied не смешиваются; отмена молчит,
 * отказ — алерт (deniedSource), успешный выбор идёт через кроп.
 */
class PhotoPickerFlowTest {

    private class Harness {
        val picked = mutableListOf<ByteArray>()
        var cropCalls = 0
        var lastCropRect: CropRect? = null
        val raw = byteArrayOf(1, 2, 3)
        val cropped = byteArrayOf(9, 9)

        fun controller(result: suspend (PickSource) -> PickResult): PhotoPickerController =
            PhotoPickerController(
                pick = result,
                crop = { _, rect ->
                    cropCalls++
                    lastCropRect = rect
                    cropped
                },
                onPicked = { picked += it },
            )
    }

    private fun TestScope.launchOn(
        controller: PhotoPickerController,
        source: PickSource = PickSource.Gallery,
    ) {
        controller.launch(backgroundScope, source)
        // TestScope-диспетчер: докручиваем запущенную корутину до suspensions.
        runCurrent()
    }

    @Test
    fun cancelledPickerIsSilent() = runTest {
        val h = Harness()
        val controller = h.controller { PickResult.Cancelled }
        launchOn(controller)
        assertNull(controller.cropping, "после отмены кроп не активен")
        assertNull(controller.deniedSource, "отмена — не отказ в разрешении")
        assertNull(controller.pickErrorMessage)
        assertEquals(0, h.cropCalls, "кроп не запускается")
        assertEquals(0, h.picked.size, "onPicked не вызывается — отмена молчит")
    }

    @Test
    fun permissionDeniedSetsSourceNotPicked() = runTest {
        val h = Harness()
        val controller = h.controller { PickResult.PermissionDenied(PickSource.Camera) }
        launchOn(controller, PickSource.Camera)
        assertEquals(PickSource.Camera, controller.deniedSource, "отказ виден вызывающему коду")
        assertNull(controller.cropping)
        assertEquals(0, h.picked.size, "onPicked при отказе не вызывается")
        assertEquals(0, h.cropCalls)
    }

    @Test
    fun pickedRunsCropAndDeliversBytes() = runTest {
        val h = Harness()
        val controller = h.controller { PickResult.Picked(h.raw) }
        launchOn(controller)
        assertContentEquals(h.raw, controller.cropping, "после выбора ждём кроп")
        assertNull(controller.deniedSource)

        val rect = CropRect(0.25f, 0.25f, 0.75f, 0.75f)
        controller.confirmCrop(rect)
        runCurrent()
        assertEquals(1, h.cropCalls, "кроп вызван ровно один раз")
        assertEquals(rect, h.lastCropRect, "в crop ушёл подтверждённый прямоугольник")
        assertEquals(1, h.picked.size, "onPicked ровно один раз")
        assertContentEquals(h.cropped, h.picked.single())
        assertNull(controller.cropping, "состояние кропа сброшено")
    }

    @Test
    fun cropCancellationIsSilent() = runTest {
        val h = Harness()
        val controller = h.controller { PickResult.Picked(h.raw) }
        launchOn(controller)
        assertNotNull(controller.cropping)
        controller.cancelCrop()
        runCurrent()
        assertNull(controller.cropping)
        assertEquals(0, h.cropCalls, "отмена кропа — без пиксельной обработки")
        assertEquals(0, h.picked.size, "отмена кропа молчит, как отмена пикера")
        assertNull(controller.deniedSource)
    }

    @Test
    fun pickerFailureSurfacesAsErrorNotCrash() = runTest {
        val h = Harness()
        val controller = h.controller { throw IllegalStateException("cannot decode") }
        launchOn(controller)
        assertEquals("cannot decode", controller.pickErrorMessage)
        assertEquals(0, h.picked.size)
        assertNull(controller.cropping)
        assertNull(controller.deniedSource, "ошибка ≠ отказ в разрешении")
    }

    @Test
    fun threeResultsAreDistinct() {
        // Типы различимы статически: смешать нельзя, nullable-возврат запрещён.
        val results: List<PickResult> = listOf(
            PickResult.Picked(byteArrayOf(0)),
            PickResult.Cancelled,
            PickResult.PermissionDenied(PickSource.Gallery),
        )
        assertEquals(3, results.map { it::class }.distinct().size)
    }
}
