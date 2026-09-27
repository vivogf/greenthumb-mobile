package site.xmpp.greenthumb.core.platform

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.request.Options
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * VAL-PHOTO-005: ключ кэша Coil — SHA-256 от data-URI, не сама строка.
 *
 * Тесты идут через `ComponentRegistry` НАСТОЯЩЕГО `ImageLoader` (сборка
 * registry — тот же путь, что в проде: пользовательские компоненты + общие
 * `StringMapper`/`UriKeyer`), поэтому проверяют и перехват `data:`-моделей
 * нашим Keyer'ом, и сохранение штатного поведения для обычных URI.
 * Оракул SHA-256 — `java.security.MessageDigest` (независимая реализация,
 * не та, что в проде).
 */
class DataUriKeyerTest {

    private val context = PlatformContext.INSTANCE
    private val options = Options(context)

    /** Рабочий лоадер приложения: наш Keyer зарегистрирован первым. */
    private val appLoader = ImageLoader.Builder(context)
        .components { add(DataUriKeyer()) }
        .build()

    /** Лоадер без Keyer'а — коробочный Coil (базовая линия). */
    private val stockLoader = ImageLoader.Builder(context).build()

    /** Ключ, который вычислит registry [loader] для модели-строки. */
    private fun keyVia(loader: ImageLoader, model: String): String? {
        val mapped = loader.components.map(model, options)
        return loader.components.key(mapped, options)
    }

    private fun oracleSha256Hex(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** Длинный data-URI (фото 800×800 в base64 — сотни килобайт). */
    private fun longDataUri(payloadChars: Int = 300_000): String = buildString {
        append("data:image/jpeg;base64,")
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        repeat(payloadChars) { append(alphabet[it % alphabet.length]) }
    }

    @Test
    fun longDataUriProducesShortStableKey() {
        val uri = longDataUri()
        val key = keyVia(appLoader, uri)

        assertNotNull(key, "data-URI должен получить ключ")
        // Короткий: ровно SHA-256 (64 hex-символа), а не сотни килобайт.
        assertEquals(64, key.length, "ключ должен быть длиной 64 hex-символа")
        assertTrue(key.all { it in "0123456789abcdef" }, "ключ должен быть hex: $key")
        assertNotEquals(uri, key, "ключом не должна быть сама строка data-URI")
        assertTrue(uri.length > 100_000, "фикстура должна быть длинной")
        // Стабильность: повторный вызов того же запроса — тот же ключ.
        assertEquals(key, keyVia(appLoader, uri), "ключ должен быть стабильным")
        // Совпадает с независимым оракулом SHA-256 от полной строки.
        assertEquals(oracleSha256Hex(uri), key)
    }

    @Test
    fun differentDataUrisProduceDifferentKeys() {
        val a = longDataUri(payloadChars = 50_000)
        val b = a.dropLast(1)
        assertNotEquals(keyVia(appLoader, a), keyVia(appLoader, b))
    }

    @Test
    fun stockLoaderWithoutKeyerUsesWholeStringAsKey() {
        // Базовая линия (проблема, которую чинит Keyer): коробочный UriKeyer
        // отдаёт ключом полную строку data-URI.
        val uri = longDataUri(payloadChars = 50_000)
        assertEquals(uri, keyVia(stockLoader, uri))
    }

    @Test
    fun keyerReturnsNullForNonDataUris() {
        // Штатный путь: http/file модели ключер не трогает (в них разбирается
        // UriKeyer) — возврат null из нашего Keyer'а обязателен.
        val keyer = DataUriKeyer()
        for (model in listOf(
            "https://example.com/plants/leaf.jpg",
            "file:///tmp/leaf.jpg",
        )) {
            val mapped = stockLoader.components.map(model, options) as coil3.Uri
            assertNull(keyer.key(mapped, options), "не-data URI должен вернуть null: $model")
        }
        // А data-URI через тот же прямой вызов — ключ.
        val dataUri = longDataUri(payloadChars = 100)
        val dataMapped = stockLoader.components.map(dataUri, options) as coil3.Uri
        assertEquals(oracleSha256Hex(dataUri), keyer.key(dataMapped, options))
    }

    @Test
    fun nonDataModelStillKeyedByStockKeyerInAppLoader() {
        // Сквозная проверка registry: обычный URL через наш лоадер ключится
        // штатным UriKeyer'ом (полный URL, не хэш) — data-перехват не ломает
        // остальные схемы.
        val url = "https://example.com/plants/leaf.jpg"
        assertEquals(url, keyVia(appLoader, url))
    }

    @Test
    fun sha256MatchesKnownVectors() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            sha256Hex(""),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256Hex("abc"),
        )
        assertEquals(oracleSha256Hex("data:image/jpeg;base64,SGVsbG8="), sha256Hex("data:image/jpeg;base64,SGVsbG8="))
    }
}
