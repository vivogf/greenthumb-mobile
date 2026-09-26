package site.xmpp.greenthumb.ui.login

import site.xmpp.greenthumb.core.network.ApiError
import site.xmpp.greenthumb.ui.screens.login.explainAuthError
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * VAL-LOGIN-003: дифференциация ошибок входа (порт RN explainAuthError из
 * staged P2/P3 `app/(auth)/login.tsx`):
 *
 * - явный 401 на login (isLogin=true) → «неверный recovery key» — единственный
 *   случай, когда виноват ключ;
 * - 401 на create (isLogin=false) → НЕ «неверный ключ» (ключа пользователь не
 *   вводил; сбои создания — сервисные);
 * - сеть/таймаут → «ошибка сети»;
 * - 4xx/5xx (квота Neon исторически 400, 5xx) → «сервис недоступен» — НИКОГДА
 *   «неверный ключ» (корень июньского бага: 400 от квоты показывался как
 *   «Invalid recovery key» и лочил пользователей из аккаунта).
 *
 * Строки-маркеры вместо ресурсов: функция чистая (локализацию решает вызов),
 * тест фиксирует МАППИНГ, не тексты.
 */
class AuthErrorMappingTest {

    private val invalidKey = "INVALID_KEY"
    private val network = "NETWORK"
    private val service = "SERVICE"

    private fun explain(error: ApiError, isLogin: Boolean): String =
        explainAuthError(error, isLogin, invalidKey, network, service)

    // ------------------------------------------------------------------
    // login (isLogin=true): 401 — ключ, остальные — сервис/сеть
    // ------------------------------------------------------------------

    @Test
    fun login401_isInvalidKey() {
        assertEquals(invalidKey, explain(ApiError.Unauthorized, isLogin = true))
    }

    @Test
    fun loginNetwork_isNetworkError() {
        assertEquals(network, explain(ApiError.Network, isLogin = true))
    }

    @Test
    fun loginTimeout_isNetworkError() {
        assertEquals(network, explain(ApiError.Timeout, isLogin = true))
    }

    @Test
    fun login400_isServiceUnavailable_neverInvalidKey() {
        // Квота Neon исторически отвечает 400 — не «неверный ключ».
        assertEquals(service, explain(ApiError.Client(400, "quota exceeded"), isLogin = true))
    }

    @Test
    fun login500_isServiceUnavailable() {
        assertEquals(service, explain(ApiError.Server(503, ""), isLogin = true))
    }

    @Test
    fun login429_isServiceUnavailable() {
        assertEquals(service, explain(ApiError.Client(429, ""), isLogin = true))
    }

    // ------------------------------------------------------------------
    // create (isLogin=false): даже 401 — НЕ «неверный ключ»
    // ------------------------------------------------------------------

    @Test
    fun create401_isServiceUnavailable_neverInvalidKey() {
        assertEquals(service, explain(ApiError.Unauthorized, isLogin = false))
    }

    @Test
    fun createNetwork_isNetworkError() {
        assertEquals(network, explain(ApiError.Network, isLogin = false))
    }

    @Test
    fun create400_isServiceUnavailable() {
        assertEquals(service, explain(ApiError.Client(400, "quota"), isLogin = false))
    }
}
