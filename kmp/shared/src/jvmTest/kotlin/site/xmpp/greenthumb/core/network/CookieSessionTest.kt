package site.xmpp.greenthumb.core.network

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * VAL-NET-004: cookie-сессии в памяти.
 *  - Set-Cookie, выданный «сервером», принимается и отправляется в последующих
 *    запросах ТОГО ЖЕ ApiClient (cookie-сессия RN живёт в памяти, `lib/api.ts:26-28`);
 *  - НОВЫЙ ApiClient стартует с пустым хранилищем — как перезапуск процесса.
 */
class CookieSessionTest {

    private val sessionCookie = "connect.sid=s%3Asessionvalue; Path=/; HttpOnly"

    private fun sessionServerHandler(): MockRequestHandler =
        { request ->
            val hasCookie = request.headers["Cookie"] != null
            if (!hasCookie) {
                respond(
                    "{\"user\":{}}",
                    HttpStatusCode.OK,
                    headersOf(
                        "Content-Type" to listOf("application/json"),
                        "Set-Cookie" to listOf(sessionCookie),
                    ),
                )
            } else {
                respond(
                    "{\"ok\":true}",
                    HttpStatusCode.OK,
                    headersOf("Content-Type", "application/json"),
                )
            }
        }

    @Test
    fun `cookie from first response is sent in next request of same client`() {
        val engine = MockEngine(sessionServerHandler())
        val client = ApiClient(engine)

        runBlocking {
            // Первый вызов: сервер ставит cookie (как POST create-anonymous/login-recovery).
            val first = client.raw(HttpMethod.Post, "/api/auth/create-anonymous", body = "{}")
            assertEquals(200, first.status.value)

            // Второй вызов: cookie должна уехать в заголовке (как GET /api/plants).
            val second = client.raw(HttpMethod.Get, "/api/plants")
            assertEquals(200, second.status.value)
        }

        val sentRequests = engine.requestHistory
        assertTrue(sentRequests.size >= 2, "ожидалось два запроса, было ${sentRequests.size}")
        assertNull(
            sentRequests[0].headers["Cookie"],
            "первый запрос ещё без cookie",
        )
        // Кука доходит с cookie-pair (имя=значение); полный формат зависит от Ktor.
        assertTrue(
            sentRequests[1].headers.getAll("Cookie")!!.any { it.contains("connect.sid") },
            "второй запрос обязан нести connect.sid",
        )
        client.close()
    }

    @Test
    fun `new client instance starts with empty cookie storage`() {
        val engine = MockEngine(sessionServerHandler())
        // Клиент А получает cookie...
        val clientA = ApiClient(engine)
        runBlocking {
            clientA.raw(HttpMethod.Post, "/api/auth/create-anonymous", body = "{}")
        }

        // ...клиент Б (как перезапуск процесса) отправляет запрос БЕЗ cookie.
        val clientB = ApiClient(engine)
        runBlocking {
            val response = clientB.raw(HttpMethod.Get, "/api/plants")
            assertEquals(200, response.status.value)
        }
        val sentRequests = engine.requestHistory
        val lastRequest = sentRequests.last()
        assertNull(
            lastRequest.headers["Cookie"],
            "новый клиент не должен нести cookie предыдущей сессии",
        )
        clientA.close()
        clientB.close()
    }
}
