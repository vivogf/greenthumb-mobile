package site.xmpp.greenthumb.ui.screens.login

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.MutableStateFlow
import site.xmpp.greenthumb.core.network.ApiClient
import site.xmpp.greenthumb.core.network.GreenThumbApi
import site.xmpp.greenthumb.core.network.SessionRecoveryProvider
import site.xmpp.greenthumb.core.network.UserDto
import site.xmpp.greenthumb.core.platform.RecoveryKeyVault
import site.xmpp.greenthumb.core.platform.VaultGetResult
import site.xmpp.greenthumb.core.platform.VaultSaveResult
import site.xmpp.greenthumb.core.platform.recoveryKeyCredentialId
import site.xmpp.greenthumb.core.storage.AppLanguage
import site.xmpp.greenthumb.core.storage.AppPreferencesStore
import site.xmpp.greenthumb.core.storage.HandoffPayload
import site.xmpp.greenthumb.core.storage.HandoffSource
import site.xmpp.greenthumb.core.storage.LayoutMode
import site.xmpp.greenthumb.core.storage.SecureKeyValueStore
import site.xmpp.greenthumb.core.storage.SessionManager
import site.xmpp.greenthumb.core.storage.ThemePreference

/** Placeholder keys shaped like server recovery keys (UUID). Not real secrets. */
internal const val VAULT_TEST_KEY: String = "0f0e0d0c-0b0a-4908-8706-050403020100"
internal const val VAULT_TEST_NEW_KEY: String = "1a1b1c1d-1e1f-4a2b-8c2d-2e2f30313233"
internal const val VAULT_TEST_USER_ID: String = "4242"

/** Scripted vault: returns the queued outcome and records every call. */
internal class FakeVault(
    override val isSupported: Boolean = true,
    var saveResult: VaultSaveResult = VaultSaveResult.Saved,
    var getResult: VaultGetResult = VaultGetResult.Cancelled,
) : RecoveryKeyVault {
    val savedIds = mutableListOf<String>()
    val savedKeys = mutableListOf<String>()
    var getCalls = 0

    override suspend fun save(userId: String, recoveryKey: String): VaultSaveResult {
        savedIds += recoveryKeyCredentialId(userId)
        savedKeys += recoveryKey
        return saveResult
    }

    override suspend fun get(): VaultGetResult {
        getCalls++
        return getResult
    }
}

internal class VaultFakeSecureStore : SecureKeyValueStore {
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

internal class VaultFakeSettings : AppPreferencesStore {
    private val cachedUserState = MutableStateFlow<UserDto?>(null)
    override suspend fun getLanguage(): AppLanguage? = null
    override suspend fun setLanguage(language: AppLanguage) = Unit
    override suspend fun getLayoutMode(): LayoutMode? = null
    override suspend fun setLayoutMode(mode: LayoutMode) = Unit
    override suspend fun getTheme(): ThemePreference? = null
    override suspend fun setTheme(theme: ThemePreference) = Unit
    override suspend fun isIntroSeen(): Boolean = true
    override suspend fun setIntroSeen() = Unit
    override suspend fun getCachedUser(): UserDto? = cachedUserState.value
    override suspend fun setCachedUser(user: UserDto) {
        cachedUserState.value = user
    }
    override suspend fun clearCachedUser() {
        cachedUserState.value = null
    }
    override val language = MutableStateFlow<AppLanguage?>(null)
    override val layoutMode = MutableStateFlow<LayoutMode?>(null)
    override val theme = MutableStateFlow<ThemePreference?>(null)
    override val introSeen = MutableStateFlow(true)
    override val cachedUser = cachedUserState
    override suspend fun applyLegacyHandoffValues(
        language: AppLanguage?,
        theme: ThemePreference?,
        layoutMode: LayoutMode?,
        introSeen: Boolean?,
    ) = Unit
}

internal class VaultNoHandoff : HandoffSource {
    override fun readHandoff(): HandoffPayload? = null
    override fun clearHandoff() {}
}

/** Recorded request: method + path + body text (bodies here are tiny JSON). */
internal data class RecordedCall(val method: String, val path: String, val body: String)

internal fun userJson(id: String = VAULT_TEST_USER_ID, key: String = VAULT_TEST_KEY): String =
    """{"user":{"id":$id,"name":null,"recovery_key":"$key","created_at":"2026-10-27T00:00:00.000Z"}}"""

internal fun HttpRequestData.bodyText(): String =
    (body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString() ?: ""

/**
 * Session over a MockEngine. [respond] decides each response; every call is
 * appended to [calls] so tests can prove which transport carried the key.
 */
internal fun vaultSession(
    calls: MutableList<RecordedCall>,
    respond: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
): SessionManager {
    val api = GreenThumbApi(
        ApiClient(
            MockEngine { request ->
                calls += RecordedCall(request.method.value, request.url.encodedPath, request.bodyText())
                respond(request)
            },
            object : SessionRecoveryProvider {
                override suspend fun getRecoveryKey(): String? = null
                override suspend fun onSessionReset() = Unit
            },
        ),
    )
    return SessionManager(VaultFakeSecureStore(), VaultFakeSettings(), VaultNoHandoff(), api)
}

/** Login-recovery accepts only [acceptedKey]; any other key gets the server's 401 body. */
internal fun loginRecoveryResponder(
    acceptedKey: String = VAULT_TEST_KEY,
    /** Fixed confirm-recovery-key answer (e.g. 503); OK = committed. */
    confirmStatus: HttpStatusCode = HttpStatusCode.OK,
): suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { request ->
    val json = headersOf("Content-Type", "application/json")
    when (request.url.encodedPath) {
        "/api/auth/login-recovery" ->
            if (request.bodyText().contains(acceptedKey)) {
                respond(userJson(key = acceptedKey), HttpStatusCode.OK, json)
            } else {
                respond("""{"error":"Invalid recovery key"}""", HttpStatusCode.Unauthorized, json)
            }
        // Two-phase key change: the new key arrives only as `pending_recovery_key`.
        "/api/auth/regenerate-recovery-key" ->
            respond(
                userJson(key = acceptedKey).removeSuffix("}") + ""","pending_recovery_key":"$VAULT_TEST_NEW_KEY"}""",
                HttpStatusCode.OK,
                json,
            )
        "/api/auth/confirm-recovery-key" ->
            if (confirmStatus == HttpStatusCode.OK) {
                respond(userJson(key = VAULT_TEST_NEW_KEY).removeSuffix("}") + ""","status":"committed"}""", HttpStatusCode.OK, json)
            } else {
                respond("{}", confirmStatus, json)
            }
        else -> respond("{}", HttpStatusCode.NotFound, json)
    }
}
