package site.xmpp.greenthumb.core.network

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * GreenThumbApi: каждый метод бьёт ровно в путь/метод/тело таблицы контракта
 * (`library/backend-contract.md`, 15 эндпоинтов Stage 2 + FCM-троица и
 * DELETE /api/auth/account из mission-scope; тело FCM-подписки — из плана Stage 9).
 * MockEngine фиксирует HttpRequestData; ответы — типовые тела контракта.
 */
class GreenThumbApiTest {

    /** Фиксация запроса + выдача canned-ответа. */
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
                captured.body = bodyText(request.body)
                respond(
                    response,
                    status,
                    headersOf("Content-Type", "application/json"),
                )
            },
        )
        return GreenThumbApi(client) to client
    }

    /** Тело запроса как текст: ContentNegotiation получает готовую строку (TextContent). */
    private fun bodyText(content: Any): String? = when (content) {
        is TextContent -> content.text
        is io.ktor.http.content.OutgoingContent -> null
        else -> null
    }.takeIf { !it.isNullOrEmpty() }

    // ------------------------------------------------------------------
    // Auth (7)
    // ------------------------------------------------------------------

    @Test
    fun `me GETs auth me and decodes user`() = runTest {
        val captured = Captured()
        val (api, client) = apiFor(
            captured,
            response =
                """{"user":{"id":1,"name":null,"notification_time":"09:00","timezone":null,"last_notified_date":null,"recovery_key":"r","created_at":"2026-09-20T10:00:00.000Z"}}""",
        )
        val user = api.me()
        assertEquals("GET", captured.method)
        assertEquals("https://greenthumb.xmpp.site/api/auth/me", captured.url)
        assertEquals("r", user.recoveryKey)
        client.close()
    }

    @Test
    fun `createAnonymous posts optional name`() = runTest {
        val captured = Captured()
        val (api, client) = apiFor(
            captured,
            response =
                """{"user":{"id":2,"name":"kmp","notification_time":"09:00","recovery_key":"r","created_at":"2026-09-20T10:00:00.000Z"}}""",
        )
        val user = api.createAnonymous("kmp")
        assertEquals("POST", captured.method)
        assertEquals("""{"name":"kmp"}""", captured.body)
        assertEquals("2", user.id)
        client.close()
    }

    @Test
    fun `createAnonymous without name omits field`() = runTest {
        val captured = Captured()
        val (api, client) = apiFor(captured, response = """{"user":{"id":2,"name":null,"recovery_key":"r","created_at":"2026-09-20T10:00:00.000Z"}}""")
        val user = api.createAnonymous(null)
        assertEquals("""{}""", captured.body)
        assertEquals("2", user.id)
        client.close()
    }

    @Test
    fun `loginRecovery posts recoveryKey`() = runTest {
        val captured = Captured()
        val (api, client) = apiFor(captured, response = """{"user":{"id":3,"name":null,"recovery_key":"r","created_at":"2026-09-20T10:00:00.000Z"}}""")
        val user = api.loginRecovery("key")
        assertEquals("POST", captured.method)
        assertEquals("""{"recoveryKey":"key"}""", captured.body)
        assertEquals("3", user.id)
        client.close()
    }

    @Test
    fun `regenerateRecoveryKey posts with no body`() = runTest {
        val captured = Captured()
        val (api, client) = apiFor(captured, response = """{"user":{"id":3,"name":null,"recovery_key":"new","created_at":"2026-09-20T10:00:00.000Z"}}""")
        val user = api.regenerateRecoveryKey()
        assertEquals("POST", captured.method)
        assertEquals(null, captured.body)
        assertEquals("new", user.recoveryKey)
        client.close()
    }

    @Test
    fun `logout posts with no body`() = runTest {
        val captured = Captured()
        val (api, client) = apiFor(captured, response = """{"success":true}""")
        val result = api.logout()
        assertEquals("POST", captured.method)
        assertEquals("https://greenthumb.xmpp.site/api/auth/logout", captured.url)
        assertEquals(null, captured.body)
        assertEquals(true, result.success)
        client.close()
    }

    @Test
    fun `updateTimezone patches IANA string`() = runTest {
        val captured = Captured()
        val (api, client) = apiFor(captured, response = """{"user":{"id":3,"name":null,"recovery_key":"r","created_at":"2026-09-20T10:00:00.000Z"}}""")
        val updated = api.updateTimezone("Europe/Moscow")
        assertEquals("PATCH", captured.method)
        assertEquals("""{"timezone":"Europe/Moscow"}""", captured.body)
        assertEquals("3", updated.id)
        client.close()
    }

    @Test
    fun `updateNotificationTime patches HH00`() = runTest {
        val captured = Captured()
        val (api, client) = apiFor(captured, response = """{"user":{"id":3,"name":null,"recovery_key":"r","created_at":"2026-09-20T10:00:00.000Z"}}""")
        val updated = api.updateNotificationTime("09:00")
        assertEquals("PATCH", captured.method)
        assertEquals("""{"notification_time":"09:00"}""", captured.body)
        assertEquals("3", updated.id)
        client.close()
    }

    @Test
    fun `deleteAccount issues DELETE`() = runTest {
        val captured = Captured()
        val (api, client) = apiFor(captured, response = """{"success":true}""")
        val result = api.deleteAccount()
        assertEquals("DELETE", captured.method)
        assertEquals("https://greenthumb.xmpp.site/api/auth/account", captured.url)
        assertEquals(true, result.success)
        client.close()
    }

    // ------------------------------------------------------------------
    // Plants (8)
    // ------------------------------------------------------------------

    private fun plantJson(id: String = "550e8400-e29b-41d4-a716-446655440000"): String =
        """{"id":"$id","user_id":"1","name":"Ficus","location":"Living room","photo_url":"","water_frequency_days":7,"last_watered_date":"2026-09-20","fertilize_frequency_days":null,"last_fertilized_date":null,"repot_frequency_months":null,"last_repotted_date":null,"prune_frequency_months":null,"last_pruned_date":null,"notes":"","created_at":"2026-09-20T10:00:00.000Z"}"""

    @Test
    fun `getPlants GETs list and decodes`() = runTest {
        val captured = Captured()
        val (api, client) = apiFor(captured, response = "[${plantJson()}]")
        val plants = api.getPlants()
        assertEquals("GET", captured.method)
        assertEquals("https://greenthumb.xmpp.site/api/plants", captured.url)
        assertEquals(1, plants.size)
        assertEquals("Ficus", plants[0].name)
        client.close()
    }

    @Test
    fun `addPlant POSTs insert dto with optional care omitted`() = runTest {
        val captured = Captured()
        val (api, client) = apiFor(captured, response = plantJson())
        val added = api.addPlant(
            InsertPlantDto(
                name = "Ficus",
                location = "Living room",
                photoUrl = "",
                waterFrequencyDays = 7,
                lastWateredDate = "2026-09-20",
            ),
        )
        assertEquals("Ficus", added.name)
        assertEquals("POST", captured.method)
        // RN всегда шлёт photo_url ("" без фото); серверный zod требует поле.
        // @EncodeDefault(ALWAYS): photo_url="" пишется и для дефолта; notes="" — дефолт, в провод не пишется.
        assertEquals(
            """{"name":"Ficus","location":"Living room","photo_url":"","water_frequency_days":7,"last_watered_date":"2026-09-20"}""",
            captured.body,
        )
        client.close()
    }

    @Test
    fun `addPlant with advanced care includes filled fields`() = runTest {
        val captured = Captured()
        val (api, client) = apiFor(captured, response = plantJson())
        val added = api.addPlant(
            InsertPlantDto(
                name = "F",
                location = "L",
                photoUrl = "",
                waterFrequencyDays = 3,
                lastWateredDate = "2026-09-20",
                notes = "note",
                fertilizeFrequencyDays = 14,
                lastFertilizedDate = "2026-09-01",
                repotFrequencyMonths = 12,
                lastRepottedDate = "2026-01-01",
            ),
        )
        val body = captured.body
        assertTrue(body != null && body.contains(""""fertilize_frequency_days":14"""))
        assertTrue(body!!.contains(""""repot_frequency_months":12"""))
        // Не заполненное prune — не в теле (encodeDefaults=false).
        assertTrue(!body.contains("prune_frequency_months"))
        assertEquals("Ficus", added.name)
        client.close()
    }

    @Test
    fun `updatePlant PATCHes path with id and three-state body`() = runTest {
        val captured = Captured()
        val (api, client) = apiFor(captured, response = plantJson())
        val patched = api.updatePlant(
            "550e8400-e29b-41d4-a716-446655440000",
            PatchPlantDto(notes = Patch.Value("hello"), fertilizeFrequencyDays = Patch.Null),
        )
        assertEquals("PATCH", captured.method)
        assertEquals(
            "https://greenthumb.xmpp.site/api/plants/550e8400-e29b-41d4-a716-446655440000",
            captured.url,
        )
        // Порядок ключей — по порядку полей DTO; смысл: Value и явный null пишутся.
        assertEquals("""{"fertilize_frequency_days":null,"notes":"hello"}""", captured.body)
        assertEquals("Ficus", patched.name)
        client.close()
    }

    @Test
    fun `deletePlant issues DELETE with id path`() = runTest {
        val captured = Captured()
        val (api, client) = apiFor(captured, response = """{"success":true}""")
        val result = api.deletePlant("550e8400-e29b-41d4-a716-446655440000")
        assertEquals("DELETE", captured.method)
        assertEquals(
            "https://greenthumb.xmpp.site/api/plants/550e8400-e29b-41d4-a716-446655440000",
            captured.url,
        )
        assertEquals(true, result.success)
        client.close()
    }

    @Test
    fun `waterAll and postponeAll POST mass actions`() = runTest {
        val captured = Captured()
        val (api, client) = apiFor(captured, response = """{"success":true,"count":2}""")
        val watered = api.waterAll()
        assertEquals("POST", captured.method)
        assertEquals("https://greenthumb.xmpp.site/api/plants/water-all", captured.url)
        assertEquals(2, watered.count)
        val postponed = api.postponeAll()
        assertEquals("https://greenthumb.xmpp.site/api/plants/postpone-all", captured.url)
        assertEquals(2, postponed.count)
        client.close()
    }

    @Test
    fun `mass action count decodes`() = runTest {
        val captured = Captured()
        val (api, client) = apiFor(captured, response = """{"success":true,"count":2}""")
        val watered = api.waterAll()
        val postponed = api.postponeAll()
        assertEquals(2, watered.count)
        assertEquals(2, postponed.count)
        client.close()
    }

    // ------------------------------------------------------------------
    // Push (7)
    // ------------------------------------------------------------------

    @Test
    fun `webPushSubscription status decodes`() = runTest {
        val captured = Captured()
        val (api, client) = apiFor(captured, response = """{"subscribed":true}""")
        val status = api.webPushSubscriptionStatus()
        assertTrue(status.subscribed)
        assertEquals("https://greenthumb.xmpp.site/api/push/subscription", captured.url)
        client.close()
    }

    @Test
    fun `subscribeExpo posts token and language`() = runTest {
        val captured = Captured()
        val (api, client) = apiFor(captured, response = """{"ok":true}""")
        val result = api.subscribeExpo("ExponentPushToken[abc]", "en")
        assertEquals("POST", captured.method)
        assertEquals("""{"expo_push_token":"ExponentPushToken[abc]","language":"en"}""", captured.body)
        assertEquals(true, result.ok)
        client.close()
    }

    @Test
    fun `unsubscribeExpo issues DELETE`() = runTest {
        val captured = Captured()
        val (api, client) = apiFor(captured, response = """{"ok":true}""")
        val result = api.unsubscribeExpo()
        assertEquals("DELETE", captured.method)
        assertEquals("https://greenthumb.xmpp.site/api/push/subscribe-expo", captured.url)
        assertEquals(true, result.ok)
        client.close()
    }

    @Test
    fun `expoSubscriptionStatus decodes flag`() = runTest {
        val captured = Captured()
        val (api, client) = apiFor(captured, response = """{"subscribed":false}""")
        val status = api.expoSubscriptionStatus()
        assertEquals(false, status.subscribed)
        client.close()
    }

    @Test
    fun `subscribeFcm posts full body`() = runTest {
        val captured = Captured()
        val (api, client) = apiFor(captured, response = """{"ok":true}""")
        val result = api.subscribeFcm(FcmSubscriptionRequest(fcmToken = "tok", platform = PlatformDto.android, language = "ru"))
        assertEquals("POST", captured.method)
        assertEquals("https://greenthumb.xmpp.site/api/push/subscribe-fcm", captured.url)
        assertEquals(
            """{"fcm_token":"tok","platform":"android","language":"ru"}""",
            captured.body,
        )
        assertEquals(true, result.ok)
        client.close()
    }

    @Test
    fun `unsubscribeFcm and fcmSubscriptionStatus hit contract paths`() = runTest {
        val captured = Captured()
        val (api, client) = apiFor(captured, response = """{"subscribed":true}""")
        // Ответ DELETE — {ok:true}; тестовый canned-ответ тот же, проверяем только методы/пути.
        api.unsubscribeFcm()
        assertEquals("DELETE", captured.method)
        api.fcmSubscriptionStatus()
        assertEquals("GET", captured.method)
        assertEquals("https://greenthumb.xmpp.site/api/push/fcm-subscription", captured.url)
        client.close()
    }

    @Test
    fun `fcm platform is encoded enum value`() {
        // Значение провода — "android"/"ios", не имя enum-константы.
        assertEquals(
            """"android"""",
            ApiClient.json.encodeToString(PlatformDto.serializer(), PlatformDto.android),
        )
    }
}
