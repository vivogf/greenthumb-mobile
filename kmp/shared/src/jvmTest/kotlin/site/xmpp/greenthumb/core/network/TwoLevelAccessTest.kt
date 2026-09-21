package site.xmpp.greenthumb.core.network

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * VAL-NET-002: двухуровневый доступ.
 *  - raw() на 503/401 возвращает HttpResponse БЕЗ исключения (поведение baseFetch,
 *    на которое опирается initSession: `contexts/AuthContext.tsx:53-66`);
 *  - request() на тех же статусах бросает ApiError (поведение apiRequest/apiFetch).
 */
class TwoLevelAccessTest {

    private fun handlerFor(status: HttpStatusCode, body: String): MockRequestHandler =
        { _ -> respond(body, status, headersOf("Content-Type", "application/json")) }

    @Test
    fun `raw returns 503 response without throwing`() = runTest {
        val client = ApiClient(MockEngine(handlerFor(HttpStatusCode.ServiceUnavailable, "quota exceeded")))
        val response = client.raw(HttpMethod.Get, "/api/plants")
        assertEquals(503, response.status.value)
        assertEquals("quota exceeded", response.bodyAsText())
        client.close()
    }

    @Test
    fun `raw returns 401 response without throwing`() = runTest {
        val client = ApiClient(MockEngine(handlerFor(HttpStatusCode.Unauthorized, "no session")))
        val response = client.raw(HttpMethod.Get, "/api/auth/me")
        assertEquals(401, response.status.value)
        client.close()
    }

    @Test
    fun `request throws Server on 503`() = runTest {
        val client = ApiClient(MockEngine(handlerFor(HttpStatusCode.ServiceUnavailable, "quota exceeded")))
        val error = assertFailsWith<ApiError> {
            client.request(HttpMethod.Get, "/api/plants")
        }
        assertIs<ApiError.Server>(error)
        assertEquals(503, error.status)
        assertEquals("quota exceeded", error.body)
        client.close()
    }

    @Test
    fun `request throws Unauthorized on 401`() = runTest {
        val client = ApiClient(MockEngine(handlerFor(HttpStatusCode.Unauthorized, "no session")))
        val error = assertFailsWith<ApiError> {
            client.request(HttpMethod.Get, "/api/auth/me")
        }
        assertIs<ApiError.Unauthorized>(error)
        client.close()
    }

    @Test
    fun `raw keeps non-ok status readable for initSession branching`() = runTest {
        // initSession различает ровно три исхода: 200 / 401 / «иной не-OK».
        // raw обязан давать это различие без исключений.
        val client = ApiClient(
            MockEngine { _ ->
                respond("{}", HttpStatusCode.Forbidden, headersOf("Content-Type", "application/json"))
            },
        )
        val response = client.raw(HttpMethod.Get, "/api/auth/me")
        assertEquals(403, response.status.value)
        client.close()
    }

    @Test
    fun `raw preserves error body text for message fallback`() = runTest {
        // RN: `const text = (await res.text()) || res.statusText` — пустое тело заменяется.
        val client = ApiClient(
            MockEngine { _ ->
                respond("", HttpStatusCode.BadGateway, headersOf("Content-Type", "application/json"))
            },
        )
        val error = assertFailsWith<ApiError> {
            client.request(HttpMethod.Get, "/api/plants")
        }
        assertIs<ApiError.Server>(error)
        assertEquals(502, error.status)
        assertEquals("", error.body)
        // message-фолбэк из statusText: "HTTP 502", не пустая строка.
        assertEquals("HTTP 502", error.message)
        client.close()
    }
}
