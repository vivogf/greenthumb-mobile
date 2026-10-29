package site.xmpp.greenthumb.core.storage

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import site.xmpp.greenthumb.core.network.ApiClient
import site.xmpp.greenthumb.core.network.ApiError
import site.xmpp.greenthumb.core.network.GreenThumbApi
import site.xmpp.greenthumb.core.network.SessionRecoveryProvider
import site.xmpp.greenthumb.core.network.UserDto
import site.xmpp.greenthumb.season.SeasonMode
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Recovery-key integrity: no path may leave the phone with a dead key or erase a working one.
 * The server is moving to hash-only storage: `recovery_key` arrives only from create-anonymous
 * and regenerate-recovery-key; the old server still sends it everywhere.
 */
class SessionManagerKeyIntegrityTest {

    /**
     * A view over [map] (the phone's disk). A restarted process gets a new view over the same map.
     * After [dead] every call of this view hangs: the process is gone.
     */
    private class GatedSecureStore(
        val map: java.util.concurrent.ConcurrentHashMap<String, String> = java.util.concurrent.ConcurrentHashMap(),
    ) : SecureKeyValueStore {
        @Volatile
        var failSet = false

        /** Writes to these slots fail; others succeed. */
        val failSetKeys: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

        /** The next get reads the value, then waits on [gate] before returning it (stale read). */
        @Volatile
        var holdNextGet = false

        @Volatile
        var holding = false

        val gate = CompletableDeferred<Unit>()

        /** Process death: set by a death point; every later call hangs. */
        @Volatile
        var dead = false

        /** Death points: the process dies before / after writing or removing this slot. */
        @Volatile var dieBeforeSet: String? = null
        @Volatile var dieAfterSet: String? = null
        @Volatile var dieAfterRemove: String? = null

        private suspend fun die(): Nothing {
            dead = true
            kotlinx.coroutines.awaitCancellation()
        }

        // yield(): the real stores switch to an IO dispatcher, which is a cancellation point.
        override suspend fun get(key: String): String? {
            kotlinx.coroutines.yield()
            if (dead) die()
            val value = map[key]
            if (holdNextGet) {
                holdNextGet = false
                holding = true
                gate.await()
            }
            return value
        }

        override suspend fun set(key: String, value: String): Boolean {
            kotlinx.coroutines.yield()
            if (dead || dieBeforeSet == key) die()
            if (failSet || key in failSetKeys) return false
            map[key] = value
            if (dieAfterSet == key) die()
            return true
        }

        override suspend fun remove(key: String) {
            if (dead) die()
            map.remove(key)
            if (dieAfterRemove == key) die()
        }
    }

    private class Settings : AppPreferencesStore {
        @Volatile
        var storedUser: UserDto? = null

        /** cached_user writes fail like a full disk. */
        @Volatile
        var failCachedWrite = false

        override suspend fun getLanguage(): AppLanguage? = null
        override suspend fun setLanguage(language: AppLanguage) = Unit
        override suspend fun getLayoutMode(): LayoutMode? = null
        override suspend fun setLayoutMode(mode: LayoutMode) = Unit
        override suspend fun getTheme(): ThemePreference? = null
        override suspend fun setTheme(theme: ThemePreference) = Unit
        override suspend fun isIntroSeen(): Boolean = true
        override suspend fun setIntroSeen() = Unit
        override suspend fun getCachedUser(): UserDto? = storedUser
        override suspend fun setCachedUser(user: UserDto) {
            if (failCachedWrite) throw java.io.IOException("No space left on device")
            storedUser = user
        }
        override suspend fun clearCachedUser() {
            storedUser = null
        }
        override val language = kotlinx.coroutines.flow.emptyFlow<AppLanguage?>()
        override val layoutMode = kotlinx.coroutines.flow.emptyFlow<LayoutMode?>()
        override val theme = kotlinx.coroutines.flow.emptyFlow<ThemePreference?>()
        override val introSeen = kotlinx.coroutines.flow.emptyFlow<Boolean>()
        override val cachedUser = kotlinx.coroutines.flow.emptyFlow<UserDto?>()
        override suspend fun applyLegacyHandoffValues(
            language: AppLanguage?,
            theme: ThemePreference?,
            layoutMode: LayoutMode?,
            introSeen: Boolean?,
        ) = Unit
    }

    private class NoHandoff : HandoffSource {
        override fun readHandoff(): HandoffPayload? = null
        override fun clearHandoff() {}
    }

    /**
     * Server with per-endpoint knobs. User id 55. Two-phase key change contract: regenerate stores
     * a pending key and returns it in `pending_recovery_key`; confirm commits it; login-recovery
     * accepts the current or the pending key, and a pending hit commits it.
     */
    private class Server {
        @Volatile var createKey: String? = KEY_A

        /** The next pending key regenerate hands out; null leaves `pending_recovery_key` out. */
        @Volatile var regenerateKey: String? = KEY_B

        /** HEAD (pre-rotation) server: regenerate commits at once and sends the key only in `user.recovery_key`. */
        @Volatile var regenerateLegacy = false

        /** The account's keys on the server. */
        @Volatile var current: String? = null
        @Volatile var pending: String? = null

        /** `recovery_key` in me / login-recovery / PATCH responses; null leaves it out. */
        @Volatile var otherKey: String? = null
        @Volatile var meStatus = HttpStatusCode.OK

        /** me / PATCH / regenerate / confirm answer 401 without a live session cookie. */
        @Volatile var meBySession = false
        @Volatile var sessionAlive = false

        /** Fixed login-recovery answer; null = the contract (current or pending key). */
        @Volatile var loginStatus: HttpStatusCode? = null

        /** Fixed confirm answer (404 = HEAD server, 503 = storage failure); null = the contract. */
        @Volatile var confirmStatus: HttpStatusCode? = null
        @Volatile var failConfirmTransport = false

        /** Regenerate commits the pending key, then the answer is lost in transport. */
        @Volatile var loseRegenerateAnswer = false
        @Volatile var failMeTransport = false

        /** Death points on the server side: commit, then the phone's process dies before the answer. */
        @Volatile var dieAfterRegenerateCommit = false
        @Volatile var dieAfterConfirmCommit = false
        @Volatile var onDeath: () -> Unit = {}

        /** Regenerate waits on this gate before answering (the server already committed). */
        @Volatile var regenerateGate: CompletableDeferred<Unit>? = null
        val regenerateReceived = CompletableDeferred<Unit>()

        @Volatile var regenerateCount = 0
        @Volatile var loginCount = 0
        val loginKeys: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
        val confirmKeys: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

        /** The phone's slots (RECOVERY_KEY, PENDING) at the moment each confirm arrived. */
        val slotsAtConfirm: MutableList<Pair<String?, String?>> = java.util.Collections.synchronizedList(mutableListOf())
        @Volatile var phoneSlots: () -> Pair<String?, String?> = { null to null }

        private val json = headersOf("Content-Type", "application/json")

        private fun userBody(key: String?): String {
            val field = key?.let { "\"recovery_key\":\"$it\"," } ?: ""
            return """{"id":55,"name":"kmp",$field"season_mode":"auto","created_at":"2026-09-21T10:00:00.000Z"}"""
        }

        private fun user(key: String?): String = """{"user":${userBody(key)}}"""

        private fun bodyKey(request: io.ktor.client.request.HttpRequestData): String? {
            val text = (request.body as? io.ktor.http.content.TextContent)?.text ?: return null
            return Regex("\"recoveryKey\":\"([^\"]*)\"").find(text)?.groupValues?.get(1)
        }

        private fun noSession(): Boolean = meBySession && !sessionAlive

        val handler: MockRequestHandler = { request ->
            when (request.url.encodedPath) {
                "/api/auth/create-anonymous" -> {
                    current = createKey
                    pending = null
                    sessionAlive = true
                    respond(user(createKey), HttpStatusCode.OK, json)
                }
                "/api/auth/regenerate-recovery-key" -> if (noSession()) {
                    respond("{}", HttpStatusCode.Unauthorized, json)
                } else {
                    regenerateCount++
                    regenerateReceived.complete(Unit)
                    regenerateGate?.await()
                    val key = regenerateKey
                    val body = if (regenerateLegacy) {
                        // The server matches keys in canonical (lower-case) form.
                        if (!key.isNullOrBlank()) current = key.lowercase()
                        user(key)
                    } else {
                        if (!key.isNullOrBlank()) pending = key
                        val field = key?.let { ""","pending_recovery_key":"$it"""" } ?: ""
                        """{"user":${userBody(otherKey)}$field}"""
                    }
                    if (dieAfterRegenerateCommit) {
                        onDeath()
                        kotlinx.coroutines.awaitCancellation()
                    }
                    if (loseRegenerateAnswer) throw IllegalStateException("Connection reset")
                    respond(body, HttpStatusCode.OK, json)
                }
                // HEAD has no confirm route: its catch-all answers 404 even without a session.
                "/api/auth/confirm-recovery-key" -> if (noSession() && confirmStatus != HttpStatusCode.NotFound) {
                    respond("{}", HttpStatusCode.Unauthorized, json)
                } else {
                    val key = bodyKey(request) ?: ""
                    confirmKeys += key
                    slotsAtConfirm += phoneSlots()
                    if (failConfirmTransport) throw IllegalStateException("Connection reset")
                    val fixed = confirmStatus
                    val response = when {
                        fixed != null -> fixed to "{}"
                        key == pending -> {
                            current = key
                            pending = null
                            HttpStatusCode.OK to """{"user":${userBody(otherKey)},"status":"committed"}"""
                        }
                        key == current -> HttpStatusCode.OK to """{"user":${userBody(otherKey)},"status":"already_current"}"""
                        else -> HttpStatusCode.Conflict to """{"code":"not_pending"}"""
                    }
                    if (dieAfterConfirmCommit) {
                        onDeath()
                        kotlinx.coroutines.awaitCancellation()
                    }
                    respond(response.second, response.first, json)
                }
                "/api/auth/me" -> {
                    if (failMeTransport) throw IllegalStateException("Connection reset")
                    val status = if (meBySession) {
                        if (sessionAlive) HttpStatusCode.OK else HttpStatusCode.Unauthorized
                    } else {
                        meStatus
                    }
                    respond(user(otherKey), status, json)
                }
                "/api/auth/login-recovery" -> {
                    loginCount++
                    val key = bodyKey(request) ?: ""
                    loginKeys += key
                    val fixed = loginStatus
                    val status = when {
                        fixed != null -> fixed
                        key == current -> HttpStatusCode.OK
                        key == pending -> {
                            current = key
                            pending = null
                            HttpStatusCode.OK
                        }
                        else -> HttpStatusCode.Unauthorized
                    }
                    if (status == HttpStatusCode.OK) sessionAlive = true
                    respond(user(otherKey), status, json)
                }
                "/api/auth/update-season-mode" ->
                    if (noSession()) respond("{}", HttpStatusCode.Unauthorized, json)
                    else respond(user(otherKey), HttpStatusCode.OK, json)
                "/api/auth/logout" -> respond("""{"success":true}""", HttpStatusCode.OK, json)
                "/api/auth/account" -> respond("""{"success":true}""", HttpStatusCode.OK, json)
                else -> respond("{}", HttpStatusCode.NotFound, json)
            }
        }
    }

    private var secure = GatedSecureStore()
    private var settings = Settings()
    private var server = Server()
    private var deletedDatabases = java.util.Collections.synchronizedList(mutableListOf<String>())
    private var sessionEnded = 0
    private var client: ApiClient? = null
    private val clients = mutableListOf<ApiClient>()

    @AfterTest
    fun cleanup() {
        secure.gate.complete(Unit)
        server.regenerateGate?.complete(Unit)
        clients.forEach { it.close() }
    }

    /**
     * [seam] true wires the client's silent re-login to the manager as the app graph does
     * ([SessionManagerRecovery]); false leaves the client without one, so sign-in runs only
     * through the manager's own recovery.
     */
    private fun newManager(seam: Boolean = false): SessionManager {
        var manager: SessionManager? = null
        val provider: SessionRecoveryProvider = if (seam) {
            SessionManagerRecovery { manager!! }
        } else {
            object : SessionRecoveryProvider {
                override suspend fun getRecoveryKey(): String? = null
                override suspend fun onSessionReset() = Unit
            }
        }
        val apiClient = ApiClient(MockEngine(server.handler), provider)
        client = apiClient
        clients += apiClient
        val store = secure
        server.phoneSlots = { store.map[SecureStoreKeys.RECOVERY_KEY] to store.map[PENDING] }
        server.onDeath = { store.dead = true }
        return SessionManager(
            secure,
            settings,
            NoHandoff(),
            GreenThumbApi(apiClient),
            onSessionEnded = { sessionEnded++ },
            deleteUserDatabase = { deletedDatabases.add(it) },
        ).also { manager = it }
    }

    /** Kills the current process (its store view hangs from now on) and starts a fresh one. */
    private fun restart(seam: Boolean): SessionManager {
        secure.dead = true
        client?.close()
        secure = GatedSecureStore(secure.map)
        server.sessionAlive = false
        return newManager(seam)
    }

    /** A fresh phone and server for the next case of a table test. */
    private fun reset() {
        secure.dead = true
        secure = GatedSecureStore()
        settings = Settings()
        server = Server()
        deletedDatabases = java.util.Collections.synchronizedList(mutableListOf<String>())
    }

    private fun SessionManager.currentKey(): CurrentRecoveryKey.Present =
        assertIs<CurrentRecoveryKey.Present>(recoveryKey.value)

    private fun user(id: String, key: String? = null) =
        UserDto(id = id, recoveryKey = key, createdAt = "2026-09-21T10:00:00.000Z")

    private suspend fun waitFor(what: String, condition: () -> Boolean) {
        try {
            withTimeout(5_000) { while (!condition()) delay(10) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("timed out waiting for: $what")
        }
    }

    // ------------------------------------------------------------------
    // R1: regenerate without a key
    // ------------------------------------------------------------------

    @Test
    fun `regenerate response without a key is an error and keeps the current key`() = runBlocking {
        for (missing in listOf(null, "")) {
            server.regenerateKey = missing
            val manager = newManager()
            manager.createAnonymousAccount()

            val error = runCatching { manager.regenerateRecoveryKey() }.exceptionOrNull()

            assertIs<MissingRecoveryKeyException>(error, "key=$missing")
            assertEquals(KEY_A, secure.map[KEY], "storage untouched")
            assertNull(secure.map[PENDING], "no pending key written")
            assertEquals(emptyList(), server.confirmKeys.toList(), "no confirm")
            assertEquals(KEY_A, manager.currentKey().key, "memory untouched")
            assertIs<SessionState.SignedIn>(manager.state.value, "the session stays alive for a retry")
            client?.close()
        }
    }

    // ------------------------------------------------------------------
    // R2: regenerate is not cancellable by the caller
    // ------------------------------------------------------------------

    @Test
    fun `regenerate stores the new key even when the caller is cancelled mid-request`() = runBlocking {
        val manager = newManager()
        manager.createAnonymousAccount()
        val gate = CompletableDeferred<Unit>()
        server.regenerateGate = gate
        val callerScope = CoroutineScope(Job() + Dispatchers.Default)

        callerScope.launch { manager.regenerateRecoveryKey() }
        server.regenerateReceived.await()
        callerScope.cancel()
        gate.complete(Unit)

        waitFor("new key in storage") { secure.map[SecureStoreKeys.RECOVERY_KEY] == KEY_B }
        assertEquals(KEY_B, manager.currentKey().key)
    }

    // ------------------------------------------------------------------
    // R3: a failing cached_user write does not skip the key
    // ------------------------------------------------------------------

    @Test
    fun `create and regenerate store the key when cached_user cannot be written`() = runBlocking {
        settings.failCachedWrite = true
        val manager = newManager()

        manager.createAnonymousAccount()
        assertEquals(KEY_A, secure.map[SecureStoreKeys.RECOVERY_KEY], "create stored the key")

        manager.regenerateRecoveryKey()
        assertEquals(KEY_B, secure.map[SecureStoreKeys.RECOVERY_KEY], "regenerate stored the key")
        assertEquals(KEY_B, manager.currentKey().key)
    }

    // ------------------------------------------------------------------
    // R4: a 401 on a typed key does not erase a different stored key
    // ------------------------------------------------------------------

    @Test
    fun `401 on a typed key keeps a different stored key and the previous account`() = runBlocking {
        secure.map[SecureStoreKeys.RECOVERY_KEY] = KEY_A
        settings.storedUser = user("55")
        server.loginStatus = HttpStatusCode.Unauthorized
        val manager = newManager()

        val error = runCatching { manager.signInWithRecoveryKey(KEY_B) }.exceptionOrNull()

        assertIs<ApiError.Unauthorized>(error)
        assertEquals(KEY_A, secure.map[SecureStoreKeys.RECOVERY_KEY], "the working key stays")
        assertEquals("55", settings.storedUser?.id, "cached_user stays")
        assertEquals(emptyList(), deletedDatabases.toList(), "the previous account's database stays")
    }

    @Test
    fun `401 on the stored key itself still erases it`() = runBlocking {
        secure.map[SecureStoreKeys.RECOVERY_KEY] = KEY_A
        settings.storedUser = user("55")
        server.loginStatus = HttpStatusCode.Unauthorized
        val manager = newManager()

        runCatching { manager.signInWithRecoveryKey(KEY_A) }

        assertNull(secure.map[SecureStoreKeys.RECOVERY_KEY], "the rejected key is erased")
        assertNull(settings.storedUser)
        assertEquals(listOf("55"), deletedDatabases.toList())
    }

    @Test
    fun `transient start failure with a stored key asks to retry`() = runBlocking {
        secure.map[SecureStoreKeys.RECOVERY_KEY] = KEY_A
        val cases = listOf<Pair<String, Server.() -> Unit>>(
            "me 503" to { meStatus = HttpStatusCode.ServiceUnavailable },
            "me network, no cached user" to { failMeTransport = true },
            "me 401, login 503" to {
                meStatus = HttpStatusCode.Unauthorized
                loginStatus = HttpStatusCode.ServiceUnavailable
            },
            "me 401, login 429" to {
                meStatus = HttpStatusCode.Unauthorized
                loginStatus = HttpStatusCode.TooManyRequests
            },
        )
        for ((name, setup) in cases) {
            server.meStatus = HttpStatusCode.OK
            server.loginStatus = null
            server.failMeTransport = false
            server.setup()
            val manager = newManager()

            val state = manager.startup()

            assertEquals(SessionState.RetryNeeded, state, name)
            assertEquals(KEY_A, secure.map[SecureStoreKeys.RECOVERY_KEY], "$name: key kept")
            client?.close()
        }
    }

    // ------------------------------------------------------------------
    // R5: session lost after a successful re-login keeps the key
    // ------------------------------------------------------------------

    @Test
    fun `onSessionLost ends the session but keeps the key and the account database`() = runBlocking {
        secure.map[SecureStoreKeys.RECOVERY_KEY] = KEY_A
        val manager = newManager()
        manager.startup()
        assertIs<SessionState.SignedIn>(manager.state.value)
        val endedBefore = sessionEnded

        manager.onSessionLost()

        assertEquals(KEY_A, secure.map[SecureStoreKeys.RECOVERY_KEY], "key kept")
        assertEquals(SessionState.RetryNeeded, manager.state.value, "start screen offers a retry")
        assertEquals(endedBefore + 1, sessionEnded, "cookies dropped")
        assertEquals(emptyList(), deletedDatabases.toList())
    }

    // ------------------------------------------------------------------
    // R6: only create and regenerate may set the key
    // ------------------------------------------------------------------

    @Test
    fun `a key in me or PATCH responses never replaces the current key`() = runBlocking {
        val manager = newManager()
        manager.createAnonymousAccount()
        manager.regenerateRecoveryKey()
        // The old server: a /me or PATCH that left before the regenerate commit carries KEY_A.
        server.otherKey = KEY_A

        manager.refreshUser()
        assertEquals(KEY_B, secure.map[SecureStoreKeys.RECOVERY_KEY], "refreshUser")
        assertEquals(KEY_B, manager.currentKey().key)

        manager.updateSeasonMode(SeasonMode.North)
        assertEquals(KEY_B, secure.map[SecureStoreKeys.RECOVERY_KEY], "updateSeasonMode")
        assertEquals(KEY_B, manager.currentKey().key)
    }

    @Test
    fun `sign-in stores the key that worked, not the one in the response`() = runBlocking {
        server.current = KEY_B
        server.otherKey = KEY_A
        val manager = newManager()

        manager.signInWithRecoveryKey(KEY_B)

        assertEquals(KEY_B, secure.map[SecureStoreKeys.RECOVERY_KEY])
        assertEquals(KEY_B, manager.currentKey().key)
    }

    // ------------------------------------------------------------------
    // R7: concurrent applyUser (refreshUser vs regenerate)
    // ------------------------------------------------------------------

    @Test
    fun `a slow refresh does not put the old key back after a regenerate`() = runBlocking {
        val manager = newManager()
        manager.createAnonymousAccount()
        secure.holdNextGet = true

        val refresh = async(Dispatchers.Default) { manager.refreshUser() }
        waitFor("refresh read storage") { secure.holding }
        val regenerate = async(Dispatchers.Default) { manager.regenerateRecoveryKey() }
        waitFor("regenerate reached the server") { server.regenerateCount == 1 }
        delay(200)
        secure.gate.complete(Unit)
        refresh.await()
        regenerate.await()

        assertEquals(KEY_B, secure.map[SecureStoreKeys.RECOVERY_KEY])
        assertEquals(KEY_B, manager.currentKey().key, "memory holds the new key, not the stale read")
    }

    // ------------------------------------------------------------------
    // R9: sign-in whose key the phone could not store raises a notice
    // ------------------------------------------------------------------

    @Test
    fun `sign-in with a key the phone cannot store raises the notice`() = runBlocking {
        server.current = KEY_A
        val manager = newManager()
        manager.signInWithRecoveryKey(KEY_A)
        assertFalse(manager.keyNotStoredNotice.value, "stored key: no notice")

        manager.signOut()
        secure.failSet = true
        manager.signInWithRecoveryKey(KEY_A)

        assertTrue(manager.keyNotStoredNotice.value, "unstored key: notice")
        manager.dismissKeyNotStoredNotice()
        assertFalse(manager.keyNotStoredNotice.value)
    }

    // ------------------------------------------------------------------
    // R10: cached_user from an old build loses its key on startup
    // ------------------------------------------------------------------

    @Test
    fun `startup strips a key left in cached_user by an old build`() = runBlocking {
        secure.map[SecureStoreKeys.RECOVERY_KEY] = KEY_A
        settings.storedUser = user("55", key = KEY_A)
        server.failMeTransport = true
        val manager = newManager()

        val state = manager.startup()

        assertIs<SessionState.Offline>(state)
        assertEquals("55", settings.storedUser?.id, "cached_user kept")
        assertNull(settings.storedUser?.recoveryKey, "its plaintext key is gone")
    }

    // ------------------------------------------------------------------
    // Two-phase key change (design step 8 v2, KMP items 4-9)
    // ------------------------------------------------------------------

    private fun presetHeldKeys(confirmed: String?, pending: String?) {
        confirmed?.let { secure.map[KEY] = it }
        pending?.let { secure.map[PENDING] = it }
        settings.storedUser = user("55")
    }

    @Test
    fun `rotation writes the pending key before confirm and stores the new key only after it`() = runBlocking {
        val manager = newManager()
        manager.createAnonymousAccount()

        manager.regenerateRecoveryKey()

        assertEquals(listOf(KEY_B), server.confirmKeys.toList(), "confirm carries the pending key")
        assertEquals(listOf<Pair<String?, String?>>(KEY_A to KEY_B), server.slotsAtConfirm.toList(), "pending stored, old key kept, before confirm")
        assertEquals(KEY_B, server.current)
        assertEquals(KEY_B, secure.map[KEY], "the confirmed key is the current key")
        assertNull(secure.map[PENDING], "pending removed after the key write")
        assertEquals(KEY_B, manager.currentKey().key)
        assertTrue(manager.currentKey().savedOnDevice)
    }

    @Test
    fun `a failing pending write sends no confirm, shows both keys and keeps the old key`() = runBlocking {
        val manager = newManager()
        manager.createAnonymousAccount()
        secure.failSetKeys += PENDING

        manager.regenerateRecoveryKey()

        assertEquals(emptyList(), server.confirmKeys.toList(), "no confirm without a stored pending key")
        assertEquals(KEY_A, secure.map[KEY], "the old key is untouched")
        assertNull(secure.map[PENDING])
        assertEquals(KEY_A, manager.currentKey().key, "the stored, current key stays on screen")
        assertEquals(KEY_B, manager.unconfirmedRecoveryKey.value, "the new key goes next to it")
        assertFalse(manager.keyChangePending.value, "nothing pending on disk")
        assertEquals(KEY_A, manager.sessionRecoveryKey(), "a silent re-login tries the old key first")

        manager.refreshUser()
        assertEquals(KEY_A, secure.map[KEY], "a later user update does not write the unconfirmed key over the old one")
    }

    @Test
    fun `409 on confirm removes only the pending key`() = runBlocking {
        server.confirmStatus = HttpStatusCode.Conflict
        val manager = newManager()
        manager.createAnonymousAccount()

        manager.regenerateRecoveryKey()

        assertEquals(listOf(KEY_B), server.confirmKeys.toList())
        assertEquals(KEY_A, secure.map[KEY], "the old key stays")
        assertNull(secure.map[PENDING], "pending removed")
        assertEquals(KEY_A, manager.currentKey().key)
    }

    @Test
    fun `confirm 404, 5xx or no answer keeps both keys and the next sign-in finishes the change`() = runBlocking {
        val failures = listOf<Pair<String, Server.() -> Unit>>(
            "503" to { confirmStatus = HttpStatusCode.ServiceUnavailable },
            "no answer" to { failConfirmTransport = true },
        )
        for ((name, fail) in failures) {
            for (seam in listOf(false, true)) {
                reset()
                server.meBySession = true
                val manager = newManager(seam)
                manager.createAnonymousAccount()
                server.fail()

                manager.regenerateRecoveryKey()

                assertEquals(KEY_A, secure.map[KEY], "$name: old key kept")
                assertEquals(KEY_B, secure.map[PENDING], "$name: pending kept")
                assertEquals(KEY_A, manager.currentKey().key, "$name: the confirmed key is shown")
                assertEquals(KEY_B, manager.unconfirmedRecoveryKey.value, "$name: the unproven new key next to it")

                server.confirmStatus = null
                server.failConfirmTransport = false
                val restarted = restart(seam)
                assertIs<SessionState.SignedIn>(restarted.startup(), "$name seam=$seam")
                waitFor("$name seam=$seam: change finished") {
                    secure.map[KEY] == KEY_B && secure.map[PENDING] == null
                }
                assertEquals(KEY_B, server.current, "$name seam=$seam")
                assertEquals(emptyList(), deletedDatabases.toList(), "$name seam=$seam: no erase")
            }
        }
    }

    @Test
    fun `401 on every held key erases both, at startup and in the seam`() = runBlocking {
        for (seam in listOf(false, true)) {
            reset()
            presetHeldKeys(KEY_A, KEY_B)
            server.current = KEY_C
            server.meBySession = true
            val manager = newManager(seam)

            val state = manager.startup()

            assertEquals(SessionState.SignedOut, state, "seam=$seam")
            assertTrue(server.loginKeys.toList().containsAll(listOf(KEY_A, KEY_B)), "seam=$seam: both keys tried")
            assertNull(secure.map[KEY], "seam=$seam")
            assertNull(secure.map[PENDING], "seam=$seam")
            assertNull(settings.storedUser, "seam=$seam")
            assertEquals(listOf("55"), deletedDatabases.toList(), "seam=$seam")
        }
    }

    @Test
    fun `401 on the confirmed key and 200 on the pending key promotes it, at startup and in the seam`() = runBlocking {
        for (seam in listOf(false, true)) {
            reset()
            presetHeldKeys(KEY_A, KEY_B)
            // The confirm committed, its answer was lost.
            server.current = KEY_B
            server.meBySession = true
            val manager = newManager(seam)

            val state = manager.startup()

            assertIs<SessionState.SignedIn>(state, "seam=$seam")
            assertEquals(listOf(KEY_A, KEY_B), server.loginKeys.toList().take(2), "seam=$seam: confirmed key first")
            assertEquals(KEY_B, secure.map[KEY], "seam=$seam: promoted")
            assertNull(secure.map[PENDING], "seam=$seam")
            assertEquals(KEY_B, manager.currentKey().key, "seam=$seam")
            assertEquals("55", settings.storedUser?.id, "seam=$seam: no erase")
            assertEquals(emptyList(), deletedDatabases.toList(), "seam=$seam")
        }
    }

    @Test
    fun `a typed stored key that the server rejects falls back to the held pending key`() = runBlocking {
        presetHeldKeys(KEY_A, KEY_B)
        server.current = KEY_B
        val manager = newManager()

        val state = manager.signInWithRecoveryKey(KEY_A)

        assertIs<SessionState.SignedIn>(state)
        assertEquals(KEY_B, secure.map[KEY])
        assertNull(secure.map[PENDING])
        assertEquals(emptyList(), deletedDatabases.toList())
    }

    @Test
    fun `startup drops a pending key equal to the current key`() = runBlocking {
        presetHeldKeys(KEY_A, KEY_A)
        server.current = KEY_A
        val manager = newManager()

        assertIs<SessionState.SignedIn>(manager.startup())
        waitFor("pending dropped") { secure.map[PENDING] == null }
        assertEquals(KEY_A, secure.map[KEY])
        delay(100)
        assertEquals(emptyList(), server.confirmKeys.toList(), "nothing to confirm")
    }

    @Test
    fun `sign-out and account deletion clear the pending key`() = runBlocking {
        val exits = listOf<Pair<String, suspend SessionManager.() -> Unit>>(
            "signOut" to { signOut() },
            "deleteAccount" to { deleteAccount() },
        )
        for ((name, exit) in exits) {
            reset()
            val manager = newManager()
            manager.createAnonymousAccount()
            secure.map[PENDING] = KEY_B

            manager.exit()

            assertNull(secure.map[KEY], name)
            assertNull(secure.map[PENDING], name)
        }
    }

    @Test
    fun `process death at any rotation step leaves a key the server accepts`() = runBlocking {
        val deaths = listOf<Pair<String, () -> Unit>>(
            "regenerate committed, answer lost" to { server.dieAfterRegenerateCommit = true },
            "regenerate answered, pending not written" to { secure.dieBeforeSet = PENDING },
            "pending written" to { secure.dieAfterSet = PENDING },
            "confirm committed, answer lost" to { server.dieAfterConfirmCommit = true },
            "confirm answered, key not written" to { secure.dieBeforeSet = KEY },
            "key written" to { secure.dieAfterSet = KEY },
            "pending removed" to { secure.dieAfterRemove = PENDING },
        )
        for ((name, arm) in deaths) {
            for (seam in listOf(false, true)) {
                reset()
                server.meBySession = true
                val manager = newManager(seam)
                manager.createAnonymousAccount()
                arm()
                val dying = CoroutineScope(Job() + Dispatchers.Default)
                dying.launch { runCatching { manager.regenerateRecoveryKey() } }
                waitFor("$name: process died") { secure.dead }
                dying.cancel()

                val restarted = restart(seam)
                val state = restarted.startup()

                assertIs<SessionState.SignedIn>(state, "$name seam=$seam")
                waitFor("$name seam=$seam: phone holds the server's key") {
                    secure.map[PENDING] == null && secure.map[KEY] == server.current
                }
                assertEquals("55", settings.storedUser?.id, "$name seam=$seam: no erase")
                assertEquals(emptyList(), deletedDatabases.toList(), "$name seam=$seam: no erase")
            }
        }
    }

    @Test
    fun `pending stored, confirm failed, a second regenerate answer lost - the old key still signs in`() = runBlocking {
        server.meBySession = true
        val manager = newManager()
        manager.createAnonymousAccount()
        server.confirmStatus = HttpStatusCode.ServiceUnavailable
        manager.regenerateRecoveryKey()
        assertEquals(KEY_B, secure.map[PENDING])

        // Another tap: the stored pending key is confirmed first; no second regenerate overwrites it.
        manager.regenerateRecoveryKey()
        assertEquals(1, server.regenerateCount, "no regenerate while a pending key is unconfirmed")
        assertEquals(KEY_B, secure.map[PENDING])

        // A second regenerate reached the server some other way; its answer never arrived.
        server.pending = KEY_C
        server.confirmStatus = null
        val restarted = restart(seam = true)

        assertIs<SessionState.SignedIn>(restarted.startup())
        assertEquals(KEY_A, server.loginKeys.last(), "signed in with the old key")
        waitFor("stale pending dropped on 409") { secure.map[PENDING] == null }
        assertEquals(KEY_A, secure.map[KEY])
        assertEquals(emptyList(), deletedDatabases.toList())
    }

    @Test
    fun `a lost regenerate answer leaves the old key working`() = runBlocking {
        server.meBySession = true
        val manager = newManager()
        manager.createAnonymousAccount()
        server.loseRegenerateAnswer = true

        val error = runCatching { manager.regenerateRecoveryKey() }.exceptionOrNull()

        assertIs<ApiError>(error)
        assertEquals(KEY_A, secure.map[KEY])
        assertNull(secure.map[PENDING])
        val restarted = restart(seam = true)
        assertIs<SessionState.SignedIn>(restarted.startup())
        assertEquals(KEY_A, secure.map[KEY])
    }

    // ------------------------------------------------------------------
    // Item 9 fallback: a HEAD (pre-rotation) server commits at once and sends the key in user
    // ------------------------------------------------------------------

    @Test
    fun `HEAD regenerate answer - key from user goes to the pending slot, the probe after confirm 404 promotes it`() = runBlocking {
        for (seam in listOf(false, true)) {
            reset()
            server.meBySession = true
            server.regenerateLegacy = true
            server.confirmStatus = HttpStatusCode.NotFound
            // Not canonical on the wire: upper case.
            server.regenerateKey = KEY_B.uppercase()
            val manager = newManager(seam)
            manager.createAnonymousAccount()

            val result = runCatching { manager.regenerateRecoveryKey() }

            assertIs<KeyRotation.Done>(result.getOrNull(), "seam=$seam: not an error for the user: ${result.exceptionOrNull()}")
            assertEquals(listOf(KEY_B), server.confirmKeys.toList(), "seam=$seam: confirm sent with the canonical key")
            // Confirm 404 → a probe sign-in with K2 (no seam, no erase) proves HEAD made it current.
            assertEquals(KEY_B, server.loginKeys.last(), "seam=$seam: probe with K2")
            assertEquals(KEY_B, secure.map[KEY], "seam=$seam: promoted")
            assertNull(secure.map[PENDING], "seam=$seam")
            assertEquals(KEY_B, manager.currentKey().key, "seam=$seam: the new key is shown")
            manager.refreshUser()
            assertEquals(KEY_B, manager.currentKey().key, "seam=$seam: a user update keeps it")

            val restarted = restart(seam)
            assertIs<SessionState.SignedIn>(restarted.startup(), "seam=$seam")
            assertEquals(KEY_B, secure.map[KEY], "seam=$seam: promoted")
            assertNull(secure.map[PENDING], "seam=$seam")
            assertEquals("55", settings.storedUser?.id, "seam=$seam: no wipe")
            assertEquals(emptyList(), deletedDatabases.toList(), "seam=$seam: local DB intact")
        }
    }

    @Test
    fun `HEAD regenerate answer - death between the pending write and confirm recovers through the new key`() = runBlocking {
        server.meBySession = true
        server.regenerateLegacy = true
        server.confirmStatus = HttpStatusCode.NotFound
        val manager = newManager(seam = true)
        manager.createAnonymousAccount()
        secure.dieAfterSet = PENDING
        val dying = CoroutineScope(Job() + Dispatchers.Default)
        dying.launch { runCatching { manager.regenerateRecoveryKey() } }
        waitFor("process died") { secure.dead }
        dying.cancel()

        val restarted = restart(seam = true)

        assertIs<SessionState.SignedIn>(restarted.startup())
        assertEquals(KEY_B, secure.map[KEY])
        assertNull(secure.map[PENDING])
        assertEquals(emptyList(), deletedDatabases.toList())
    }

    @Test
    fun `HEAD-shaped answer carrying the current key changes nothing`() = runBlocking {
        // A server that answers already_current, and a HEAD one whose confirm is 404.
        for (confirm in listOf<HttpStatusCode?>(null, HttpStatusCode.NotFound)) {
            reset()
            server.meBySession = true
            server.regenerateLegacy = true
            server.regenerateKey = KEY_A
            server.confirmStatus = confirm
            val manager = newManager(seam = true)
            manager.createAnonymousAccount()

            runCatching { manager.regenerateRecoveryKey() }
            assertEquals(KEY_A, secure.map[KEY], "confirm=$confirm")
            assertEquals(KEY_A, manager.currentKey().key, "confirm=$confirm")

            val restarted = restart(seam = true)
            assertIs<SessionState.SignedIn>(restarted.startup(), "confirm=$confirm")
            waitFor("confirm=$confirm: slot dropped") { secure.map[PENDING] == null }
            assertEquals(KEY_A, secure.map[KEY], "confirm=$confirm")
            assertEquals(KEY_A, server.current, "confirm=$confirm")
            assertEquals(emptyList(), deletedDatabases.toList(), "confirm=$confirm")
        }
    }

    private companion object {
        const val KEY_A = "0000000a-0000-4000-8000-000000000055"
        const val KEY_B = "0000000b-0000-4000-8000-000000000055"
        const val KEY_C = "0000000c-0000-4000-8000-000000000055"
        const val PENDING = SecureStoreKeys.RECOVERY_KEY_PENDING
        const val KEY = SecureStoreKeys.RECOVERY_KEY
    }
}
