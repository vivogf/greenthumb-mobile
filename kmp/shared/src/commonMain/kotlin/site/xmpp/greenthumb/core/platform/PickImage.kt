package site.xmpp.greenthumb.core.platform

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.CompletableDeferred
import kotlin.io.encoding.Base64
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Источник фото (RN bottom sheet `components/ImagePickerField.tsx`: Camera /
 * Gallery). На desktop оба источника отдают файловый диалог (миссия: «камера =
 * файловый диалог» — вида камеры на десктопе нет).
 */
enum class PickSource { Camera, Gallery }

/**
 * Результат [pickImage]. Nullable запрещён (Stage 8 п.1): отмена пикера и
 * отказ в разрешении — РАЗНЫЕ случаи (RN-паритет: отмена молчит,
 * `components/ImagePickerField.tsx:36`; отказ показывает локализованный алерт
 * `:52-55` / `:67-71`, ключи `common.permissionDenied*`).
 */
sealed interface PickResult {
    /** Выбранное изображение (до кропа — см. [cropSquareJpeg]). */
    data class Picked(val bytes: ByteArray) : PickResult

    /** Пользователь закрыл пикер (или кроп) — вызывающий код молчит. */
    data object Cancelled : PickResult

    /** Отказ в разрешении [source] — вызывающий код показывает алерт. */
    data class PermissionDenied(val source: PickSource) : PickResult
}

/** Прямоугольник кропа в долях 0..1 от изображения (EXIF-нормализованного). */
data class CropRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    /**
     * Пиксельный прямоугольник для изображения [width]×[height]. Кламп к
     * границам, ширина/высота ≥ 1px, прямоугольник всегда внутри изображения.
     */
    fun pixelRect(width: Int, height: Int): PixelRect {
        val l = left.coerceIn(0f, 1f)
        val r = right.coerceIn(0f, 1f)
        val t = top.coerceIn(0f, 1f)
        val b = bottom.coerceIn(0f, 1f)
        val x0 = floor(min(l, r) * width).toInt().coerceIn(0, max(0, width - 1))
        val x1 = ceil(max(l, r) * width).toInt().coerceIn(x0 + 1, width)
        val y0 = floor(min(t, b) * height).toInt().coerceIn(0, max(0, height - 1))
        val y1 = ceil(max(t, b) * height).toInt().coerceIn(y0 + 1, height)
        return PixelRect(x = x0, y = y0, width = x1 - x0, height = y1 - y0)
    }

    companion object {
        /**
         * Нормализованный кроп = пересечение отображённого прямоугольника
         * [displayed] (где реально лежит картинка на экране, px) с квадратным
         * вьюпортом [viewport] (px). Если пересечения нет — вся картинка.
         */
        fun fromDisplay(displayed: Rect, viewport: Rect): CropRect {
            val intersection = displayed.intersect(viewport)
            if (intersection.width <= 0f || intersection.height <= 0f) {
                return CropRect(0f, 0f, 1f, 1f)
            }
            return CropRect(
                left = (intersection.left - displayed.left) / displayed.width,
                top = (intersection.top - displayed.top) / displayed.height,
                right = (intersection.right - displayed.left) / displayed.width,
                bottom = (intersection.bottom - displayed.top) / displayed.height,
            )
        }
    }
}

/** Пиксельный прямоугольник (общий для обоих платформенных actual'ов). */
data class PixelRect(val x: Int, val y: Int, val width: Int, val height: Int)

/** Строка файлового диалога (jvm: каталог/файл). */
data class PickDirEntry(val name: String, val isDirectory: Boolean)

/**
 * Запрос «покажи файловый диалог»: jvm-actual создаёт его и ждёт [deferred]
 * (абсолютный путь выбранного файла; null = отмена), commonMain-UI показывает
 * диалог и закрывает deferred.
 */
class FilePickRequest internal constructor(
    internal val initialPath: String,
    internal val deferred: CompletableDeferred<String?>,
)

/**
 * Мост «jvm-actual попросил файловый диалог» → UI в commonMain показывает
 * [site.xmpp.greenthumb.ui.components.GtFilePicker] и закрывает deferred.
 * На Android мост не используется (там платформенный пикер сам показывает UI).
 */
internal object FilePickBridge {
    private var state by mutableStateOf<FilePickRequest?>(null)

    val pending: FilePickRequest? get() = state

    fun submit(request: FilePickRequest) {
        state?.deferred?.complete(null)
        state = request
    }

    fun clear(request: FilePickRequest) {
        if (state === request) state = null
    }
}

/**
 * Платформенный пикер изображения: галерея / камера.
 * androidMain — `PickVisualMedia` / `TakePicture` (+ запрос разрешений);
 * jvmMain — файловый диалог (камера на десктопе нет — тоже файловый диалог).
 */
expect suspend fun pickImage(source: PickSource): PickResult

/**
 * Квадратный кроп + финальный resize: режет [rect] из [bytes], масштабирует
 * до 800×800 и кодирует JPEG quality 0.8 — тот же выходной контракт, что RN
 * `allowsEditing 1:1` → `manipulateAsync resize 800×800 compress 0.8`
 * (`components/ImagePickerField.tsx:39-44`). EXIF-ориентация нормализуется
 * (см. [jpegExifOrientation]).
 *
 * Stage 8 п.2 (`resizeJpeg`) НЕ вызывается после кропа: оба — альтернативные
 * выходы из одного конвейера (кадрирование или просто уменьшение), цепочка
 * crop → resize дала бы второй JPEG re-encode зря.
 */
expect fun cropSquareJpeg(bytes: ByteArray, rect: CropRect): ByteArray

/**
 * Ошибки декодирования входных байтов: неподдерживаемый формат (HEIC на
 * Android API 24–25, где системного HEIF-декодера нет — факты в
 * `research/android-heic-m8.md`) или повреждённый файл.
 *
 * Бросается ДО показа экрана кропа и из [resizeJpeg]/[cropSquareJpeg];
 * `PhotoPickerController` ловит её и показывает алерт `common.error`
 * с текстом — вход не декодируется БЕЗ краша (не «пустой экран кропа»).
 */
class UnsupportedImageException(message: String) : IllegalArgumentException(message)

/** Текст ошибки «вход не декодируется» — им же показывается алерт `common.error`. */
internal const val UNSUPPORTED_IMAGE_MESSAGE =
    "unsupported or corrupt image file — pick a JPEG or another photo this device can decode"

/** Размеры изображения (ширина × высота, px). */
data class ImageSize(val width: Int, val height: Int)

/**
 * Размеры с сохранением пропорций и ограничением [maxSide] по большей
 * стороне. Маленькое фото НЕ увеличивается (стороны ≤ maxSide — на месте).
 */
internal fun fitWithin(width: Int, height: Int, maxSide: Int): ImageSize {
    if (width <= 0 || height <= 0) return ImageSize(width, height)
    val longest = max(width, height)
    if (longest <= maxSide) return ImageSize(width, height)
    val scale = maxSide.toDouble() / longest
    return ImageSize(
        width = max(1, (width * scale).roundToInt()),
        height = max(1, (height * scale).roundToInt()),
    )
}

/**
 * Уменьшение без кропа (Stage 8 п.2, план `library/kmp-migration-plan.md`):
 * пропорции сохраняются, большая сторона ≤ [maxSide] (по умолчанию 800,
 * RN `manipulateAsync resize 800…` для неквадратного ввода), маленькие
 * фото не растягиваются, EXIF-ориентация нормализуется (выход — свежий
 * JPEG без APP1), качество [quality] (RN `compress: 0.8` → 0.8 по умолчанию).
 *
 * androidMain — `BitmapFactory` c `inSampleSize` ДО декодирования (иначе
 * кадр с камеры выест память) + `Bitmap.compress`; jvmMain — `javax.imageio`.
 *
 * Альтернатива [cropSquareJpeg], не шаг после него: вызов обоих подряд
 * сделал бы лишний JPEG re-encode.
 */
expect fun resizeJpeg(bytes: ByteArray, maxSide: Int = 800, quality: Double = 0.8): ByteArray

/** Список файлов [path] для файлового диалога: без скрытых, каталоги первыми. */
expect fun listDirectory(path: String): List<PickDirEntry>

/** data-URI для `photo_url` (контракт бэкенда, Stage 8 п.3 — без изменений). */
fun photoDataUri(bytes: ByteArray): String =
    "data:image/jpeg;base64," + Base64.encode(bytes)
