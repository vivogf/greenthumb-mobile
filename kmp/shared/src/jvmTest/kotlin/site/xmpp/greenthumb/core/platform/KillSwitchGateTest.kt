package site.xmpp.greenthumb.core.platform

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import site.xmpp.greenthumb.KillSwitchGate

/**
 * Kill-switch (Stage 12 п.1, VAL-REL-001): решающая логика
 * [evaluateKillSwitch] (границы сравнения build numbers) и state machine
 * [KillSwitchGate] (fail-open на сбое fetch'а, идемпотентность на процесс).
 * Источник конфига подменяется двойником (jvm-actual открыт, паттерн PushTokens).
 */
class KillSwitchGateTest {

    /** Двойник источника: фиксированное значение / управляемый сбой + счётчик. */
    private class FakeSwitch : RemoteKillSwitch() {
        var value: Long = DEFAULT_MIN_SUPPORTED_BUILD
        var failure: Throwable? = null
        var fetchCount = 0

        override suspend fun fetchMinimumSupportedBuild(): Long {
            fetchCount++
            failure?.let { throw it }
            return value
        }
    }

    // ── evaluateKillSwitch: границы сравнения (текущий build = 6, как в сборке) ──

    @Test
    fun minimum_above_current_build_is_blocked() {
        val verdict = evaluateKillSwitch(currentBuild = 6, minimumSupportedBuild = 99)
        val blocked = assertIs<KillSwitchVerdict.Blocked>(verdict)
        assertEquals(99, blocked.minimumBuild)
    }

    @Test
    fun minimum_equal_to_current_build_is_allowed() {
        // План Stage 12 п.1 «если он меньше»: равенство не отзывает сборку.
        assertEquals(
            KillSwitchVerdict.Allowed,
            evaluateKillSwitch(currentBuild = 6, minimumSupportedBuild = 6),
        )
    }

    @Test
    fun minimum_below_current_build_is_allowed() {
        assertEquals(
            KillSwitchVerdict.Allowed,
            evaluateKillSwitch(currentBuild = 6, minimumSupportedBuild = 5),
        )
    }

    @Test
    fun missing_parameter_default_never_blocks() {
        // Дефолт 0 (нет параметра в Remote Config) не блокирует ни одну сборку.
        assertEquals(
            KillSwitchVerdict.Allowed,
            evaluateKillSwitch(currentBuild = 1, minimumSupportedBuild = DEFAULT_MIN_SUPPORTED_BUILD),
        )
    }

    // ── KillSwitchGate: state machine поверх источника ──

    @Test
    fun gate_verdict_unknown_before_check() = runTest {
        val gate = KillSwitchGate(FakeSwitch(), currentBuild = 6)
        assertNull(gate.verdict.value)
    }

    @Test
    fun gate_reports_blocked_when_fetched_minimum_above_build() = runTest {
        val fake = FakeSwitch().apply { value = 99 }
        val gate = KillSwitchGate(fake, currentBuild = 6)
        gate.checkIfNeeded()
        val blocked = assertIs<KillSwitchVerdict.Blocked>(gate.verdict.value)
        assertEquals(99, blocked.minimumBuild)
    }

    @Test
    fun gate_reports_allowed_when_fetched_minimum_at_or_below_build() = runTest {
        val gate = KillSwitchGate(FakeSwitch(), currentBuild = 6)
        gate.checkIfNeeded()
        assertEquals(KillSwitchVerdict.Allowed, gate.verdict.value)
    }

    @Test
    fun gate_is_fail_open_when_fetch_fails() = runTest {
        // Kill-switch не ломает старт: сбой чтения конфига = «не требуется».
        val fake = FakeSwitch().apply { failure = RuntimeException("Remote Config down") }
        val gate = KillSwitchGate(fake, currentBuild = 6)
        gate.checkIfNeeded()
        assertEquals(KillSwitchVerdict.Allowed, gate.verdict.value)
    }

    @Test
    fun gate_checks_once_per_process() = runTest {
        // Пересоздание Activity не перечитывает конфиг: вердикт на процесс.
        val fake = FakeSwitch().apply { value = 99 }
        val gate = KillSwitchGate(fake, currentBuild = 6)
        gate.checkIfNeeded()
        gate.checkIfNeeded()
        assertEquals(1, fake.fetchCount)
        assertIs<KillSwitchVerdict.Blocked>(gate.verdict.value)
    }
}
