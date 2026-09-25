package site.xmpp.greenthumb.data

/**
 * Итог [PlantRepository.replayPending]: досылка журнала по `created_at`,
 * затем refresh, если очередь разобрана до конца.
 */
public sealed class ReplayResult {
    /** Журнал пуст. Сеть не трогали, refresh не звали. */
    public data object Idle : ReplayResult()

    /**
     * Каждая строка либо подтверждена, либо откатана по ответу сервера,
     * затем выполнен refresh. Локальная таблица сошлась с сервером.
     */
    public data object Converged : ReplayResult()

    /**
     * Сеть или таймаут. Остаток журнала на месте, refresh не звали:
     * досылка продолжится со следующей успешной попытки, в том же порядке.
     */
    public data class Held(public val cause: Throwable) : ReplayResult()
}
