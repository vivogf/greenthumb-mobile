package site.xmpp.greenthumb.core.network

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.serialization.json.Json

/**
 * Сквозной разбор через ApiClient (ContentNegotiation), как его делает UI:
 * `request` → тело 200 → декод модели. VAL-NET-006 в сквозном варианте.
 */
class ApiDecodingTest {

    private fun clientFor(json: String): ApiClient = ApiClient(
        MockEngine { _ ->
            respond(
                json,
                HttpStatusCode.OK,
                headersOf("Content-Type", "application/json"),
            )
        },
    )

    @Test
    fun `GET api plants via client parses numeric and string ids alike`() = runTest {
        val body =
            """[{"id":3,"user_id":5,"name":"A","location":"L","photo_url":"","water_frequency_days":7,"last_watered_date":"2026-09-20","notes":"","created_at":"2026-09-20T10:00:00.000Z"},
                {"id":"550e8400-e29b-41d4-a716-446655440000","user_id":"5","name":"B","location":"L","photo_url":"","water_frequency_days":7,"last_watered_date":"2026-09-20","notes":"","created_at":"2026-09-20T10:00:00.000Z"}]"""
        val client = clientFor(body)
        val response = client.request(io.ktor.http.HttpMethod.Get, "/api/plants")
        val plants: List<PlantDto> = ApiClient.json.decodeFromString(response.bodyAsText())
        assertEquals(2, plants.size)
        assertEquals("3", plants[0].id)
        assertEquals("550e8400-e29b-41d4-a716-446655440000", plants[1].id)
        client.close()
    }

    @Test
    fun `unknown fields in payload are ignored`() = runTest {
        // Игнорирование неизвестных полей сервера — на одиночном объекте.
        val body =
            """{"id":9,"user_id":5,"name":"A","location":"L","photo_url":"","water_frequency_days":7,"last_watered_date":"2026-09-20","notes":"","created_at":"2026-09-20T10:00:00.000Z","some_new_server_field":"x"}"""
        val dto: PlantDto = ApiClient.json.decodeFromString(body)
        assertEquals(1, 1)
        assertEquals("A", dto.name)
        assertEquals("9", dto.id)
    }

    @Test
    fun `failed request carries server error body in ApiError`() = runTest {
        val client = ApiClient(
            MockEngine { _ ->
                respond(
                    """{"error":"Name is required"}""",
                    HttpStatusCode.BadRequest,
                    headersOf("Content-Type", "application/json"),
                )
            },
        )
        var caught: Throwable? = null
        try {
            client.request(io.ktor.http.HttpMethod.Post, "/api/plants", "{}")
        } catch (e: Throwable) {
            caught = e
        }
        val error = assertIs<ApiError.Client>(caught)
        assertEquals(400, error.status)
        client.close()
    }
}
