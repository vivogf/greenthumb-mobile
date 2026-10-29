package site.xmpp.greenthumb.core.platform

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * EXIF fixtures for the privacy guard: an APP1 `Exif` segment whose IFD0 holds `Make = GtTest` and a GPS IFD
 * (55°45'N 37°36'E), the same tags the device check writes with exiftool.
 */
internal const val GPS_FIXTURE_MAKE = "GtTest"

/** [jpeg] with an APP1 `Exif` segment (Make + GPS IFD) inserted right after SOI. */
internal fun withGpsExif(jpeg: ByteArray): ByteArray {
    val tiff = ByteBuffer.allocate(148).order(ByteOrder.LITTLE_ENDIAN)
    tiff.put(0x49).put(0x49).putShort(0x2A).putInt(8)
    // IFD0 at 8: Make (ASCII, 7 bytes at 38) and the GPS IFD pointer (at 46).
    tiff.putShort(2)
    tiff.putShort(0x010F).putShort(2).putInt(GPS_FIXTURE_MAKE.length + 1).putInt(38)
    tiff.putShort(0x8825.toShort()).putShort(4).putInt(1).putInt(46)
    tiff.putInt(0)
    tiff.put(GPS_FIXTURE_MAKE.encodeToByteArray()).put(0).put(0)
    // GPS IFD at 46: LatitudeRef N, Latitude (rationals at 100), LongitudeRef E, Longitude (rationals at 124).
    tiff.putShort(4)
    tiff.putShort(0x0001).putShort(2).putInt(2).put('N'.code.toByte()).put(0).put(0).put(0)
    tiff.putShort(0x0002).putShort(5).putInt(3).putInt(100)
    tiff.putShort(0x0003).putShort(2).putInt(2).put('E'.code.toByte()).put(0).put(0).put(0)
    tiff.putShort(0x0004).putShort(5).putInt(3).putInt(124)
    tiff.putInt(0)
    listOf(55, 45, 0, 37, 36, 0).forEach { tiff.putInt(it).putInt(1) }
    check(tiff.position() == 148)

    val payload = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0x00, 0x00) + tiff.array()
    val length = payload.size + 2
    val segment = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), (length and 0xFF).toByte()) + payload
    return jpeg.copyOfRange(0, 2) + segment + jpeg.copyOfRange(2, jpeg.size)
}

/** The GPS fixture: a painted [width]×[height] JPEG with Make and GPS in APP1 `Exif`. */
internal fun gpsExifJpeg(width: Int = 1600, height: Int = 1200): ByteArray = withGpsExif(paintedJpeg(width, height))

/** One JPEG segment before the scan data, e.g. `APP0`, `APP1:Exif`, `DQT`, ending with `SOS`. */
internal fun jpegMarkerList(bytes: ByteArray): List<String> {
    val names = mutableListOf<String>()
    if (bytes.size < 2 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) return listOf("NOT_JPEG")
    names += "SOI"
    var offset = 2
    while (offset + 4 <= bytes.size) {
        if (bytes[offset] != 0xFF.toByte()) return names + "BROKEN@$offset"
        val marker = bytes[offset + 1].toInt() and 0xFF
        if (marker == 0xDA) return names + "SOS"
        val length = ((bytes[offset + 2].toInt() and 0xFF) shl 8) or (bytes[offset + 3].toInt() and 0xFF)
        names += when (marker) {
            in 0xE0..0xEF -> {
                val header = bytes.copyOfRange(offset + 4, minOf(offset + 2 + length, offset + 10)).decodeToString()
                "APP${marker - 0xE0}" + if (header.startsWith("Exif")) ":Exif" else ""
            }
            0xDB -> "DQT"
            0xC4 -> "DHT"
            0xC0, 0xC1, 0xC2 -> "SOF${marker - 0xC0}"
            0xDD -> "DRI"
            0xFE -> "COM"
            else -> "0x%02X".format(marker)
        }
        offset += 2 + length
    }
    return names
}

/** True when the bytes still carry the GPS IFD pointer tag 0x8825 or the fixture's Make value anywhere. */
internal fun carriesGpsOrMake(bytes: ByteArray): Boolean {
    val make = GPS_FIXTURE_MAKE.encodeToByteArray()
    val gpsTagLe = byteArrayOf(0x25, 0x88.toByte(), 0x04, 0x00)
    return indexOf(bytes, make) >= 0 || (jpegMarkerList(bytes).any { it == "APP1:Exif" } && indexOf(bytes, gpsTagLe) >= 0)
}

private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
    outer@ for (i in 0..haystack.size - needle.size) {
        for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
        return i
    }
    return -1
}
