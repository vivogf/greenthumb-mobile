package site.xmpp.greenthumb.core.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.SecureRandom
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android-actual SecureStore (architecture.md §6): ключ AES-256-GCM живёт в
 * AndroidKeyStore (не экспортируется), шифротекст значений — в DataStore-файле.
 * security-crypto (deprecated) не используется.
 *
 * Файл `greenthumb_secure_store.preferences_pb` в filesDir исключён из
 * Auto Backup и Device Transfer через full-backup-content /
 * data-extraction-rules в androidApp-манифесте: ключ Keystore на новом
 * устройстве не восстанавливается, поэтому восстановленный шифротекст был бы
 * невалидным мусором. DataStore-файл лежит по явному полному пути
 * (`filesDir/greenthumb_secure_store.preferences_pb`, domain "file").
 *
 * Провал set конвертируется в false и виден вызывающему (VAL-STOR-001).
 * Порча DataStore-файла → хранилище сбрасывается в пустое (get = null),
 * порча/подмена Keystore-ключа → get = null без крэша.
 *
 * Context — applicationContext androidApp-активности (инжектируется точкой
 * входа Stage 6; до неё actual создаётся лениво из текущего процесса).
 */
public actual class SecureStore actual constructor(
    appContext: Any,
) : SecureKeyValueStore {
    private val delegate = AndroidSecureStoreStorage((appContext as Context).applicationContext)

    actual override suspend fun get(key: String): String? = delegate.get(key)

    actual override suspend fun set(key: String, value: String): Boolean =
        delegate.set(key, value)

    actual override suspend fun remove(key: String) = delegate.remove(key)
}

/**
 * Инжектируемая android-реализация (открытый конструктор для тестов/входной
 * точки). Логика описана в [SecureStore]; сессионный слой потребляет её
 * через [SecureKeyValueStore] (SessionManager).
 */
public class AndroidSecureStoreStorage(
    private val appContext: Context,
) : SecureKeyValueStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dataStore: DataStore<Preferences> by lazy { createDataStore() }
    private val crypto = AndroidAesGcm()
    private val writeMutex = Mutex()

    override suspend fun get(key: String): String? {
        val ciphertext = readCiphertext(key)
        if (ciphertext == null) return null
        // Подмена/порча ключа Keystore читается как «не записано», не крэш.
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

    private fun createDataStore(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { androidx.datastore.preferences.core.emptyPreferences() },
            scope = scope,
            produceFile = { dataStoreFile() },
        )

    private fun dataStoreFile(): File {
        // Полный файл-путь (domain "file" в backup-правилах), не sharedPreferences.
        return appContext.getFileStreamPath(DATA_FILE_NAME)
    }

    private suspend fun readCiphertext(key: String): String? =
        dataStore.data.first()[stringPreferencesKey(key)]

    private companion object {
        const val DATA_FILE_NAME = "greenthumb_secure_store.preferences_pb"
    }
}

/**
 * AES-256-GCM поверх AndroidKeyStore. Ключ создаётся один раз через
 * KeyGenParameterSpec: PURPOSE_ENCRYPT|PURPOSE_DECRYPT, GCM, no user auth.
 * Шифротекст-контейнер: [1-байт версия][12-байт IV][шифротекст + 16-байт тег].
 */
internal class AndroidAesGcm {

    private val random = SecureRandom()
    private val secret: SecretKey by lazy { loadOrCreateKey() }

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
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        // AndroidKeyStore НЕ принимает пароль ни в каком виде (включая пустой
        // CharArray) — IllegalArgumentException "password not supported".
        keyStore.load(null)
        val entry = keyStore.getEntry(KEY_ALIAS, null)
        val existing = entry as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        val generator = KeyGenerator.getInstance(KEY_ALGORITHM, ANDROID_KEYSTORE)
        val purposes = KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, purposes)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            .setRandomizedEncryptionRequired(false)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "greenthumb_secure_store"
        const val KEY_ALGORITHM = KeyProperties.KEY_ALGORITHM_AES
        const val TRANSFORMATION = "${KeyProperties.KEY_ALGORITHM_AES}/${KeyProperties.BLOCK_MODE_GCM}/${KeyProperties.ENCRYPTION_PADDING_NONE}"
        const val KEY_SIZE_BITS = 256
        const val TAG_LENGTH_BITS = 128
        const val IV_LENGTH_BYTES = 12
        const val CONTAINER_VERSION: Byte = 1
    }
}
