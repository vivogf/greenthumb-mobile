package site.xmpp.greenthumb.data

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import site.xmpp.greenthumb.core.network.AccountSession
import site.xmpp.greenthumb.core.network.ApiClient
import site.xmpp.greenthumb.core.network.ApiError
import site.xmpp.greenthumb.core.network.NoSessionRecoveryProvider
import site.xmpp.greenthumb.core.network.SessionRecoveryProvider
import site.xmpp.greenthumb.core.network.SessionSuperseded
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Stage 4 п.5, VAL-DATA-007: очередь на plant_id и session-id guard.
 * Поздний ответ после смены сессии не пишется. Две мутации одного
 * растения не пересекаются на сети. Смена сессии чистит cookies и
 * отменяет чужой запрос.
 */
class SessionRaceTest {

    @Test
    fun two_mutations_of_the_same_plant_run_one_after_another() = runBlocking<Unit> {
        val order = Collections.synchronizedList(mutableListOf<String>())
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var patches = 0
        val harness = openHarness { request ->
            if (request.method == HttpMethod.Get) {
                ok(plantsJson(listOf(overduePlant())))
            } else {
                val n = synchronized(order) { ++patches }
                order.add("start-$n")
                if (n == 1) {
                    firstEntered.complete(Unit)
                    releaseFirst.await()
                }
                order.add("end-$n")
                ok(plantJson(overduePlant().copy(lastWateredDate = TODAY, name = "n$n")))
            }
        }
        harness.use { box ->
            box.repo.refresh()
            val first = async(Dispatchers.IO) { runCatching { box.repo.water("overdue") } }
            withTimeout(10_000) { firstEntered.await() }
            val second = async(Dispatchers.IO) { runCatching { box.repo.water("overdue") } }
            repeat(30) {
                delay(10)
                assertEquals(
                    listOf("start-1"),
                    order.filter { it.startsWith("start") },
                    "вторая мутация не должна начать сеть, пока первая не закончила",
                )
            }
            releaseFirst.complete(Unit)
            assertTrue(first.await().isSuccess)
            assertTrue(second.await().isSuccess)
            assertEquals(listOf("start-1", "end-1", "start-2", "end-2"), order.toList())
        }
    }

    @Test
    fun late_response_after_account_switch_is_discarded() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val session = AccountSession()
        var calls = 0
        val harness = openHarness(session = session) { _ ->
            calls++
            if (calls == 1) {
                ok(plantsJson(listOf(plant("a-local", "2026-09-01"))))
            } else {
                started.complete(Unit)
                release.await()
                ok(plantsJson(listOf(plant("stolen", "2026-09-20", name = "from-old-session"))))
            }
        }
        harness.use { box ->
            box.repo.refresh()
            assertEquals(setOf("a-local"), box.repo.currentPlants().map { it.id }.toSet())
            val synced = box.repo.lastSyncedAtMillis()

            val call = async(Dispatchers.IO) { runCatching { box.repo.refresh() } }
            withTimeout(10_000) { started.await() }
            session.advance()
            release.complete(Unit)

            val error = call.await().exceptionOrNull()
            assertIs<SessionSuperseded>(error)
            assertEquals(setOf("a-local"), box.repo.currentPlants().map { it.id }.toSet())
            assertTrue(box.repo.currentPlants().none { it.id == "stolen" })
            assertEquals(synced, box.repo.lastSyncedAtMillis())

            val repoB = PlantRepository(
                userId = "99",
                db = box.databases.open("99"),
                api = site.xmpp.greenthumb.core.network.GreenThumbApi(box.client),
                deleteFiles = { id -> box.databases.delete(id) },
                session = session,
            )
            assertTrue(repoB.currentPlants().none { it.id == "stolen" })
            repoB.close()
        }
    }

    @Test
    fun session_change_clears_cookie_storage() = runBlocking<Unit> {
        val sessionCookie = "connect.sid=s%3Asessionvalue; Path=/; HttpOnly"
        val engine = MockEngine { request ->
            val hasCookie = request.headers["Cookie"] != null
            if (!hasCookie) {
                respond(
                    "{}",
                    HttpStatusCode.OK,
                    headersOf(
                        "Content-Type" to listOf("application/json"),
                        "Set-Cookie" to listOf(sessionCookie),
                    ),
                )
            } else {
                respond("{}", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            }
        }
        val session = AccountSession()
        val client = ApiClient(engine, NoSessionRecoveryProvider, session)
        try {
            client.raw(HttpMethod.Get, "/api/auth/me")
            client.raw(HttpMethod.Get, "/api/plants")
            assertTrue(
                engine.requestHistory[1].headers.getAll("Cookie")!!.any { it.contains("connect.sid") },
                "до смены сессии cookie уходит",
            )
            client.endSession()
            client.raw(HttpMethod.Get, "/api/plants")
            assertNull(
                engine.requestHistory.last().headers["Cookie"],
                "после смены сессии cookie-хранилище пустое",
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun session_change_cancels_in_flight_request() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>()
        val engine = MockEngine {
            started.complete(Unit)
            kotlinx.coroutines.awaitCancellation()
        }
        val session = AccountSession()
        val client = ApiClient(engine, NoSessionRecoveryProvider, session)
        try {
            val call = async(Dispatchers.IO) { runCatching { client.raw(HttpMethod.Get, "/api/plants") } }
            withTimeout(10_000) { started.await() }
            client.endSession()
            val error = withTimeout(10_000) { call.await().exceptionOrNull() }
            assertIs<kotlinx.coroutines.CancellationException>(error)
        } finally {
            client.close()
        }
    }

    @Test
    fun session_reset_inside_the_failing_request_still_returns_401() = runBlocking<Unit> {
        lateinit var client: ApiClient
        val session = AccountSession()
        val provider = object : SessionRecoveryProvider {
            override suspend fun getRecoveryKey(): String? = "key"
            override suspend fun onSessionReset() {
                client.endSession()
            }
        }
        val engine = MockEngine {
            respond("no", HttpStatusCode.Unauthorized, headersOf("Content-Type", "application/json"))
        }
        client = ApiClient(engine, provider, session)
        try {
            assertFailsWith<ApiError.Unauthorized> {
                client.request(HttpMethod.Get, "/api/plants")
            }
            client.raw(HttpMethod.Get, "/api/auth/me")
            assertNull(engine.requestHistory.last().headers["Cookie"])
        } finally {
            client.close()
        }
    }
}
