package site.xmpp.greenthumb.core.network

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Идентификатор сессии аккаунта (Stage 4 п.5).
 *
 * Меняется при выходе, смене аккаунта и сбросе по 401. Запрос запоминает
 * [current] на старте; ответ применяется, только если [isCurrent] ещё тот же.
 * Стартовое значение — 1, его же ждёт cookie-хранилище до первого [advance].
 */
public class AccountSession {
    private val mutex = Mutex()
    private var id: Long = 1L

    public suspend fun current(): Long = mutex.withLock { id }

    public suspend fun isCurrent(stamp: Long): Boolean = mutex.withLock { id == stamp }

    /** Новая сессия. Вызывающий отменяет запросы и чистит cookies. */
    public suspend fun advance(): Long = mutex.withLock { ++id }
}
