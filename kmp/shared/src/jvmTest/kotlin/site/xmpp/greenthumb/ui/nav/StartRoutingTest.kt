package site.xmpp.greenthumb.ui.nav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import site.xmpp.greenthumb.core.network.UserDto
import site.xmpp.greenthumb.core.storage.SessionState

/**
 * Стартовая маршрутизация (Stage 6 п.2, VAL-SHELL-002) — матрица RN
 * `app/index.tsx:43-51`; порядок проверок важен.
 */
class StartRoutingTest {

    private fun user(id: String = "30"): UserDto = UserDto(
        id = id,
        name = null,
        notificationTime = null,
        timezone = null,
        lastNotifiedDate = null,
        recoveryKey = "test-recovery-key",
        createdAt = "2026-01-01T00:00:00.000Z",
    )

    // ------------------------------------------------------------------
    // loading-гейт: индикатор на фоне сплеша, без редиректа
    // ------------------------------------------------------------------

    @Test
    fun startupNotFinishedHoldsSplashWithoutRedirect() {
        // Сессия ещё крутится (state == null) — редиректа нет при любом флаге интро.
        assertEquals(StartRoute.Loading, resolveStartRoute(null, null))
        assertEquals(StartRoute.Loading, resolveStartRoute(null, true))
        assertEquals(StartRoute.Loading, resolveStartRoute(null, false))
    }

    @Test
    fun unreadIntroFlagHoldsSignedOutOnSplash() {
        // RN: ready = !loading && introSeen !== null — SignedOut без прочитанного
        // флага интро остаётся на индикаторе.
        assertEquals(StartRoute.Loading, resolveStartRoute(SessionState.SignedOut, null))
    }

    @Test
    fun unreadIntroFlagHoldsSignedInUserOnSplash() {
        // Гейт ДО проверки пользователя: даже вход ждёт чтения флага (RN ready).
        assertEquals(StartRoute.Loading, resolveStartRoute(SessionState.SignedIn(user()), null))
    }

    // ------------------------------------------------------------------
    // Три состояния; пользователь проверяется ПЕРВЫМ
    // ------------------------------------------------------------------

    @Test
    fun signedInUserGoesToDashboardEvenWhenIntroNotSeen() {
        // Мигрировавший пользователь (user есть, интро когда-то видено) — и
        // не-видевший интро тоже: пользовательское состояние важнее флага,
        // иначе после Stage 0/3 каждый мигрант попадает в карусель.
        assertEquals(StartRoute.Dashboard, resolveStartRoute(SessionState.SignedIn(user()), true))
        assertEquals(StartRoute.Dashboard, resolveStartRoute(SessionState.SignedIn(user()), false))
    }

    @Test
    fun offlineCachedUserGoesToDashboard() {
        // Офлайн-сессия (Stage 3 п.5) — пользователь есть (cached_user): dashboard.
        assertEquals(StartRoute.Dashboard, resolveStartRoute(SessionState.Offline(user()), true))
        assertEquals(StartRoute.Dashboard, resolveStartRoute(SessionState.Offline(user()), false))
    }

    @Test
    fun signedOutWithoutIntroGoesToWelcome() {
        assertEquals(StartRoute.Welcome, resolveStartRoute(SessionState.SignedOut, false))
    }

    @Test
    fun signedOutWithIntroSeenGoesToLogin() {
        assertEquals(StartRoute.Login, resolveStartRoute(SessionState.SignedOut, true))
    }

    // ------------------------------------------------------------------
    // KMP-состояния вне RN-матрицы (Stage 3)
    // ------------------------------------------------------------------

    @Test
    fun keyNotFoundMapsToItsOwnSurface() {
        assertEquals(StartRoute.KeyNotFound, resolveStartRoute(SessionState.KeyNotFound, true))
        assertEquals(StartRoute.KeyNotFound, resolveStartRoute(SessionState.KeyNotFound, false))
    }

    @Test
    fun handoffImportFailedCarriesRecoveryKeyToSurface() {
        val route = resolveStartRoute(SessionState.HandoffImportFailed("gt-key"), true)
        assertIs<StartRoute.HandoffImportFailed>(route)
        assertEquals("gt-key", route.recoveryKey)
    }

    // ------------------------------------------------------------------
    // Хелпер id пользователя
    // ------------------------------------------------------------------

    @Test
    fun sessionUserIdOrNullResolvesUserBearingStates() {
        assertEquals("30", SessionState.SignedIn(user()).sessionUserIdOrNull())
        assertEquals("31", SessionState.Offline(user(id = "31")).sessionUserIdOrNull())
        assertEquals(null, SessionState.SignedOut.sessionUserIdOrNull())
        assertEquals(null, null.sessionUserIdOrNull())
    }
}
