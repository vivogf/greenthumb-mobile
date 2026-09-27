package site.xmpp.greenthumb.core.platform

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageOutputStream

/**
 * Общие JPEG-фикстуры jvm-тестов (Stage 8 п.1/п.2): кадр с двумя половинами,
 * JPEG с EXIF-ориентацией, шум для метрики качества, HEIC-заголовок.
 * Живут в одном пакете с тестируемым кодом — внутренние хелперы.
 */

/** Кадр [width]×[height]: левая половина красная, правая синяя → JPEG. */
internal fun paintedJpeg(width: Int, height: Int): ByteArray {
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

/** Вставляет APP1-сегмент EXIF с IFD0 tag 0x0112 = [orientation] сразу после SOI. */
internal fun withExifOrientation(jpeg: ByteArray, orientation: Int): ByteArray {
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

/** RGB тройка пикселя (x, y) декодированного [jpeg]. */
internal fun colorAt(jpeg: ByteArray, x: Int, y: Int): Triple<Int, Int, Int> {
    val image = ImageIO.read(jpeg.inputStream())
    val argb = image.getRGB(x, y)
    return Triple((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)
}

/** Детерминированный шумовый кадр (качество JPEG заметно влияет на размер). */
internal fun noisyJpeg(width: Int, height: Int, seed: Long = 42L): ByteArray {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val random = java.util.Random(seed)
    for (y in 0 until height) {
        for (x in 0 until width) {
            val rgb = random.nextInt(0xFFFFFF)
            image.setRGB(x, y, rgb)
        }
    }
    val out = ByteArrayOutputStream()
    ImageIO.write(image, "jpg", out)
    return out.toByteArray()
}

/**
 * Размер JPEG [jpeg], перекодированный из его декодирования с качеством
 * [quality] — опорная метрика для проверки качества выхода resizeJpeg.
 */
internal fun jpegSizeAtQuality(jpeg: ByteArray, quality: Float): Int {
    val image = ImageIO.read(ByteArrayInputStream(jpeg))
        ?: throw AssertionError("fixture must decode")
    return encodeJpeg(image, quality).size
}

/** Кодирование BufferedImage в JPEG с явным качеством (тот же writer, что в jvm-actual). */
internal fun encodeJpeg(image: BufferedImage, quality: Float): ByteArray {
    val out = ByteArrayOutputStream()
    val writer = ImageIO.getImageWritersByFormatName("jpg").let { readers ->
        if (readers.hasNext()) readers.next() else null
    }
    if (writer == null) {
        ImageIO.write(image, "jpg", out)
        return out.toByteArray()
    }
    val stream = MemoryCacheImageOutputStream(out)
    writer.output = stream
    val params: ImageWriteParam = writer.defaultWriteParam
    params.compressionMode = ImageWriteParam.MODE_EXPLICIT
    params.compressionQuality = quality
    writer.write(null, IIOImage(image, null, null), params)
    writer.dispose()
    stream.close()
    return out.toByteArray()
}

/**
 * Минимальный HEIC-файл: сигнатура box-структуры `ftyp` c brand `heic`
 * (реальный файл дальше содержит HEVC-байты — imageio их не читает,
 * на Android API 24–25 не читает и BitmapFactory).
 */
internal fun heicLikeBytes(): ByteArray {
    val out = ByteArrayOutputStream()
    // size(0x18) + "ftyp" + major_brand "heic" + minor 0 + compat "mif1","heic"
    val header = byteArrayOf(
        0x00, 0x00, 0x00, 0x18,
        0x66, 0x74, 0x79, 0x70, // "ftyp"
        0x68, 0x65, 0x69, 0x63, // "heic"
        0x00, 0x00, 0x00, 0x00,
        0x6D, 0x69, 0x66, 0x31, // "mif1"
        0x68, 0x65, 0x69, 0x63, // "heic"
    )
    out.write(header)
    // Несколько «мусорных» байтов македаты, чтобы файл не был ровно заголовком.
    repeat(64) { out.write((it * 31) and 0xFF) }
    return out.toByteArray()
}

/** Ширина/высота декодированного [jpeg] (или null, если не декодируется). */
internal fun imageSize(jpeg: ByteArray): Pair<Int, Int>? =
    ImageIO.read(jpeg.inputStream())?.let { it.width to it.height }
