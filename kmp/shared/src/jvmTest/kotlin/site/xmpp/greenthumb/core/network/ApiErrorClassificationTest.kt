package site.xmpp.greenthumb.core.network

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * VAL-NET-001: классификация всех пяти вариантов ApiError на MockEngine.
 * Правила — `lib/api.ts:19-29` (toApiError) и `lib/api.ts:61-70` (catch baseFetch).
 */
class ApiErrorClassificationTest {

    private fun handlerFor(status: HttpStatusCode, body: String): MockRequestHandler =
        { _ -> respond(body, status, headersOf("Content-Type", "application/json")) }

    private fun clientFor(handler: MockRequestHandler): ApiClient =
        ApiClient(MockEngine(handler))

    @Test
    fun `401 maps to Unauthorized`() = runTest {
        val client = clientFor(handlerFor(HttpStatusCode.Unauthorized, "Invalid recovery key"))
        val error = assertFailsWithApiError {
            client.request(HttpMethod.Get, "/api/auth/me")
        }
        assertIs<ApiError.Unauthorized>(error)
        client.close()
    }

    @Test
    fun `500 maps to Server`() = runTest {
        val client = clientFor(handlerFor(HttpStatusCode.InternalServerError, "boom"))
        val error = assertFailsWithApiError {
            client.request(HttpMethod.Get, "/api/plants")
        }
        assertIs<ApiError.Server>(error)
        assertEquals(500, error.status)
        assertEquals("boom", error.body)
        client.close()
    }

    @Test
    fun `503 maps to Server`() = runTest {
        val client = clientFor(handlerFor(HttpStatusCode.ServiceUnavailable, "quota"))
        val error = assertFailsWithApiError {
            client.request(HttpMethod.Get, "/api/plants")
        }
        assertIs<ApiError.Server>(error)
        assertEquals(503, error.status)
        client.close()
    }

    @Test
    fun `400 maps to Client with error body`() = runTest {
        val client = clientFor(handlerFor(HttpStatusCode.BadRequest, "{\"error\":\"Invalid recovery key\"}"))
        val error = assertFailsWithApiError {
            client.request(
                HttpMethod.Post,
                "/api/auth/login-recovery",
                body = "{\"recoveryKey\":\"bad\"}",
            )
        }
        assertIs<ApiError.Client>(error)
        assertEquals(400, error.status)
        assertEquals("{\"error\":\"Invalid recovery key\"}", error.body)
        client.close()
    }

    @Test
    fun `429 maps to Client`() = runTest {
        val client = clientFor(handlerFor(HttpStatusCode.TooManyRequests, "rate limited"))
        val error = assertFailsWithApiError {
            client.request(HttpMethod.Get, "/api/plants")
        }
        assertIs<ApiError.Client>(error)
        client.close()
    }

    @Test
    fun `timeout maps to Timeout with status 0`() = runTest {
        // Транспортный уровень: HttpRequestTimeoutException — исключение плагина HttpTimeout.
        val engine = MockEngine { _ ->
            throw io.ktor.client.plugins.HttpRequestTimeoutException("/api/plants", 10_000L)
        }
        val client = ApiClient(engine)
        val error = assertFailsWithApiError {
            client.request(HttpMethod.Get, "/api/plants")
        }
        assertIs<ApiError.Timeout>(error)
        client.close()
    }

    @Test
    fun `timeout wrapped in CancellationException maps to Timeout`() = runTest {
        // Как HttpTimeout реально доставляет таймаут: отмена job с причиной-таймаутом.
        val engine = MockEngine { _ ->
            val timeout = io.ktor.client.plugins.HttpRequestTimeoutException("/api/plants", 10_000L)
            throw kotlinx.coroutines.CancellationException(timeout.message, timeout)
        }
        val client = ApiClient(engine)
        val error = assertFailsWithApiError {
            client.request(HttpMethod.Get, "/api/plants")
        }
        assertIs<ApiError.Timeout>(error)
        client.close()
    }

    @Test
    fun `socket timeout maps to Timeout`() = runTest {
        val engine = MockEngine { _ ->
            throw io.ktor.client.network.sockets.SocketTimeoutException("read timed out", null)
        }
        val client = ApiClient(engine)
        val error = assertFailsWithApiError {
            client.request(HttpMethod.Get, "/api/plants")
        }
        assertIs<ApiError.Timeout>(error)
        client.close()
    }

    @Test
    fun `transport error maps to Network`() = runTest {
        val engine = MockEngine { _ ->
            throw IllegalStateException("Connection reset")
        }
        val client = ApiClient(engine)
        val error = assertFailsWithApiError {
            client.request(HttpMethod.Get, "/api/plants")
        }
        assertIs<ApiError.Network>(error)
        client.close()
    }

    @Test
    fun `raw also maps transport errors to Network`() = runTest {
        val engine = MockEngine { _ ->
            throw IllegalStateException("Connection reset")
        }
        val client = ApiClient(engine)
        val error = assertFailsWithApiError {
            client.raw(HttpMethod.Get, "/api/plants")
        }
        assertIs<ApiError.Network>(error)
        client.close()
    }

    @Test
    fun `request path merges with default base url`() = runTest {
        var seenUrl = ""
        val engine = MockEngine { request ->
            seenUrl = request.url.toString()
            respond("{}", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        val client = ApiClient(engine)
        client.request(HttpMethod.Get, "/api/plants")
        assertEquals("https://greenthumb.xmpp.site/api/plants", seenUrl)
        client.close()
    }
}

/** Локальный suspend-хелпер: ловит ApiError и возвращает его для assertIs. */
internal suspend fun assertFailsWithApiError(
    block: suspend () -> Unit,
): ApiError {
    try {
        block()
    } catch (e: ApiError) {
        return e
    } catch (e: Throwable) {
        throw AssertionError("Ожидался ApiError, получен ${e::class.simpleName}: ${e.message}", e)
    }
    throw AssertionError("Ожидался ApiError, но вызов прошёл успешно")
}
