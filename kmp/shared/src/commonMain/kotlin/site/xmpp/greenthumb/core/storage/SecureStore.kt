package site.xmpp.greenthumb.core.storage

/**
 * Кроссплатформенное secure-хранилище невосстановимых креденшлов (recovery key)
 * — architecture.md §6, VAL-STOR-001.
 *
 * Контракт:
 * - get(key) — значение или null (не записано / хранилище нечитаемо);
 * - set(key, value) — Boolean: true = запись надёжно сохранена, false = провал.
 *   Провал ОБЯЗАН быть видим вызывающему: иначе handoff-файл удалится,
 *   а ключ не сохранится (VAL-HANDOFF-IMP-005);
 * - remove(key) — идемпотентное удаление; IO-ошибка пробрасывается
 *   (выход из аккаунта обязан увидеть провал чистки ключа).
 *
 * Платформенные actual'ы:
 * - androidMain: ключ AES-256-GCM в AndroidKeyStore (KeyGenParameterSpec),
 *   шифротекст в DataStore-файле, исключённом из Auto Backup / Device Transfer
 *   (security-crypto deprecated — не используется);
 * - jvmMain: файл-контейнер AES-GCM в каталоге пользователя (desktop-харнесс).
 *
 * iOS Keychain — вне скоупа миссии (iOS-таргетов нет).
 */
public expect class SecureStore {
    suspend fun get(key: String): String?
    suspend fun set(key: String, value: String): Boolean
    suspend fun remove(key: String)
}

/** Имена ключей SecureStore (значения — из RN lib/constants.ts, переносятся 1:1). */
public object SecureStoreKeys {
    /** Recovery key: auto-login на последующих запусках (RECOVERY_KEY_STORE_KEY в RN). */
    public const val RECOVERY_KEY: String = "greenthumb_recovery_key"
}
