package site.xmpp.greenthumb.core.platform

/**
 * JPEG metadata guard (architecture §9.7): no photo leaves the phone with an APP1 segment. APP1 holds EXIF
 * (GPS, camera make, capture time) and XMP, which can repeat the same GPS data, so every APP1 is dropped.
 * Markers are walked from SOI to SOS; the scan data after SOS is copied unchanged.
 */
internal object JpegSegments {

    /** True when [bytes] is a JPEG with an APP1 segment that starts with `Exif\0\0`. */
    fun hasExifApp1(bytes: ByteArray): Boolean =
        app1Segments(bytes).any { range -> range.first + 10 <= range.last + 1 && startsWithExif(bytes, range.first + 4) }

    /** [bytes] without any APP1 segment; the same array when there is none or the input is not a JPEG. */
    fun stripExifApp1(bytes: ByteArray): ByteArray {
        val drop = app1Segments(bytes)
        if (drop.isEmpty()) return bytes
        val out = ByteArray(bytes.size - drop.sumOf { it.last - it.first + 1 })
        var from = 0
        var to = 0
        for (range in drop) {
            bytes.copyInto(out, to, from, range.first)
            to += range.first - from
            from = range.last + 1
        }
        bytes.copyInto(out, to, from, bytes.size)
        return out
    }

    private fun app1Segments(bytes: ByteArray): List<IntRange> {
        if (bytes.size < 4 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) return emptyList()
        val found = mutableListOf<IntRange>()
        var offset = 2
        while (offset + 4 <= bytes.size) {
            if (bytes[offset] != 0xFF.toByte()) return found
            val marker = bytes[offset + 1].toInt() and 0xFF
            when {
                // Fill byte before a marker.
                marker == 0xFF -> {
                    offset += 1
                    continue
                }
                marker == 0x00 || marker == 0x01 || marker in 0xD0..0xD8 -> {
                    offset += 2
                    continue
                }
                marker == 0xDA || marker == 0xD9 -> return found
            }
            val length = ((bytes[offset + 2].toInt() and 0xFF) shl 8) or (bytes[offset + 3].toInt() and 0xFF)
            if (length < 2 || offset + 2 + length > bytes.size) return found
            if (marker == 0xE1) found += offset until offset + 2 + length
            offset += 2 + length
        }
        return found
    }

    private fun startsWithExif(bytes: ByteArray, at: Int): Boolean =
        EXIF_HEADER.indices.all { bytes[at + it] == EXIF_HEADER[it] }

    private val EXIF_HEADER = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0x00, 0x00)
}
