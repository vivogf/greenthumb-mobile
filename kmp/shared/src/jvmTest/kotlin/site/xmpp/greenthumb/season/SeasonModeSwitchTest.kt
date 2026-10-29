package site.xmpp.greenthumb.season

import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import site.xmpp.greenthumb.core.network.ApiError
import site.xmpp.greenthumb.core.storage.SecureStoreKeys
import site.xmpp.greenthumb.core.storage.SessionState
import site.xmpp.greenthumb.data.WateringStatus
import site.xmpp.greenthumb.data.wateringStatus
import site.xmpp.greenthumb.ui.res.Res
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * VAL-SEASON-021/022: the Profile switcher saves through
 * [site.xmpp.greenthumb.core.storage.SessionManager.updateSeasonMode] optimistically (session state and
 * cached_user change before the response) and restores the previous mode on any failure. Dashboard and
 * detail read the mode from the session state, so their status follows the same value.
 */
class SeasonModeSwitchTest {

    private val latitudes: TzLatitudes by lazy {
        runBlocking { decodeTzLatitudes(Res.readBytes(TZ_LATITUDES_RESOURCE).decodeToString()) }
    }

    private fun currentMode(harness: SeasonHarness): String? = when (val state = harness.session.state.value) {
        is SessionState.SignedIn -> state.user.seasonMode
        is SessionState.Offline -> state.user.seasonMode
        else -> null
    }

    /** North-dormant plant (watered 2026-01-10, every 10 days) seen on 2026-01-22 from Singapore. */
    private fun dashboardStatus(harness: SeasonHarness): WateringStatus {
        val season = SeasonContext(seasonModeOf(currentMode(harness)), "Asia/Singapore", latitudes)
        return wateringStatus("2026-01-10", 10, LocalDate(2026, 1, 22), season)
    }

    @Test
    fun selectedModeIsShownBeforeTheResponseAndKeptOn200() = runBlocking {
        SeasonHarness().use { harness ->
            harness.signedInWithKey()
            assertEquals("auto", currentMode(harness))
            assertEquals(WateringStatus.Overdue, dashboardStatus(harness))
            val gate = CompletableDeferred<Unit>()
            harness.server.seasonGate = gate

            val save = async { harness.session.updateSeasonMode(SeasonMode.North) }
            waitFor { harness.server.seasonBodies.size == 1 }
            assertEquals("north", currentMode(harness), "optimistic before the response")
            assertEquals("north", harness.settings.cachedUserState.value?.seasonMode)
            assertEquals(WateringStatus.Healthy, dashboardStatus(harness))

            gate.complete(Unit)
            val saved = save.await()
            assertEquals("north", saved.seasonMode)
            assertEquals("north", currentMode(harness))
            assertEquals("north", harness.settings.cachedUserState.value?.seasonMode)
            assertEquals("""{"season_mode":"north"}""", harness.server.seasonBodies.single())
            assertEquals(null, harness.settings.cachedUserState.value?.recoveryKey, "cached_user (unencrypted) holds no key")
        }
    }

    private fun assertRollback(configure: (SeasonFakeServer) -> Unit, expected: (ApiError) -> Unit) = runBlocking {
        SeasonHarness().use { harness ->
            harness.signedInWithKey()
            configure(harness.server)
            val error = assertFailsWith<ApiError> { harness.session.updateSeasonMode(SeasonMode.South) }
            expected(error)
            assertEquals("auto", currentMode(harness))
            assertEquals("auto", harness.settings.cachedUserState.value?.seasonMode)
            assertIs<SessionState.SignedIn>(harness.session.state.value)
            assertEquals(1, harness.server.seasonBodies.size)
        }
    }

    @Test
    fun rollsBackOn400() = assertRollback({ it.seasonStatus = HttpStatusCode.BadRequest }) {
        assertIs<ApiError.Client>(it)
    }

    @Test
    fun rollsBackOn500() = assertRollback({ it.seasonStatus = HttpStatusCode.InternalServerError }) {
        assertIs<ApiError.Server>(it)
    }

    @Test
    fun rollsBackOnNetworkError() = assertRollback({ it.seasonFailure = IOException("airplane mode") }) {
        assertIs<ApiError.Network>(it)
    }

    @Test
    fun rollsBackOnTimeout() = assertRollback({
        it.seasonFailure = HttpRequestTimeoutException("https://greenthumb.xmpp.site/api/auth/update-season-mode", 10_000)
    }) {
        assertIs<ApiError.Timeout>(it)
    }

    @Test
    fun dashboardStatusReturnsToThePreviousModeAfterRollback() = runBlocking {
        SeasonHarness().use { harness ->
            harness.signedInWithKey()
            val gate = CompletableDeferred<Unit>()
            harness.server.seasonGate = gate
            harness.server.seasonFailure = IOException("airplane mode")
            val save = async { runCatching { harness.session.updateSeasonMode(SeasonMode.North) } }
            waitFor { harness.server.seasonBodies.size == 1 }
            assertEquals(WateringStatus.Healthy, dashboardStatus(harness))
            gate.complete(Unit)
            assertTrue(save.await().isFailure)
            assertEquals(WateringStatus.Overdue, dashboardStatus(harness))
        }
    }

    @Test
    fun savedModeSurvivesAnOfflineStartAndAFailedChangeKeepsIt() = runBlocking {
        val cached = SeasonHarness().use { online ->
            online.signedInWithKey()
            online.session.updateSeasonMode(SeasonMode.North)
            online.settings.cachedUserState.value!!
        }
        val offlineServer = SeasonFakeServer().apply {
            meFailure = IOException("airplane mode")
            seasonFailure = IOException("airplane mode")
        }
        SeasonHarness(offlineServer).use { restarted ->
            restarted.secure.set(SecureStoreKeys.RECOVERY_KEY, "season-fixture-key")
            restarted.settings.setCachedUser(cached)
            assertIs<SessionState.Offline>(restarted.session.startup())
            assertEquals("north", currentMode(restarted))

            assertFailsWith<ApiError.Network> { restarted.session.updateSeasonMode(SeasonMode.South) }
            assertIs<SessionState.Offline>(restarted.session.state.value)
            assertEquals("north", currentMode(restarted))
            assertEquals("north", restarted.settings.cachedUserState.value?.seasonMode)
        }
    }

    @Test
    fun serverChangeArrivesWithTheNextMeRefresh() = runBlocking {
        SeasonHarness().use { harness ->
            harness.signedInWithKey()
            harness.session.updateSeasonMode(SeasonMode.North)
            harness.server.seasonMode = "off"
            harness.session.refreshUser()
            assertEquals("off", currentMode(harness))
            assertEquals("off", harness.settings.cachedUserState.value?.seasonMode)
        }
    }

    @Test
    fun lateMeResponseDoesNotOverwriteAJustSavedMode() = runBlocking {
        SeasonHarness().use { harness ->
            harness.signedInWithKey()
            val meGate = CompletableDeferred<Unit>()
            harness.server.meGate = meGate
            val mesBefore = harness.server.meCount

            // /me reads the user (mode auto) now and answers only after the save below finished.
            val refresh = async { harness.session.refreshUser() }
            waitFor { harness.server.meCount == mesBefore + 1 }
            harness.session.updateSeasonMode(SeasonMode.North)
            assertEquals("north", currentMode(harness))

            meGate.complete(Unit)
            refresh.await()
            assertEquals("north", currentMode(harness), "the stale /me must not bring back 'auto'")
            assertEquals("north", harness.settings.cachedUserState.value?.seasonMode)
        }
    }
}
