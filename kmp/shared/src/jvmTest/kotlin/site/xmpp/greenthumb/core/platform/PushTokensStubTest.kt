package site.xmpp.greenthumb.core.platform

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/**
 * Заглушки push-подсистемы до M9 (фича screen-enable-notifications): оба
 * actual'а — [PushOutcome.Denied] без запроса разрешения; экран ведёт себя
 * как при отказе (VAL-INTRO-002-ветка отказ + parity RN «null → подсказка»).
 * Открытость класса ([PushTokens] — open) — контракт тестовой инжекции
 * UI-флоу (PostSignInFlowTest подменяет исходы).
 */
class PushTokensStubTest {

    @Test
    fun jvmStubDeniesWithoutRequesting() = runBlocking<Unit> {
        val outcome = PushTokens().requestSubscribe("ru")
        assertIs<PushOutcome.Denied>(outcome)
    }

    @Test
    fun languageArgumentAcceptedForM9Contract() = runBlocking<Unit> {
        // Язык — часть подписи M9 ('ru'/'en', RN i18n.language); заглушка
        // контрактно принимает wire-строку (не enum), исход не меняет.
        val ru = PushTokens().requestSubscribe("ru")
        val en = PushTokens().requestSubscribe("en")
        assertIs<PushOutcome.Denied>(ru)
        assertIs<PushOutcome.Denied>(en)
        assertEquals(ru::class, en::class)
    }

    /**
     * M9 push-android: шов сети ([PushSubscriptions]) на desktop-заглушке
     * НЕ используется — граф передаёт [FcmPushSubscriptions] всем таргетам,
     * но jvm-actual его игнорирует: Denied и ноль сетевых вызовов (Firebase
     * на JVM не существует, architecture.md §3).
     */
    @Test
    fun wiredJvmStubStillDeniesWithoutNetworkCalls() = runBlocking<Unit> {
        val calls = RecordingSubscriptions()
        val outcome = PushTokens(calls).requestSubscribe("ru")
        assertIs<PushOutcome.Denied>(outcome)
        assertEquals(0, calls.subscribeCalls, "desktop-заглушка сеть не трогает")
        assertEquals(0, calls.unsubscribeCalls)
        assertFalse(calls.statusCalled)
    }

    private class RecordingSubscriptions : PushSubscriptions {
        var subscribeCalls = 0
        var unsubscribeCalls = 0
        var statusCalled = false

        override suspend fun subscribe(token: String, platform: String, language: String) {
            subscribeCalls++
        }

        override suspend fun unsubscribe() {
            unsubscribeCalls++
        }

        override suspend fun status(): Boolean {
            statusCalled = true
            return false
        }
    }
}
