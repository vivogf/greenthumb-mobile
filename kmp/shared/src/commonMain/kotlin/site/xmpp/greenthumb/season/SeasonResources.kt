package site.xmpp.greenthumb.season

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import site.xmpp.greenthumb.core.storage.SessionManager
import site.xmpp.greenthumb.core.storage.SessionState
import site.xmpp.greenthumb.data.PlantClocks
import site.xmpp.greenthumb.ui.res.Res

/** Process-wide latitude table, read from the APK resources once. No network is involved. */
public object TzLatitudesStore {
    private val mutex = Mutex()
    private var cached: TzLatitudes? = null

    public fun cachedOrNull(): TzLatitudes? = cached

    public suspend fun latitudes(): TzLatitudes = mutex.withLock {
        cached ?: withContext(Dispatchers.Default) {
            decodeTzLatitudes(Res.readBytes(TZ_LATITUDES_RESOURCE).decodeToString())
        }.also { cached = it }
    }
}

/** The bundled latitude table; null until it is read, [TzLatitudes.Empty] if reading fails. */
@Composable
public fun rememberTzLatitudes(): TzLatitudes? {
    val latitudes by produceState(TzLatitudesStore.cachedOrNull()) {
        value = try {
            TzLatitudesStore.latitudes()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            TzLatitudes.Empty
        }
    }
    return latitudes
}

/**
 * Season of the signed-in user: `season_mode` from the session user (the server user, or the
 * `cached_user` in AppSettings when offline) and the device zone. Recomposes when the mode changes.
 */
@Composable
public fun rememberSeasonContext(session: SessionManager): SeasonContext {
    val state by session.state.collectAsState()
    val wire = when (val current = state) {
        is SessionState.SignedIn -> current.user.seasonMode
        is SessionState.Offline -> current.user.seasonMode
        else -> null
    }
    val latitudes = rememberTzLatitudes() ?: TzLatitudes.Empty
    val clocks = PlantClocks()
    val mode = seasonModeOf(wire)
    return remember(mode, clocks.zone, latitudes) { clocks.season(mode, latitudes) }
}
