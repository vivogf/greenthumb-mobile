package site.xmpp.greenthumb.ui.screens.login

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.stringResource
import site.xmpp.greenthumb.core.network.ApiError
import site.xmpp.greenthumb.core.platform.RecoveryKeyVault
import site.xmpp.greenthumb.core.platform.VaultGetResult
import site.xmpp.greenthumb.core.storage.SessionManager
import site.xmpp.greenthumb.ui.res.Res
import site.xmpp.greenthumb.ui.res.login_errors_invalidRecoveryKey
import site.xmpp.greenthumb.ui.res.login_errors_networkError
import site.xmpp.greenthumb.ui.res.login_errors_serviceUnavailable
import site.xmpp.greenthumb.ui.res.vault_noCredential
import site.xmpp.greenthumb.ui.res.vault_restoreFailed
import site.xmpp.greenthumb.ui.res.vault_restoreUnavailable
import site.xmpp.greenthumb.ui.res.vault_staleKey

/** Result of "Sign in with saved key". */
public sealed interface SavedKeyOutcome {
    public data object SignedIn : SavedKeyOutcome
    public data object Cancelled : SavedKeyOutcome
    public data object NoCredential : SavedKeyOutcome
    public data object Unsupported : SavedKeyOutcome
    public data object Failed : SavedKeyOutcome

    /** The server rejected the saved key: it was regenerated elsewhere or is malformed. */
    public data object StaleKey : SavedKeyOutcome

    /** Network or service failure during login-recovery; the saved key may still be valid. */
    public data class AuthFailed(public val error: ApiError) : SavedKeyOutcome
}

/**
 * Picks a saved key and signs in with it through the normal login-recovery
 * path ([SessionManager.signInWithRecoveryKey]); there is no second transport.
 */
public suspend fun signInWithSavedKey(vault: RecoveryKeyVault, session: SessionManager): SavedKeyOutcome {
    if (!vault.isSupported) return SavedKeyOutcome.Unsupported
    val key = when (val picked = vault.get()) {
        is VaultGetResult.Found -> picked.recoveryKey
        VaultGetResult.Cancelled -> return SavedKeyOutcome.Cancelled
        VaultGetResult.NoCredential -> return SavedKeyOutcome.NoCredential
        VaultGetResult.Unsupported -> return SavedKeyOutcome.Unsupported
        VaultGetResult.Failed -> return SavedKeyOutcome.Failed
    }
    return try {
        session.signInWithRecoveryKey(key)
        SavedKeyOutcome.SignedIn
    } catch (e: ApiError) {
        // A malformed key gets 400 from the server (uuid parse error), not 401.
        if (e is ApiError.Unauthorized || (e is ApiError.Client && e.status == 400)) {
            SavedKeyOutcome.StaleKey
        } else {
            SavedKeyOutcome.AuthFailed(e)
        }
    }
}

/** User-facing text for an outcome; null when the outcome stays silent. */
@Composable
internal fun savedKeyMessage(outcome: SavedKeyOutcome?): String? = when (outcome) {
    null, SavedKeyOutcome.SignedIn, SavedKeyOutcome.Cancelled -> null
    SavedKeyOutcome.NoCredential -> stringResource(Res.string.vault_noCredential)
    SavedKeyOutcome.Unsupported -> stringResource(Res.string.vault_restoreUnavailable)
    SavedKeyOutcome.Failed -> stringResource(Res.string.vault_restoreFailed)
    SavedKeyOutcome.StaleKey -> stringResource(Res.string.vault_staleKey)
    is SavedKeyOutcome.AuthFailed -> explainAuthError(
        outcome.error,
        isLogin = true,
        invalidKeyText = stringResource(Res.string.login_errors_invalidRecoveryKey),
        networkErrorText = stringResource(Res.string.login_errors_networkError),
        serviceUnavailableText = stringResource(Res.string.login_errors_serviceUnavailable),
    )
}
