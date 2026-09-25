package site.xmpp.greenthumb.core.network

import kotlinx.coroutines.CancellationException

/**
 * Сессия сменилась, пока запрос был в полёте (Stage 4 п.5).
 *
 * Наследник [CancellationException]: журнал не откатывается (как при отмене
 * корутины), но ответ в базу не пишется. Это не [ApiError] — ключ по ней
 * сам не чистится, чистку делает смена сессии.
 */
public class SessionSuperseded : CancellationException("session changed")
