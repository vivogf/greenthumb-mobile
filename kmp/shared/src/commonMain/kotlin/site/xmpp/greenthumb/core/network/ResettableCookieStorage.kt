package site.xmpp.greenthumb.core.network

import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.plugins.cookies.CookiesStorage
import io.ktor.http.Cookie
import io.ktor.http.Url
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Epoch запроса. Cookie старой сессии не пишется в хранилище новой:
 * [ResettableCookieStorage] сверяет элемент контекста с открытой сессией.
 */
internal class CookieEpoch(val epoch: Long) : AbstractCoroutineContextElement(CookieEpoch) {
    internal companion object : CoroutineContext.Key<CookieEpoch>
}

/**
 * Cookie только в памяти ([AcceptAllCookiesStorage]), с полным сбросом.
 *
 * Ktor-хранилище не умеет clear. Смена сессии подменяет делегат: старый
 * Set-Cookie (epoch запроса не совпал) в новый jar не попадает.
 * Стартовый epoch — 1, как [AccountSession].
 */
internal class ResettableCookieStorage : CookiesStorage {
    private val mutex = Mutex()
    private var delegate: CookiesStorage = AcceptAllCookiesStorage()
    private var openEpoch: Long = 1L

    override suspend fun get(requestUrl: Url): List<Cookie> {
        val epoch = currentCoroutineContext()[CookieEpoch]?.epoch
        return mutex.withLock {
            if (epoch != null && epoch != openEpoch) {
                emptyList()
            } else {
                delegate.get(requestUrl)
            }
        }
    }

    override suspend fun addCookie(requestUrl: Url, cookie: Cookie) {
        val epoch = currentCoroutineContext()[CookieEpoch]?.epoch
        mutex.withLock {
            if (epoch != null && epoch != openEpoch) return@withLock
            delegate.addCookie(requestUrl, cookie)
        }
    }

    /** Пустой jar, привязанный к [epoch] новой сессии. */
    suspend fun openFor(epoch: Long) {
        val previous = mutex.withLock {
            val old = delegate
            delegate = AcceptAllCookiesStorage()
            openEpoch = epoch
            old
        }
        previous.close()
    }

    override fun close() {
        delegate.close()
    }
}
