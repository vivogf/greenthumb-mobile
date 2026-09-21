package site.xmpp.greenthumb.core.network

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.utils.unwrapCancellationException
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

/** Базовый URL API; значение из RN `lib/constants.ts:1`. */
const val API_BASE_URL: String = "https://greenthumb.xmpp.site"

/**
 * Дедлайн запроса, мс; дефолт `baseFetch` (`lib/api.ts:40`),
 * ни один вызов его не переопределяет.
 */
const val REQUEST_TIMEOUT_MILLIS: Long = 10_000L

/**
 * Обёртка над Ktor HttpClient; порт `lib/api.ts` (baseFetch/apiRequest/apiFetch).
 *
 * Конфигурация — architecture.md §5, дословно из плана Stage 2 п.2:
 *  - `defaultRequest` на [API_BASE_URL] — вызывающие передают только путь `/api/...`;
 *  - таймаут [REQUEST_TIMEOUT_MILLIS] (HttpTimeout);
 *  - `HttpCookies(AcceptAllCookiesStorage())` — cookie-сессии ТОЛЬКО в памяти,
 *    как cookie RN в памяти (`lib/api.ts:26-28`); новый клиент = новая сессия;
 *  - `ContentNegotiation(Json { ignoreUnknownKeys = true })`;
 *  - `expectSuccess = false` — статусы обрабатываем сами, не через ClientRequestException;
 *  - `Logging` при debugLogging — только desktop-харнесс (отладка агентом).
 *
 * Два уровня доступа (смешивать нельзя — architecture.md §5):
 *  - [raw] возвращает [HttpResponse] НЕ бросая на не-OK статус — поведение `baseFetch`,
 *    на которое опирается initSession (401 → пробуем recovery key; иной не-OK → ничего
 *    не делаем и ключ храним; `contexts/AuthContext.tsx:53-66`). Транспортные сбои
 *    (сеть/таймаут) он, как и `baseFetch`, бросает — но уже как [ApiError];
 *  - [request] бросает [ApiError] по правилам `lib/api.ts:19-29` — поведение
 *    `apiRequest`/`apiFetch` (`lib/api.ts:86-102`).
 *
 * Движки: okhttp в androidMain, cio в jvmMain (пин Ktor 3.5.2).
 */
class ApiClient(
    debugLogging: Boolean,
    engineFactory: HttpClientEngineFactory<*>,
) {
    constructor(engineFactory: HttpClientEngineFactory<*>) : this(debugLogging = false, engineFactory = engineFactory)

    /**
     * Тестовый конструктор: готовый движок (MockEngine в jvmTest) вместо фабрики.
     * Прода не пользуется; фабрика-обёртка отдаёт движок как есть.
     */
    internal constructor(engine: io.ktor.client.engine.HttpClientEngine) :
        this(debugLogging = false, engineFactory = EngineInstanceFactory(engine))

    val client: HttpClient = HttpClient(engineFactory) { configureCommon(debugLogging) }

    /**
     * Базовый запрос; НЕ бросает на не-OK статус — поведение `baseFetch`.
     * Транспортный сбой (сеть/таймаут) бросается как [ApiError.Network]/[ApiError.Timeout];
     * чистая отмена корутины ([CancellationException] без причины) пробрасывается как есть.
     */
    suspend fun raw(
        method: HttpMethod,
        path: String,
        body: String? = null,
    ): HttpResponse =
        try {
            requestVia(client, method, path, body)
        } catch (cancellation: CancellationException) {
            throw mapTransportException(cancellation)
        } catch (cause: Throwable) {
            throw mapTransportException(cause)
        }

    /**
     * Бросающий уровень: не-OK статус → [ApiError.fromStatus] (тело ответа — в
     * [ApiError.Client.body]/[ApiError.Server.body], как `toApiError` в `lib/api.ts:16-29`);
     * транспорт/таймаут уже промаплены в [raw]. Поведение `apiRequest`/`apiFetch`.
     */
    suspend fun request(
        method: HttpMethod,
        path: String,
        body: String? = null,
    ): HttpResponse {
        val response = raw(method, path, body)
        val status = response.status.value
        if (status in 200..299) return response
        val text =
            try {
                response.bodyAsText()
            } catch (_: Throwable) {
                ""
            }
        throw ApiError.fromStatus(status, text)
    }

    fun close() {
        client.close()
    }

    companion object {
        /**
         * Единый Json-инстанс: тот же формат, что установлен в ContentNegotiation.
         * `encodeDefaults = false` — механизм «Absent не пишется» в [Patch] (VAL-NET-005):
         * поле-дефолт `Patch.Absent` не попадает в провод, `Value`/`Null` — попадают.
         * `ignoreUnknownKeys = true` — сервер добавляет новые поля без слома клиента.
         * `explicitNulls = false` — nullability-дефолты (PlantDto-конверты) тоже
         * не пишутся; явные `null` приходят через Patch.Null (пишется декодером).
         */
        val json: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
            explicitNulls = false
        }

        /** Фабрика-обёртка готового движка (тестовый путь MockEngine). */
        internal class EngineInstanceFactory(
            private val engine: io.ktor.client.engine.HttpClientEngine,
        ) : HttpClientEngineFactory<io.ktor.client.engine.HttpClientEngineConfig> {
            override fun create(block: io.ktor.client.engine.HttpClientEngineConfig.() -> Unit): io.ktor.client.engine.HttpClientEngine = engine
        }

        /** Общая конфигурация; вынесена для переиспользования в тестах. */
        internal fun HttpClientConfig<*>.configureCommon(
            debugLogging: Boolean,
            requestTimeoutMillisOverride: Long? = null,
        ) {
            expectSuccess = false
            defaultRequest {
                url(API_BASE_URL)
            }
            install(HttpTimeout) {
                requestTimeoutMillis = requestTimeoutMillisOverride ?: REQUEST_TIMEOUT_MILLIS
            }
            install(HttpCookies) {
                storage = AcceptAllCookiesStorage()
            }
            install(ContentNegotiation) {
                json(ApiClient.json)
            }
            if (debugLogging) {
                install(Logging) {
                    level = LogLevel.ALL
                }
            }
        }

        /** Единая механика запроса поверх готового [HttpClient] (для raw и тестов). */
        internal suspend fun requestVia(
            client: HttpClient,
            method: HttpMethod,
            path: String,
            body: String? = null,
        ): HttpResponse = client.request { applyRequest(method, path, body) }

        /**
         * Маппинг транспортных исключений в [ApiError] — порт catch-блока `baseFetch`
         * (`lib/api.ts:57-64`: AbortError → timeout, TypeError → network).
         *
         * Осознанное отличие от RN (план Stage 2 п.1): `CancellationException` наверх
         * как ApiError не идёт — в Ktor отмена корутины не есть таймаут. Чистая отмена
         * (без причины) пробрасывается как есть; отмена-таймаут (HttpTimeout отменяет
         * job запроса с [HttpRequestTimeoutException] в причине) мапится в
         * [ApiError.Timeout]. Connect/socket-таймауты движка мапятся по факту типа;
         * прочее (в т.ч. DNS-отказ) — [ApiError.Network].
         */
        internal fun mapTransportException(cause: Throwable): Throwable {
            if (cause is ApiError) return cause
            if (cause is CancellationException) {
                val root = cause.unwrapCancellationException()
                if (root is CancellationException) return cause
                return asTimeoutOrNetwork(root)
            }
            return asTimeoutOrNetwork(cause)
        }

        private fun asTimeoutOrNetwork(cause: Throwable): Throwable =
            if (hasTimeoutCause(cause)) ApiError.Timeout else ApiError.Network

        private fun hasTimeoutCause(cause: Throwable): Boolean {
            var current: Throwable? = cause
            while (current != null) {
                if (current is HttpRequestTimeoutException ||
                    current is ConnectTimeoutException ||
                    current is SocketTimeoutException
                ) {
                    return true
                }
                current = current.cause
            }
            return false
        }

        /**
         * Общая механика запроса: метод, путь (URL сливается с базой в DefaultRequest),
         * тело и Content-Type. Выделена, чтобы raw/request и тесты не расходились.
         */
        internal fun HttpRequestBuilder.applyRequest(
            method: HttpMethod,
            path: String,
            body: String?,
        ) {
            this.method = method
            url(path)
            if (body != null) {
                setBody(body)
                contentType(ContentType.Application.Json)
            }
        }
    }
}
