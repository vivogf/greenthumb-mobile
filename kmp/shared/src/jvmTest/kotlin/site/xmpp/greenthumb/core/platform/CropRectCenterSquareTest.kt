package site.xmpp.greenthumb.core.platform

import kotlin.test.Test
import kotlin.test.assertEquals

class CropRectCenterSquareTest {
    @Test
    fun landscapeKeepsTheMiddleSquare() {
        assertEquals(CropRect(0.25f, 0f, 0.75f, 1f), CropRect.centerSquare(200, 100))
    }

    @Test
    fun portraitKeepsTheMiddleSquare() {
        assertEquals(CropRect(0f, 0.25f, 1f, 0.75f), CropRect.centerSquare(100, 200))
    }

    @Test
    fun squareAndInvalidSizesKeepEverything() {
        assertEquals(CropRect(0f, 0f, 1f, 1f), CropRect.centerSquare(50, 50))
        assertEquals(CropRect(0f, 0f, 1f, 1f), CropRect.centerSquare(0, 10))
    }
}
