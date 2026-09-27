package site.xmpp.greenthumb.core.platform

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Валидация входа jvm-пикера (Stage 8 п.2): до показа экрана кропа файл
 * должен быть декодируем; HEIC-ридер в imageio нет — тот же обработанный
 * отказ, что на Android API 24–25 (без системного HEIF-декодера).
 */
class PickValidationJvmTest {

    @Test
    fun jpegIsAcceptedForCropping() {
        assertTrue(canDecodeImage(paintedJpeg(320, 240)), "обычный JPEG проходит валидацию")
    }

    @Test
    fun heicIsRejectedWithoutCrash() {
        assertFalse(canDecodeImage(heicLikeBytes()), "HEIC без ридера → отказ, не краш")
    }

    @Test
    fun garbageIsRejectedWithoutCrash() {
        assertFalse(canDecodeImage(byteArrayOf(1, 2, 3, 4)), "мусор → отказ, не краш")
    }
}
