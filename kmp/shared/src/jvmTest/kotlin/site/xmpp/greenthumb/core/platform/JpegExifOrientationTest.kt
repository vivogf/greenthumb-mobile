package site.xmpp.greenthumb.core.platform

import kotlin.test.Test
import kotlin.test.assertEquals

class JpegExifOrientationTest {

    /** Минимальный JPEG: SOI + APP1(EXIF, IFD0 tag 0x0112) + EOI. */
    private fun buildJpeg(orientation: Int, little: Boolean = true): ByteArray {
        val tiff = ArrayList<Byte>()
        fun b(v: Int) {
            tiff.add(v.toByte())
        }

        if (little) {
            // II, magic 42, offset 8
            listOf(0x49, 0x49, 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00).forEach(::b)
            // IFD0: 1 entry
            listOf(0x01, 0x00).forEach(::b)
            // entry: tag 0x0112, type SHORT(3), count 1, value (LE)
            listOf(0x12, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00)
                .forEach(::b)
            listOf(orientation and 0xFF, (orientation shr 8) and 0xFF, 0x00, 0x00).forEach(::b)
            // next IFD offset
            listOf(0x00, 0x00, 0x00, 0x00).forEach(::b)
        } else {
            // MM, magic 42, offset 8
            listOf(0x4D, 0x4D, 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08).forEach(::b)
            listOf(0x00, 0x01).forEach(::b)
            listOf(0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01)
                .forEach(::b)
            listOf(0x00, orientation and 0xFF, 0x00, 0x00).forEach(::b)
            listOf(0x00, 0x00, 0x00, 0x00).forEach(::b)
        }

        val exifHeader = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0x00, 0x00) // "Exif\0\0"
        val payload = exifHeader + tiff.toByteArray()
        val length = payload.size + 2

        val out = ArrayList<Byte>()
        listOf(0xFF, 0xD8).forEach { out.add(it.toByte()) } // SOI
        out.add(0xFF.toByte())
        out.add(0xE1.toByte()) // APP1
        out.add((length shr 8).toByte())
        out.add((length and 0xFF).toByte())
        payload.forEach(out::add)
        listOf(0xFF, 0xD9).forEach { out.add(it.toByte()) } // EOI
        return out.toByteArray()
    }

    @Test
    fun readsAllOrientationsLittleEndian() {
        for (orientation in 1..8) {
            assertEquals(orientation, jpegExifOrientation(buildJpeg(orientation)), "orientation=$orientation")
        }
    }

    @Test
    fun readsAllOrientationsBigEndian() {
        for (orientation in 1..8) {
            assertEquals(
                orientation,
                jpegExifOrientation(buildJpeg(orientation, little = false)),
                "orientation=$orientation MM",
            )
        }
    }

    @Test
    fun garbageAndMissingExifFallBackToOne() {
        assertEquals(1, jpegExifOrientation(ByteArray(0)))
        assertEquals(1, jpegExifOrientation(byteArrayOf(0x00, 0x01, 0x02)))
        // SOI + обрезанный сегмент (длина заявляет больше, чем есть).
        assertEquals(1, jpegExifOrientation(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(), 0x7F, 0xFF.toByte())))
        // SOI + APP1 с не-EXIF payload + EOI.
        val nonExif = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),
            0xFF.toByte(), 0xE1.toByte(), 0x00, 0x08,
            0x68, 0x74, 0x74, 0x70, 0xFF.toByte(), 0xD9.toByte(),
        )
        assertEquals(1, jpegExifOrientation(nonExif))
        // Без APP1 вовсе.
        assertEquals(1, jpegExifOrientation(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())))
    }

    @Test
    fun outOfRangeValueFallsBackToOne() {
        assertEquals(1, jpegExifOrientation(buildJpeg(9)))
        assertEquals(1, jpegExifOrientation(buildJpeg(0)))
    }
}
