package site.xmpp.greenthumb.core.storage

import site.xmpp.greenthumb.core.network.ApiError
import site.xmpp.greenthumb.core.network.SessionRecoveryProvider

/**
 * The [site.xmpp.greenthumb.core.network.ApiClient] silent re-login seam backed by a
 * [SessionManager]. [manager] is read lazily: the graph builds the client before the manager.
 */
public class SessionManagerRecovery(private val manager: () -> SessionManager) : SessionRecoveryProvider {
    override suspend fun getRecoveryKey(): String? = manager().sessionRecoveryKey()

    override suspend fun getRecoveryKeys(): List<String> = manager().sessionRecoveryKeys()

    override suspend fun onReloginSucceeded(key: String) {
        manager().onReloginSucceeded(key)
    }

    override suspend fun onRecoveryKeysRejected(keys: List<String>) {
        manager().onRecoveryKeysRejected(keys)
    }

    override suspend fun onSessionReset() {
        manager().onAuthError(ApiError.Unauthorized)
    }

    override suspend fun onSessionLost() {
        manager().onSessionLost()
    }
}
