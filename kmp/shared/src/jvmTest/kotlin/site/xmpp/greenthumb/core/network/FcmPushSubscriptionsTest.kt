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
import kotlin.test.assertTrue

/**
 * FcmPushSubscriptions (M9 push-android): шов сети [PushSubscriptions] поверх
 * GreenThumbApi — тело подписки 1:1 с контрактом backend-contract.md №24–26
 * (platform: "android"/"ios", фолбэк android; ошибки НЕ глотаются — тумблер
 * профиля показывает сообщение, RN-паритет catch(error: any)).
 */
class FcmPushSubscriptionsTest {

    private class Captured {
        var method: String = ""
        var url: String = ""
        var body: String? = null
    }

    private fun subscriptionsFor(
        captured: Captured,
        status: HttpStatusCode = HttpStatusCode.OK,
        response: String = "{}",
    ): Pair<FcmPushSubscriptions, ApiClient> {
        val client = ApiClient(
            MockEngine { request ->
                captured.method = request.method.value
                captured.url = request.url.toString()
                captured.body = (request.body as? TextContent)?.text
                respond(response, status, headersOf("Content-Type", "application/json"))
            },
        )
        return FcmPushSubscriptions(GreenThumbApi(client)) to client
    }

    @Test
    fun `subscribe posts fcm token platform and language`() = runTest {
        val captured = Captured()
        val (subs, client) = subscriptionsFor(captured)
        subs.subscribe("fcm-token-1", "android", "ru")
        assertEquals("POST", captured.method)
        assertEquals("https://greenthumb.xmpp.site/api/push/subscribe-fcm", captured.url)
        assertTrue(
            captured.body!!.contains("\"fcm_token\":\"fcm-token-1\"") &&
                captured.body!!.contains("\"platform\":\"android\"") &&
                captured.body!!.contains("\"language\":\"ru\""),
            "тело подписки: ${captured.body}",
        )
        client.close()
    }

    @Test
    fun `platform wire value maps ios and unknown falls back to android`() = runTest {
        val ios = Captured()
        val (iosSubs, iosClient) = subscriptionsFor(ios)
        iosSubs.subscribe("tok", "ios", "en")
        assertTrue(ios.body!!.contains("\"platform\":\"ios\""), "ios: ${ios.body}")
        iosClient.close()

        val fallback = Captured()
        val (fallbackSubs, fallbackClient) = subscriptionsFor(fallback)
        fallbackSubs.subscribe("tok", "unknown", "en")
        assertTrue(
            fallback.body!!.contains("\"platform\":\"android\""),
            "фолбэк android: ${fallback.body}",
        )
        fallbackClient.close()
    }

    @Test
    fun `unsubscribe issues DELETE subscribe-fcm`() = runTest {
        val captured = Captured()
        val (subs, client) = subscriptionsFor(captured)
        subs.unsubscribe()
        assertEquals("DELETE", captured.method)
        assertEquals("https://greenthumb.xmpp.site/api/push/subscribe-fcm", captured.url)
        client.close()
    }

    @Test
    fun `status decodes subscribed flag`() = runTest {
        val captured = Captured()
        val (subs, client) = subscriptionsFor(captured, response = """{"subscribed":true}""")
        assertEquals(true, subs.status())
        assertEquals("GET", captured.method)
        assertEquals("https://greenthumb.xmpp.site/api/push/fcm-subscription", captured.url)
        client.close()
    }

    @Test
    fun `status propagates ApiError without swallowing`() = runTest {
        // Эндпоинта нет на проде (до деплоя M2) → 404: ApiError.Client наверх,
        // НЕ false-заглушка (решение о «недоступен → off» принимает экран).
        val captured = Captured()
        val (subs, client) = subscriptionsFor(captured, status = HttpStatusCode.NotFound)
        val error = assertFailsWith<ApiError> { subs.status() }
        assertTrue(error is ApiError.Client, "404 → Client, got $error")
        client.close()
    }
}
