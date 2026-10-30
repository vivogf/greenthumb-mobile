package site.xmpp.greenthumb.core.network

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * POST /api/plants with a base64 cover must get the photo upload deadline: a slow upload that
 * times out after the server already created the plant made the offline queue replay it (duplicate).
 */
class AddPlantTimeoutTest {

    private val created =
        """{"id":"p1","user_id":1,"name":"Fern","location":"","photo_url":"","water_frequency_days":7,"last_watered_date":"2026-10-30","notes":"","created_at":"2026-10-30T10:00:00.000Z"}"""

    private fun plant(photoUrl: String) = InsertPlantDto(
        name = "Fern",
        location = "",
        photoUrl = photoUrl,
        waterFrequencyDays = 7,
        lastWateredDate = "2026-10-30",
    )

    private fun slowClient(delayMillis: Long, seenTimeouts: MutableList<Long?>) = ApiClient(
        MockEngine { request ->
            seenTimeouts += request.getCapabilityOrNull(HttpTimeoutCapability)?.requestTimeoutMillis
            delay(delayMillis)
            respond(created, HttpStatusCode.Created, headersOf("Content-Type", "application/json"))
        },
    )

    @Test
    fun `addPlant with a cover photo waits past the 10 s default`() = runTest {
        val seen = mutableListOf<Long?>()
        val client = slowClient(delayMillis = 15_000L, seenTimeouts = seen)
        val result = GreenThumbApi(client).addPlant(plant("data:image/jpeg;base64,AAAA"))
        assertEquals("p1", result.id)
        assertEquals(listOf<Long?>(PHOTO_UPLOAD_TIMEOUT_MILLIS), seen)
        client.close()
    }

    @Test
    fun `addPlant without a photo keeps the 10 s default`() = runTest {
        val seen = mutableListOf<Long?>()
        val client = slowClient(delayMillis = 15_000L, seenTimeouts = seen)
        val error = assertFailsWith<ApiError> { GreenThumbApi(client).addPlant(plant("")) }
        assertIs<ApiError.Timeout>(error)
        assertEquals(1, seen.size)
        assertEquals(true, seen.single() == null || seen.single() == REQUEST_TIMEOUT_MILLIS)
        client.close()
    }
}
