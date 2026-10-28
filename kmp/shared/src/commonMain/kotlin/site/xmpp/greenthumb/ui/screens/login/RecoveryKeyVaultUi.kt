package site.xmpp.greenthumb.ui.screens.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import site.xmpp.greenthumb.core.platform.Haptics
import site.xmpp.greenthumb.core.platform.LocalRecoveryKeyVault
import site.xmpp.greenthumb.core.platform.RecoveryKeyVault
import site.xmpp.greenthumb.core.platform.VaultSaveResult
import site.xmpp.greenthumb.ui.components.SecondaryButton
import site.xmpp.greenthumb.ui.components.gtButtonWidth
import site.xmpp.greenthumb.ui.res.Res
import site.xmpp.greenthumb.ui.res.vault_saveFailed
import site.xmpp.greenthumb.ui.res.vault_saveKey
import site.xmpp.greenthumb.ui.res.vault_saveNewKey
import site.xmpp.greenthumb.ui.res.vault_saveUnavailable
import site.xmpp.greenthumb.ui.res.vault_saved
import site.xmpp.greenthumb.ui.res.vault_signInWithSavedKey
import site.xmpp.greenthumb.ui.theme.GreenThumbColors
import site.xmpp.greenthumb.ui.theme.Spacing

internal enum class VaultSaveStatus { Idle, Saving, Saved, Failed, Unavailable }

/** Save-to-password-manager state of one screen; a cancelled sheet keeps the previous status. */
@Stable
internal class VaultSaver(private val vault: RecoveryKeyVault, private val scope: CoroutineScope) {
    var status: VaultSaveStatus by mutableStateOf(
        if (vault.isSupported) VaultSaveStatus.Idle else VaultSaveStatus.Unavailable,
    )
        private set

    fun save(userId: String, recoveryKey: String) {
        val before = status
        if (before == VaultSaveStatus.Saving || before == VaultSaveStatus.Unavailable) return
        status = VaultSaveStatus.Saving
        scope.launch {
            status = when (vault.save(userId, recoveryKey)) {
                VaultSaveResult.Saved -> {
                    Haptics.success()
                    VaultSaveStatus.Saved
                }
                VaultSaveResult.Cancelled -> before
                VaultSaveResult.Unsupported -> VaultSaveStatus.Unavailable
                VaultSaveResult.Failed -> VaultSaveStatus.Failed
            }
        }
    }

    /** A new key makes an earlier "saved" confirmation wrong. */
    fun resetConfirmation() {
        if (status == VaultSaveStatus.Saved || status == VaultSaveStatus.Failed) status = VaultSaveStatus.Idle
    }
}

@Composable
internal fun rememberVaultSaver(): VaultSaver {
    val vault = LocalRecoveryKeyVault.current
    val scope = rememberCoroutineScope()
    return remember(vault, scope) { VaultSaver(vault, scope) }
}

/**
 * "Save key to password manager" with its inline status. Without a password manager the
 * button is replaced by an explanation; copying the key stays the fallback.
 */
@Composable
internal fun SaveKeyToPasswordManager(
    saver: VaultSaver,
    userId: String,
    recoveryKey: String,
    isNewKey: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        if (saver.status == VaultSaveStatus.Unavailable) {
            Text(
                text = stringResource(Res.string.vault_saveUnavailable),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.testTag("vault_status"),
            )
            return@Column
        }
        SecondaryButton(
            text = stringResource(if (isNewKey) Res.string.vault_saveNewKey else Res.string.vault_saveKey),
            onClick = { saver.save(userId, recoveryKey) },
            enabled = saver.status != VaultSaveStatus.Saving,
            modifier = Modifier.gtButtonWidth().testTag("vault_save"),
        )
        when (saver.status) {
            VaultSaveStatus.Saved -> Text(
                text = stringResource(Res.string.vault_saved),
                style = MaterialTheme.typography.bodySmall,
                color = GreenThumbColors.success,
                modifier = Modifier.testTag("vault_status"),
            )
            VaultSaveStatus.Failed -> Text(
                text = stringResource(Res.string.vault_saveFailed),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.error,
                modifier = Modifier.testTag("vault_status"),
            )
            else -> Unit
        }
    }
}

/**
 * "Sign in with saved key" plus the message of the last attempt. Hidden when
 * the platform has no bridge or the last attempt found no provider.
 */
@Composable
internal fun SavedKeySignInBlock(
    onClick: () -> Unit,
    outcome: SavedKeyOutcome?,
    enabled: Boolean,
) {
    val supported = LocalRecoveryKeyVault.current.isSupported
    val showButton = supported && outcome != SavedKeyOutcome.Unsupported
    val message = savedKeyMessage(outcome)
    if (!showButton && message == null) return
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        if (showButton) {
            SecondaryButton(
                text = stringResource(Res.string.vault_signInWithSavedKey),
                onClick = onClick,
                enabled = enabled,
                modifier = Modifier.gtButtonWidth().testTag("vault_signIn"),
            )
        }
        if (message != null) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("vault_message"),
            )
        }
    }
}
