package site.xmpp.greenthumb.core.platform

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageOutputStream
import kotlin.math.PI

/**
 * jvm-actual (desktop-харнесс): файловый диалог (Stage 8 п.1). Вида камеры
 * на десктопе нет, поэтому Camera тоже идёт через файловый диалог — так же,
 * как «камера = файловый диалог» записано в mission.md.
 *
 * Диалог — Compose-поверхность через [FilePickBridge]: jvm-actual только
 * запрашивает выбор и ждёт путь; рисует [site.xmpp.greenthumb.ui.components.GtFilePicker]
 * (см. KDoc там: нативный JFileChooser в этой среде неуправляем — AX выключен).
 */
actual suspend fun pickImage(source: PickSource): PickResult {
    val deferred = CompletableDeferred<String?>()
    val request = FilePickRequest(
        initialPath = System.getProperty("user.home") ?: ".",
        deferred = deferred,
    )
    FilePickBridge.submit(request)
    val path = try {
        deferred.await()
    } finally {
        FilePickBridge.clear(request)
    }
    if (path == null) return PickResult.Cancelled
    val bytes = withContext(Dispatchers.IO) { File(path).readBytes() }
    if (bytes.isEmpty()) return PickResult.Cancelled
    return PickResult.Picked(bytes)
}

/**
 * jvm-actual кропа: imageio декодирует (полностью — desktop хватает памяти),
 * EXIF-ориентация нормализуется [applyJpegOrientation], регион вырезается,
 * результат масштабируется до 800×800 (RN `resize 800×800`) и кодируется
 * JPEG quality 0.8 (RN `compress: 0.8`).
 */
actual fun cropSquareJpeg(bytes: ByteArray, rect: CropRect): ByteArray {
    val source = ImageIO.read(ByteArrayInputStream(bytes))
        ?: throw IllegalArgumentException("cannot decode image")
    val normalized = applyJpegOrientation(source, jpegExifOrientation(bytes))
    val region = rect.pixelRect(normalized.width, normalized.height)
    val cropped = normalized.getSubimage(region.x, region.y, region.width, region.height)

    val output = BufferedImage(800, 800, BufferedImage.TYPE_INT_RGB)
    val graphics = output.createGraphics()
    graphics.drawImage(cropped, 0, 0, 800, 800, null)
    graphics.dispose()

    val out = ByteArrayOutputStream()
    val writer = ImageIO.getImageWritersByFormatName("jpg").let { readers ->
        if (readers.hasNext()) readers.next() else null
    }
    if (writer == null) {
        ImageIO.write(output, "jpg", out)
    } else {
        val stream = MemoryCacheImageOutputStream(out)
        writer.output = stream
        val params: ImageWriteParam = writer.defaultWriteParam
        params.compressionMode = ImageWriteParam.MODE_EXPLICIT
        params.compressionQuality = 0.8f
        writer.write(null, IIOImage(output, null, null), params)
        writer.dispose()
        stream.close()
    }
    return out.toByteArray()
}

actual fun listDirectory(path: String): List<PickDirEntry> {
    val children = File(path).listFiles() ?: return emptyList()
    return children
        .filter { !it.name.startsWith('.') }
        .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
        .map { PickDirEntry(name = it.name, isDirectory = it.isDirectory) }
}

/**
 * Поворот/отражение растра по EXIF-ориентации (1..8) в дисплейное
 * пространство. Правило Graphics2D: последний вызов трансформации
 * применяется к точке ПЕРВЫМ. Точки совпадают с android-actual
 * (`Matrix.setRotate/postScale` в той же последовательности).
 */
internal fun applyJpegOrientation(source: BufferedImage, orientation: Int): BufferedImage {
    if (orientation == 1) return source
    val swaps = orientation in 5..8
    val width = if (swaps) source.height else source.width
    val height = if (swaps) source.width else source.height
    val canvas = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val graphics = canvas.createGraphics()
    when (orientation) {
        // 2 — mirror horizontal: (x,y) → (srcW - x, y)
        2 -> {
            graphics.scale(-1.0, 1.0)
            graphics.translate(-source.width.toDouble(), 0.0)
        }
        // 3 — rotate 180.
        3 -> {
            graphics.translate(source.width.toDouble(), source.height.toDouble())
            graphics.rotate(PI)
        }
        // 4 — mirror vertical: (x,y) → (x, srcH - y)
        4 -> {
            graphics.scale(1.0, -1.0)
            graphics.translate(0.0, -source.height.toDouble())
        }
        // 5 — transpose: (x,y) → (y, x)
        5 -> {
            graphics.scale(-1.0, 1.0)
            graphics.rotate(PI / 2)
        }
        // 6 — rotate 90 CW: (x,y) → (srcH - y, x)
        6 -> {
            graphics.translate(source.height.toDouble(), 0.0)
            graphics.rotate(PI / 2)
        }
        // 7 — transverse: (x,y) → (srcH - y, srcW - x)
        7 -> {
            graphics.translate(source.height.toDouble(), source.width.toDouble())
            graphics.scale(-1.0, 1.0)
            graphics.rotate(-PI / 2)
        }
        // 8 — rotate 270 CW (90 CCW): (x,y) → (y, srcW - x)
        8 -> {
            graphics.translate(0.0, source.width.toDouble())
            graphics.rotate(-PI / 2)
        }
        else -> return source
    }
    graphics.drawImage(source, 0, 0, null)
    graphics.dispose()
    return canvas
}
