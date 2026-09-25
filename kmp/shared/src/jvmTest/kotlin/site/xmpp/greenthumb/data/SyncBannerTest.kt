package site.xmpp.greenthumb.data

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * VAL-DATA-010: баннер от sync_meta на порогах 59/61/299/301 с.
 * Ровно 60 с и 300 с — граница RN (`>=`), часы инжектированы.
 */
class SyncBannerTest {

    @Test
    fun banner_follows_sync_meta_at_age_thresholds() = runBlocking<Unit> {
        val harness = openHarness { _ -> ok(plantsJson(listOf(healthyPlant()))) }
        harness.use { box ->
            assertFalse(box.repo.syncBanner(SYNCED_AT).visible, "ещё не синхронизировались")
            assertNull(box.repo.syncBanner(SYNCED_AT).syncedAtLabel)

            box.repo.refresh()
            assertEquals(SYNCED_AT, box.repo.lastSyncedAtMillis())

            val hidden = box.repo.syncBanner(SYNCED_AT + 59_000)
            assertFalse(hidden.visible)
            assertFalse(hidden.showOfflineIcon)

            val at60 = box.repo.syncBanner(SYNCED_AT + 60_000)
            assertTrue(at60.visible, "ровно 60 с — баннер уже есть")
            assertFalse(at60.showOfflineIcon)
            assertEquals("12:00", at60.syncedAtLabel)

            val shown = box.repo.syncBanner(SYNCED_AT + 61_000)
            assertTrue(shown.visible)
            assertFalse(shown.showOfflineIcon)
            assertEquals("12:00", shown.syncedAtLabel, "время синхронизации, не «сейчас»")

            val beforeIcon = box.repo.syncBanner(SYNCED_AT + 299_000)
            assertTrue(beforeIcon.visible)
            assertFalse(beforeIcon.showOfflineIcon)

            val at300 = box.repo.syncBanner(SYNCED_AT + 300_000)
            assertTrue(at300.visible)
            assertTrue(at300.showOfflineIcon, "ровно 300 с — иконка офлайна")
            assertEquals("12:00", at300.syncedAtLabel)

            val offlineIcon = box.repo.syncBanner(SYNCED_AT + 301_000)
            assertTrue(offlineIcon.visible)
            assertTrue(offlineIcon.showOfflineIcon)
            assertEquals("12:00", offlineIcon.syncedAtLabel)
        }
    }

    @Test
    fun future_sync_time_hides_banner() = runBlocking<Unit> {
        val harness = openHarness { _ -> ok("[]") }
        harness.use { box ->
            box.repo.refresh()
            val banner = box.repo.syncBanner(SYNCED_AT - 5_000)
            assertFalse(banner.visible)
            assertFalse(banner.showOfflineIcon)
        }
    }
}
