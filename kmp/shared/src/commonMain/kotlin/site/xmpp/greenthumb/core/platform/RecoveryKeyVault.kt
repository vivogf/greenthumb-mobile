package site.xmpp.greenthumb.core.platform

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

/** Outcome of saving the recovery key to the platform password manager. */
public sealed interface VaultSaveResult {
    public data object Saved : VaultSaveResult

    /** The user dismissed the system sheet. Callers stay silent. */
    public data object Cancelled : VaultSaveResult

    /** No usable password manager on this device; the manual copy path stays. */
    public data object Unsupported : VaultSaveResult

    public data object Failed : VaultSaveResult
}

/** Outcome of picking a saved recovery key from the platform password manager. */
public sealed interface VaultGetResult {
    public class Found(public val recoveryKey: String) : VaultGetResult {
        override fun toString(): String = "Found(recoveryKey=<redacted>)"
    }

    /** The user dismissed the picker. No automatic retry. */
    public data object Cancelled : VaultGetResult

    public data object NoCredential : VaultGetResult

    public data object Unsupported : VaultGetResult

    public data object Failed : VaultGetResult
}

/**
 * Recovery key as a password entry (architecture §5.3). Android: Credential
 * Manager with the foreground Activity; desktop harness: always [isSupported]
 * = false. Implementations never log the key.
 */
public interface RecoveryKeyVault {
    /** False when the platform has no password manager bridge at all. */
    public val isSupported: Boolean

    public suspend fun save(userId: String, recoveryKey: String): VaultSaveResult

    public suspend fun get(): VaultGetResult
}

/**
 * Credential id shown in the system sheet and picker. Stable and not localized:
 * saving again after regeneration with the same id updates the existing entry.
 */
public fun recoveryKeyCredentialId(userId: String): String = "GreenThumb #$userId"

public expect fun platformRecoveryKeyVault(): RecoveryKeyVault

/** Screens read the vault from here so tests can provide a scripted one. */
public val LocalRecoveryKeyVault: ProvidableCompositionLocal<RecoveryKeyVault> =
    staticCompositionLocalOf { platformRecoveryKeyVault() }
