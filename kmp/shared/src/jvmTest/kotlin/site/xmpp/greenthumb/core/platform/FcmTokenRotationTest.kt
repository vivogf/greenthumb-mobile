package site.xmpp.greenthumb.core.platform

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import site.xmpp.greenthumb.core.network.ApiError

/**
 * Ротация FCM-токена (Stage 9 п.4, фича kmp-push-offline-routing) — оба пути
 * через ЛОКАЛЬНЫЙ сетевой шов (двойник [PushSubscriptions]), без live-запросов
 * (стоп M9 на live-аккаунты):
 * - включённая подписка обновляется НОВЫМ токеном (тот же провод, что у
 *   включения тумблера);
 * - выключенная подписка НЕ создаётся (push самовольно не включается);
 * - ошибки статуса/отправки push НЕ включают «незаметно» (Failed наверх).
 */
class FcmTokenRotationTest {

    /** Двойник шва: исходы инжектируются, вызовы и аргументы фиксируются. */
    private class FakeSubs : PushSubscriptions {
        var statusValue: Boolean = false
        var statusError: Throwable? = null
        var subscribeError: Throwable? = null
        var statusCalls = 0
        var subscribeCalls = 0
        var lastToken: String? = null
        var lastPlatform: String? = null
        var lastLanguage: String? = null

        override suspend fun subscribe(token: String, platform: String, language: String) {
            subscribeCalls++
            lastToken = token
            lastPlatform = platform
            lastLanguage = language
            statusError?.let { throw it }
            subscribeError?.let { throw it }
        }

        override suspend fun unsubscribe() {}

        override suspend fun status(): Boolean {
            statusCalls++
            statusError?.let { throw it }
            return statusValue
        }
    }

    @Test
    fun enabledSubscription_isUpdatedWithNewToken() = runTest {
        val subs = FakeSubs().apply { statusValue = true }
        val outcome = FcmTokenRotation(subs).rotate("new-token-2", "android", "en")
        assertIs<FcmTokenRotation.Outcome.Updated>(outcome)
        assertEquals(1, subs.subscribeCalls)
        assertEquals("new-token-2", subs.lastToken)
        assertEquals("android", subs.lastPlatform)
        assertEquals("en", subs.lastLanguage, "язык подписки — текущий язык UI (VAL-PUSH-007)")
        assertEquals(1, subs.statusCalls, "включённость решает сервер (status ДО subscribe)")
    }

    @Test
    fun disabledSubscription_isNotCreated_pushNotEnabledSilently() = runTest {
        val subs = FakeSubs().apply { statusValue = false }
        val outcome = FcmTokenRotation(subs).rotate("new-token-2", "android", "ru")
        assertIs<FcmTokenRotation.Outcome.NotSubscribed>(outcome)
        assertEquals(0, subs.subscribeCalls, "выключенная подписка не создаётся ротацией")
    }

    @Test
    fun statusError_doesNotSubscribe_andDoesNotEnablePush() = runTest {
        // Без живой сессии шов отвечает 401 (фоновый вызов сервиса) —
        // неопределённость НЕ превращается во включение push.
        val subs = FakeSubs().apply { statusError = ApiError.Unauthorized }
        val outcome = FcmTokenRotation(subs).rotate("new-token-2", "android", "ru")
        val failed = assertIs<FcmTokenRotation.Outcome.Failed>(outcome)
        assertEquals("Unauthorized", failed.message)
        assertEquals(0, subs.subscribeCalls, "ошибка статуса не подписывает")
    }

    @Test
    fun subscribeError_reportedAsFailed_notSilentlyOk() = runTest {
        val subs = FakeSubs().apply {
            statusValue = true
            subscribeError = ApiError.Server(500, "boom")
        }
        val outcome = FcmTokenRotation(subs).rotate("new-token-2", "android", "ru")
        val failed = assertIs<FcmTokenRotation.Outcome.Failed>(outcome)
        assertEquals("boom", failed.message)
        assertEquals(1, subs.subscribeCalls, "попытка отправки была")
    }

    @Test
    fun noSeam_isNoop_notSubscribed() = runTest {
        // desktop-харнесс / no-arg граф: шва нет — ротация безопасно ничего
        // не делает (Firebase на JVM не существует).
        val outcome = FcmTokenRotation(null).rotate("new-token-2", "android", "ru")
        assertIs<FcmTokenRotation.Outcome.NotSubscribed>(outcome)
    }
}
