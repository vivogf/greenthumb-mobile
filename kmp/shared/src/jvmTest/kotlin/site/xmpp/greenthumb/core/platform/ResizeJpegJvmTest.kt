package site.xmpp.greenthumb.core.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.math.abs

/**
 * Stage 8 п.2 (VAL-PHOTO-004): resizeJpeg — пропорции, потолок 800 по
 * большей стороне, БЕЗ увеличения маленьких фото, качество ~0.8 и
 * нормализация EXIF-ориентации 90°/270°. Фикстуры — JpegFixtures.kt.
 */
class ResizeJpegJvmTest {

    @Test
    fun capsLongSideAt800AndPreservesAspect() {
        // 1600×1200 (4:3) → 800×600: большая сторона = 800, пропорция та же.
        val result = resizeJpeg(paintedJpeg(1600, 1200))
        assertEquals(800 to 600, imageSize(result), "пропорции сохранены, длинная сторона = 800")
        assertTrue(result[0] == 0xFF.toByte() && result[1] == 0xD8.toByte(), "выход — JPEG (SOI)")
    }

    @Test
    fun capsPortraitLongSideAt800() {
        // Портрет: вертикаль ограничивается, горизонталь масштабируется тем же коэффициентом.
        val result = resizeJpeg(paintedJpeg(1200, 1600))
        assertEquals(600 to 800, imageSize(result))
    }

    @Test
    fun doesNotUpscaleSmallPhoto() {
        // 400×300 < 800: размеры выхода совпадают с входом (фото не растягивается).
        val source = paintedJpeg(400, 300)
        val result = resizeJpeg(source)
        assertEquals(400 to 300, imageSize(result), "маленькое фото НЕ увеличивается")
        assertTrue(result.size > 0)
    }

    @Test
    fun respectsCustomMaxSide() {
        val result = resizeJpeg(paintedJpeg(1600, 900), maxSide = 400)
        assertEquals(400 to 225, imageSize(result), "maxSide параметризован, пропорция 16:9")
    }

    @Test
    fun exifRotation90IsNormalizedByResize() {
        // EXIF orientation 6 (90° CW): сырой 400×300 → дисплей 300×400,
        // красная половина сверху; выход — свежий JPEG без APP1 (ориентация 1).
        val source = withExifOrientation(paintedJpeg(400, 300), 6)
        val result = resizeJpeg(source)

        assertEquals(300 to 400, imageSize(result), "поворот меняет дисплейные пропорции")
        assertEquals(1, jpegExifOrientation(result), "выход нормализован: APP1-ориентации нет")

        val (rTop, _, bTop) = colorAt(result, 150, 40)
        assertTrue(rTop > 150 && rTop > bTop, "верх после 90° — красный (сырая левая половина), got ($rTop,$bTop)")
        val (rBottom, _, bBottom) = colorAt(result, 150, 360)
        assertTrue(bBottom > 150 && bBottom > rBottom, "низ после 90° — синий, got ($rBottom,$bBottom)")
    }

    @Test
    fun exifRotation270IsNormalizedByResize() {
        // EXIF orientation 8 (270° CW): сырая левая половина уходит на НИЗ.
        val source = withExifOrientation(paintedJpeg(400, 300), 8)
        val result = resizeJpeg(source)

        assertEquals(300 to 400, imageSize(result))
        assertEquals(1, jpegExifOrientation(result), "выход нормализован: APP1-ориентации нет")

        val (rTop, _, bTop) = colorAt(result, 150, 40)
        assertTrue(bTop > 150 && bTop > rTop, "верх после 270° — синий, got ($rTop,$bTop)")
        val (rBottom, _, bBottom) = colorAt(result, 150, 360)
        assertTrue(rBottom > 150 && rBottom > bBottom, "низ после 270° — красный, got ($rBottom,$bBottom)")
    }

    @Test
    fun largeRotatedSourceScalesInDisplaySpace() {
        // 1600×1200 с orientation 6 → дисплей 1200×1600 → выход 600×800:
        // ограничение применяется к дисплейным размерам после поворота.
        val source = withExifOrientation(paintedJpeg(1600, 1200), 6)
        val result = resizeJpeg(source)
        assertEquals(600 to 800, imageSize(result))
        assertEquals(1, jpegExifOrientation(result))
    }

    @Test
    fun jpegQualityIsAroundPoint8() {
        // Шум: качество заметно влияет на размер. Выход должен лежать между
        // перекодированием в 0.5 и 1.0 и быть близок к опорному 0.8 (±15%).
        val source = noisyJpeg(600, 600)
        val result = resizeJpeg(source)

        assertEquals(600 to 600, imageSize(result), "600×600 ≤ 800 — без изменения размеров")
        val atHalf = jpegSizeAtQuality(result, 0.5f)
        val atReference = jpegSizeAtQuality(result, 0.8f)
        val atFull = jpegSizeAtQuality(result, 1.0f)

        assertTrue(result.size > atHalf, "quality ~0.8 должен быть крупнее 0.5: ${result.size} ≤ $atHalf")
        assertTrue(result.size < atFull, "quality ~0.8 должен быть мельче 1.0: ${result.size} ≥ $atFull")
        assertTrue(
            abs(result.size - atReference) <= atReference * 0.15,
            "выход resizeJpeg ~quality 0.8: ${result.size} вне ±15% от опорного $atReference",
        )
    }

    @Test
    fun undecodableBytesThrowUnsupportedImageException() {
        assertFailsWith<UnsupportedImageException> {
            resizeJpeg(byteArrayOf(1, 2, 3, 4))
        }
    }

    @Test
    fun heicBytesFailGracefullyWithoutCrash() {
        // HEIC-байты на платформе без декодера (jvm: imageio; Android: API 24–25
        // BitmapFactory → null) — обработанная ошибка, не краш.
        val error = assertFailsWith<UnsupportedImageException> {
            resizeJpeg(heicLikeBytes())
        }
        assertTrue(error.message.orEmpty().isNotBlank(), "сообщение для алерта common.error")
    }
}
