package site.xmpp.greenthumb.core.platform

import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * jvm-actual кропа: квадратный регион → ровно 800×800 JPEG (RN-паритет
 * `manipulateAsync resize 800×800 compress 0.8`), EXIF-поворот применяется
 * ДО вырезания (VAL-PHOTO-001 метрики выходного изображения). Фикстуры
 * paintedJpeg/withExifOrientation/colorAt живут в JpegFixtures.kt (общие
 * с ResizeJpegJvmTest).
 */
class CropSquareJpegJvmTest {

    @Test
    fun cropsSquareRegionToExact800Jpeg() {
        val source = paintedJpeg(1200, 900)
        // Квадрат 900×900 справа: нормализованный прямоугольник как из кроп-экрана.
        val rect = CropRect(0.25f, 0f, 1f, 1f)
        val result = cropSquareJpeg(source, rect)

        val decoded = ImageIO.read(result.inputStream())
        assertEquals(800, decoded.width, "ширина ровно 800 (RN resize 800×800)")
        assertEquals(800, decoded.height, "высота ровно 800")
        assertTrue(decoded.width <= 800 && decoded.height <= 800, "VAL-PHOTO-001: ≤800px")
        assertTrue(
            result[0] == 0xFF.toByte() && result[1] == 0xD8.toByte(),
            "контракт — JPEG (SOI-маркер)",
        )

        // Регион x∈[300,1200]: слева от 600 — красный, справа — синий.
        val (r1, g1, b1) = colorAt(result, 40, 400)
        assertTrue(r1 > 150 && r1 > b1, "левая часть региона должна быть красной, got ($r1,$g1,$b1)")
        val (r2, g2, b2) = colorAt(result, 760, 400)
        assertTrue(b2 > 150 && b2 > r2, "правая часть региона должна быть синей, got ($r2,$g2,$b2)")
    }

    @Test
    fun exifRotation6IsNormalizedBeforeCrop() {
        // Сырой кадр 400×300 (левая половина красная), EXIF orientation 6
        // (поворот 90° CW при показе): на дисплее красное сверху.
        val source = withExifOrientation(paintedJpeg(400, 300), 6)
        val result = cropSquareJpeg(source, CropRect(0f, 0f, 1f, 1f))

        val decoded = ImageIO.read(result.inputStream())
        assertEquals(800, decoded.width)
        assertEquals(800, decoded.height)
        assertEquals(1, jpegExifOrientation(result), "выход нормализован: APP1-ориентации нет")

        val (rTop, _, bTop) = colorAt(result, 400, 60)
        assertTrue(rTop > 150 && rTop > bTop, "верх после поворота — красный (сырая левая половина), got ($rTop,$bTop)")
        val (rBottom, _, bBottom) = colorAt(result, 400, 740)
        assertTrue(bBottom > 150 && bBottom > rBottom, "низ после поворота — синий, got ($rBottom,$bBottom)")
    }

    @Test
    fun exifRotation8IsNormalizedBeforeCrop() {
        // EXIF orientation 8 (поворот 270° CW при показе) — вторая фикстура
        // VAL-PHOTO-004: сырая левая половина уходит на НИЗ дисплейного кадра.
        val source = withExifOrientation(paintedJpeg(400, 300), 8)
        val result = cropSquareJpeg(source, CropRect(0f, 0f, 1f, 1f))

        val decoded = ImageIO.read(result.inputStream())
        assertEquals(800, decoded.width)
        assertEquals(800, decoded.height, "квадратный crop остаётся 800×800")
        assertEquals(1, jpegExifOrientation(result), "выход нормализован: APP1-ориентации нет")

        val (rTop, _, bTop) = colorAt(result, 400, 60)
        assertTrue(bTop > 150 && bTop > rTop, "верх после 270° — синий, got ($rTop,$bTop)")
        val (rBottom, _, bBottom) = colorAt(result, 400, 740)
        assertTrue(rBottom > 150 && rBottom > bBottom, "низ после 270° — красный (сырая левая половина), got ($rBottom,$bBottom)")
    }

    @Test
    fun undecodableBytesThrow() {
        assertFailsWith<UnsupportedImageException> {
            cropSquareJpeg(byteArrayOf(1, 2, 3, 4), CropRect(0f, 0f, 1f, 1f))
        }
    }
}
