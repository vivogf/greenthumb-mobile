package site.xmpp.greenthumb.core.platform

/** Desktop harness: no password manager bridge, manual key paths only. */
private object UnsupportedRecoveryKeyVault : RecoveryKeyVault {
    override val isSupported: Boolean = false

    override suspend fun save(userId: String, recoveryKey: String): VaultSaveResult = VaultSaveResult.Unsupported

    override suspend fun get(): VaultGetResult = VaultGetResult.Unsupported
}

public actual fun platformRecoveryKeyVault(): RecoveryKeyVault = UnsupportedRecoveryKeyVault
