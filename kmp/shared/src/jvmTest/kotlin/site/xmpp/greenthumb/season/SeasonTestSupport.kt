package site.xmpp.greenthumb.season

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeout
import site.xmpp.greenthumb.core.network.ApiClient
import site.xmpp.greenthumb.core.network.GreenThumbApi
import site.xmpp.greenthumb.core.network.UserDto
import site.xmpp.greenthumb.core.storage.AppLanguage
import site.xmpp.greenthumb.core.storage.AppPreferencesStore
import site.xmpp.greenthumb.core.storage.HandoffPayload
import site.xmpp.greenthumb.core.storage.HandoffSource
import site.xmpp.greenthumb.core.storage.LayoutMode
import site.xmpp.greenthumb.core.storage.SecureKeyValueStore
import site.xmpp.greenthumb.core.storage.SecureStoreKeys
import site.xmpp.greenthumb.core.storage.SessionManager
import site.xmpp.greenthumb.core.storage.ThemePreference

internal class MemorySecureStore : SecureKeyValueStore {
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

internal class MemorySettings : AppPreferencesStore {
    private val languageState = MutableStateFlow<AppLanguage?>(null)
    private val themeState = MutableStateFlow<ThemePreference?>(null)
    private val layoutState = MutableStateFlow<LayoutMode?>(null)
    private val introState = MutableStateFlow(true)
    val cachedUserState = MutableStateFlow<UserDto?>(null)

    override suspend fun getLanguage(): AppLanguage? = languageState.value
    override suspend fun setLanguage(language: AppLanguage) { languageState.value = language }
    override suspend fun getLayoutMode(): LayoutMode? = layoutState.value
    override suspend fun setLayoutMode(mode: LayoutMode) { layoutState.value = mode }
    override suspend fun getTheme(): ThemePreference? = themeState.value
    override suspend fun setTheme(theme: ThemePreference) { themeState.value = theme }
    override suspend fun isIntroSeen(): Boolean = introState.value
    override suspend fun setIntroSeen() { introState.value = true }
    override suspend fun getCachedUser(): UserDto? = cachedUserState.value
    override suspend fun setCachedUser(user: UserDto) { cachedUserState.value = user }
    override suspend fun clearCachedUser() { cachedUserState.value = null }

    override val language: Flow<AppLanguage?> = languageState
    override val layoutMode: Flow<LayoutMode?> = layoutState
    override val theme: Flow<ThemePreference?> = themeState
    override val introSeen: Flow<Boolean> = introState
    override val cachedUser: Flow<UserDto?> = cachedUserState

    override suspend fun applyLegacyHandoffValues(
        language: AppLanguage?,
        theme: ThemePreference?,
        layoutMode: LayoutMode?,
        introSeen: Boolean?,
    ) = Unit
}

internal object NoHandoffSource : HandoffSource {
    override fun readHandoff(): HandoffPayload? = null
    override fun clearHandoff() = Unit
}

/**
 * Fake auth server for the season switcher and timezone sync: `/me` answers with the stored user
 * (or 401 when [signedIn] is false), the two PATCH routes store the body and count the calls.
 */
internal class SeasonFakeServer {
    var signedIn = true
    var meFailure: Throwable? = null
    var seasonMode: String? = "auto"
    var timezone: String? = null
    var seasonStatus: HttpStatusCode = HttpStatusCode.OK
    var seasonFailure: Throwable? = null
    var timezoneStatus: HttpStatusCode = HttpStatusCode.OK
    var timezoneFailure: Throwable? = null

    /** When set, the season PATCH waits for it before it answers. */
    var seasonGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    val seasonBodies = java.util.Collections.synchronizedList(mutableListOf<String>())
    val timezoneBodies = java.util.Collections.synchronizedList(mutableListOf<String>())

    private val jsonHeaders = headersOf("Content-Type", "application/json")

    fun userJson(): String {
        val mode = seasonMode?.let { "\"$it\"" } ?: "null"
        val zone = timezone?.let { "\"$it\"" } ?: "null"
        return """{"user":{"id":501,"name":"gt-test-season","recovery_key":"season-fixture-key",""" +
            """"notification_time":"09:00","timezone":$zone,"season_mode":$mode,""" +
            """"created_at":"2026-10-27T06:00:00.000Z"}}"""
    }

    private fun field(body: String, name: String): String? =
        Regex("\"$name\":\"([^\"]*)\"").find(body)?.groupValues?.get(1)

    suspend fun MockRequestHandleScope.handle(request: HttpRequestData): HttpResponseData {
        val body = (request.body as? TextContent)?.text.orEmpty()
        return when (request.url.encodedPath) {
            "/api/auth/me" ->
                if (meFailure != null) throw meFailure!!
                else if (signedIn) respond(userJson(), HttpStatusCode.OK, jsonHeaders)
                else respond("""{"error":"Not authenticated"}""", HttpStatusCode.Unauthorized, jsonHeaders)
            "/api/auth/create-anonymous" -> {
                signedIn = true
                respond(userJson(), HttpStatusCode.OK, jsonHeaders)
            }
            "/api/auth/update-season-mode" -> {
                seasonBodies += body
                seasonGate?.await()
                seasonFailure?.let { throw it }
                if (seasonStatus == HttpStatusCode.OK) {
                    seasonMode = field(body, "season_mode")
                    respond(userJson(), HttpStatusCode.OK, jsonHeaders)
                } else {
                    respond("""{"error":"failed"}""", seasonStatus, jsonHeaders)
                }
            }
            "/api/auth/update-timezone" -> {
                timezoneBodies += body
                timezoneFailure?.let { throw it }
                if (timezoneStatus == HttpStatusCode.OK) {
                    timezone = field(body, "timezone")
                    respond(userJson(), HttpStatusCode.OK, jsonHeaders)
                } else {
                    respond("""{"error":"failed"}""", timezoneStatus, jsonHeaders)
                }
            }
            "/api/plants" -> respond("[]", HttpStatusCode.OK, jsonHeaders)
            else -> respond("""{"error":"not found"}""", HttpStatusCode.NotFound, jsonHeaders)
        }
    }
}

internal class SeasonHarness(
    val server: SeasonFakeServer = SeasonFakeServer(),
) : AutoCloseable {
    val secure = MemorySecureStore()
    val settings = MemorySettings()
    val client = ApiClient(MockEngine { request -> with(server) { handle(request) } })
    val api = GreenThumbApi(client)
    val session = SessionManager(secure, settings, NoHandoffSource, api)

    suspend fun signedInWithKey() {
        secure.set(SecureStoreKeys.RECOVERY_KEY, "season-fixture-key")
        session.startup()
    }

    override fun close() {
        runCatching { client.close() }
    }
}

internal suspend fun waitFor(timeoutMillis: Long = 5_000, condition: () -> Boolean) {
    withTimeout(timeoutMillis) {
        while (!condition()) delay(10)
    }
}
