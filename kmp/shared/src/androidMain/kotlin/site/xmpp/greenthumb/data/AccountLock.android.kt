package site.xmpp.greenthumb.data

internal actual fun <T> withAccountLock(lock: Any, block: () -> T): T =
    kotlin.synchronized(lock, block)
