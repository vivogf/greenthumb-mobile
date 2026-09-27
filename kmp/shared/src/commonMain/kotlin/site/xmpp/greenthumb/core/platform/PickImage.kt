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
 * Примечание для Stage 8 п.2 (`resizeJpeg`): пиксельная обработка уже идёт
 * здесь (даунсэмпл до декодирования, поворот по EXIF, компрессия) — отдельный
 * `resizeJpeg` может переиспользовать те же хелперы либо заменить финальный
 * шаг, не дублируя декодирование.
 */
expect fun cropSquareJpeg(bytes: ByteArray, rect: CropRect): ByteArray

/** Список файлов [path] для файлового диалога: без скрытых, каталоги первыми. */
expect fun listDirectory(path: String): List<PickDirEntry>

/** data-URI для `photo_url` (контракт бэкенда, Stage 8 п.3 — без изменений). */
fun photoDataUri(bytes: ByteArray): String =
    "data:image/jpeg;base64," + Base64.encode(bytes)
