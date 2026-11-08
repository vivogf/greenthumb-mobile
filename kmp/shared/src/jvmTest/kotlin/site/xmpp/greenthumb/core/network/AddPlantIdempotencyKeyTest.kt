package site.xmpp.greenthumb.core.network

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** POST /api/plants carries the queued add's Idempotency-Key, including the retry after a 401 re-login. */
class AddPlantIdempotencyKeyTest {

    private val created =
        """{"id":"p1","user_id":1,"name":"Fern","location":"","photo_url":"","water_frequency_days":7,"last_watered_date":"2026-10-30","notes":"","created_at":"2026-10-30T10:00:00.000Z"}"""
    private val jsonHeaders = headersOf("Content-Type", "application/json")
    private val plant = InsertPlantDto(name = "Fern", location = "", waterFrequencyDays = 7, lastWateredDate = "2026-10-30")
    private val key = "00000000-0000-4000-8000-000000000001"

    @Test
    fun `the key goes out as the Idempotency-Key header`() = runTest {
        val seen = mutableListOf<String?>()
        val client = ApiClient(MockEngine { request ->
            seen += request.headers[IDEMPOTENCY_KEY_HEADER]
            respond(created, HttpStatusCode.OK, jsonHeaders)
        })
        GreenThumbApi(client).addPlant(plant, idempotencyKey = key)
        GreenThumbApi(client).addPlant(plant)
        assertEquals(listOf<String?>(key, null), seen)
        client.close()
    }

    @Test
    fun `a key the server would reject is not sent`() = runTest {
        val seen = mutableListOf<String?>()
        val client = ApiClient(MockEngine { request ->
            seen += request.headers[IDEMPOTENCY_KEY_HEADER]
            respond(created, HttpStatusCode.OK, jsonHeaders)
        })
        GreenThumbApi(client).addPlant(plant, idempotencyKey = "has space")
        GreenThumbApi(client).addPlant(plant, idempotencyKey = "x".repeat(129))
        assertEquals(listOf<String?>(null, null), seen)
        assertTrue(isValidIdempotencyKey(key))
        assertTrue(isValidIdempotencyKey("temp-1"))
        assertFalse(isValidIdempotencyKey(""))
        assertFalse(isValidIdempotencyKey("a;b"))
        client.close()
    }

    @Test
    fun `the retry after a 401 re-login keeps the same key`() = runTest {
        val seen = mutableListOf<Pair<String, String?>>()
        var sessionAlive = false
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            seen += path to request.headers[IDEMPOTENCY_KEY_HEADER]
            when {
                path == "/api/auth/login-recovery" -> {
                    sessionAlive = true
                    respond("""{"user":{"id":1,"name":"a"}}""", HttpStatusCode.OK, jsonHeaders)
                }
                !sessionAlive -> respond("""{"error":"unauthorized"}""", HttpStatusCode.Unauthorized, jsonHeaders)
                else -> respond(created, HttpStatusCode.OK, jsonHeaders)
            }
        }
        val provider = object : SessionRecoveryProvider {
            override suspend fun getRecoveryKey(): String = "recovery"
            override suspend fun onSessionReset() = Unit
        }
        val client = ApiClient(engine, provider)
        assertEquals("p1", GreenThumbApi(client).addPlant(plant, idempotencyKey = key).id)
        assertEquals(
            listOf("/api/plants" to key, "/api/auth/login-recovery" to null, "/api/plants" to key),
            seen,
        )
        client.close()
    }

    @Test
    fun `a server that does not know the header still creates the plant`() = runTest {
        // Pre-migration server: ignores the header, answers as before.
        val client = ApiClient(MockEngine { respond(created, HttpStatusCode.OK, jsonHeaders) })
        val result = GreenThumbApi(client).addPlant(plant, idempotencyKey = key)
        assertEquals("p1", result.id)
        assertNull(result.photoUrl.takeIf { it.isNotEmpty() })
        client.close()
    }
}
