package site.xmpp.greenthumb.core.platform

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
