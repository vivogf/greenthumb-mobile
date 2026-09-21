package site.xmpp.greenthumb.core.network

/**
 * Ошибка сетевого вызова; порт `lib/api.ts:6-14` (RN-приложение).
 *
 * Классификация по `lib/api.ts:19-29` + `lib/api.ts:61-70`:
 *  - 401 → [Unauthorized] («нет сессии»; единственный код, который чистит recovery key);
 *  - прочие 4xx и прочие 3xx → [Client] (внутренние сбои бэка исторически дают 400);
 *  - >= 500 → [Server];
 *  - истечение дедлайна (10 с) → [Timeout], статус 0 (RN: AbortError);
 *  - транспортный сбой (DNS/TLS/сокет) → [Network], статус 0 (RN: TypeError).
 *
 * Осознанное отличие от RN (`lib/api.ts:61-64`): там любой AbortError, включая отмену
 * вызывающим, мапился в `timeout`. В Ktor отмена корутины остаётся
 * [kotlinx.coroutines.CancellationException] и наверх как [ApiError] не идёт —
 * см. ApiClient.mapException.
 */
sealed class ApiError : Throwable() {
    /** 401: сессия умерла или ключ невалиден. */
    data object Unauthorized : ApiError()

    /** Не-OK статус < 500 (обычно 4xx). */
    data class Client(val status: Int, val body: String) : ApiError()

    /** Статус >= 500. */
    data class Server(val status: Int, val body: String) : ApiError()

    /** Транспортный сбой: DNS, TLS, обрыв соединения. Статус 0. */
    data object Network : ApiError()

    /** Истёк дедлайн запроса (10 с). Статус 0 (как AbortError в RN). */
    data object Timeout : ApiError()

    /** Человекочитаемое описание; тело ошибки бэка, если было. */
    override val message: String
        get() = when (this) {
            is Client -> if (body.isBlank()) "HTTP $status" else body
            is Server -> if (body.isBlank()) "HTTP $status" else body
            Unauthorized -> "Unauthorized"
            Network -> "Network error"
            Timeout -> "Request timed out"
        }

    companion object {
        /** Классификация не-OK статуса; правила `lib/api.ts:19-29`. */
        fun fromStatus(status: Int, body: String = ""): ApiError = when {
            status == 401 -> Unauthorized
            status >= 500 -> Server(status, body)
            else -> Client(status, body)
        }
    }
}
