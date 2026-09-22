package site.xmpp.greenthumb.core.network

/**
 * Провайдер recovery key для восстановления сессии (Stage 2 п.7; architecture.md §5).
 * Связывает [ApiClient] с хранилищем ключа: интерфейс в M2, реализация на
 * SecureStore приходит в M3 (Stage 3 п.4, [SessionManager]) — до того клиент
 * работает без восстановления ([NoSessionRecoveryProvider]).
 *
 * Оба метода suspend с M3 (осознанное изменение интерфейса M2): реальное
 * хранилище ключа ([SecureStore]/[SecureKeyValueStore]) — suspend-чтение
 * DataStore-файла, и его чтение нельзя выдавать за синхронное.
 *
 *  - [getRecoveryKey] — сохранённый ключ (RN: `SecureStore.getItemAsync` в
 *    `contexts/AuthContext.tsx:64`) или null, когда ключа нет;
 *  - [onSessionReset] — второй 401: сброс сессии, экран входа (RN-параноия:
 *    только явный 401 чистит ключ; `contexts/AuthContext.tsx:85-88`).
 *    Реальный сброс в M3 (SessionManager): ключ + cached_user + handoff-файл;
 *    до M3 сессия (cookie) живёт в памяти клиента, стирается нечему.
 */
interface SessionRecoveryProvider {
    /** Сохранённый ключ или null, если восстановить нечем. */
    suspend fun getRecoveryKey(): String?

    /** Второй 401: стереть ключ / уведомить UI («экран входа»). */
    suspend fun onSessionReset()
}

/** Дефолт: восстановления нет (ключа нет, сброса нет). Заглушка до M3. */
data object NoSessionRecoveryProvider : SessionRecoveryProvider {
    override suspend fun getRecoveryKey(): String? = null
    override suspend fun onSessionReset() {}
}
