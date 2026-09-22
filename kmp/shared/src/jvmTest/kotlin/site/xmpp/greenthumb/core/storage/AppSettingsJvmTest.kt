package site.xmpp.greenthumb.core.storage

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import site.xmpp.greenthumb.core.network.UserDto
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * VAL-STOR-002 (jvm-половина): AppSettings — дефолты отсутствующих ключей,
 * roundtrip всех ключей, персистенция между инстансами (паритет рестарта),
 * реактивность Flow, cached_user roundtrip + изолированная чистка,
 * устойчивость к мусору, применение handoff-значений.
 *
 * JvmAppSettingsStorage — инжектируемый каталог (public constructor для
 * jvmTest); default-конструктор AppSettings() использует ~/.greenthumb.
 * DataStore «один активный инстанс на файл» — все хранилища закрываются
 * в @AfterTest (см. kmp-toolchain.md, дополнение m3).
 */
@OptIn(ExperimentalPathApi::class)
class AppSettingsJvmTest {

    private val storageDir = createTempDirectory(prefix = "gt-app-settings")
    private val storageFile = storageDir / "settings.preferences_pb"

    /** Хранилища текущего теста; закрываются в cleanup (DataStore-синглтон на файл). */
    private val activeStores = mutableListOf<JvmAppSettingsStorage>()

    @AfterTest
    fun cleanup() {
        activeStores.forEach { it.close() }
        activeStores.clear()
        storageDir.deleteRecursively()
    }

    private fun newStorage(): JvmAppSettingsStorage =
        JvmAppSettingsStorage(storageDir.toFile()).also { activeStores.add(it) }

    private fun testUser(): UserDto = UserDto(
        id = "24",
        name = "kmp-val-settings",
        notificationTime = "09:00",
        timezone = "Europe/Moscow",
        lastNotifiedDate = null,
        recoveryKey = "gtk_probe_key_42",
        createdAt = "2026-09-22T10:00:00.000Z",
    )

    @Test
    fun defaults_are_absent_before_any_write() = runBlocking {
        val storage = newStorage()

        assertNull(storage.getLanguage())
        assertNull(storage.getLayoutMode())
        assertNull(storage.getTheme())
        assertFalse(storage.isIntroSeen())
        assertNull(storage.getCachedUser())
        assertFalse(storage.introSeen.first())
        // DataStore создаёт файл при первой ЗАПИСИ, чистое чтение — нет.
        assertFalse(storageFile.exists())
    }

    @Test
    fun set_then_read_roundtrip_all_fields() = runBlocking {
        val storage = newStorage()

        storage.setLanguage(AppLanguage.Ru)
        storage.setLayoutMode(LayoutMode.Grid)
        storage.setTheme(ThemePreference.Dark)
        storage.setIntroSeen()
        storage.setCachedUser(testUser())

        assertEquals(AppLanguage.Ru, storage.getLanguage())
        assertEquals(LayoutMode.Grid, storage.getLayoutMode())
        assertEquals(ThemePreference.Dark, storage.getTheme())
        assertTrue(storage.isIntroSeen())
        assertEquals(testUser(), storage.getCachedUser())
    }

    @Test
    fun settings_persist_across_storage_instances() = runBlocking {
        val writer = newStorage()
        writer.setLanguage(AppLanguage.Ru)
        writer.setLayoutMode(LayoutMode.Card)
        writer.setTheme(ThemePreference.Light)
        writer.setIntroSeen()
        writer.setCachedUser(testUser())
        writer.close()
        activeStores.remove(writer)

        // «Рестарт»: новый инстанс на том же файле — значения те же
        val reader = newStorage()
        assertEquals(AppLanguage.Ru, reader.getLanguage())
        assertEquals(LayoutMode.Card, reader.getLayoutMode())
        assertEquals(ThemePreference.Light, reader.getTheme())
        assertTrue(reader.isIntroSeen())
        assertEquals(testUser(), reader.getCachedUser())
    }

    @Test
    fun flows_reflect_updates_without_restart() = runBlocking {
        val storage = newStorage()

        assertNull(storage.language.first())
        storage.setLanguage(AppLanguage.En)
        assertEquals(AppLanguage.En, storage.language.first())
        storage.setLanguage(AppLanguage.Ru)
        assertEquals(AppLanguage.Ru, storage.language.first())

        assertNull(storage.layoutMode.first())
        storage.setLayoutMode(LayoutMode.Grid)
        assertEquals(LayoutMode.Grid, storage.layoutMode.first())

        assertFalse(storage.introSeen.first())
        storage.setIntroSeen()
        assertTrue(storage.introSeen.first())

        assertNull(storage.cachedUser.first())
        storage.setCachedUser(testUser())
        assertEquals(testUser(), storage.cachedUser.first())
    }

    @Test
    fun clear_cached_user_keeps_other_settings() = runBlocking {
        val storage = newStorage()
        storage.setLanguage(AppLanguage.Ru)
        storage.setLayoutMode(LayoutMode.Grid)
        storage.setIntroSeen()
        storage.setCachedUser(testUser())

        // signOut-семантика (VAL-DATA-008): cached_user стирается,
        // предпочтения (язык/тема/сетка/интро) НЕ тронуты.
        storage.clearCachedUser()

        assertNull(storage.getCachedUser())
        assertEquals(AppLanguage.Ru, storage.getLanguage())
        assertEquals(LayoutMode.Grid, storage.getLayoutMode())
        assertTrue(storage.isIntroSeen())
    }

    @Test
    fun corrupted_storage_reads_as_defaults() = runBlocking {
        val writer = newStorage()
        writer.setLanguage(AppLanguage.Ru)
        writer.setCachedUser(testUser())
        writer.close()
        activeStores.remove(writer)

        // Мусор вместо preferences_pb — порча DataStore сбрасывается в пустое
        // хранилище (ReplaceFileCorruptionHandler), а не крэшится.
        storageFile.toFile().writeBytes("not-a-preferences-file".encodeToByteArray())

        val reader = newStorage()
        assertNull(reader.getLanguage())
        assertNull(reader.getCachedUser())
        assertFalse(reader.isIntroSeen())
    }

    @Test
    fun fromWire_unknown_values_are_null() {
        assertNull(AppLanguage.fromWire(null))
        assertNull(AppLanguage.fromWire("fr"))
        assertNull(AppLanguage.fromWire("RU"))
        assertEquals(AppLanguage.En, AppLanguage.fromWire("en"))
        assertEquals(AppLanguage.Ru, AppLanguage.fromWire("ru"))

        assertNull(ThemePreference.fromWire(null))
        assertNull(ThemePreference.fromWire("system"))
        assertEquals(ThemePreference.Auto, ThemePreference.fromWire("auto"))
        assertEquals(ThemePreference.Light, ThemePreference.fromWire("light"))
        assertEquals(ThemePreference.Dark, ThemePreference.fromWire("dark"))

        assertNull(LayoutMode.fromWire(null))
        assertNull(LayoutMode.fromWire("gridx"))
        assertEquals(LayoutMode.List, LayoutMode.fromWire("list"))
        assertEquals(LayoutMode.Card, LayoutMode.fromWire("card"))
        assertEquals(LayoutMode.Grid, LayoutMode.fromWire("grid"))
    }

    @Test
    fun applyLegacyHandoffValues_applies_values_and_skips_nulls() = runBlocking {
        val storage = newStorage()
        // Уже существующие настройки не даунгрейдятся: false из handoff
        // не стирает уже увиденное интро; null-значения не перезаписывают.
        storage.setIntroSeen()

        storage.applyLegacyHandoffValues(
            language = AppLanguage.Ru,
            theme = null,
            layoutMode = LayoutMode.Grid,
            introSeen = false,
        )

        assertEquals(AppLanguage.Ru, storage.getLanguage())
        assertNull(storage.getTheme())
        assertEquals(LayoutMode.Grid, storage.getLayoutMode())
        assertTrue(storage.isIntroSeen())
    }

    @Test
    fun applyLegacyHandoffValues_persists_across_instances() = runBlocking {
        val writer = newStorage()
        writer.applyLegacyHandoffValues(
            language = AppLanguage.Ru,
            theme = ThemePreference.Dark,
            layoutMode = LayoutMode.Grid,
            introSeen = true,
        )
        writer.close()
        activeStores.remove(writer)

        val reader = newStorage()
        assertEquals(AppLanguage.Ru, reader.getLanguage())
        assertEquals(ThemePreference.Dark, reader.getTheme())
        assertEquals(LayoutMode.Grid, reader.getLayoutMode())
        assertTrue(reader.isIntroSeen())
    }
}
