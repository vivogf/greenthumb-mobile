package site.xmpp.greenthumb.core.platform

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Stage 4 п.8: jvm-actual [Connectivity] — «всегда онлайн» (desktop-харнесс
 * не имеет источника состояния сети; kmp-migration-plan.md Stage 4 п.8).
 *
 * Android-actual (ConnectivityManager.NetworkCallback) на JVM не выполним —
 * он проверяется на эмуляторе (см. handoff фичи kmp-connectivity).
 */
class ConnectivityJvmTest {

    @Test
    fun `jvm connectivity is always online`() = runTest {
        val connectivity = Connectivity(Any())

        assertTrue(connectivity.isOnline.value, "desktop-харнесс считается онлайн")
        assertTrue(connectivity.isOnline.first(), "поток отдаёт то же значение подписчику")

        connectivity.close()
    }

    @Test
    fun `jvm connectivity stays online after close`() = runTest {
        val connectivity = Connectivity(Any())

        connectivity.close()
        connectivity.close()

        assertTrue(connectivity.isOnline.value, "close идемпотентен и состояние не ломает")
    }
}
