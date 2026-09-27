package site.xmpp.greenthumb.core.platform

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Android-actual пикера (Stage 8 п.1):
 * - галерея — `PickVisualMedia` (системный photo picker);
 * - камера — `TakePicture` на файл в cacheDir через FileProvider;
 * - разрешения запрашиваются ДО пикера (RN-паритет:
 *   `requestCameraPermissionsAsync`/`requestMediaLibraryPermissionsAsync`),
 *   отказ — `PermissionDenied(source)`, не отмена;
 * - отмена системного пикера — `Cancelled` (молчит).
 *
 * UI кропа здесь НЕ рисуется — после `Picked` экран показывает общий
 * Compose-кроп ([site.xmpp.greenthumb.ui.components.GtImageCropScreen]),
 * потому что Android-пикер сам не кадрирует (RN `allowsEditing` был штатным).
 */
private var gtPickRequestCounter = 0

actual suspend fun pickImage(source: PickSource): PickResult {
    val activity = AppActivityHolder.activity ?: return PickResult.Cancelled
    val permission = when (source) {
        PickSource.Camera -> Manifest.permission.CAMERA
        PickSource.Gallery ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_IMAGES
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }
    }
    val granted = ContextCompat.checkSelfPermission(activity, permission) ==
        PackageManager.PERMISSION_GRANTED ||
        requestPermission(activity, permission)
    if (!granted) return PickResult.PermissionDenied(source)

    val result = when (source) {
        PickSource.Gallery -> launchGallery(activity)
        PickSource.Camera -> launchCamera(activity)
    }
    // HEIC на API 24–25 (системного HEIF-декодера нет), мусор или обрезанный
    // файл: падаем ЗДЕСЬ обработанной ошибкой (controller → алерт
    // common.error), а не «пустым» экраном кропа. Bounds-only — без аллокации
    // пикселей. На API 26+ HEIC декодируется платформой и идёт дальше.
    if (result is PickResult.Picked) requireDecodable(result.bytes)
    return result
}

/**
 * Проверка, что байты вообще декодируются платформой (`inJustDecodeBounds`,
 * пиксели не аллоцируются). Нет — [UnsupportedImageException].
 */
private fun requireDecodable(bytes: ByteArray) {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        throw UnsupportedImageException(UNSUPPORTED_IMAGE_MESSAGE)
    }
}

/**
 * Медиатип запроса к пикеру. API 24–25 (Android 7.x) — системного
 * HEIF-декодера нет, поэтому просим JPEG как ФИЛЬТР доступного входа
 * (`SingleMimeType` фильтрует выбор, но НЕ транскодирует HEIC → JPEG;
 * факты и ссылки — `research/android-heic-m8.md`). На API 26+ HEIF-декодер
 * платформы есть (supported-formats: HEIF decoder Android 8.0+), фильтр
 * не нужен — HEIC принимается и перекодируется в JPEG приложением.
 */
private fun mediaTypeForRequest(): ActivityResultContracts.PickVisualMedia.VisualMediaType =
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
        ActivityResultContracts.PickVisualMedia.SingleMimeType("image/jpeg")
    } else {
        ActivityResultContracts.PickVisualMedia.ImageOnly
    }

/** Запрос runtime-разрешения через registry текущей Activity. */
private suspend fun requestPermission(activity: ComponentActivity, permission: String): Boolean =
    suspendCancellableCoroutine { continuation ->
        val key = "gt-pick-permission-${gtPickRequestCounter++}"
        lateinit var launcher: ActivityResultLauncher<String>
        launcher = activity.activityResultRegistry.register(
            key,
            ActivityResultContracts.RequestPermission(),
        ) { isGranted ->
            launcher.unregister()
            if (continuation.isActive) continuation.resume(isGranted)
        }
        continuation.invokeOnCancellation { launcher.unregister() }
        if (continuation.isActive) launcher.launch(permission)
    }

private suspend fun launchGallery(activity: ComponentActivity): PickResult =
    suspendCancellableCoroutine { continuation ->
        val key = "gt-pick-gallery-${gtPickRequestCounter++}"
        lateinit var launcher: ActivityResultLauncher<PickVisualMediaRequest>
        launcher = activity.activityResultRegistry.register(
            key,
            ActivityResultContracts.PickVisualMedia(),
        ) { uri ->
            launcher.unregister()
            if (!continuation.isActive) return@register
            val bytes = uri?.let {
                activity.contentResolver.openInputStream(it)?.use { stream -> stream.readBytes() }
            }
            if (bytes == null || bytes.isEmpty()) {
                continuation.resume(PickResult.Cancelled)
            } else {
                continuation.resume(PickResult.Picked(bytes))
            }
        }
        continuation.invokeOnCancellation { launcher.unregister() }
        if (continuation.isActive) {
            launcher.launch(PickVisualMediaRequest(mediaTypeForRequest()))
        }
    }

private suspend fun launchCamera(activity: ComponentActivity): PickResult =
    suspendCancellableCoroutine { continuation ->
        // EXTRA_OUTPUT требует grantable content:// — FileProvider (манифест
        // androidApp + res/xml/file_paths.xml: cache-path gt-pick/).
        val directory = File(activity.cacheDir, "gt-pick")
        directory.mkdirs()
        val file = File(directory, "gt-pick-${System.nanoTime()}.jpg")
        val uri = FileProvider.getUriForFile(
            activity,
            activity.packageName + ".fileprovider",
            file,
        )
        val key = "gt-pick-camera-${gtPickRequestCounter++}"
        lateinit var launcher: ActivityResultLauncher<Uri>
        launcher = activity.activityResultRegistry.register(
            key,
            ActivityResultContracts.TakePicture(),
        ) { isSaved ->
            launcher.unregister()
            if (!continuation.isActive) {
                file.delete()
                return@register
            }
            if (!isSaved) {
                file.delete()
                continuation.resume(PickResult.Cancelled)
                return@register
            }
            val bytes = runCatching { file.readBytes() }.getOrNull()
            file.delete()
            if (bytes == null || bytes.isEmpty()) {
                continuation.resume(PickResult.Cancelled)
            } else {
                continuation.resume(PickResult.Picked(bytes))
            }
        }
        continuation.invokeOnCancellation {
            launcher.unregister()
            file.delete()
        }
        if (continuation.isActive) launcher.launch(uri)
    }

/**
 * Кроп + финальный размер (контракт RN `resize 800×800 compress 0.8`):
 * 1) bounds + EXIF → дисплейные размеры, пиксельный регион [CropRect];
 * 2) даунсэмпл [BitmapFactory.Options.inSampleSize] ДО декодирования —
 *    иначе кадр с камеры (12+ мп) ест память целиком;
 * 3) поворот по EXIF ([applyOrientation]) — BitmapFactory EXIF не применяет;
 * 4) вырезание региона, масштаб до 800×800, JPEG q80.
 */
actual fun cropSquareJpeg(bytes: ByteArray, rect: CropRect): ByteArray {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        throw UnsupportedImageException(UNSUPPORTED_IMAGE_MESSAGE)
    }
    val orientation = jpegExifOrientation(bytes)
    val swaps = orientation in 5..8
    val displayWidth = if (swaps) bounds.outHeight else bounds.outWidth
    val displayHeight = if (swaps) bounds.outWidth else bounds.outHeight
    val region = rect.pixelRect(displayWidth, displayHeight)
    val sample = (min(region.width, region.height) / 800).coerceAtLeast(1)

    var decoded = BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sample },
    ) ?: throw UnsupportedImageException(UNSUPPORTED_IMAGE_MESSAGE)
    decoded = applyOrientation(decoded, orientation)

    // Регион считался в дисплейных размерах; декодированный может отличаться
    // (округление inSampleSize) — переносим регион пропорционально.
    val scaleX = decoded.width.toFloat() / displayWidth
    val scaleY = decoded.height.toFloat() / displayHeight
    val x = (region.x * scaleX).toInt().coerceIn(0, decoded.width - 1)
    val y = (region.y * scaleY).toInt().coerceIn(0, decoded.height - 1)
    val width = (region.width * scaleX).toInt().coerceAtLeast(1).coerceAtMost(decoded.width - x)
    val height = (region.height * scaleY).toInt().coerceAtLeast(1).coerceAtMost(decoded.height - y)

    var cropped = Bitmap.createBitmap(decoded, x, y, width, height)
    if (cropped !== decoded) decoded.recycle()
    val scaled = Bitmap.createScaledBitmap(cropped, 800, 800, true)
    if (scaled !== cropped) cropped.recycle()
    val output = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, 80, output)
    scaled.recycle()
    return output.toByteArray()
}

/**
 * Stage 8 п.2 (VAL-PHOTO-004): уменьшение без кропа с сохранением пропорций.
 * Тот же конвейер, что и кроп, только без вырезания региона:
 * 1) bounds + EXIF → дисплейные размеры (поворот 90/270 меняет пропорции);
 * 2) даунсэмпл `BitmapFactory.Options.inSampleSize` ДО декодирования —
 *    большой исходник не аллоцируется целиком;
 * 3) поворот по EXIF ([applyOrientation]) — BitmapFactory EXIF не применяет;
 * 4) подгонка под [maxSide] (маленькие фото НЕ увеличиваются),
 * 5) JPEG-компрессия [quality] (0.8 → 80).
 */
actual fun resizeJpeg(bytes: ByteArray, maxSide: Int, quality: Double): ByteArray {
    require(maxSide >= 1) { "maxSide must be >= 1, got $maxSide" }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        throw UnsupportedImageException(UNSUPPORTED_IMAGE_MESSAGE)
    }
    val orientation = jpegExifOrientation(bytes)
    // inSampleSize считаем по большей стороне: max(сырые) == max(дисплейные),
    // поворот размеры не меняет. Остаток масштабирования добирает createScaledBitmap.
    val sample = (maxOf(bounds.outWidth, bounds.outHeight) / maxSide).coerceAtLeast(1)
    var decoded = BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sample },
    ) ?: throw UnsupportedImageException(UNSUPPORTED_IMAGE_MESSAGE)
    decoded = applyOrientation(decoded, orientation)

    val target = fitWithin(decoded.width, decoded.height, maxSide)
    var output = decoded
    if (target.width != decoded.width || target.height != decoded.height) {
        output = Bitmap.createScaledBitmap(decoded, target.width, target.height, true)
        if (output !== decoded) decoded.recycle()
    }
    val stream = ByteArrayOutputStream()
    output.compress(
        Bitmap.CompressFormat.JPEG,
        (quality * 100).roundToInt().coerceIn(1, 100),
        stream,
    )
    output.recycle()
    return stream.toByteArray()
}

/** Android не участвует в файловом диалоге (jvm-харнесс). */
actual fun listDirectory(path: String): List<PickDirEntry> = emptyList()
/**
 * EXIF-ориентация → поворот/отражение растра. Последовательность операций
 * согласована с jvm-actual (`applyJpegOrientation`): post-вызовы применяются
 * к точкам в порядке вызовов; createBitmap нормализует отрицательные
 * координаты в новые размеры.
 */
private fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
    if (orientation == 1 || orientation !in 2..8) return bitmap
    val matrix = Matrix()
    when (orientation) {
        2 -> matrix.setScale(-1f, 1f)
        3 -> matrix.setRotate(180f)
        4 -> {
            matrix.setRotate(180f)
            matrix.postScale(-1f, 1f)
        }
        5 -> {
            matrix.setRotate(90f)
            matrix.postScale(-1f, 1f)
        }
        6 -> matrix.setRotate(90f)
        7 -> {
            matrix.setRotate(-90f)
            matrix.postScale(-1f, 1f)
        }
        8 -> matrix.setRotate(-90f)
        else -> return bitmap
    }
    val result = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    if (result !== bitmap) bitmap.recycle()
    return result
}
