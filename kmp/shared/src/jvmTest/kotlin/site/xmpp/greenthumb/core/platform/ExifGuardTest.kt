package site.xmpp.greenthumb.core.platform

import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Privacy guard (architecture §9.7): no photo leaves the phone with an APP1 `Exif` segment (GPS, camera make).
 * The re-encoders write fresh JPEGs, and [photoDataUri] strips APP1 again before Base64.
 */
class ExifGuardTest {

    private val fixture = gpsExifJpeg()

    @Test
    fun fixtureCarriesExifGpsAndMake() {
        assertTrue("APP1:Exif" in jpegMarkerList(fixture), jpegMarkerList(fixture).toString())
        assertTrue(JpegSegments.hasExifApp1(fixture))
        assertTrue(carriesGpsOrMake(fixture))
        assertEquals(1600 to 1200, imageSize(fixture))
    }

    @Test
    fun resizeJpegDropsGps() {
        val out = resizeJpeg(fixture, maxSide = 1280, quality = 0.85)
        println("resizeJpeg markers: ${jpegMarkerList(out)}")
        assertFalse(JpegSegments.hasExifApp1(out))
        assertFalse(jpegMarkerList(out).any { it.startsWith("APP1") })
        assertFalse(carriesGpsOrMake(out))
        assertEquals(1280 to 960, imageSize(out))
    }

    @Test
    fun cropSquareJpegDropsGps() {
        val out = cropSquareJpeg(fixture, CropRect(0f, 0f, 1f, 1f))
        println("cropSquareJpeg markers: ${jpegMarkerList(out)}")
        assertFalse(JpegSegments.hasExifApp1(out))
        assertFalse(jpegMarkerList(out).any { it.startsWith("APP1") })
        assertFalse(carriesGpsOrMake(out))
        assertEquals(800 to 800, imageSize(out))
    }

    @Test
    fun photoDataUriStripsApp1() {
        val uri = photoDataUri(fixture)
        assertTrue(uri.startsWith("data:image/jpeg;base64,"))
        val sent = Base64.decode(uri.removePrefix("data:image/jpeg;base64,"))
        println("photoDataUri(raw fixture) markers: ${jpegMarkerList(sent)}")
        assertFalse(JpegSegments.hasExifApp1(sent))
        assertFalse(jpegMarkerList(sent).any { it.startsWith("APP1") })
        assertFalse(carriesGpsOrMake(sent))
    }

    @Test
    fun stripKeepsImageDecodable() {
        val stripped = JpegSegments.stripExifApp1(fixture)
        assertEquals(imageSize(fixture), imageSize(stripped))
        assertEquals(fixture.size - app1Length(fixture), stripped.size)
        assertEquals(jpegMarkerList(fixture) - "APP1:Exif", jpegMarkerList(stripped))
        assertEquals(colorAt(fixture, 100, 100), colorAt(stripped, 100, 100))
        assertEquals(colorAt(fixture, 1500, 1100), colorAt(stripped, 1500, 1100))
    }

    @Test
    fun noOpOnJpegWithoutApp1() {
        val plain = paintedJpeg(320, 240)
        assertFalse(JpegSegments.hasExifApp1(plain))
        assertSame(plain, JpegSegments.stripExifApp1(plain))
        assertContentEquals(plain, Base64.decode(photoDataUri(plain).removePrefix("data:image/jpeg;base64,")))
    }

    @Test
    fun xmpApp1IsDroppedToo() {
        val xmp = "http://ns.adobe.com/xap/1.0/\u0000<x:xmpmeta><exif:GPSLatitude>55,45N</exif:GPSLatitude></x:xmpmeta>"
            .encodeToByteArray()
        val length = xmp.size + 2
        val plain = paintedJpeg(64, 64)
        val tagged = plain.copyOfRange(0, 2) +
            byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), (length and 0xFF).toByte()) + xmp +
            plain.copyOfRange(2, plain.size)
        val stripped = JpegSegments.stripExifApp1(tagged)
        assertContentEquals(plain, stripped)
    }

    @Test
    fun nonJpegAndTruncatedInputsPassUnchanged() {
        val garbage = byteArrayOf(1, 2, 3, 4)
        assertSame(garbage, JpegSegments.stripExifApp1(garbage))
        assertFalse(JpegSegments.hasExifApp1(garbage))
        val truncated = fixture.copyOfRange(0, 10)
        assertFalse(JpegSegments.hasExifApp1(truncated))
        assertSame(truncated, JpegSegments.stripExifApp1(truncated))
        val empty = ByteArray(0)
        assertSame(empty, JpegSegments.stripExifApp1(empty))
    }

    @Test
    fun stripsEveryApp1WhenThereAreSeveral() {
        val twice = withGpsExif(fixture)
        assertEquals(2, jpegMarkerList(twice).count { it == "APP1:Exif" })
        val stripped = JpegSegments.stripExifApp1(twice)
        assertFalse(JpegSegments.hasExifApp1(stripped))
        assertEquals(imageSize(fixture), imageSize(stripped))
    }

    private fun app1Length(jpeg: ByteArray): Int {
        // The fixture puts APP1 right after SOI.
        return 2 + (((jpeg[4].toInt() and 0xFF) shl 8) or (jpeg[5].toInt() and 0xFF))
    }
}
