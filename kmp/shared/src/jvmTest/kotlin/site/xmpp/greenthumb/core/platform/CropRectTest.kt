package site.xmpp.greenthumb.core.platform

import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CropRectTest {

    @Test
    fun pixelRectMapsNormalizedFractions() {
        val rect = CropRect(0.25f, 0.25f, 0.75f, 0.75f).pixelRect(100, 200)
        assertEquals(25, rect.x)
        assertEquals(50, rect.y)
        assertEquals(50, rect.width)
        assertEquals(100, rect.height)
    }

    @Test
    fun pixelRectClampsOutOfRange() {
        val rect = CropRect(-1f, -1f, 2f, 2f).pixelRect(100, 50)
        assertEquals(0, rect.x)
        assertEquals(0, rect.y)
        assertEquals(100, rect.width)
        assertEquals(50, rect.height)
        assertTrue(rect.x + rect.width <= 100)
        assertTrue(rect.y + rect.height <= 50)
    }

    @Test
    fun pixelRectDegenerateIsAtLeastOnePixel() {
        val rect = CropRect(0.5f, 0.5f, 0.5f, 0.5f).pixelRect(100, 100)
        assertTrue(rect.width >= 1, "нулевая ширина запрещена")
        assertTrue(rect.height >= 1, "нулевая высота запрещена")
        assertTrue(rect.x + rect.width <= 100 && rect.y + rect.height <= 100)
    }

    @Test
    fun pixelRectAcceptsSwappedCorners() {
        val rect = CropRect(0.8f, 0.9f, 0.2f, 0.1f).pixelRect(100, 100)
        assertEquals(20, rect.x)
        assertEquals(10, rect.y)
        assertEquals(60, rect.width)
        assertEquals(80, rect.height)
    }

    @Test
    fun fromDisplayNormalizesIntersection() {
        // Картинка отрисована в (100,50)..(500,450); вьюпорт (200,100)..(400,300).
        val crop = CropRect.fromDisplay(
            displayed = Rect(100f, 50f, 500f, 450f),
            viewport = Rect(200f, 100f, 400f, 300f),
        )
        assertEquals(0.25f, crop.left, 1e-6f)
        assertEquals(0.125f, crop.top, 1e-6f)
        assertEquals(0.75f, crop.right, 1e-6f)
        assertEquals(0.625f, crop.bottom, 1e-6f)
        val px = crop.pixelRect(400, 400)
        assertEquals(100, px.x)
        assertEquals(50, px.y)
        assertEquals(200, px.width)
        assertEquals(200, px.height)
    }

    @Test
    fun fromDisplayWithoutIntersectionFallsBackToWholeImage() {
        val crop = CropRect.fromDisplay(
            displayed = Rect(0f, 0f, 100f, 100f),
            viewport = Rect(500f, 500f, 600f, 600f),
        )
        assertEquals(CropRect(0f, 0f, 1f, 1f), crop)
    }

    @Test
    fun photoDataUriRoundTrips() {
        val bytes = byteArrayOf(0x01, 0x02, 0xFF.toByte(), 0x7F)
        val uri = photoDataUri(bytes)
        assertTrue(uri.startsWith("data:image/jpeg;base64,"), uri.take(40))
        val decoded = java.util.Base64.getDecoder().decode(uri.substringAfter(","))
        assertTrue(decoded.contentEquals(bytes))
    }
}
