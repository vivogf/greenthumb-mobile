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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
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
 * Восстановление сессии на 401 (Stage 2 п.7, осознанное улучшение относительно RN,
 * где 401 в рантайме не обрабатывался): 401 на любой запрос → одна попытка re-login
 * сохранённым recovery key ([SessionRecoveryProvider]) + повтор исходного запроса;
 * второй 401 — сброс сессии, экран входа. Это не retry: повторов по сетевым ошибкам
 * нет (`retry: false` в RN `lib/queryClient.ts:32,35` сохраняется), восстановление
 * срабатывает только на 401.
 *
 * Движки: okhttp в androidMain, cio в jvmMain (пин Ktor 3.5.2).
 */
class ApiClient(
    debugLogging: Boolean,
    engineFactory: HttpClientEngineFactory<*>,
    recoveryProvider: SessionRecoveryProvider,
) {
    constructor(
        engineFactory: HttpClientEngineFactory<*>,
        recoveryProvider: SessionRecoveryProvider,
    ) : this(debugLogging = false, engineFactory = engineFactory, recoveryProvider = recoveryProvider)

    constructor(engineFactory: HttpClientEngineFactory<*>) : this(engineFactory, NoSessionRecoveryProvider)

    /**
     * Тестовый конструктор: готовый движок (MockEngine в jvmTest) вместо фабрики.
     * Прода не пользуется; фабрика-обёртка отдаёт движок как есть.
     */
    internal constructor(engine: io.ktor.client.engine.HttpClientEngine) :
        this(debugLogging = false, engineFactory = EngineInstanceFactory(engine), recoveryProvider = NoSessionRecoveryProvider)

    internal constructor(
        engine: io.ktor.client.engine.HttpClientEngine,
        recoveryProvider: SessionRecoveryProvider,
    ) : this(debugLogging = false, engineFactory = EngineInstanceFactory(engine), recoveryProvider = recoveryProvider)

    val client: HttpClient = HttpClient(engineFactory) { configureCommon(debugLogging) }

    /** Восстановление сессии на 401 (Stage 2 п.7): провайдер ключа + сериализация попыток. */
    private val recoveryProvider: SessionRecoveryProvider = recoveryProvider
    private val recoveryMutex = Mutex()

    /**
     * Базовый запрос; НЕ бросает на не-OK статус — поведение `baseFetch`.
     * 401 запускает восстановление сессии: одна попытка re-login сохранённым
     * ключом ([recoverAndRetry]) + повтор исходного запроса; второй 401 — сброс
     * сессии ([SessionRecoveryProvider.onSessionReset]) и 401 наверх. Сам
     * `/api/auth/login-recovery` от восстановления исключён: 401 на него —
     * «явный 401» для initSession/экрана входа (M3), повтор чужим ключом был бы
     * ошибкой. Транспортный сбой (сеть/таймаут) бросается как
     * [ApiError.Network]/[ApiError.Timeout]; чистая отмена корутины
     * ([CancellationException] без причины) пробрасывается как есть.
     */
    suspend fun raw(
        method: HttpMethod,
        path: String,
        body: String? = null,
    ): HttpResponse {
        val first = try {
            requestVia(client, method, path, body)
        } catch (cancellation: CancellationException) {
            throw mapTransportException(cancellation)
        } catch (cause: Throwable) {
            throw mapTransportException(cause)
        }
        if (first.status.value != 401 || path == LOGIN_RECOVERY_PATH) return first

        // Восстановление сессии (только на 401): re-login сохранённым ключом,
        // затем повтор исходного запроса. Не retry — сетевые ошибки сюда не входят.
        return recoverAndRetry(method, path, body) ?: first
    }

    /**
     * Бросающий уровень: не-OK статус → [ApiError.fromStatus] (тело ответа — в
     * [ApiError.Client.body]/[ApiError.Server.body], как `toApiError` в `lib/api.ts:16-29`);
     * транспорт/таймаут уже промаплены в [raw]. 401 наверх — значит восстановление
     * не состоялось (ключа нет или сессия не ожила). Поведение `apiRequest`/`apiFetch`.
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

    /**
     * Одна попытка восстановления сессии (Stage 2 п.7) + повтор исходного запроса.
     * POST /api/auth/login-recovery сохранённым ключом; 200 — cookie обновлена
     * ([retryAfterRelogin]); явный 401 — «второй 401»: сброс сессии
     * ([SessionRecoveryProvider.onSessionReset]) без повторного входа — экран входа;
     * прочие сбои — ключ НЕ трогается (parity initSession: только явный 401 чистит
     * ключ). Concurrent 401 сериализуются mutex-ом: каждый запрос делает ровно одну
     * свою попытку re-login.
     *
     * @return результат повтора исходного запроса либо null, если повтора не было
     *   (ключа нет или re-login дал 401) — тогда caller отдаёт исходный 401.
     * @throws ApiError транспортный сбой или не-401 статус любого из шагов
     *   (в т.ч. повтора) — parity `baseFetch`: сеть/таймаут/серверный сбой
     *   бросаются, не «съедаются».
     */
    private suspend fun recoverAndRetry(
        method: HttpMethod,
        path: String,
        body: String?,
    ): HttpResponse? = recoveryMutex.withLock {
        val key = recoveryProvider.getRecoveryKey() ?: return@withLock null
        val login = try {
            requestVia(client, HttpMethod.Post, LOGIN_RECOVERY_PATH, json.encodeToString(RecoveryKeyRequest(key)))
        } catch (cancellation: CancellationException) {
            throw mapTransportException(cancellation)
        } catch (cause: Throwable) {
            throw mapTransportException(cause)
        }
        when (login.status.value) {
            in 200..299 -> {
                val retry = retryAfterRelogin(method, path, body)
                if (retry.status.value == 401) {
                    // Второй 401 (вторая ветка): re-login удался, но повтор снова
                    // 401 — сессия не ожила: сброс сессии, экран входа.
                    recoveryProvider.onSessionReset()
                }
                retry
            }
            401 -> {
                // Второй 401: сброс сессии, экран входа; сам re-login не рекурсивен.
                recoveryProvider.onSessionReset()
                null
            }
            else ->
                // Не-401 сбой re-login (4xx/5xx): ключ НЕ трогается; наверх уходит
                // ошибка re-login («сервис недоступен»), НЕ исходный 401 — иначе
                // серверный сбой выглядел бы как «неверный ключ» (пarity-ловушка
                // квот Neon; только явный 401 = неверный ключ).
                throw ApiError.fromStatus(
                    login.status.value,
                    try {
                        login.bodyAsText()
                    } catch (_: Throwable) {
                        ""
                    },
                )
        }
    }

    /** Повтор исходного запроса после успешного re-login (уже внутри mutex). */
    private suspend fun retryAfterRelogin(
        method: HttpMethod,
        path: String,
        body: String?,
    ): HttpResponse = try {
        requestVia(client, method, path, body)
    } catch (cancellation: CancellationException) {
        throw mapTransportException(cancellation)
    } catch (cause: Throwable) {
        throw mapTransportException(cause)
    }

    companion object {
        /** Путь re-login для восстановления сессии (та же таблица контракта). */
        internal const val LOGIN_RECOVERY_PATH: String = "/api/auth/login-recovery"

        /** Тело re-login (GreenThumbApi использует свою приватную копию того же формата). */
        @kotlinx.serialization.Serializable
        internal data class RecoveryKeyRequest(
            @kotlinx.serialization.SerialName("recoveryKey") val recoveryKey: String,
        )

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
