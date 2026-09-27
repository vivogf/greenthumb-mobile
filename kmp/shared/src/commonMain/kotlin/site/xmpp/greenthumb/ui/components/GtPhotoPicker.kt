package site.xmpp.greenthumb.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import site.xmpp.greenthumb.core.platform.CropRect
import site.xmpp.greenthumb.core.platform.FilePickBridge
import site.xmpp.greenthumb.core.platform.PickResult
import site.xmpp.greenthumb.core.platform.PickSource
import site.xmpp.greenthumb.core.platform.cropSquareJpeg
import site.xmpp.greenthumb.core.platform.pickImage
import site.xmpp.greenthumb.ui.res.Res
import site.xmpp.greenthumb.ui.res.common_cameraPermissionDenied
import site.xmpp.greenthumb.ui.res.common_error
import site.xmpp.greenthumb.ui.res.common_galleryPermissionDenied
import site.xmpp.greenthumb.ui.res.common_permissionDeniedTitle

/**
 * Оркестратор флоу фото (Stage 8 п.1): пикер → квадратный кроп → байты для
 * `photo_url`. Поведенческие правила RN-паритета:
 * - отмена пикера И отмена кропа — молча (ничего не происходит);
 * - отказ в разрешении — локализованный алерт с указанием источника
 *   (`common.permissionDenied*`); не объединяется с отменой;
 * - успешный выбор — [GtImageCropScreen], затем [onPicked] с байтами
 *   выходного JPEG (crop + resize 800×800 — см. [cropSquareJpeg]);
 * - ошибка платформенного пикера — алерт `common.error` c `err.message`
 *   (как RN `processPhoto` в `app/plant/[id].tsx:203-207`).
 *
 * Логика отделена от Composable: тесты крутят состояние машину с подставными
 * pick/crop (jvmTest PhotoPickerFlowTest), UI рисует [PhotoPickerHost].
 */
class PhotoPickerController internal constructor(
    private val pick: suspend (PickSource) -> PickResult,
    private val crop: (ByteArray, CropRect) -> ByteArray,
    private val onPicked: (ByteArray) -> Unit,
) {
    /** Байты, ожидающие решения кропа; null — кроп не активен. */
    var cropping by mutableStateOf<ByteArray?>(null)
        private set

    /** Источник отказа в разрешении; null — алерт не показывается. */
    var deniedSource by mutableStateOf<PickSource?>(null)
        private set

    /** Сообщение ошибки пикера (как RN `err.message`); null — алерта нет. */
    var pickErrorMessage by mutableStateOf<String?>(null)
        private set

    private var cropDecision: CompletableDeferred<CropRect?>? = null

    fun launch(scope: CoroutineScope, source: PickSource) {
        // Пикер уже открыт (кроп или другой запуск) — не дублируем.
        if (cropping != null || cropDecision != null) return
        scope.launch {
            val result = try {
                pick(source)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                pickErrorMessage = error.message ?: error.toString()
                return@launch
            }
            when (result) {
                is PickResult.Cancelled -> Unit
                is PickResult.PermissionDenied -> deniedSource = result.source
                is PickResult.Picked -> awaitCrop(result.bytes)
            }
        }
    }

    private suspend fun awaitCrop(bytes: ByteArray) {
        val decision = CompletableDeferred<CropRect?>()
        cropDecision = decision
        cropping = bytes
        try {
            val rect = decision.await()
            if (rect != null) {
                val output = crop(bytes, rect)
                onPicked(output)
            }
            // rect == null — отмена кропа: молчим, как отмена пикера.
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            // RN: showAlert(t('common.error'), err.message) — и для кропа тоже.
            pickErrorMessage = error.message ?: error.toString()
        } finally {
            cropping = null
            cropDecision = null
        }
    }

    fun confirmCrop(rect: CropRect) {
        cropDecision?.complete(rect)
    }

    fun cancelCrop() {
        cropDecision?.complete(null)
    }

    fun dismissDenied() {
        deniedSource = null
    }

    fun dismissPickError() {
        pickErrorMessage = null
    }
}

/**
 * Контроллер, привязанный к scope экрана. Колбэк [onPicked] читается через
 * `rememberUpdatedState` — рекомпозиция экрана не пересоздаёт контроллер
 * (иначе открытый кроп терялся бы на каждом кадре).
 */
@Composable
fun rememberPhotoPicker(onPicked: (ByteArray) -> Unit): PhotoPickerController {
    val current = rememberUpdatedState(onPicked)
    return remember {
        PhotoPickerController(
            pick = { source -> pickImage(source) },
            crop = { bytes, rect -> cropSquareJpeg(bytes, rect) },
            onPicked = { current.value(it) },
        )
    }
}

/**
 * UI-слой контроллера: рисует поверх экрана файловый диалог (jvm,
 * через [FilePickBridge]), кроп-экран и алерты разрешения/ошибки.
 * Размещается в корневом Box экрана (AddPlant/PlantDetail).
 */
@Composable
fun PhotoPickerHost(controller: PhotoPickerController) {
    // Файловый диалог (jvm-actual): платформа запросила выбор файла.
    FilePickBridge.pending?.let { request ->
        GtFilePicker(
            initialPath = request.initialPath,
            onPicked = { path ->
                FilePickBridge.clear(request)
                request.deferred.complete(path)
            },
            onCancel = {
                FilePickBridge.clear(request)
                request.deferred.complete(null)
            },
        )
    }

    controller.cropping?.let { bytes ->
        GtImageCropScreen(
            bytes = bytes,
            onCancel = controller::cancelCrop,
            onConfirm = controller::confirmCrop,
        )
    }

    controller.deniedSource?.let { source ->
        // RN: showAlert(t('common.permissionDeniedTitle'), t('common.*PermissionDenied')).
        GtAlertDialog(
            title = stringResource(Res.string.common_permissionDeniedTitle),
            message = stringResource(
                when (source) {
                    PickSource.Camera -> Res.string.common_cameraPermissionDenied
                    PickSource.Gallery -> Res.string.common_galleryPermissionDenied
                },
            ),
            onDismissRequest = controller::dismissDenied,
        )
    }

    controller.pickErrorMessage?.let { message ->
        GtAlertDialog(
            title = stringResource(Res.string.common_error),
            message = message,
            onDismissRequest = controller::dismissPickError,
        )
    }
}
