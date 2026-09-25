package site.xmpp.greenthumb.data

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import site.xmpp.greenthumb.core.network.AccountSession
import site.xmpp.greenthumb.core.network.ApiClient
import site.xmpp.greenthumb.core.network.GreenThumbApi
import site.xmpp.greenthumb.core.network.NoSessionRecoveryProvider
import site.xmpp.greenthumb.core.network.UserDto
import site.xmpp.greenthumb.core.storage.AppLanguage
import site.xmpp.greenthumb.core.storage.AppPreferencesStore
import site.xmpp.greenthumb.core.storage.HandoffSource
import site.xmpp.greenthumb.core.storage.JvmPlantDatabases
import site.xmpp.greenthumb.core.storage.LayoutMode
import site.xmpp.greenthumb.core.storage.PlantEntity
import site.xmpp.greenthumb.core.storage.SecureKeyValueStore
import site.xmpp.greenthumb.core.storage.SecureStoreKeys
import site.xmpp.greenthumb.core.storage.SessionManager
import site.xmpp.greenthumb.core.storage.SessionState
import site.xmpp.greenthumb.core.storage.ThemePreference
import site.xmpp.greenthumb.core.storage.plantDatabaseRelatedFileNames
import java.io.File
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * VAL-DATA-008: выход стирает ключ, cached_user, cookies и файлы базы
 * (db/wal/shm/journal), язык/тема/сетка/интро остаются.
 * VAL-DATA-009 (jvm-половина): после выхода B не видит растений A,
 * `plants_A.db` нет. Смена аккаунта без явного выхода — то же удаление
 * (architecture.md §7).
 */
@OptIn(ExperimentalPathApi::class)
class AccountLifecycleTest {

    private val storageDir = createTempDirectory(prefix = "gt-account-life")
    private val databases = JvmPlantDatabases(storageDir.toFile())
    private val secure = FakeSecure()
    private val settings = FakeSettings()
    private val handoff = CountingHandoff()
    private val server = ScriptedAuth()
    private val session = AccountSession()
    private val client = ApiClient(MockEngine(server.handler), NoSessionRecoveryProvider, session)
    private val api = GreenThumbApi(client)
    private val gate = AccountPlantGate(
        openDatabase = { userId -> databases.open(userId) },
        deleteDatabase = { userId -> databases.delete(userId) },
        api = api,
        session = session,
    )
    private val manager = SessionManager(
        secure,
        settings,
        handoff,
        api,
        onSessionEnded = { client.endSession() },
        deleteUserDatabase = { userId -> gate.closeAndDelete(userId) },
    )

    @AfterTest
    fun cleanup() {
        client.close()
        storageDir.deleteRecursively()
    }

    @Test
    fun signOut_removes_key_cached_user_cookies_and_database_files_keeps_preferences() = runBlocking {
        settings.setLanguage(AppLanguage.Ru)
        settings.setTheme(ThemePreference.Dark)
        settings.setLayoutMode(LayoutMode.Grid)
        settings.setIntroSeen()
        server.userId = "A"
        server.recoveryKey = "key-a"
        server.logoutStatus = HttpStatusCode.InternalServerError

        val signedIn = manager.signInWithRecoveryKey("key-a")
        assertEquals("A", (signedIn as SessionState.SignedIn).user.id)
        insertPlant("A", "fern-a", "Fern A")
        val repo = gate.open("A")
        assertEquals(listOf("Fern A"), repo.currentPlants().map { it.name })
        plantDatabaseRelatedFileNames("A").filter { it.endsWith("-wal") || it.endsWith("-shm") || it.endsWith("-journal") }
            .forEach { name -> File(storageDir.toFile(), name).writeBytes(byteArrayOf(1)) }
        File(storageDir.toFile(), "plants_A.db.lck").writeBytes(byteArrayOf(1))
        File(storageDir.toFile(), "plants_B.db").writeBytes(byteArrayOf(2))

        val beforeLogout = server.cookies.size
        client.raw(io.ktor.http.HttpMethod.Get, "/api/plants")
        assertTrue(
            server.cookies.last().orEmpty().contains("connect.sid"),
            "до выхода cookie сессии уходит с запросом",
        )

        val state = manager.signOut()

        assertEquals(SessionState.SignedOut, state)
        assertNull(secure.get(SecureStoreKeys.RECOVERY_KEY), "ключ стёрт")
        assertNull(settings.getCachedUser(), "cached_user стёрт")
        assertEquals(1, handoff.clears, "handoff стёрт вместе с выходом")
        assertEquals(AppLanguage.Ru, settings.getLanguage(), "язык не тронут")
        assertEquals(ThemePreference.Dark, settings.getTheme(), "тема не тронута")
        assertEquals(LayoutMode.Grid, settings.getLayoutMode(), "сетка не тронута")
        assertTrue(settings.isIntroSeen(), "интро не тронуто")
        assertTrue(server.cookies.size > beforeLogout + 1, "logout вызван после проверочного запроса")
        assertTrue(
            server.cookies[beforeLogout + 1].orEmpty().contains("connect.sid"),
            "logout ещё нёс cookie, сброс — после него",
        )
        plantDatabaseRelatedFileNames("A").forEach { name ->
            assertFalse(File(storageDir.toFile(), name).exists(), "$name удалён")
        }
        assertFalse(File(storageDir.toFile(), "plants_A.db.lck").exists(), "lock-файл с userId удалён")
        assertTrue(File(storageDir.toFile(), "plants_B.db").isFile, "чужая база не тронута")
        assertTrue(repo.isClosed(), "соединение закрыто до удаления файла")

        client.raw(io.ktor.http.HttpMethod.Get, "/api/plants")
        assertNull(server.cookies.last(), "после выхода cookie-хранилище пустое")
    }

    @Test
    fun account_B_does_not_see_plants_of_A_and_file_is_gone() = runBlocking {
        server.userId = "A"
        server.recoveryKey = "key-a"
        manager.signInWithRecoveryKey("key-a")
        insertPlant("A", "fern-a", "Fern A")
        gate.open("A")
        File(storageDir.toFile(), "plants_A.db-wal").writeBytes(byteArrayOf(1))
        File(storageDir.toFile(), "plants_A.db-shm").writeBytes(byteArrayOf(1))

        manager.signOut()

        plantDatabaseRelatedFileNames("A").forEach { name ->
            assertFalse(File(storageDir.toFile(), name).exists(), name)
        }

        server.userId = "B"
        server.recoveryKey = "key-b"
        manager.signInWithRecoveryKey("key-b")
        val plantsB = gate.open("B")
        assertEquals(emptyList(), plantsB.currentPlants().map { it.name }, "B не видит растений A")
        assertFalse(File(storageDir.toFile(), "plants_A.db").exists())
        assertTrue(File(storageDir.toFile(), "plants_B.db").isFile)
        plantsB.close()
    }

    @Test
    fun account_switch_deletes_previous_database() = runBlocking {
        server.userId = "A"
        server.recoveryKey = "key-a"
        manager.signInWithRecoveryKey("key-a")
        insertPlant("A", "fern-a", "Fern A")
        gate.open("A")

        server.userId = "B"
        server.recoveryKey = "key-b"
        val switched = manager.signInWithRecoveryKey("key-b")

        assertEquals("B", (switched as SessionState.SignedIn).user.id)
        plantDatabaseRelatedFileNames("A").forEach { name ->
            assertFalse(File(storageDir.toFile(), name).exists(), "$name стёрт при смене аккаунта")
        }
        assertEquals(emptyList(), gate.open("B").currentPlants().map { it.id })
        gate.open("B").close()
    }

    /** Пишет строку и закрывает временное соединение. Репозиторий UI открывают после. */
    private suspend fun insertPlant(userId: String, id: String, name: String) {
        val db = databases.open(userId)
        try {
            db.plants().upsert(samplePlant(id, userId, name))
        } finally {
            db.close()
        }
    }
}

private fun samplePlant(id: String, userId: String, name: String) = PlantEntity(
    id = id,
    userId = userId,
    name = name,
    location = "shelf",
    photoUrl = "",
    waterFrequencyDays = 7,
    lastWateredDate = "2026-09-20",
    fertilizeFrequencyDays = null,
    lastFertilizedDate = null,
    repotFrequencyMonths = null,
    lastRepottedDate = null,
    pruneFrequencyMonths = null,
    lastPrunedDate = null,
    notes = null,
    createdAt = "2026-09-01T00:00:00.000Z",
)

/**
 * Вставка через уже открытый репозиторий нельзя: второй Room на тот же
 * файл запрещён. Пишем через DAO репозитория, достав его базу рефлексией? Нет.
 * Тест открывает репозиторий и пишет через публичный currentPlants после
 * upsert на том же инстансе — upsert не публичен. Поэтому вставка идёт
 * отдельным открытием ДО репозитория, а репозиторий потом только читает.
 */
private class ScriptedAuth {
    var userId: String = "A"
    var recoveryKey: String = "key-a"
    var logoutStatus: HttpStatusCode = HttpStatusCode.OK
    val cookies = mutableListOf<String?>()

    val handler: io.ktor.client.engine.mock.MockRequestHandler = { request ->
        cookies += request.headers["Cookie"]
        val json = headersOf("Content-Type", "application/json")
        when (request.url.encodedPath) {
            "/api/auth/login-recovery" -> respond(
                """{"user":{"id":"$userId","recovery_key":"$recoveryKey","created_at":"2026-09-21T10:00:00.000Z"}}""",
                HttpStatusCode.OK,
                headersOf(
                    "Content-Type" to listOf("application/json"),
                    "Set-Cookie" to listOf("connect.sid=session-$userId; Path=/; HttpOnly"),
                ),
            )
            "/api/auth/logout" -> respond(
                """{"success":false}""",
                logoutStatus,
                json,
            )
            else -> respond("[]", HttpStatusCode.OK, json)
        }
    }
}

private class FakeSecure : SecureKeyValueStore {
    val map = linkedMapOf<String, String>()
    override suspend fun get(key: String): String? = map[key]
    override suspend fun set(key: String, value: String): Boolean {
        map[key] = value
        return true
    }
    override suspend fun remove(key: String) {
        map.remove(key)
    }
}

private class CountingHandoff : HandoffSource {
    var clears: Int = 0
    override fun readHandoff() = null
    override fun clearHandoff() {
        clears++
    }
}

private class FakeSettings : AppPreferencesStore {
    private val values = mutableMapOf<String, String>()
    var storedUser: UserDto? = null

    override suspend fun getLanguage(): AppLanguage? =
        values["language"]?.let { AppLanguage.fromWire(it) }

    override suspend fun setLanguage(language: AppLanguage) {
        values["language"] = language.wire
    }

    override suspend fun getLayoutMode(): LayoutMode? =
        values["layout"]?.let { LayoutMode.fromWire(it) }

    override suspend fun setLayoutMode(mode: LayoutMode) {
        values["layout"] = mode.wire
    }

    override suspend fun getTheme(): ThemePreference? =
        values["theme"]?.let { ThemePreference.fromWire(it) }

    override suspend fun setTheme(theme: ThemePreference) {
        values["theme"] = theme.wire
    }

    override suspend fun isIntroSeen(): Boolean = values["intro"] == "1"

    override suspend fun setIntroSeen() {
        values["intro"] = "1"
    }

    override suspend fun getCachedUser(): UserDto? = storedUser

    override suspend fun setCachedUser(user: UserDto) {
        storedUser = user
    }

    override suspend fun clearCachedUser() {
        storedUser = null
    }

    override val language = emptyFlow<AppLanguage?>()
    override val layoutMode = emptyFlow<LayoutMode?>()
    override val theme = emptyFlow<ThemePreference?>()
    override val introSeen = emptyFlow<Boolean>()
    override val cachedUser = emptyFlow<UserDto?>()

    override suspend fun applyLegacyHandoffValues(
        language: AppLanguage?,
        theme: ThemePreference?,
        layoutMode: LayoutMode?,
        introSeen: Boolean?,
    ) = Unit
}
