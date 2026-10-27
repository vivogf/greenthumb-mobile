package site.xmpp.greenthumb.season

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import site.xmpp.greenthumb.core.storage.SessionState
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * VAL-SEASON-023: [TimezoneSync] sends one `PATCH /api/auth/update-timezone` on sign-in, cold start
 * and resume when the device zone differs from the server user's `timezone` (NULL included), none when
 * they are equal, and a failed sync keeps the session.
 */
class TimezoneSyncTest {

    private class Rig(val harness: SeasonHarness, var zone: String) {
        val resumed = MutableStateFlow(true)
        val sync = TimezoneSync(harness.session) { zone }
    }

    private fun withRig(zone: String, server: SeasonFakeServer = SeasonFakeServer(), block: suspend Rig.() -> Unit) =
        runBlocking {
            SeasonHarness(server).use { harness ->
                val rig = Rig(harness, zone)
                val job = launch { rig.sync.run(rig.resumed) }
                try {
                    rig.block()
                } finally {
                    job.cancelAndJoin()
                }
            }
        }

    /** Home, then back: the collector must observe the pause before the resume. */
    private suspend fun Rig.pauseAndResume() {
        resumed.value = false
        delay(50)
        resumed.value = true
    }

    private fun Rig.userZone(): String? = (harness.session.state.value as? SessionState.SignedIn)?.user?.timezone

    @Test
    fun coldStartSendsOnePatchWhenServerZoneIsNull() = withRig("Europe/Berlin") {
        harness.signedInWithKey()
        waitFor { harness.server.timezoneBodies.size == 1 }
        delay(200)
        assertEquals(listOf("""{"timezone":"Europe/Berlin"}"""), harness.server.timezoneBodies.toList())
        waitFor { userZone() == "Europe/Berlin" }
        assertEquals("Europe/Berlin", harness.settings.cachedUserState.value?.timezone)
    }

    @Test
    fun coldStartSendsNothingWhenZonesAreEqual() =
        withRig("Europe/Berlin", SeasonFakeServer().apply { timezone = "Europe/Berlin" }) {
            harness.signedInWithKey()
            delay(300)
            assertEquals(0, harness.server.timezoneBodies.size)
        }

    @Test
    fun signInSendsOnePatch() = withRig("Australia/Sydney", SeasonFakeServer().apply { signedIn = false }) {
        harness.session.startup()
        assertIs<SessionState.SignedOut>(harness.session.state.value)
        delay(100)
        assertEquals(0, harness.server.timezoneBodies.size)
        harness.session.createAnonymousAccount("gt-test-season")
        waitFor { harness.server.timezoneBodies.size == 1 }
        delay(200)
        assertEquals(listOf("""{"timezone":"Australia/Sydney"}"""), harness.server.timezoneBodies.toList())
    }

    @Test
    fun resumeSendsOnePatchOnlyWhenTheZoneChanged() = withRig("Europe/Berlin") {
        harness.signedInWithKey()
        waitFor { userZone() == "Europe/Berlin" }
        assertEquals(1, harness.server.timezoneBodies.size)

        pauseAndResume()
        delay(200)
        assertEquals(1, harness.server.timezoneBodies.size, "same zone on resume sends nothing")

        zone = "Asia/Tokyo"
        pauseAndResume()
        waitFor { harness.server.timezoneBodies.size == 2 }
        delay(200)
        assertEquals("""{"timezone":"Asia/Tokyo"}""", harness.server.timezoneBodies.last())
        assertEquals(2, harness.server.timezoneBodies.size)
        waitFor { userZone() == "Asia/Tokyo" }
    }

    @Test
    fun failedSyncKeepsTheSession() =
        withRig("Europe/Berlin", SeasonFakeServer().apply { timezoneStatus = HttpStatusCode.InternalServerError }) {
            harness.signedInWithKey()
            waitFor { harness.server.timezoneBodies.size == 1 }
            delay(200)
            assertIs<SessionState.SignedIn>(harness.session.state.value)
            assertEquals(null, userZone())
            harness.server.timezoneStatus = HttpStatusCode.OK
            harness.server.timezoneFailure = IOException("airplane mode")
            pauseAndResume()
            waitFor { harness.server.timezoneBodies.size == 2 }
            delay(200)
            assertIs<SessionState.SignedIn>(harness.session.state.value)
            assertEquals("season-fixture-key", harness.secure.map.values.single())
        }
}
