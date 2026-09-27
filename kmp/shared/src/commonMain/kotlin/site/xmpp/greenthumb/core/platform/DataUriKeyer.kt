package site.xmpp.greenthumb.core.platform

import coil3.key.Keyer
import coil3.request.Options
import coil3.Uri

/**
 * Ключ кэша Coil для data-URI фото (Stage 8 п.4, VAL-PHOTO-005).
 *
 * Контракт фото — data-URI `data:image/jpeg;base64,…` в поле `photo_url`
 * (остаётся до смены контракта; часть издержек — объём `GET /api/plants`,
 * разбор JSON и строки в Room — этим ключ НЕ чинит, честно фиксируется в
 * handoff). Без Keyer `UriKeyer` из коробки делает ключом **саму строку**
 * (сотни килобайт на ключ), отсюда раздувание `MemoryCache` и повторное
 * декодирование при прокрутке списка.
 *
 * Порядок в `ComponentRegistry` (проверено по байткоду Coil 3.6.3): общие
 * компоненты (`StringMapper`, `UriKeyer`, `DataUriFetcher`) добавляются ПОСЛЕ
 * пользовательских, а `ComponentRegistry.key` идёт по списку вперёд и берёт
 * первое не-null совпадение. Поэтому, зарегистрированный через
 * `ImageLoader.Builder.components { add(DataUriKeyer()) }`, этот Keyer
 * перехватывает `data:`-URI, а обычные http/file-модели прозрачно уходят в
 * штатный `UriKeyer` (возврат `null`).
 *
 * Модель попадает сюда ПОСЛЕ маппинга `String → coil3.Uri`
 * (`StringMapper`), поэтому параметр — [Uri], а не строка.
 */
class DataUriKeyer : Keyer<Uri> {

    override fun key(data: Uri, options: Options): String? {
        if (!data.scheme.equals(SCHEME_DATA, ignoreCase = true)) return null
        // SHA-256 от полной строки: короткий (64 hex-символа) стабильный ключ.
        return sha256Hex(data.toString())
    }

    private companion object {
        const val SCHEME_DATA = "data"
    }
}
