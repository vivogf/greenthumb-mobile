package site.xmpp.greenthumb.core.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Android-actual [Connectivity]: `ConnectivityManager.registerDefaultNetworkCallback`.
 *
 * «Онлайн» = у дефолтной сети есть `NET_CAPABILITY_INTERNET` и
 * `NET_CAPABILITY_VALIDATED`: без валидации состояние «подключён к Wi-Fi с
 * captive portal» выглядело бы как рабочая сеть, и координатор обновления
 * ходил бы в сеть впустую.
 *
 * Стартовое значение берётся синхронно из `activeNetwork` — подписчик
 * получает актуальное состояние сразу, не дожидаясь первого колбэка.
 * Любой сбой (нет сервиса, отказ регистрации из-за отсутствия
 * `ACCESS_NETWORK_STATE`) даёт «онлайн»: полоса «нет сети» на рабочей сети
 * мешает сильнее, чем её отсутствие на сломанной, а реальный сбой всё равно
 * придёт как [site.xmpp.greenthumb.core.network.ApiError.Network].
 *
 * Колбэк следит только за дефолтной сетью. `onLost` означает «этой дефолтной
 * сети больше нет» и ставит офлайн напрямую: перечитывать `activeNetwork`
 * здесь нельзя — сразу после `onLost` система какое-то время отдаёт прежнюю
 * сеть с прежними capabilities, и полоса «нет сети» не появляется
 * (воспроизведено на эмуляторе). Поздний `onCapabilitiesChanged` той же сети
 * игнорируется, иначе полоса мигнёт и спрячется. Придёт замена — её объявит
 * `onAvailable` / `onCapabilitiesChanged` уже другой сети.
 *
 * Колбэки приходят на looper ConnectivityManager (не обязательно main).
 * [StateFlow] это переживает; поля колбэка пишет только его поток.
 */
public actual class Connectivity actual constructor(appContext: Any) {

    private val context = (appContext as Context).applicationContext

    private val manager: ConnectivityManager? =
        runCatching {
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        }.getOrNull()

    private val state = MutableStateFlow(manager.currentlyOnline())

    public actual val isOnline: StateFlow<Boolean> = state

    /** Сеть, которую колбэк считает текущей дефолтной. null — дефолтной сети нет. */
    private var tracked: Network? = null

    /**
     * Сеть, которую только что потеряли. Её capabilities после `onLost` ещё
     * какое-то время «валидные» — публиковать их нельзя.
     */
    private var lost: Network? = null

    /** Пишется с потока владельца ([close]), читается из колбэка. */
    @Volatile
    private var closed: Boolean = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (closed) return
            lost = null
            tracked = network
            // onAvailable часто приходит до capabilities. Нет сведений — не
            // затираем стартовое значение; их принесёт onCapabilitiesChanged.
            publish(manager.capabilitiesOf(network))
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (closed) return
            if (network == lost) return
            if (tracked != null && network != tracked) return
            tracked = network
            state.value = capabilities.usableForInternet()
        }

        override fun onLost(network: Network) {
            if (closed) return
            lost = network
            // Уже сменились на другую дефолтную — потеря старой её не гасит.
            if (tracked != null && tracked != network) return
            tracked = null
            state.value = false
        }

        override fun onUnavailable() {
            if (closed) return
            tracked = null
            lost = null
            state.value = false
        }
    }

    private var registered: Boolean = runCatching {
        manager?.registerDefaultNetworkCallback(callback) ?: return@runCatching false
        true
    }.getOrDefault(false)

    public actual fun close() {
        if (closed) return
        closed = true
        if (!registered) return
        registered = false
        runCatching { manager?.unregisterNetworkCallback(callback) }
    }

    private fun publish(capabilities: NetworkCapabilities?) {
        if (capabilities == null) return
        state.value = capabilities.usableForInternet()
    }
}

/** Текущее состояние дефолтной сети; отсутствие сервиса/сведений читается как «онлайн». */
private fun ConnectivityManager?.currentlyOnline(): Boolean {
    if (this == null) return true
    return runCatching {
        val active = activeNetwork ?: return false
        getNetworkCapabilities(active)?.usableForInternet() ?: false
    }.getOrDefault(true)
}

private fun ConnectivityManager?.capabilitiesOf(network: Network): NetworkCapabilities? {
    if (this == null) return null
    return runCatching { getNetworkCapabilities(network) }.getOrNull()
}

/** Сеть годится для запросов: есть интернет И он прошёл проверку системы (не captive portal). */
private fun NetworkCapabilities.usableForInternet(): Boolean =
    hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
