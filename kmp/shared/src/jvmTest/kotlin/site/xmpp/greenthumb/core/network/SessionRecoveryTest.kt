package site.xmpp.greenthumb.core.network

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * VAL-NET-008: восстановление сессии на 401 (Stage 2 п.7; architecture.md §5).
 *  - 401 на любой запрос → одна попытка re-login сохранённым ключом → повтор исходного;
 *    успех → результат запроса;
 *  - второй 401 (повтор или сам re-login) → сброс сессии, экран входа;
 *  - попытка re-login ровно одна — не цикл;
 *  - сетевые ошибки/таймаут/5xx/4xx восстановление НЕ триггерят: повтор — не retry,
 *    только на 401 (retry: false из RN `lib/queryClient.ts:32,35` сохраняется).
 */

private const val plantsJson =
    """[{"id":"550e8400-e29b-41d4-a716-446655440000","user_id":"7","name":"Ficus","location":"Living room",""" +
        """"photo_url":"","water_frequency_days":7,"last_watered_date":"2026-09-20",""" +
        """"notes":"","created_at":"2026-09-20T10:00:00.000Z"}]"""

/** Провайдер-двойник: ключ + счётчик сбросов; реальный M3 reset тоже стирает ключ. */
private class FakeProvider(initialKey: String?) : SessionRecoveryProvider {
    var key: String? = initialKey
    var resetCount = 0
    override suspend fun getRecoveryKey(): String? = key
    override suspend fun onSessionReset() {
        resetCount++
        key = null
    }
}

class SessionRecoveryTest {

    /** «Сервер»: GET /api/plants требует cookie; POST login-recovery — настраиваемый исход. */
    private class Server {
        var loginRecoveryCount = 0
        var plantsCount = 0
        var loginResponds: HttpStatusCode = HttpStatusCode.OK

        /** Следующий GET /api/plants с cookie отвечает 401 (сессия умерла снова). */
        var invalidateNextPlants = false

        val handler: MockRequestHandler = { request ->
            when (request.url.encodedPath) {
                "/api/auth/login-recovery" -> {
                    loginRecoveryCount++
                    if (loginResponds == HttpStatusCode.OK) {
                        respond(
                            """{"user":{"id":7,"name":null,"recovery_key":"stale","created_at":"2026-09-20T10:00:00.000Z"}}""",
                            HttpStatusCode.OK,
                            headersOf(
                                "Content-Type" to listOf("application/json"),
                                "Set-Cookie" to listOf("connect.sid=refreshed; Path=/; HttpOnly"),
                            ),
                        )
                    } else {
                        respond("no session", loginResponds, headersOf("Content-Type", "application/json"))
                    }
                }
                "/api/plants" -> {
                    plantsCount++
                    val hasCookie = request.headers["Cookie"] != null
                    when {
                        !hasCookie ->
                            respond("no session", HttpStatusCode.Unauthorized, headersOf("Content-Type", "application/json"))
                        invalidateNextPlants -> {
                            invalidateNextPlants = false
                            respond("session dropped", HttpStatusCode.Unauthorized, headersOf("Content-Type", "application/json"))
                        }
                        else ->
                            respond(plantsJson, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
                    }
                }
                else -> respond("{}", HttpStatusCode.InternalServerError, headersOf("Content-Type", "application/json"))
            }
        }
    }

    /** Тело запроса как текст (ContentNegotiation не трогает pre-serialized String). */
    private fun bodyText(content: Any): String? = (content as? TextContent)?.text?.takeIf { it.isNotEmpty() }

    private fun loginRecoveryRequests(engine: MockEngine) =
        engine.requestHistory.filter { it.url.encodedPath == "/api/auth/login-recovery" }

    // ------------------------------------------------------------------
    // Успех: 401 → re-login → повтор → 200
    // ------------------------------------------------------------------

    @Test
    fun `401 relogin retry succeeds`() = runTest {
        val server = Server()
        val engine = MockEngine(server.handler)
        val provider = FakeProvider("key-1")
        val client = ApiClient(engine, provider)

        val response = client.request(HttpMethod.Get, "/api/plants")

        assertEquals(200, response.status.value, "результат — ответ повтора, не исходный 401")
        assertEquals(1, server.loginRecoveryCount, "re-login ровно один")
        assertEquals(2, server.plantsCount, "исходный запрос + один повтор, не больше")
        assertEquals(0, provider.resetCount, "успех — сброса сессии нет")

        val login = engine.requestHistory[1]
        assertEquals("POST", login.method.value)
        assertEquals("""{"recoveryKey":"key-1"}""", bodyText(login.body), "re-login отправляет сохранённый ключ")
        assertNull(engine.requestHistory[0].headers["Cookie"], "исходный запрос без cookie")
        assertTrue(
            engine.requestHistory[2].headers.getAll("Cookie")!!.any { it.contains("connect.sid") },
            "повтор исходного запроса с новой cookie-сессией",
        )
        client.close()
    }

    @Test
    fun `greenThumbApi call recovers transparently`() = runTest {
        val server = Server()
        val engine = MockEngine(server.handler)
        val provider = FakeProvider("key-1")
        val api = GreenThumbApi(ApiClient(engine, provider))

        val plants = api.getPlants()

        assertEquals(1, plants.size)
        assertEquals("Ficus", plants[0].name)
        assertEquals(1, server.loginRecoveryCount)
    }

    // ------------------------------------------------------------------
    // Второй 401 → сброс сессии
    // ------------------------------------------------------------------

    @Test
    fun `second 401 after relogin resets session`() = runTest {
        val server = Server()
        server.loginResponds = HttpStatusCode.Unauthorized
        val engine = MockEngine(server.handler)
        val provider = FakeProvider("key-1")
        val client = ApiClient(engine, provider)

        val error = assertFailsWithApiError { client.request(HttpMethod.Get, "/api/plants") }

        assertIs<ApiError.Unauthorized>(error)
        assertEquals(1, provider.resetCount, "второй 401 — сброс сессии")
        assertNull(provider.key, "ключ стёрт при сбросе (поведение M3)")
        assertEquals(1, server.loginRecoveryCount, "re-login ровно один — не цикл")
        assertEquals(2, engine.requestHistory.size, "только исходный запрос и re-login, без цикла")
        client.close()
    }

    @Test
    fun `retry after recovered session still 401 resets session`() = runTest {
        // Другая ветка второго 401: re-login успешен, но повтор исходного всё равно 401.
        val server = Server()
        server.invalidateNextPlants = true
        val engine = MockEngine(server.handler)
        val provider = FakeProvider("key-1")
        val client = ApiClient(engine, provider)

        val error = assertFailsWithApiError { client.request(HttpMethod.Get, "/api/plants") }

        assertIs<ApiError.Unauthorized>(error)
        assertEquals(1, provider.resetCount)
        assertNull(provider.key)
        assertEquals(1, server.loginRecoveryCount, "один re-login, повтор 401 не запускает второй")
        assertEquals(3, engine.requestHistory.size, "исходный, re-login, повтор — третьей попытки нет")
        client.close()
    }

    @Test
    fun `after reset further requests do not relogin`() = runTest {
        val server = Server()
        server.loginResponds = HttpStatusCode.Unauthorized
        val engine = MockEngine(server.handler)
        val provider = FakeProvider("key-1")
        val client = ApiClient(engine, provider)

        assertFailsWithApiError { client.request(HttpMethod.Get, "/api/plants") } // волна со сбросом
        val second = client.raw(HttpMethod.Get, "/api/plants")

        assertEquals(401, second.status.value)
        assertEquals(1, server.loginRecoveryCount, "после сброса ключа нет — восстановление не запускается")
        assertEquals(1, provider.resetCount)
        assertEquals(1, loginRecoveryRequests(engine).size)
        client.close()
    }

    @Test
    fun `later 401 after successful recovery starts new recovery cycle`() = runTest {
        val server = Server()
        val engine = MockEngine(server.handler)
        val provider = FakeProvider("key-1")
        val client = ApiClient(engine, provider)

        val first = client.request(HttpMethod.Get, "/api/plants")
        assertEquals(200, first.status.value)
        server.invalidateNextPlants = true // сессия снова умерла

        val second = client.request(HttpMethod.Get, "/api/plants")

        assertEquals(200, second.status.value)
        assertEquals(2, server.loginRecoveryCount, "новая волна 401 — новая попытка восстановления")
        assertEquals(0, provider.resetCount)
        client.close()
    }

    // ------------------------------------------------------------------
    // raw-уровень: восстановление внутри «не бросающего» слоя
    // ------------------------------------------------------------------

    @Test
    fun `raw retries after relogin and returns recovered response`() = runTest {
        val server = Server()
        val provider = FakeProvider("key-1")
        val client = ApiClient(MockEngine(server.handler), provider)

        val response = client.raw(HttpMethod.Get, "/api/plants")

        assertEquals(200, response.status.value)
        assertEquals(1, server.loginRecoveryCount)
        client.close()
    }

    @Test
    fun `raw second 401 resets session without throwing`() = runTest {
        val server = Server()
        server.invalidateNextPlants = true
        val provider = FakeProvider("key-1")
        val client = ApiClient(MockEngine(server.handler), provider)

        val response = client.raw(HttpMethod.Get, "/api/plants")

        assertEquals(401, response.status.value, "raw не бросает на 401 — даже после сброса сессии")
        assertEquals(1, provider.resetCount)
        client.close()
    }

    // ------------------------------------------------------------------
    // Ровно одна попытка; сам re-login не рекурсивен
    // ------------------------------------------------------------------

    @Test
    fun `401 on login-recovery itself does not recurse`() = runTest {
        val server = Server()
        server.loginResponds = HttpStatusCode.Unauthorized
        val provider = FakeProvider("key-1")
        val client = ApiClient(MockEngine(server.handler), provider)

        val response = client.raw(HttpMethod.Post, "/api/auth/login-recovery", body = """{"recoveryKey":"bad"}""")

        assertEquals(401, response.status.value, "raw возвращает 401 login-recovery без восстановления")
        assertEquals(1, server.loginRecoveryCount, "ровно один запрос, без рекурсии")
        assertEquals(0, provider.resetCount, "raw-уровень не решает за initSession (M3)")
        client.close()
    }

    @Test
    fun `concurrent 401s serialize recovery without deadlock`() = runTest {
        val server = Server()
        val provider = FakeProvider("key-1")
        val client = ApiClient(MockEngine(server.handler), provider)

        val results = (1..2).map { async { client.request(HttpMethod.Get, "/api/plants") } }.awaitAll()

        results.forEach { assertEquals(200, it.status.value) }
        assertEquals(2, server.loginRecoveryCount, "по одной попытке на каждый запрос")
        assertEquals(0, provider.resetCount)
        client.close()
    }

    // ------------------------------------------------------------------
    // Восстановление НЕ триггерится вне 401
    // ------------------------------------------------------------------

    @Test
    fun `network error does not trigger recovery`() = runTest {
        val engine = MockEngine { _ -> throw IllegalStateException("Connection reset") }
        val provider = FakeProvider("key-1")
        val client = ApiClient(engine, provider)

        val error = assertFailsWithApiError { client.request(HttpMethod.Get, "/api/plants") }

        assertIs<ApiError.Network>(error)
        assertEquals(0, provider.resetCount)
        assertTrue(loginRecoveryRequests(engine).isEmpty(), "сетевая ошибка не запускает re-login")
        client.close()
    }

    @Test
    fun `timeout does not trigger recovery`() = runTest {
        val engine = MockEngine { _ ->
            throw HttpRequestTimeoutException("/api/plants", 10_000L)
        }
        val provider = FakeProvider("key-1")
        val client = ApiClient(engine, provider)

        val error = assertFailsWithApiError { client.request(HttpMethod.Get, "/api/plants") }

        assertIs<ApiError.Timeout>(error)
        assertTrue(loginRecoveryRequests(engine).isEmpty(), "таймаут не запускает re-login")
        assertEquals(0, provider.resetCount)
        client.close()
    }

    @Test
    fun `server error 5xx does not trigger recovery`() = runTest {
        val engine = MockEngine { _ ->
            respond("quota exceeded", HttpStatusCode.ServiceUnavailable, headersOf("Content-Type", "application/json"))
        }
        val provider = FakeProvider("key-1")
        val client = ApiClient(engine, provider)

        val error = assertFailsWithApiError { client.request(HttpMethod.Get, "/api/plants") }

        assertIs<ApiError.Server>(error)
        assertEquals(503, error.status)
        assertTrue(loginRecoveryRequests(engine).isEmpty(), "5xx не запускает re-login (транзиент, ключ хранится)")
        assertEquals(0, provider.resetCount)
        client.close()
    }

    @Test
    fun `client error 4xx does not trigger recovery`() = runTest {
        val engine = MockEngine { _ ->
            respond("""{"error":"Invalid recovery key"}""", HttpStatusCode.BadRequest, headersOf("Content-Type", "application/json"))
        }
        val provider = FakeProvider("key-1")
        val client = ApiClient(engine, provider)

        val error = assertFailsWithApiError { client.request(HttpMethod.Get, "/api/plants") }

        assertIs<ApiError.Client>(error)
        assertEquals(400, error.status)
        assertTrue(loginRecoveryRequests(engine).isEmpty(), "4xx (кроме 401) не запускает re-login")
        assertEquals(0, provider.resetCount)
        client.close()
    }

    // ------------------------------------------------------------------
    // Граничные случаи re-login
    // ------------------------------------------------------------------

    @Test
    fun `401 without stored key is returned as is`() = runTest {
        val server = Server()
        val provider = FakeProvider(null)
        val client = ApiClient(MockEngine(server.handler), provider)

        val response = client.raw(HttpMethod.Get, "/api/plants")

        assertEquals(401, response.status.value, "ключа нет — 401 возвращается вызывающему")
        assertEquals(1, server.plantsCount, "повтора нет")
        assertEquals(0, server.loginRecoveryCount, "re-login не запускается")
        assertEquals(0, provider.resetCount)
        client.close()
    }

    @Test
    fun `relogin server error surfaces without session reset`() = runTest {
        // Не-401 сбой re-login: ключ НЕ стирается (parity initSession: только явный 401);
        // наверх уходит ошибка re-login — «сервис недоступен», не «неверный ключ».
        val server = Server()
        server.loginResponds = HttpStatusCode.ServiceUnavailable
        val provider = FakeProvider("key-1")
        val client = ApiClient(MockEngine(server.handler), provider)

        val error = assertFailsWithApiError { client.request(HttpMethod.Get, "/api/plants") }

        assertIs<ApiError.Server>(error)
        assertEquals(503, error.status)
        assertEquals("key-1", provider.key, "ключ сохранён при не-401 сбое re-login")
        assertEquals(0, provider.resetCount)
        client.close()
    }
}
