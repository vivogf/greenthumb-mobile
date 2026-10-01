package site.xmpp.greenthumb.core.platform

/**
 * Аварийный выключатель (Stage 12 п.1, VAL-REL-001; architecture.md §12):
 * Firebase Remote Config `min_supported_build` против build number установки
 * ([AppVersion.code]). Замена EAS Update (OTA) как механизм «остановить
 * распространение сломанной версии без релиза»: при старте приложение читает
 * конфиг; значение выше текущего build number → блокирующий экран «обновите
 * приложение» со ссылкой в стор.
 *
 * РАЗДЕЛЕНИЕ ОТВЕТСТВЕННОСТЕЙ:
 * - [RemoteKillSwitch] (expect) — ТОЛЬКО чтение конфига: платформенный
 *   источник числа min_supported_build. Никогда не бросает (fail-open:
 *   любой сбой — последнее известное значение/дефолт).
 * - [evaluateKillSwitch] — РЕШАЮЩАЯ логика: чистая функция, jvmTest.
 * - [site.xmpp.greenthumb.KillSwitchGate] — держатель вердикта на процесс
 *   (корневой пакет, рядом с SessionGraph; проверка — из App() параллельно
 *   со стартом сессии).
 *
 * КОНТРАКТ И ОГРАНИЧЕНИЯ: Remote Config читается только если приложение
 * стартует, доходит до сети и успевает за таймаут fetch'а. Kill-switch НЕ
 * ловит краш на старте до чтения конфига, не работает без сети на первом
 * старте (дефолт 0 = не активен) и не откатывает испорченные локальные
 * данные — полное описание: missionDir/library/kill-switch.md.
 */

/**
 * Вердикт kill-switch — результат сравнения build numbers (потребитель —
 * App(): Blocked рисует экран обновления вместо всего содержимого).
 */
public sealed class KillSwitchVerdict {
    /** min_supported_build <= текущего: приложение продолжает работу. */
    public data object Allowed : KillSwitchVerdict()

    /**
     * min_supported_build > текущего: версия отозвана, показывается
     * блокирующий экран. [minimumBuild] — требуемый минимум (диагностика).
     */
    public data class Blocked(public val minimumBuild: Long) : KillSwitchVerdict()
}

/**
 * Дефолт min_supported_build: параметра в конфиге нет (или не прочитан) —
 * выключатель не активен (fail-open). То же значение задано как Remote Config
 * default в android-актуале: свежая установка без сети никогда не блокируется.
 */
public const val DEFAULT_MIN_SUPPORTED_BUILD: Long = 0L

/**
 * Решение kill-switch: блокируем, только если требуемый минимум СТРОГО выше
 * текущего build number (план Stage 12 п.1 «если он меньше»); равенство —
 * разрешено (значение ровно по границе не отзывает установленную сборку).
 */
public fun evaluateKillSwitch(currentBuild: Long, minimumSupportedBuild: Long): KillSwitchVerdict =
    if (minimumSupportedBuild > currentBuild) {
        KillSwitchVerdict.Blocked(minimumSupportedBuild)
    } else {
        KillSwitchVerdict.Allowed
    }

/**
 * Платформенный источник min_supported_build (architecture.md §3 «RemoteKillSwitch»,
 * один из 14 expect/actual). open — тестовая инжекция: jvmTest подменяет источник
 * двойником ([site.xmpp.greenthumb.KillSwitchGate] решает по его ответу).
 *
 * androidMain — Firebase Remote Config (короткий таймаут, fail-open, дев-оверрайд
 * debug-сборок файлом в filesDir); jvmMain — заглушка: Firebase на JVM нет
 * (architecture.md §0), дефолт 0 + лог; дев-ручка hotRun — env GT_MIN_SUPPORTED_BUILD.
 */
public expect open class RemoteKillSwitch {
    /** Дефолтный конструктор: источник платформенный (context/env). */
    public constructor()

    /**
     * Прочитать min_supported_build. НЕ бросает (кроме [kotlinx.coroutines
     * .CancellationException] — отмена вызывающего наверх): сетевой/платформенный
     * провал → последнее известное значение или [DEFAULT_MIN_SUPPORTED_BUILD].
     * Реальный fetch — раз на процесс (гейт вызывает один раз; повторный вызов
     * законен, но троттлится Remote Config'ом).
     */
    public open suspend fun fetchMinimumSupportedBuild(): Long
}
