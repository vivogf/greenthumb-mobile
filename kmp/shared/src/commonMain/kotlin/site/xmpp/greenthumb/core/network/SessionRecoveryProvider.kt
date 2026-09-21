package site.xmpp.greenthumb.core.network

/**
 * Провайдер recovery key для восстановления сессии (Stage 2 п.7; architecture.md §5).
 * Связывает [ApiClient] с хранилищем ключа: интерфейс в M2, реализация на
 * SecureStore приходит в M3 (Stage 3) — до того клиент работает без
 * восстановления ([NoSessionRecoveryProvider]).
 *
 *  - [getRecoveryKey] — сохранённый ключ (RN: `SecureStore.getItemAsync` в
 *    `contexts/AuthContext.tsx:64`) или null, когда ключа нет;
 *  - [onSessionReset] — второй 401: сброс сессии, экран входа (RN-паранойя:
 *    только явный 401 чистит ключ; `contexts/AuthContext.tsx:85-88`).
 */
interface SessionRecoveryProvider {
    /** Сохранённый ключ или null, если восстановить нечем. */
    fun getRecoveryKey(): String?

    /** Второй 401: стереть ключ / уведомить UI («экран входа»). */
    fun onSessionReset()
}

/** Дефолт: восстановления нет (ключа нет, сброса нет). Заглушка до M3. */
data object NoSessionRecoveryProvider : SessionRecoveryProvider {
    override fun getRecoveryKey(): String? = null
    override fun onSessionReset() {}
}
