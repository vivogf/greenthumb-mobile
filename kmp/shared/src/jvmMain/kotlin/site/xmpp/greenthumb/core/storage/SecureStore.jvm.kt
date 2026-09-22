package site.xmpp.greenthumb.core.storage

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * JVM-actual SecureStore для desktop-харнесса (architecture.md §6).
 *
 * Значения хранятся в DataStore-файле (Preferences) как AES-256-GCM-шифротекст:
 * [1-байт версия][12-байт IV][шифротекст + 16-байт GCM-тег], Base64.
 * На JVM AndroidKeyStore нет — ключ шифрования лежит рядом в файле
 * `secure_store.key` с правами 600. Харнесс не несёт прод-секретов;
 * шифрование + права файла закрывают требование «не в открытом виде».
 *
 * - Порча DataStore-файла → ReplaceFileCorruptionHandler сбрасывает в пустое
 *   хранилище: чтение = null, следующая set самопочинится.
 * - Порча/подмена ключа шифрования → decrypt неуспешен → get = null (не крэш).
 * - set возвращает false при любом IO-провале записи (VAL-STOR-001).
 *
 * Каталог по умолчанию — `~/.greenthumb` (desktop-харнесс); тестам —
 * JvmSecureStoreStorage с инжектируемым каталогом.
 */
public actual class SecureStore actual constructor(appContext: Any) : SecureKeyValueStore {
    private val delegate = JvmSecureStoreStorage(defaultStorageDir())

    actual override suspend fun get(key: String): String? = delegate.get(key)

    actual override suspend fun set(key: String, value: String): Boolean =
        delegate.set(key, value)

    actual override suspend fun remove(key: String) = delegate.remove(key)

    public fun close() = delegate.close()
}

/**
 * Инжектируемая jvm-реализация (открытый конструктор для jvmTest).
 * Логика описана в [SecureStore]; сессионный слой потребляет её через
 * [SecureKeyValueStore] (SessionManager).
 */
public class JvmSecureStoreStorage(
    storageDir: File,
) : SecureKeyValueStore {
    private val dir: File = storageDir.absoluteFile
    private val dataStoreFile = File(dir, DATA_FILE_NAME)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dataStore: DataStore<Preferences> by lazy { createDataStore() }
    private val crypto = JvmAesGcm(dir)
    private val writeMutex = Mutex()

    override suspend fun get(key: String): String? {
        val ciphertext = readCiphertext(key)
        if (ciphertext == null) return null
        // Порча/подмена ключа шифрования читается как «не записано» (null), не крэш.
        return runCatching { crypto.decrypt(ciphertext) }.getOrNull()
    }

    override suspend fun set(key: String, value: String): Boolean {
        val ciphertext = runCatching { crypto.encrypt(value) }.getOrElse { return false }
        val ok = writeMutex.withLock {
            try {
                dataStore.updateData { prefs ->
                    prefs.toMutablePreferences().apply {
                        set(stringPreferencesKey(key), ciphertext)
                    }
                }
                // verify: файл обязан содержать ровно записанный шифротекст
                dataStore.data.first()[stringPreferencesKey(key)] == ciphertext
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (t: Throwable) {
                false
            }
        }
        return ok
    }

    override suspend fun remove(key: String) {
        dataStore.edit { it.remove(stringPreferencesKey(key)) }
    }

    /** Освободить хранилище (тестам; DataStore-синглтон на файл снимается). */
    fun close() {
        scope.cancel()
    }

    private fun createDataStore(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { androidx.datastore.preferences.core.emptyPreferences() },
            scope = scope,
            produceFile = { dataStoreFile },
        )

    private suspend fun readCiphertext(key: String): String? =
        dataStore.data.first()[stringPreferencesKey(key)]

    private companion object {
        const val DATA_FILE_NAME = "secure_store.preferences_pb"
    }
}

/** Каталог хранилища по умолчанию: `~/.greenthumb` (desktop-харнесс). */
internal fun defaultStorageDir(): File =
    File(System.getProperty("user.home"), ".greenthumb")

/**
 * JVM AES-256-GCM. Уникальный IV на каждое шифрование (SecureRandom 12 байт),
 * тег 128 бит. Ключ — файл [KEY_FILE_NAME] с правами 600, создаётся при первом
 * использовании. Конверсия (encrypt/decrypt) не падает никогда: ошибки шифрования
 * в set конвертируются в false, в get — в null.
 */
internal class JvmAesGcm(private val dir: File) {

    private val keyFile = File(dir, KEY_FILE_NAME)
    private val secret: SecretKey by lazy { loadOrCreateKey() }
    private val random = SecureRandom()

    fun encrypt(plaintext: String): String {
        val iv = ByteArray(IV_LENGTH_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secret, GCMParameterSpec(TAG_LENGTH_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext.encodeToByteArray())
        val container = ByteArray(1 + iv.size + ciphertext.size)
        container[0] = CONTAINER_VERSION
        iv.copyInto(container, 1)
        ciphertext.copyInto(container, 1 + iv.size)
        return Base64.getEncoder().encodeToString(container)
    }

    fun decrypt(stored: String): String {
        val container = Base64.getDecoder().decode(stored)
        require(container.size > 1 + IV_LENGTH_BYTES) { "container too short" }
        require(container[0] == CONTAINER_VERSION) { "unknown container version" }
        val iv = container.copyOfRange(1, 1 + IV_LENGTH_BYTES)
        val ciphertext = container.copyOfRange(1 + IV_LENGTH_BYTES, container.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(TAG_LENGTH_BITS, iv))
        return cipher.doFinal(ciphertext).decodeToString()
    }

    private fun loadOrCreateKey(): SecretKey {
        dir.mkdirs()
        val raw = keyFile.takeIf { it.exists() }?.readBytes()
        if (raw != null && raw.size == KEY_LENGTH_BYTES) {
            return SecretKeySpec(raw, "AES")
        }
        val generator = KeyGenerator.getInstance("AES")
        generator.init(KEY_LENGTH_BITS)
        val key = generator.generateKey()
        keyFile.writeBytes(key.encoded)
        restrictPermissions(keyFile)
        return key
    }

    private fun restrictPermissions(file: File) {
        runCatching {
            Files.setPosixFilePermissions(
                file.toPath(),
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        }
    }

    private companion object {
        const val KEY_FILE_NAME = "secure_store.key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_LENGTH_BITS = 256
        const val KEY_LENGTH_BYTES = 32
        const val TAG_LENGTH_BITS = 128
        const val IV_LENGTH_BYTES = 12
        const val CONTAINER_VERSION: Byte = 1
    }
}
