package site.xmpp.greenthumb.season

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import site.xmpp.greenthumb.data.PlantClocks
import site.xmpp.greenthumb.ui.res.Res
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * VAL-SEASON-026: auto-mode statuses use the latitude table from the first frame that shows them. The
 * table load retries once, then falls back to unadjusted statuses without throwing.
 */
class SeasonLatitudesLoadTest {

    private val bundled: String = runBlocking { Res.readBytes(TZ_LATITUDES_RESOURCE).decodeToString() }

    @AfterTest
    fun restoreStore() {
        TzLatitudesStore.resetForTests()
    }

    @Test
    fun one_failed_read_is_retried_and_the_table_is_cached() = runBlocking {
        var reads = 0
        TzLatitudesStore.resetForTests {
            reads++
            if (reads == 1) throw IOException("resource busy") else bundled
        }

        val table = TzLatitudesStore.loadOrEmpty()

        assertEquals(2, reads)
        assertTrue(table.size > 0, "the retry returned the bundled table")
        assertSame(table, TzLatitudesStore.cachedOrNull())
        assertSame(table, TzLatitudesStore.loadOrEmpty())
        assertEquals(2, reads, "a cached table is not read again")
    }

    @Test
    fun two_failed_reads_fall_back_to_the_empty_table_without_throwing() = runBlocking {
        var reads = 0
        TzLatitudesStore.resetForTests {
            reads++
            throw IOException("resource missing")
        }

        val table = TzLatitudesStore.loadOrEmpty()

        assertEquals(2, reads, "one retry, no more")
        assertSame(TzLatitudes.Empty, table)
        assertNull(TzLatitudesStore.cachedOrNull(), "a failure is not cached")
        val fallback = resolveSeasonContext(SeasonMode.Auto, moscow(), table)
        assertNotNull(fallback)
        assertEquals(false, fallback.isAdjusted("2026-12-01"), "fallback is the unadjusted interval")
    }

    @Test
    fun auto_mode_waits_for_the_table_and_fixed_modes_do_not() {
        assertNull(resolveSeasonContext(SeasonMode.Auto, moscow(), null), "no unadjusted auto frame before the table")

        val north = assertNotNull(resolveSeasonContext(SeasonMode.North, moscow(), null))
        assertTrue(north.isAdjusted("2026-12-01"))
        val off = assertNotNull(resolveSeasonContext(SeasonMode.Off, moscow(), null))
        assertEquals(false, off.isAdjusted("2026-12-01"))

        val auto = assertNotNull(resolveSeasonContext(SeasonMode.Auto, moscow(), decodeTzLatitudes(bundled)))
        assertTrue(auto.isAdjusted("2026-12-01"), "auto in Moscow is adjusted once the table is there")
    }

    private fun moscow() = PlantClocks(zone = TimeZone.of("Europe/Moscow"))
}
