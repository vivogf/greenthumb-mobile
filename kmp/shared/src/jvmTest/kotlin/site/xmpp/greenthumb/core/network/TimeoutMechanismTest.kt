package site.xmpp.greenthumb.core.network

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import io.ktor.http.HttpMethod
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import site.xmpp.greenthumb.core.network.ApiClient.Companion.configureCommon
import site.xmpp.greenthumb.core.network.ApiClient.Companion.mapTransportException
import site.xmpp.greenthumb.core.network.ApiClient.Companion.requestVia
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * VAL-NET-003: таймаут-механизм.
 * Тест — на МЕХАНИЗМ, не на хронометраж (контракт: «инжекция короткого таймаута
 * с той же логикой»): реальный дедлайн 10 с гоняется на ускоренном виртуальном
 * времени; конфигурация клиента — та же ветка HttpTimeout, что в проде.
 */
class TimeoutMechanismTest {

    @Test
    fun `deadline expiry surfaces as ApiError Timeout`() = runTest {
        // Виртуальное время: mock-движок висит до конца теста; HttpTimeout (тот же
        // код-путь, что и с 10 с на проде) отменяет запрос на 500 мс виртуального
        // времени → mapTransportException → ApiError.Timeout.
        val engine = MockEngine { _ ->
            delay(60_000L)
            respondOk("never")
        }
        val client = TimeoutClient(engine, requestTimeoutMillis = 500L)
        val error = assertFailsWith<ApiError> {
            client.requestShortTimeout(HttpMethod.Get, "/api/plants")
        }
        assertIs<ApiError.Timeout>(error)
        client.close()
    }

    @Test
    fun `slow engine response within deadline succeeds`() = runTest {
        val engine = MockEngine { _ ->
            delay(200L)
            respondOk("ok")
        }
        val client = TimeoutClient(engine, requestTimeoutMillis = 5_000L)
        val response = client.requestShortTimeout(HttpMethod.Get, "/api/plants")
        assertEquals(200, response.status.value)
        client.close()
    }

    @Test
    fun `raw also fails with Timeout on deadline expiry`() = runTest {
        val engine = MockEngine { _ ->
            delay(60_000L)
            respondOk("never")
        }
        val client = TimeoutClient(engine, requestTimeoutMillis = 500L)
        val error = assertFailsWith<ApiError> {
            client.rawShortTimeout(HttpMethod.Get, "/api/plants")
        }
        assertIs<ApiError.Timeout>(error)
        client.close()
    }
}

/**
 * Клиент с той же конфигурацией ApiClient.configureCommon, но с коротким
 * requestTimeout — инжекция для теста механизма (не для прода).
 */
private class TimeoutClient(
    engine: MockEngine,
    requestTimeoutMillis: Long,
) {
    private val client = io.ktor.client.HttpClient(engine) {
        configureCommon(debugLogging = false, requestTimeoutMillisOverride = requestTimeoutMillis)
    }

    suspend fun requestShortTimeout(method: HttpMethod, path: String) =
        try {
            requestVia(client, method, path)
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw mapTransportException(cancellation)
        } catch (cause: Throwable) {
            throw mapTransportException(cause)
        }

    /** raw-семантика не-OK не применима на уровне транспорта: тестовый шим, идентичный raw(). */
    suspend fun rawShortTimeout(method: HttpMethod, path: String) =
        try {
            requestVia(client, method, path)
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw mapTransportException(cancellation)
        } catch (cause: Throwable) {
            throw mapTransportException(cause)
        }

    fun close() = client.close()
}
