package site.xmpp.greenthumb.core.storage


import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import site.xmpp.greenthumb.core.network.ApiClient
import site.xmpp.greenthumb.core.network.GreenThumbApi
import site.xmpp.greenthumb.core.network.UserDto
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Round 2 of the two-phase key change: the adversary's probes P1–P6 (adv-kmp-rotation-code) plus
 * F3 and F7. The fake follows the real handlers: the new server checks the session on confirm;
 * HEAD answers confirm with 404 even without a session (catch-all), commits regenerate at once
 * and sends the key only in `user.recovery_key`.
 */
class SessionManagerRotationSafetyTest {
    private class Store(val map: java.util.concurrent.ConcurrentHashMap<String, String> = java.util.concurrent.ConcurrentHashMap()) :
        SecureKeyValueStore {
        val failSetKeys: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        override suspend fun get(key: String): String? = map[key]
        @Volatile var holdPendingSet: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        @Volatile var pendingSetReached = false
        override suspend fun set(key: String, value: String): Boolean {
            if (key in failSetKeys) return false
            if (key == SecureStoreKeys.RECOVERY_KEY_PENDING) holdPendingSet?.let { pendingSetReached = true; it.await() }
            map[key] = value
            return true
        }
        override suspend fun remove(key: String) {
            map.remove(key)
        }
    }

    private class Settings : AppPreferencesStore {
        @Volatile var storedUser: UserDto? = null
        override suspend fun getLanguage(): AppLanguage? = null
        override suspend fun setLanguage(language: AppLanguage) = Unit
        override suspend fun getLayoutMode(): LayoutMode? = null
        override suspend fun setLayoutMode(mode: LayoutMode) = Unit
        override suspend fun getTheme(): ThemePreference? = null
        override suspend fun setTheme(theme: ThemePreference) = Unit
        override suspend fun isIntroSeen(): Boolean = true
        override suspend fun setIntroSeen() = Unit
        override suspend fun getCachedUser(): UserDto? = storedUser
        /** cached_user writes wait here (a slow disk); [setCachedReached] says one arrived. */
        @Volatile var holdSetCached: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        @Volatile var setCachedReached = false
        override suspend fun setCachedUser(user: UserDto) {
            holdSetCached?.let {
                setCachedReached = true
                it.await()
            }
            storedUser = user
        }
        override suspend fun clearCachedUser() { storedUser = null }
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

    /** Session-checked fake of the real handlers (userRoutes.ts / loginRecovery.ts, HEAD routes.ts). */
    private class Server {
        @Volatile var userId = 55
        @Volatile var current: String? = null
        @Volatile var pending: String? = null
        @Volatile var sessionAlive = false
        @Volatile var legacy = false
        @Volatile var nextKey = KEY_B
        @Volatile var commitThenDropConfirm = false
        @Volatile var meStatus: HttpStatusCode? = null
        @Volatile var createKey = KEY_A
        @Volatile var confirmGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        val loginKeys = java.util.Collections.synchronizedList(mutableListOf<String>())
        private val json = headersOf("Content-Type", "application/json")
        private fun userBody(key: String?): String {
            val field = key?.let { "\"recovery_key\":\"$it\"," } ?: ""
            return """{"id":$userId,"name":"kmp",$field"season_mode":"auto","created_at":"2026-09-21T10:00:00.000Z"}"""
        }
        private fun bodyKey(request: io.ktor.client.request.HttpRequestData): String? {
            val text = (request.body as? io.ktor.http.content.TextContent)?.text ?: return null
            return Regex("\"recoveryKey\":\"([^\"]*)\"").find(text)?.groupValues?.get(1)
        }
        @Volatile var confirmCount = 0

        /** Confirm answers 503 (storage failure) and commits nothing. */
        @Volatile var confirmUnavailable = false

        /** login-recovery with this key fails in transport (the probe gets no answer). */
        @Volatile var failLoginTransportFor: String? = null

        /** Keys of other accounts: login-recovery with one answers 200 for that account's id. */
        val foreignKeys = java.util.concurrent.ConcurrentHashMap<String, Int>()

        /** The account the session cookie belongs to on the server. */
        @Volatile var sessionAccount: Int? = null

        /** Regenerate commits, waits on this gate, then (with [loseRegenerateAnswer]) loses its answer. */
        @Volatile var regenerateGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        @Volatile var regenerateReached = false
        @Volatile var loseRegenerateAnswer = false

        val handler: MockRequestHandler = { request ->
            when (request.url.encodedPath) {
                "/api/auth/create-anonymous" -> {
                    current = createKey; pending = null; sessionAlive = true; sessionAccount = userId
                    respond("""{"user":${userBody(createKey)}}""", HttpStatusCode.OK, json)
                }
                "/api/auth/regenerate-recovery-key" -> if (!sessionAlive) respond("{}", HttpStatusCode.Unauthorized, json) else {
                    val body = if (legacy) {
                        current = nextKey
                        """{"user":${userBody(nextKey)}}"""
                    } else {
                        pending = nextKey
                        """{"user":${userBody(null)},"pending_recovery_key":"$nextKey"}"""
                    }
                    regenerateGate?.let {
                        regenerateReached = true
                        it.await()
                    }
                    if (loseRegenerateAnswer) throw IllegalStateException("Connection reset")
                    respond(body, HttpStatusCode.OK, json)
                }
                "/api/auth/confirm-recovery-key" -> if (legacy.also { confirmCount++ }) {
                    respond("""{"error":"Not found"}""", HttpStatusCode.NotFound, json)
                } else if (!sessionAlive) respond("{}", HttpStatusCode.Unauthorized, json) else if (confirmUnavailable) {
                    respond("{}", HttpStatusCode.ServiceUnavailable, json)
                } else {
                    val key = bodyKey(request)
                    when {
                        key != null && key == pending -> {
                            current = key; pending = null
                            confirmGate?.await()
                            if (commitThenDropConfirm) throw IllegalStateException("Connection reset after commit")
                            respond("""{"user":${userBody(null)},"status":"committed"}""", HttpStatusCode.OK, json)
                        }
                        key == current -> respond("""{"user":${userBody(null)},"status":"already_current"}""", HttpStatusCode.OK, json)
                        else -> respond("""{"code":"not_pending"}""", HttpStatusCode.Conflict, json)
                    }
                }
                "/api/auth/me" -> {
                    val fixed = meStatus
                    if (fixed != null) respond("{}", fixed, json)
                    else if (!sessionAlive) respond("{}", HttpStatusCode.Unauthorized, json)
                    else respond("""{"user":${userBody(if (legacy) current else null)}}""", HttpStatusCode.OK, json)
                }
                "/api/auth/login-recovery" -> {
                    val key = bodyKey(request) ?: ""
                    loginKeys += key
                    if (key == failLoginTransportFor) throw IllegalStateException("Connection reset")
                    val ok = when {
                        key == current -> true
                        !legacy && key == pending -> { current = key; pending = null; true }
                        else -> false
                    }
                    val foreign = foreignKeys[key]
                    if (foreign != null) {
                        sessionAlive = true
                        sessionAccount = foreign
                        val body = userBody(null).replace("\"id\":$userId,", "\"id\":$foreign,")
                        respond("""{"user":$body}""", HttpStatusCode.OK, json)
                    } else if (ok) {
                        sessionAlive = true
                        sessionAccount = userId
                        respond("""{"user":${userBody(if (legacy) current else null)}}""", HttpStatusCode.OK, json)
                    }
                    else respond("""{"error":"Invalid recovery key"}""", HttpStatusCode.Unauthorized, json)
                }
                "/api/auth/logout" -> { sessionAlive = false; respond("""{"success":true}""", HttpStatusCode.OK, json) }
                else -> respond("{}", HttpStatusCode.NotFound, json)
            }
        }
    }

    private var store = Store()
    private val settings = Settings()
    private val server = Server()
    private val clients = mutableListOf<ApiClient>()

    @AfterTest
    fun cleanup() { clients.forEach { it.close() } }

    /** The app graph wiring: the client's 401 seam goes through SessionManagerRecovery. */
    private fun newManager(): SessionManager {
        var manager: SessionManager? = null
        val client = ApiClient(MockEngine(server.handler), SessionManagerRecovery { manager!! })
        clients += client
        return SessionManager(store, settings, NoHandoff(), GreenThumbApi(client), onSessionEnded = { client.endSession() })
            .also { manager = it }
    }

    private fun SessionManager.shownKey(): String? = (recoveryKey.value as? CurrentRecoveryKey.Present)?.key

    /** Keys the server accepts at login right now. */
    private fun accepted(key: String?): Boolean =
        key != null && (key == server.current || (!server.legacy && key == server.pending))

    private suspend fun waitFor(what: String, condition: () -> Boolean) {
        try {
            withTimeout(5_000) { while (!condition()) delay(10) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("timed out waiting for: $what")
        }
    }

    /**
     * Waits for the flow itself to reach the state. Background key-change work updates several
     * flows in separate steps, so waiting on one flow (or on the store) does not mean the others
     * are already set.
     */
    private suspend fun <T> StateFlow<T>.awaitState(what: String, predicate: (T) -> Boolean): T =
        try {
            withTimeout(5_000) { first(predicate) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("timed out waiting for: $what (last value: $value)")
        }

    private fun restart(): SessionManager {
        store = Store(store.map)
        server.sessionAlive = false
        return newManager()
    }

    private fun slots() = store.map[KEY] to store.map[PENDING]

    // ------------------------------------------------------------------
    // F1: promote always writes RECOVERY_KEY before it drops the pending key
    // ------------------------------------------------------------------

    @Test
    fun `P1 HEAD fallback then a 401 in the same process - the seam promote writes the key`() = runBlocking<Unit> {
        server.legacy = true
        val manager = newManager()
        manager.createAnonymousAccount()
        manager.regenerateRecoveryKey()
        // The session expires; the next request goes through the seam: K1 401, K2 200.
        server.sessionAlive = false
        manager.refreshUser()

        assertEquals(KEY_B to null, slots(), "the accepted key K2 is in RECOVERY_KEY after the seam promote")
        assertEquals(KEY_B, manager.shownKey())
        val state = restart().startup()
        assertIs<SessionState.SignedIn>(state, "cold start signs in")
        assertEquals(KEY_B to null, slots())
    }

    @Test
    fun `P2 new server, key write fails at confirm, then a seam relogin - the key is written before pending goes`() = runBlocking<Unit> {
        val manager = newManager()
        manager.createAnonymousAccount()
        store.failSetKeys += KEY
        manager.regenerateRecoveryKey()
        assertEquals(KEY_A to KEY_B, slots(), "the confirmed key waits in the pending slot")
        store.failSetKeys -= KEY
        server.sessionAlive = false
        manager.refreshUser()

        assertEquals(KEY_B to null, slots(), "the accepted key K2 is in RECOVERY_KEY after the seam promote")
        assertIs<SessionState.SignedIn>(restart().startup())
        assertEquals(KEY_B to null, slots())
    }

    @Test
    fun `F1 a failing key write in the seam promote keeps the pending key`() = runBlocking<Unit> {
        server.legacy = true
        val manager = newManager()
        manager.createAnonymousAccount()
        store.failSetKeys += KEY
        manager.regenerateRecoveryKey()
        assertEquals(KEY_A to KEY_B, slots(), "the probe proved K2 but its key write failed: pending kept")
        assertEquals(KEY_B, manager.shownKey(), "the proven key is shown")
        server.sessionAlive = false
        manager.refreshUser()

        assertEquals(KEY_A to KEY_B, slots(), "pending kept: it is the only stored copy of the accepted key")
        store.failSetKeys -= KEY
        assertIs<SessionState.SignedIn>(restart().startup())
        assertEquals(KEY_B to null, slots())
    }

    // ------------------------------------------------------------------
    // F2: an unfinished change shows K2; sign-out finishes it or asks to save K2 first
    // ------------------------------------------------------------------

    @Test
    fun `P3 confirm committed, answer lost - K2 is shown and sign-out first finishes the change`() = runBlocking<Unit> {
        val manager = newManager()
        manager.createAnonymousAccount()
        server.commitThenDropConfirm = true
        val result = manager.regenerateRecoveryKey()

        assertIs<KeyRotation.Unfinished>(result)
        assertTrue(
            accepted(manager.shownKey()) || accepted(manager.unconfirmedRecoveryKey.value),
            "one of the keys on screen is accepted: both are shown",
        )
        assertEquals(KEY_A, manager.shownKey(), "the confirmed key")
        assertEquals(KEY_B, manager.unconfirmedRecoveryKey.value, "the new key, not called working")
        assertTrue(manager.keyChangePending.value)
        assertEquals(KEY_A to KEY_B, slots(), "K1 stays as a silent fallback")

        server.commitThenDropConfirm = false
        val check = manager.prepareSignOut()

        assertIs<SignOutCheck.Finished>(check, "already_current finishes the change; sign-out is cancelled")
        assertEquals(KEY_B to null, slots())
        assertIs<SessionState.SignedIn>(manager.state.value)
    }

    @Test
    fun `F2 sign-out with a change that still cannot finish asks to save K2 first`() = runBlocking<Unit> {
        val manager = newManager()
        manager.createAnonymousAccount()
        server.confirmUnavailable = true
        manager.regenerateRecoveryKey()

        val check = manager.prepareSignOut()

        val save = assertIs<SignOutCheck.SaveKeyFirst>(check)
        assertEquals(KEY_A, save.key, "the dialog shows the confirmed key")
        assertEquals(KEY_B, save.newKey, "and the new key")
        assertEquals(KEY_A to KEY_B, slots(), "nothing erased before an explicit sign-out")
    }

    @Test
    fun `F2c a superseded change goes back to K1`() = runBlocking<Unit> {
        val manager = newManager()
        manager.createAnonymousAccount()
        server.confirmUnavailable = true
        manager.regenerateRecoveryKey()
        assertEquals(KEY_B, manager.unconfirmedRecoveryKey.value)
        // Another regenerate (web) replaced the pending key on the server.
        server.confirmUnavailable = false
        server.pending = KEY_C

        val result = manager.finishKeyChange()

        assertIs<KeyRotation.Superseded>(result)
        assertEquals(KEY_A to null, slots())
        assertEquals(KEY_A, manager.shownKey(), "back to the confirmed key")
        assertEquals(null, manager.unconfirmedRecoveryKey.value)
        assertFalse(manager.keyChangePending.value)
    }

    @Test
    fun `P5 sign-out while confirm is in flight - the committed key reached the user`() = runBlocking<Unit> {
        val m = newManager()
        m.createAnonymousAccount()
        val shownKeys = java.util.Collections.synchronizedList(mutableListOf<String>())
        val watcher = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            m.recoveryKey.collect { (it as? CurrentRecoveryKey.Present)?.let { p -> shownKeys += p.key } }
        }
        val newKeyWatcher = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            m.unconfirmedRecoveryKey.collect { it?.let { k -> shownKeys += k } }
        }
        server.confirmGate = kotlinx.coroutines.CompletableDeferred()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        val change = scope.async { runCatching { m.regenerateRecoveryKey() } }
        withTimeout(5_000) { while (server.current != KEY_B) delay(10) }
        m.signOut()
        server.confirmGate?.complete(Unit)
        withTimeout(5_000) { change.await() }
        delay(200)
        watcher.cancel()
        newKeyWatcher.cancel()
        assertTrue(KEY_B in shownKeys || slots().toList().contains(KEY_B), "the committed key reached the user or stays on the phone")
    }

    @Test
    fun `P5b sign-out requested while confirm is in flight waits for it and is cancelled by the finished change`() = runBlocking<Unit> {
        val m = newManager()
        m.createAnonymousAccount()
        server.confirmGate = kotlinx.coroutines.CompletableDeferred()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        val change = scope.async { runCatching { m.regenerateRecoveryKey() } }
        withTimeout(5_000) { while (server.current != KEY_B) delay(10) }
        val check = scope.async { m.prepareSignOut() }
        delay(100)
        assertFalse(check.isCompleted, "waits for the change in flight")
        server.confirmGate?.complete(Unit)

        assertIs<SignOutCheck.Finished>(withTimeout(5_000) { check.await() })
        change.await()
        assertEquals(KEY_B to null, slots())
        assertIs<SessionState.SignedIn>(m.state.value)
    }

    // ------------------------------------------------------------------
    // F3: a background finish raises a one-time notice
    // ------------------------------------------------------------------

    @Test
    fun `F3 a change finished by a background sign-in raises the notice, a tapped one does not`() = runBlocking<Unit> {
        val manager = newManager()
        manager.createAnonymousAccount()
        manager.regenerateRecoveryKey()
        assertFalse(manager.keyChangeFinishedNotice.value, "the user tapped: Profile shows the key itself")

        // Confirm committed, answer lost; the next cold start finishes the change on its own.
        server.nextKey = KEY_C
        server.commitThenDropConfirm = true
        manager.regenerateRecoveryKey()
        server.commitThenDropConfirm = false
        val restarted = restart()
        assertIs<SessionState.SignedIn>(restarted.startup())
        restarted.keyChangeFinishedNotice.awaitState("background finish: tell the user to save the new key") { it }

        assertEquals(KEY_C to null, slots(), "promoted")
        restarted.dismissKeyChangeFinishedNotice()
        assertFalse(restarted.keyChangeFinishedNotice.value)
    }

    // ------------------------------------------------------------------
    // F4 / F5
    // ------------------------------------------------------------------

    @Test
    fun `P4 create account from the retry screen drops another account's pending key`() = runBlocking<Unit> {
        store.map[KEY] = KEY_A
        store.map[PENDING] = KEY_B
        server.current = KEY_A
        server.pending = KEY_B
        server.meStatus = HttpStatusCode.ServiceUnavailable
        val manager = newManager()
        assertEquals(SessionState.RetryNeeded, manager.startup())
        server.meStatus = null
        server.userId = 77
        server.createKey = KEY_C
        manager.createAnonymousAccount()

        assertEquals(KEY_C to null, slots(), "a new account does not carry the previous account's pending key")
        assertFalse(manager.keyChangePending.value)
        assertEquals(KEY_C, manager.shownKey())
    }

    @Test
    fun `P6 sign-out between the regenerate answer and the pending write - the stale job writes nothing`() = runBlocking<Unit> {
        val m = newManager()
        m.createAnonymousAccount()
        store.holdPendingSet = kotlinx.coroutines.CompletableDeferred()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        val change = scope.async { runCatching { m.regenerateRecoveryKey() } }
        withTimeout(5_000) { while (!store.pendingSetReached) delay(10) }
        m.signOut()
        store.holdPendingSet?.complete(Unit)
        withTimeout(5_000) { change.await() }
        delay(200)

        assertEquals(SessionState.SignedOut, m.state.value, "a key change does not undo a sign-out")
        assertEquals(null to null, slots(), "nothing written after the sign-out")
        assertEquals(0, server.confirmCount, "no confirm for an ended session")
    }

    // ------------------------------------------------------------------
    // F7: rollback to HEAD with a pending key from the new server
    // ------------------------------------------------------------------

    @Test
    fun `F7 rollback to HEAD with a new-server pending key - Generate still gives a working new key`() = runBlocking<Unit> {
        val manager = newManager()
        manager.createAnonymousAccount()
        server.confirmUnavailable = true
        manager.regenerateRecoveryKey()
        assertEquals(KEY_A to KEY_B, slots())
        // Rollback: HEAD knows no pending keys; confirm is a 404.
        server.legacy = true
        server.pending = null
        server.nextKey = KEY_C

        manager.regenerateRecoveryKey()

        assertEquals(KEY_C, server.current, "a new regenerate was sent")
        assertEquals(KEY_C, manager.shownKey(), "the working new key is shown")
        assertIs<SessionState.SignedIn>(restart().startup())
        assertEquals(KEY_C to null, slots())
    }

    // ------------------------------------------------------------------
    // Round 3 (adv-kmp-rotation-code-r2): K2 is never called working until the server proves it
    // ------------------------------------------------------------------

    @Test
    fun `Q1 rollback to HEAD - the probe proves K2 dead and the user is left with K1 on screen`() = runBlocking<Unit> {
        val m0 = newManager()
        m0.createAnonymousAccount()
        server.confirmUnavailable = true
        m0.regenerateRecoveryKey()
        server.confirmUnavailable = false
        // Rollback: HEAD knows only recovery_key; K2 is dead.
        server.legacy = true
        server.pending = null
        val m = restart()
        assertIs<SessionState.SignedIn>(m.startup())
        m.keyChangeFailedNotice.awaitState("the user is told the new key did not take effect") { it }

        assertFalse(m.keyChangePending.value, "the probe settled the change")
        assertTrue(accepted(m.shownKey()), "the key on screen is accepted")
        assertEquals(KEY_A to null, slots(), "pending dropped, RECOVERY_KEY untouched")
        assertEquals(null, m.unconfirmedRecoveryKey.value)
        val check = m.prepareSignOut()
        assertIs<SignOutCheck.Clear>(check, "nothing unfinished is left")
        // "Sign out anyway" now erases only after the user has seen K1, the only accepted key.
    }

    @Test
    fun `Q2 web regenerate superseded K2 and confirm fails - the dialog shows K1 too`() = runBlocking<Unit> {
        val m = newManager()
        m.createAnonymousAccount()
        server.confirmUnavailable = true
        m.regenerateRecoveryKey()
        server.pending = KEY_C

        val check = m.prepareSignOut()

        val save = assertIs<SignOutCheck.SaveKeyFirst>(check)
        assertTrue(accepted(save.key), "the dialog's confirmed key is accepted")
        assertEquals(KEY_A, save.key)
        assertEquals(KEY_B, save.newKey, "the unproven new key is shown as such")
        assertEquals(KEY_A, m.shownKey())
    }

    @Test
    fun `Q3 sign-out while a background confirm writes - sign-out stays`() = runBlocking<Unit> {
        val m = newManager()
        m.createAnonymousAccount()
        server.confirmUnavailable = true
        m.regenerateRecoveryKey()
        server.confirmUnavailable = false
        settings.holdSetCached = kotlinx.coroutines.CompletableDeferred()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        val job = scope.async { runCatching { m.finishKeyChange() } }
        waitFor("applyUser reached cacheUser") { settings.setCachedReached }
        m.signOut()
        val held = settings.holdSetCached
        settings.holdSetCached = null
        held?.complete(Unit)
        withTimeout(5_000) { job.await() }
        delay(200)

        assertEquals(SessionState.SignedOut, m.state.value, "sign-out stays")
        assertEquals(null to null, slots(), "no key written after sign-out")
        assertEquals(null, settings.storedUser, "no cached_user left behind")
    }

    @Test
    fun `Q4 finished-change notice does not survive sign-out into another account`() = runBlocking<Unit> {
        val m = newManager()
        m.createAnonymousAccount()
        server.confirmUnavailable = true
        m.regenerateRecoveryKey()
        server.confirmUnavailable = false
        server.sessionAlive = false
        m.refreshUser()
        m.keyChangeFinishedNotice.awaitState("background promote raised the notice") { it }
        assertEquals(KEY_B to null, slots(), "background promote")
        m.signOut()
        server.userId = 77
        server.createKey = KEY_C
        m.createAnonymousAccount()

        assertFalse(m.keyChangeFinishedNotice.value, "a new account does not get the old account's notice")
        assertFalse(m.keyChangeFailedNotice.value)
    }

    @Test
    fun `probe 200 after confirm 404 promotes K2 at once (HEAD fallback)`() = runBlocking<Unit> {
        server.legacy = true
        val m = newManager()
        m.createAnonymousAccount()

        val result = m.regenerateRecoveryKey()

        assertIs<KeyRotation.Done>(result)
        assertEquals(KEY_B to null, slots(), "HEAD already made K2 current: promoted")
        assertEquals(KEY_B, server.loginKeys.last(), "proven by a sign-in with K2")
        assertFalse(m.keyChangePending.value, "not shown as unfinished")
        assertEquals(KEY_B, m.shownKey())
        assertIs<SignOutCheck.Clear>(m.prepareSignOut())
    }

    @Test
    fun `probe 401 after confirm 404 drops pending and never touches RECOVERY_KEY`() = runBlocking<Unit> {
        val m = newManager()
        m.createAnonymousAccount()
        server.confirmUnavailable = true
        m.regenerateRecoveryKey()
        server.confirmUnavailable = false
        server.legacy = true
        server.pending = null
        store.failSetKeys += KEY

        val result = m.finishKeyChange()

        assertIs<KeyRotation.Superseded>(result)
        assertEquals(KEY_A to null, slots())
        assertEquals(KEY_A, m.shownKey())
        assertFalse(m.keyChangePending.value)
        assertFalse(m.keyChangeFailedNotice.value, "a tap shows its own message")
    }

    @Test
    fun `probe without an answer after confirm 404 keeps both keys and shows both`() = runBlocking<Unit> {
        val m = newManager()
        m.createAnonymousAccount()
        server.confirmUnavailable = true
        m.regenerateRecoveryKey()
        server.confirmUnavailable = false
        server.legacy = true
        server.failLoginTransportFor = KEY_B
        val loginsBefore = server.loginKeys.size

        val result = m.finishKeyChange()

        assertIs<KeyRotation.Unfinished>(result)
        assertEquals(KEY_A to KEY_B, slots(), "unknown: keep both")
        assertEquals(KEY_A, m.shownKey())
        assertEquals(KEY_B, m.unconfirmedRecoveryKey.value)
        assertEquals(listOf(KEY_B), server.loginKeys.drop(loginsBefore), "one probe, no loop")
    }

    @Test
    fun `a background 409 drops pending, shows K1 and raises the failed notice`() = runBlocking<Unit> {
        val m0 = newManager()
        m0.createAnonymousAccount()
        server.confirmUnavailable = true
        m0.regenerateRecoveryKey()
        server.confirmUnavailable = false
        // The web replaced the pending key.
        server.pending = KEY_C
        val m = restart()

        assertIs<SessionState.SignedIn>(m.startup())
        m.keyChangeFailedNotice.awaitState("background confirm raised the failed notice") { it }

        assertFalse(m.keyChangePending.value, "background confirm settled")
        assertEquals(KEY_A to null, slots())
        assertEquals(KEY_A, m.shownKey())
        m.dismissKeyChangeFailedNotice()
        assertFalse(m.keyChangeFailedNotice.value)
    }

    @Test
    fun `R2-5 sign-out while a Generate lost its answer is uncertain, not clear`() = runBlocking<Unit> {
        server.legacy = true
        val m = newManager()
        m.createAnonymousAccount()
        server.regenerateGate = kotlinx.coroutines.CompletableDeferred()
        server.loseRegenerateAnswer = true
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        val change = scope.async { runCatching { m.regenerateRecoveryKey() } }
        waitFor("regenerate reached the server") { server.regenerateReached }
        val check = scope.async { m.prepareSignOut() }
        delay(100)
        server.regenerateGate?.complete(Unit)

        assertIs<SignOutCheck.Uncertain>(withTimeout(5_000) { check.await() })
        change.await()
        assertEquals(KEY_A to null, slots(), "nothing erased")
    }

    // ------------------------------------------------------------------
    // Round 4 (adv-kmp-rotation-code-r3)
    // ------------------------------------------------------------------

    @Test
    fun `S1 NotStored keeps the stored K1 on screen and puts K2 next to it`() = runBlocking<Unit> {
        val m = newManager()
        m.createAnonymousAccount()
        store.failSetKeys += PENDING

        val r = m.regenerateRecoveryKey()

        assertIs<KeyRotation.NotStored>(r)
        assertEquals(KEY_A, m.shownKey(), "the current, stored key stays on screen")
        assertEquals(KEY_B, m.unconfirmedRecoveryKey.value, "K2 goes next to it")
        val save = assertIs<SignOutCheck.SaveKeyFirst>(m.prepareSignOut())
        assertEquals(KEY_A, save.key)
        assertEquals(KEY_B, save.newKey)
    }

    @Test
    fun `S2 HEAD - sign-out after a Generate that already lost its answer stays uncertain until proven`() = runBlocking<Unit> {
        server.legacy = true
        val m = newManager()
        m.createAnonymousAccount()
        server.loseRegenerateAnswer = true
        assertTrue(runCatching { m.regenerateRecoveryKey() }.isFailure)
        server.loseRegenerateAnswer = false

        assertIs<SignOutCheck.Uncertain>(m.prepareSignOut(), "the lost answer still blocks a plain sign-out")
        assertIs<SignOutCheck.Uncertain>(m.prepareSignOut(), "and keeps blocking it")

        // A successful regenerate proves the key state again.
        server.nextKey = KEY_C
        assertIs<KeyRotation.Done>(m.regenerateRecoveryKey())
        assertIs<SignOutCheck.Clear>(m.prepareSignOut())
    }

    @Test
    fun `R3-3 a typed key of another account resets both notices and the unproven key`() = runBlocking<Unit> {
        val m = newManager()
        m.createAnonymousAccount()
        server.confirmUnavailable = true
        m.regenerateRecoveryKey()
        server.confirmUnavailable = false
        // A background finish raised the notice for this account; a new change is unproven again.
        server.sessionAlive = false
        m.refreshUser()
        m.keyChangeFinishedNotice.awaitState("background promote raised the notice") { it }
        assertEquals(KEY_B to null, slots(), "background promote")
        server.nextKey = KEY_C
        server.confirmUnavailable = true
        m.regenerateRecoveryKey()
        assertEquals(KEY_C, m.unconfirmedRecoveryKey.value)
        // Another account's key, typed on the login screen.
        val otherKey = "0000000d-0000-4000-8000-000000000099"
        server.userId = 99
        server.current = otherKey
        server.pending = null

        m.signInWithRecoveryKey(otherKey)

        assertEquals(otherKey to null, slots())
        assertFalse(m.keyChangeFinishedNotice.value, "no notice of the previous account")
        assertFalse(m.keyChangeFailedNotice.value)
        assertEquals(null, m.unconfirmedRecoveryKey.value)
    }

    @Test
    fun `R4-1 a probe that lands on another account drops that pending key and signs straight back in with K1`() = runBlocking<Unit> {
        server.legacy = true
        val m = newManager()
        m.createAnonymousAccount()
        // A pending key of another account (99) in the slot; HEAD answers confirm with 404.
        val foreignKey = "0000000d-0000-4000-8000-000000000099"
        store.map[PENDING] = foreignKey
        server.foreignKeys[foreignKey] = 99
        val loginsBefore = server.loginKeys.size

        val r = m.finishKeyChange()

        assertIs<KeyRotation.Superseded>(r, "the foreign key is useless for this account")
        assertEquals(KEY_A to null, slots(), "pending removed, K1 kept, K2 never written to RECOVERY_KEY")
        assertEquals(listOf(foreignKey, KEY_A), server.loginKeys.drop(loginsBefore), "probe, then straight back in with K1")
        assertEquals(55, server.sessionAccount, "the shared cookie belongs to this account again")
        assertEquals(null, m.unconfirmedRecoveryKey.value)
        assertFalse(m.keyChangePending.value)
        assertEquals("55", (m.state.value as? SessionState.SignedIn)?.user?.id)

        val loginsAfter = server.loginKeys.size
        val confirmsAfter = server.confirmCount
        m.refreshUser()
        delay(200)
        assertEquals(loginsAfter, server.loginKeys.size, "no second probe on the next request")
        assertEquals(confirmsAfter, server.confirmCount)
    }

    private companion object {
        const val KEY_A = "0000000a-0000-4000-8000-000000000055"
        const val KEY_B = "0000000b-0000-4000-8000-000000000055"
        const val KEY_C = "0000000c-0000-4000-8000-000000000077"
        const val PENDING = SecureStoreKeys.RECOVERY_KEY_PENDING
        const val KEY = SecureStoreKeys.RECOVERY_KEY
    }
}
