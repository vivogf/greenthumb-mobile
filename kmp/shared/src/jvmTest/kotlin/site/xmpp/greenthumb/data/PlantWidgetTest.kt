@file:OptIn(kotlin.io.path.ExperimentalPathApi::class)

package site.xmpp.greenthumb.data

import io.ktor.client.engine.mock.MockEngine
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import site.xmpp.greenthumb.core.network.AccountSession
import site.xmpp.greenthumb.core.network.ApiClient
import site.xmpp.greenthumb.core.network.GreenThumbApi
import site.xmpp.greenthumb.core.network.NoSessionRecoveryProvider
import site.xmpp.greenthumb.core.network.UserDto
import site.xmpp.greenthumb.core.storage.AppLanguage
import site.xmpp.greenthumb.core.storage.JvmPlantDatabases
import site.xmpp.greenthumb.season.MemorySettings
import site.xmpp.greenthumb.season.SeasonContext
import site.xmpp.greenthumb.season.SeasonMode
import site.xmpp.greenthumb.season.TzLatitudes

/**
 * Home-screen widget content (architecture §5.8, §8.8): which plants it lists, in which order, its
 * empty/zero/signed-out states, date rollover, the seasonal interval, the language fallback, and the
 * read-only account source that never recreates a discarded database.
 */
class PlantWidgetTest {

    private val today = LocalDate.parse("2026-09-25")
    private val off: (SeasonMode) -> SeasonContext = { SeasonContext.Off }
    private val season: (SeasonMode) -> SeasonContext = { mode -> SeasonContext(mode, "Europe/Moscow", TzLatitudes.Empty) }

    private fun wp(id: String, name: String, lastWatered: String, frequency: Int = 7) =
        WidgetPlant(id = id, name = name, lastWateredDate = lastWatered, waterFrequencyDays = frequency)

    private fun account(plants: List<WidgetPlant>, mode: SeasonMode = SeasonMode.Off) =
        WidgetSnapshot(language = null, account = WidgetAccount("24", mode, plants))

    @Test
    fun listsAtMostFiveDuePlants_mostOverdueFirst_thenToday_tiesByName_withMoreCount() {
        val plants = listOf(
            wp("t2", "Zamioculcas", "2026-09-18"), // today
            wp("o5", "Monstera", "2026-09-13"), // 5 days overdue
            wp("o3b", "begonia", "2026-09-15"), // 3 days overdue, tie with Aloe
            wp("o3a", "Aloe", "2026-09-15"), // 3 days overdue
            wp("healthy", "Cactus", "2026-09-24"), // not due
            wp("t1", "Fern", "2026-09-18"), // today
            wp("o1", "Pothos", "2026-09-17"), // 1 day overdue
            wp("o9", "Yucca", "2026-09-09"), // 9 days overdue
        )
        val ui = buildWidgetUi(account(plants), today, off)
        val due = assertIs<WidgetUi.Due>(ui)
        assertEquals(listOf("o9", "o5", "o3a", "o3b", "o1"), due.rows.map { it.plantId })
        assertEquals(listOf(-9, -5, -3, -3, -1), due.rows.map { it.daysUntil })
        assertEquals(2, due.moreCount)
        // Same data again gives the same rows in the same order.
        assertEquals(ui, buildWidgetUi(account(plants.reversed()), today, off))
    }

    @Test
    fun exactlyFiveDue_hasNoMoreLine() {
        val plants = (1..5).map { wp("p$it", "Plant $it", "2026-09-1$it") } + wp("h", "Healthy", "2026-09-24")
        val due = assertIs<WidgetUi.Due>(buildWidgetUi(account(plants), today, off))
        assertEquals(5, due.rows.size)
        assertEquals(0, due.moreCount)
    }

    @Test
    fun noneDue_isAllWatered_zeroPlants_isNoPlants_noAccount_isSignedOut() {
        assertEquals(WidgetUi.AllWatered, buildWidgetUi(account(listOf(wp("h", "Cactus", "2026-09-24"))), today, off))
        assertEquals(WidgetUi.NoPlants, buildWidgetUi(account(emptyList()), today, off))
        assertEquals(WidgetUi.SignedOut, buildWidgetUi(WidgetSnapshot(AppLanguage.Ru, null), today, off))
    }

    @Test
    fun plantNotDueOnD_isDueOnNextDay_withoutDataChange() {
        val plants = listOf(wp("p", "Fern", "2026-09-19"))
        assertEquals(WidgetUi.AllWatered, buildWidgetUi(account(plants), today, off))
        val next = assertIs<WidgetUi.Due>(buildWidgetUi(account(plants), LocalDate.parse("2026-09-26"), off))
        assertEquals(listOf(WidgetRow("p", "Fern", 0)), next.rows)
    }

    @Test
    fun dormantMonth_usesTheEffectiveSeasonalInterval() {
        // North, January watering: 10 days x 1.5 = 15, next due 2026-01-25 (not 2026-01-20).
        val plants = listOf(wp("p", "Ficus", "2026-01-10", frequency = 10))
        val d = LocalDate.parse("2026-01-21")
        assertEquals(WidgetUi.AllWatered, buildWidgetUi(account(plants, SeasonMode.North), d, season))
        assertIs<WidgetUi.Due>(buildWidgetUi(account(plants, SeasonMode.Off), d, season))
        val onDue = assertIs<WidgetUi.Due>(buildWidgetUi(account(plants, SeasonMode.North), LocalDate.parse("2026-01-25"), season))
        assertEquals(0, onDue.rows.single().daysUntil)
    }

    @Test
    fun widgetLanguage_isTheInAppSetting_withDeviceLocaleFallback() {
        assertEquals(AppLanguage.Ru, widgetLanguage(null, "ru"))
        assertEquals(AppLanguage.En, widgetLanguage(null, "en"))
        assertEquals(AppLanguage.En, widgetLanguage(null, "de"))
        assertEquals(AppLanguage.Ru, widgetLanguage(AppLanguage.Ru, "en"))
        assertEquals(AppLanguage.En, widgetLanguage(AppLanguage.En, "ru"))
    }

    // ------------------------------------------------------------------
    // AccountWidgetSource
    // ------------------------------------------------------------------

    private val dir: File = createTempDirectory(prefix = "gt-widget").toFile()
    private val databases = JvmPlantDatabases(dir)
    private var plantsBody = "[]"
    private val client = ApiClient(MockEngine { ok(plantsBody) }, NoSessionRecoveryProvider)
    private val gate = AccountPlantGate(
        openDatabase = { databases.open(it) },
        deleteDatabase = { databases.delete(it) },
        api = GreenThumbApi(client),
        session = AccountSession(),
        databaseExists = { databases.exists(it) },
    )
    private val settings = MemorySettings()

    @AfterTest
    fun cleanup() {
        runBlocking { runCatching { gate.closeAndDelete(USER) } }
        runCatching { client.close() }
        dir.toPath().deleteRecursively()
    }

    private fun user(mode: String? = "north"): UserDto =
        UserDto(id = USER, seasonMode = mode, recoveryKey = "changeme-widget-key", createdAt = "2026-01-01T00:00:00.000Z")

    private suspend fun AccountWidgetSource.next(predicate: (WidgetSnapshot) -> Boolean): WidgetSnapshot =
        withTimeout(5_000) { observe().first(predicate) }

    @Test
    fun source_signedOut_whenNoCachedUser() = runBlocking {
        val source = AccountWidgetSource(settings, gate)
        settings.setLanguage(AppLanguage.Ru)
        val snapshot = source.next { it.language == AppLanguage.Ru }
        assertNull(snapshot.account)
    }

    @Test
    fun source_readsTheSignedInAccountPlants_andFollowsWrites() = runBlocking {
        plantsBody = plantsJson(listOf(plant("a", "2026-09-01", name = "Aloe")))
        gate.open(USER).refresh()
        settings.setCachedUser(user())
        val source = AccountWidgetSource(settings, gate)
        val first = source.next { it.account?.plants?.size == 1 }
        assertEquals(SeasonMode.North, first.account?.seasonMode)
        assertEquals("Aloe", first.account?.plants?.single()?.name)

        plantsBody = plantsJson(listOf(plant("a", "2026-09-01", name = "Aloe"), plant("b", "2026-09-02", name = "Basil")))
        gate.open(USER).refresh()
        source.next { it.account?.plants?.map { p -> p.name } == listOf("Aloe", "Basil") }

        settings.clearCachedUser()
        assertNull(source.next { it.account == null }.account)
    }

    @Test
    fun source_neverCreatesAMissingOrDiscardedDatabase() = runBlocking {
        settings.setCachedUser(user())
        val source = AccountWidgetSource(settings, gate)
        val empty = source.next { it.account != null }
        assertEquals(emptyList(), empty.account?.plants)
        assertFalse(databases.exists(USER), "the widget must not create a database file")

        gate.open(USER).refresh()
        assertTrue(databases.exists(USER))
        gate.closeAndDelete(USER)
        val afterDelete = source.next { it.account?.plants?.isEmpty() == true }
        assertEquals(emptyList(), afterDelete.account?.plants)
        assertFalse(databases.exists(USER), "a discarded database stays deleted")
    }
}
