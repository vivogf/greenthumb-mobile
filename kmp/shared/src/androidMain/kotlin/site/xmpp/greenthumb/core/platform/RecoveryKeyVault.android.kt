package site.xmpp.greenthumb.core.platform

import android.content.MutableContextWrapper
import androidx.credentials.CreatePasswordRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPasswordOption
import androidx.credentials.PasswordCredential
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.CreateCredentialNoCreateOptionException
import androidx.credentials.exceptions.CreateCredentialProviderConfigurationException
import androidx.credentials.exceptions.CreateCredentialUnsupportedException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException

/**
 * Credential Manager bridge (androidx.credentials 1.6.0 + play-services-auth
 * for API ≤ 33). The system UI needs the foreground Activity; it is wrapped in
 * a [MutableContextWrapper] so the manager does not keep the Activity alive.
 * Exceptions are mapped to results without their messages: provider messages
 * may echo request data.
 */
private object CredentialManagerRecoveryKeyVault : RecoveryKeyVault {
    override val isSupported: Boolean = true

    override suspend fun save(userId: String, recoveryKey: String): VaultSaveResult {
        val activity = AppActivityHolder.activity ?: return VaultSaveResult.Failed
        val manager = CredentialManager.create(activity.applicationContext)
        return try {
            manager.createCredential(
                context = MutableContextWrapper(activity),
                request = CreatePasswordRequest(id = recoveryKeyCredentialId(userId), password = recoveryKey),
            )
            VaultSaveResult.Saved
        } catch (e: CreateCredentialException) {
            when (e) {
                is CreateCredentialCancellationException -> VaultSaveResult.Cancelled
                is CreateCredentialProviderConfigurationException,
                is CreateCredentialUnsupportedException,
                is CreateCredentialNoCreateOptionException,
                -> VaultSaveResult.Unsupported
                else -> VaultSaveResult.Failed
            }
        }
    }

    override suspend fun get(): VaultGetResult {
        val activity = AppActivityHolder.activity ?: return VaultGetResult.Failed
        val manager = CredentialManager.create(activity.applicationContext)
        return try {
            val response = manager.getCredential(
                context = MutableContextWrapper(activity),
                request = GetCredentialRequest(credentialOptions = listOf(GetPasswordOption())),
            )
            val password = (response.credential as? PasswordCredential)?.password
            if (password.isNullOrBlank()) VaultGetResult.Failed else VaultGetResult.Found(password)
        } catch (e: GetCredentialException) {
            when (e) {
                is GetCredentialCancellationException -> VaultGetResult.Cancelled
                is NoCredentialException -> VaultGetResult.NoCredential
                is GetCredentialProviderConfigurationException,
                is GetCredentialUnsupportedException,
                -> VaultGetResult.Unsupported
                else -> VaultGetResult.Failed
            }
        }
    }
}

public actual fun platformRecoveryKeyVault(): RecoveryKeyVault = CredentialManagerRecoveryKeyVault
