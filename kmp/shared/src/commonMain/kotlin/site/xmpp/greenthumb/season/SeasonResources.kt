package site.xmpp.greenthumb.season

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import site.xmpp.greenthumb.core.platform.AppForeground
import site.xmpp.greenthumb.core.storage.SessionManager
import site.xmpp.greenthumb.core.storage.SessionState
import site.xmpp.greenthumb.data.PlantClocks
import site.xmpp.greenthumb.ui.res.Res

/** Process-wide latitude table, read from the APK resources once. No network is involved. */
public object TzLatitudesStore {
    private val mutex = Mutex()
    private var cached: TzLatitudes? = null
    private val bundledReader: suspend () -> String = { Res.readBytes(TZ_LATITUDES_RESOURCE).decodeToString() }
    private var reader: suspend () -> String = bundledReader

    public fun cachedOrNull(): TzLatitudes? = cached

    public suspend fun latitudes(): TzLatitudes = mutex.withLock {
        cached ?: withContext(Dispatchers.Default) { decodeTzLatitudes(reader()) }.also { cached = it }
    }

    /**
     * The table, read with one retry. After a second failure the result is [TzLatitudes.Empty] (auto mode
     * then gives unadjusted statuses); the failure is not cached, so a later call reads again.
     */
    public suspend fun loadOrEmpty(): TzLatitudes {
        repeat(LOAD_ATTEMPTS) {
            try {
                return latitudes()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                // Next attempt, or the fallback below.
            }
        }
        return TzLatitudes.Empty
    }

    internal fun resetForTests(reader: suspend () -> String = bundledReader) {
        cached = null
        this.reader = reader
    }

    private const val LOAD_ATTEMPTS = 2
}

/** The bundled latitude table; null until it is read, [TzLatitudes.Empty] if both reads fail. */
@Composable
public fun rememberTzLatitudes(): TzLatitudes? {
    val latitudes by produceState(TzLatitudesStore.cachedOrNull()) {
        if (value == null) value = TzLatitudesStore.loadOrEmpty()
    }
    return latitudes
}

/**
 * The season for [mode] in the zone of [clocks], or null while auto mode still waits for the latitude
 * table: a status computed without it would show the unadjusted interval and then flip.
 */
public fun resolveSeasonContext(mode: SeasonMode, clocks: PlantClocks, latitudes: TzLatitudes?): SeasonContext? =
    when {
        latitudes != null -> clocks.season(mode, latitudes)
        mode == SeasonMode.Auto -> null
        else -> clocks.season(mode, TzLatitudes.Empty)
    }

/**
 * Season of the signed-in user: `season_mode` from the session user (the server user, or the
 * `cached_user` in AppSettings when offline) and the device zone. Recomposes when the mode changes and
 * re-reads the device zone each time the app comes back to the foreground. Null while auto mode waits
 * for the latitude table (see [resolveSeasonContext]).
 */
@Composable
public fun rememberSeasonContext(session: SessionManager): SeasonContext? {
    val state by session.state.collectAsState()
    val wire = when (val current = state) {
        is SessionState.SignedIn -> current.user.seasonMode
        is SessionState.Offline -> current.user.seasonMode
        else -> null
    }
    val latitudes = rememberTzLatitudes()
    val foreground = remember { AppForeground() }
    DisposableEffect(foreground) { onDispose { foreground.close() } }
    val resumed by foreground.isResumed.collectAsState()
    val zone = remember(resumed) { TimeZone.currentSystemDefault() }
    val mode = seasonModeOf(wire)
    return remember(mode, zone, latitudes) { resolveSeasonContext(mode, PlantClocks(zone = zone), latitudes) }
}
