package site.xmpp.greenthumb.core.platform

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * jvm-actual кропа: квадратный регион → ровно 800×800 JPEG (RN-паритет
 * `manipulateAsync resize 800×800 compress 0.8`), EXIF-поворот применяется
 * ДО вырезания (VAL-PHOTO-001 метрики выходного изображения).
 */
class CropSquareJpegJvmTest {

    private fun paintedJpeg(width: Int, height: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = Color.RED
        g.fillRect(0, 0, width / 2, height)
        g.color = Color.BLUE
        g.fillRect(width / 2, 0, width - width / 2, height)
        g.dispose()
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "jpg", out)
        return out.toByteArray()
    }

    private fun withExifOrientation(jpeg: ByteArray, orientation: Int): ByteArray {
        val tiff = ArrayList<Byte>()
        fun b(v: Int) = tiff.add(v.toByte())
        listOf(0x49, 0x49, 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00).forEach(::b)
        listOf(0x01, 0x00).forEach(::b)
        listOf(0x12, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00).forEach(::b)
        listOf(orientation and 0xFF, 0x00, 0x00, 0x00).forEach(::b)
        listOf(0x00, 0x00, 0x00, 0x00).forEach(::b)
        val payload = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0x00, 0x00) + tiff.toByteArray()
        val segment = ArrayList<Byte>()
        segment.add(0xFF.toByte())
        segment.add(0xE1.toByte())
        segment.add(((payload.size + 2) shr 8).toByte())
        segment.add(((payload.size + 2) and 0xFF).toByte())
        payload.forEach(segment::add)
        // Вставка сразу после SOI.
        val head = jpeg.copyOfRange(0, 2)
        val tail = jpeg.copyOfRange(2, jpeg.size)
        return head + segment.toByteArray() + tail
    }

    private fun colorAt(jpeg: ByteArray, x: Int, y: Int): Triple<Int, Int, Int> {
        val image = ImageIO.read(jpeg.inputStream())
        val argb = image.getRGB(x, y)
        return Triple((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)
    }

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

        val (rTop, _, bTop) = colorAt(result, 400, 60)
        assertTrue(rTop > 150 && rTop > bTop, "верх после поворота — красный (сырая левая половина), got ($rTop,$bTop)")
        val (rBottom, _, bBottom) = colorAt(result, 400, 740)
        assertTrue(bBottom > 150 && bBottom > rBottom, "низ после поворота — синий, got ($rBottom,$bBottom)")
    }

    @Test
    fun undecodableBytesThrow() {
        assertFailsWith<IllegalArgumentException> {
            cropSquareJpeg(byteArrayOf(1, 2, 3, 4), CropRect(0f, 0f, 1f, 1f))
        }
    }
}
