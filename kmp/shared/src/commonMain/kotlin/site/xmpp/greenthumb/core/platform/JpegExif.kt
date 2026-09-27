package site.xmpp.greenthumb.core.platform

/**
 * EXIF-ориентация из JPEG APP1 (IFD0 tag 0x0112). Общий (commonMain) парсер:
 * нужен и androidMain (BitmapFactory не применяет EXIF), и jvmMain (imageio
 * тоже не применяет) — без новой зависимости androidx.exifinterface.
 *
 * Возвращает 1..8 (1 — без поворота); на любом мусоре/обрыве/отсутствии APP1
 * — 1, исключений не бросает. HEIC не разбирается (вне этого контракта).
 *
 * Для Stage 8 п.2 (`resizeJpeg`, VAL-PHOTO-004): тот же парсер —
 * нормализация ориентации применяется и в [cropSquareJpeg], и в
 * [resizeJpeg]; фикстуры 90°/270° проверяют оба выхода.
 */
internal fun jpegExifOrientation(bytes: ByteArray): Int {
    // SOI
    if (bytes.size < 4 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) return 1
    var offset = 2
    while (offset + 4 <= bytes.size) {
        if (bytes[offset] != 0xFF.toByte()) return 1
        val marker = bytes[offset + 1].toInt() and 0xFF
        when {
            // Standalone-маркеры без длины сегмента.
            marker == 0x00 || marker == 0x01 || marker in 0xD0..0xD9 -> {
                offset += 2
                continue
            }
            // SOS — дальше строго скан-данные, сегментов больше нет.
            marker == 0xDA -> return 1
        }
        val length = ((bytes[offset + 2].toInt() and 0xFF) shl 8) or (bytes[offset + 3].toInt() and 0xFF)
        if (length < 2 || offset + 2 + length > bytes.size) return 1
        if (marker == 0xE1) {
            val payload = offset + 4
            val payloadEnd = offset + 2 + length
            if (payload + 6 <= payloadEnd && startsWithExifHeader(bytes, payload)) {
                return readOrientationTiff(bytes, payload + 6, payloadEnd)
            }
        }
        offset += 2 + length
    }
    return 1
}

private fun startsWithExifHeader(bytes: ByteArray, at: Int): Boolean {
    // "Exif\0\0"
    val header = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0x00, 0x00)
    for (i in header.indices) {
        if (bytes[at + i] != header[i]) return false
    }
    return true
}

private fun readOrientationTiff(bytes: ByteArray, tiff: Int, end: Int): Int {
    if (tiff + 8 > end) return 1
    val little = when {
        bytes[tiff] == 0x49.toByte() && bytes[tiff + 1] == 0x49.toByte() -> true
        bytes[tiff] == 0x4D.toByte() && bytes[tiff + 1] == 0x4D.toByte() -> false
        else -> return 1
    }
    fun u16(at: Int): Int {
        if (at + 2 > end) return -1
        val b0 = bytes[at].toInt() and 0xFF
        val b1 = bytes[at + 1].toInt() and 0xFF
        return if (little) b0 or (b1 shl 8) else (b0 shl 8) or b1
    }

    if (u16(tiff + 2) != 0x002A) return 1
    val ifdOffset = u32(bytes, tiff + 4, little, end) ?: return 1
    val ifd = tiff + ifdOffset
    if (ifd + 2 > end) return 1
    val count = u16(ifd)
    if (count <= 0 || count > 512) return 1
    for (i in 0 until count) {
        val entry = ifd + 2 + i * 12
        if (entry + 12 > end) return 1
        val tag = u16(entry)
        if (tag == 0x0112) {
            val type = u16(entry + 2)
            if (type != 3) return 1 // SHORT
            val value = u16(entry + 8)
            return if (value in 1..8) value else 1
        }
    }
    return 1
}

private fun u32(bytes: ByteArray, from: Int, little: Boolean, end: Int): Int? {
    if (from + 4 > end) return null
    var result = 0L
    if (little) {
        for (i in from + 3 downTo from) {
            result = (result shl 8) or (bytes[i].toLong() and 0xFFL)
        }
    } else {
        for (i in from until from + 4) {
            result = (result shl 8) or (bytes[i].toLong() and 0xFFL)
        }
    }
    // 32-битный offset в файле — реальные смещения малы; >2^31 бессмысленно.
    if (result > Int.MAX_VALUE) return null
    return result.toInt()
}
