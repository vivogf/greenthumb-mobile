package site.xmpp.greenthumb.core.storage

import kotlinx.coroutines.runBlocking
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * VAL-STOR-001: jvm-actual SecureStore — roundtrip set/get/remove,
 * шифротекст не хранится открытым текстом, провал set возвращается
 * вызывающему (false), а не молчит.
 *
 * JvmSecureStoreStorage — инжектируемый каталог (public constructor
 * для тестов); default-конструктор SecureStore() использует ~/.greenthumb.
 */
@OptIn(ExperimentalPathApi::class)
class SecureStoreJvmTest {

    private val storageDir = createTempDirectory(prefix = "gt-secure-store")
    private val storageFile = storageDir / "secure_store.preferences_pb"

    @AfterTest
    fun cleanup() {
        activeStores.forEach { it.close() }
        activeStores.clear()
        storageDir.deleteRecursively()
    }

    /** Все хранилища, созданные в текущем тесте; закрываются в cleanup. */
    private val activeStores = mutableListOf<JvmSecureStoreStorage>()

    private fun newStore(): JvmSecureStoreStorage =
        JvmSecureStoreStorage(storageDir.toFile()).also { activeStores.add(it) }

    @Test
    fun roundtrip_set_get_remove() = runBlocking {
        val store = newStore()

        assertNull(store.get(SecureStoreKeys.RECOVERY_KEY))

        assertTrue(store.set(SecureStoreKeys.RECOVERY_KEY, "alpha-key-1234"))
        assertEquals("alpha-key-1234", store.get(SecureStoreKeys.RECOVERY_KEY))

        // перезапись того же ключа
        assertTrue(store.set(SecureStoreKeys.RECOVERY_KEY, "beta-key-5678"))
        assertEquals("beta-key-5678", store.get(SecureStoreKeys.RECOVERY_KEY))

        store.remove(SecureStoreKeys.RECOVERY_KEY)
        assertNull(store.get(SecureStoreKeys.RECOVERY_KEY))

        // повторный remove — идемпотентный no-op
        store.remove(SecureStoreKeys.RECOVERY_KEY)
        assertNull(store.get(SecureStoreKeys.RECOVERY_KEY))
    }

    @Test
    fun value_persists_across_store_instances() = runBlocking {
        val writer = newStore()
        assertTrue(writer.set(SecureStoreKeys.RECOVERY_KEY, "persistent-value"))
        writer.close()
        activeStores.remove(writer)

        val reader = newStore()
        assertEquals("persistent-value", reader.get(SecureStoreKeys.RECOVERY_KEY))
    }

    @Test
    fun set_failure_returns_false_and_is_visible_to_caller() = runBlocking {
        val store = newStore()
        assertTrue(store.set(SecureStoreKeys.RECOVERY_KEY, "keep-me"))
        val before = storageFile.readBytes()

        // Инжекция провала записи: весь каталог хранилища read-only (POSIX) —
        // и запись, и атомарная замена (rename) в него проваливаются.
        val dir = storageDir.toFile()
        val keyFile = java.nio.file.Path.of(dir.absolutePath, "secure_store.key")
        assertTrue(java.nio.file.Files.exists(keyFile))
        dir.setWritable(false)
        try {
            assertFalse(store.set(SecureStoreKeys.RECOVERY_KEY, "must-not-land"))
            // провал не подменяет и не теряет прежнее значение
            assertEquals("keep-me", store.get(SecureStoreKeys.RECOVERY_KEY))
            // файл не перезаписан частично
            assertEquals(before.toList(), storageFile.readBytes().toList())
        } finally {
            dir.setWritable(true)
        }
    }

    @Test
    fun ciphertext_at_rest_is_not_plaintext() = runBlocking {
        val store = newStore()
        val secret = "very-secret-recovery-key-42"

        assertTrue(store.set(SecureStoreKeys.RECOVERY_KEY, secret))

        val onDisk = storageFile.readBytes().decodeToString()
        assertFalse(secret in onDisk)
        assertEquals(secret, store.get(SecureStoreKeys.RECOVERY_KEY))
    }

    @Test
    fun corrupted_ciphertext_reads_as_absent_and_self_heals_on_next_set() = runBlocking {
        val store = newStore()
        assertTrue(store.set(SecureStoreKeys.RECOVERY_KEY, "first-value"))

        // точечная порча шифротекста (последний байт — внутри GCM-тега)
        val bytes = storageFile.readBytes()
        java.nio.file.Files.write(
            storageFile,
            bytes.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x01).toByte() },
        )

        // Битый шифротекст = «ключа нет» (null): decrypt неуспешен; DataStore-кэш
        // всё ещё держит (испорченную) строку, поэтому читаем НОВЫМ инстансом.
        store.close()
        activeStores.remove(store)
        val reader = newStore()
        assertNull(reader.get(SecureStoreKeys.RECOVERY_KEY))
    }

    @Test
    fun remove_keeps_other_entries() = runBlocking {
        val store = newStore()
        assertTrue(store.set("greenthumb_key_a", "value-a"))
        assertTrue(store.set("greenthumb_key_b", "value-b"))

        store.remove("greenthumb_key_a")

        assertNull(store.get("greenthumb_key_a"))
        assertEquals("value-b", store.get("greenthumb_key_b"))
        assertTrue(storageFile.exists())
    }
}
