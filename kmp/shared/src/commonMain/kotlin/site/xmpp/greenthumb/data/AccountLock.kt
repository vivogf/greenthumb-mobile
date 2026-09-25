package site.xmpp.greenthumb.data

/**
 * Короткий замок карты открытых баз. Критическая секция не suspend:
 * иначе [AccountPlantGate.open] (его зовёт композиция) мог бы встать
 * в очередь к тому же замку, который держит выход.
 */
internal expect fun <T> withAccountLock(lock: Any, block: () -> T): T
