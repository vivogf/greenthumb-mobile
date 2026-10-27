package site.xmpp.greenthumb.core.network

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Wire shape of the care journal endpoints (`server/journal/eventsRoute.ts`). */
class GreenThumbApiJournalTest {

    private class Captured {
        var method: String = ""
        var url: String = ""
        var body: String? = null
    }

    private fun apiFor(
        captured: Captured,
        status: HttpStatusCode = HttpStatusCode.OK,
        response: String = "{}",
    ): Pair<GreenThumbApi, ApiClient> {
        val client = ApiClient(
            MockEngine { request ->
                captured.method = request.method.value
                captured.url = request.url.toString()
                captured.body = (request.body as? TextContent)?.text
                respond(response, status, headersOf("Content-Type", "application/json"))
            },
        )
        return GreenThumbApi(client) to client
    }

    private val eventJson =
        """{"id":"e1","plant_id":"p1","user_id":"7","type":"water","occurred_on":"2026-10-20",""" +
            """"note":null,"photo_id":null,"created_at":"2026-10-20T08:00:00.123456Z"}"""

    @Test
    fun `listEvents GETs first page with limit`() = runTest {
        val captured = Captured()
        val (api, client) = apiFor(captured, response = """{"events":[$eventJson],"nextCursor":"abc_-1"}""")
        val page = api.listEvents("p1")
        assertEquals("GET", captured.method)
        assertEquals("https://greenthumb.xmpp.site/api/plants/p1/events?limit=100", captured.url)
        assertEquals("abc_-1", page.nextCursor)
        val event = page.events.single()
        assertEquals("e1", event.id)
        assertEquals("p1", event.plantId)
        assertEquals("7", event.userId)
        assertEquals("water", event.type)
        assertEquals("2026-10-20", event.occurredOn)
        assertNull(event.note)
        assertNull(event.photoId)
        assertEquals("2026-10-20T08:00:00.123456Z", event.createdAt)
        client.close()
    }

    @Test
    fun `listEvents passes the cursor as before`() = runTest {
        val captured = Captured()
        val (api, client) = apiFor(captured, response = """{"events":[],"nextCursor":null}""")
        val page = api.listEvents("p1", before = "WyIyMDI2LTEwLTIwIl0")
        assertEquals("https://greenthumb.xmpp.site/api/plants/p1/events?limit=100&before=WyIyMDI2LTEwLTIwIl0", captured.url)
        assertNull(page.nextCursor)
        client.close()
    }

    @Test
    fun `addNote POSTs a note event`() = runTest {
        val captured = Captured()
        val created = eventJson.replace("\"water\"", "\"note\"").replace("\"note\":null", "\"note\":\"Hi\"")
        val (api, client) = apiFor(captured, status = HttpStatusCode.Created, response = created)
        val event = api.addNote("p1", "Hi")
        assertEquals("POST", captured.method)
        assertEquals("https://greenthumb.xmpp.site/api/plants/p1/events", captured.url)
        assertEquals("""{"type":"note","note":"Hi"}""", captured.body)
        assertEquals("note", event.type)
        assertEquals("Hi", event.note)
        client.close()
    }

    @Test
    fun `deleteEvent sends DELETE and accepts 204`() = runTest {
        val captured = Captured()
        val (api, client) = apiFor(captured, status = HttpStatusCode.NoContent, response = "")
        api.deleteEvent("p1", "e1")
        assertEquals("DELETE", captured.method)
        assertEquals("https://greenthumb.xmpp.site/api/plants/p1/events/e1", captured.url)
        client.close()
    }

    @Test
    fun `deleteEvent maps 409 to a client error`() = runTest {
        val captured = Captured()
        val (api, client) = apiFor(captured, status = HttpStatusCode.Conflict, response = """{"error":"Only notes"}""")
        val error = assertFailsWith<ApiError.Client> { api.deleteEvent("p1", "e1") }
        assertEquals(409, error.status)
        client.close()
    }
}
