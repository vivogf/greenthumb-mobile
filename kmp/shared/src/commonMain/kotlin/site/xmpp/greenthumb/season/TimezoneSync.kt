package site.xmpp.greenthumb.season

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import site.xmpp.greenthumb.core.storage.SessionManager
import site.xmpp.greenthumb.core.storage.SessionState

/**
 * Keeps `users.timezone` equal to the device zone (architecture §8.6): the server computes push due
 * dates with it, the app with the device zone. Triggers: every new signed-in user (sign-in, cold
 * start, offline → online) and every return to the foreground. On resume the user is re-read first,
 * so a season mode changed elsewhere reaches the screens too.
 */
public class TimezoneSync(
    private val session: SessionManager,
    private val deviceZone: () -> String = { TimeZone.currentSystemDefault().id },
) {
    /** Runs until cancelled. [resumed] is a hot foreground flag; its first value is not a trigger. */
    public suspend fun run(resumed: Flow<Boolean>) {
        coroutineScope {
            launch {
                session.state
                    .map { (it as? SessionState.SignedIn)?.user?.id }
                    .distinctUntilChanged()
                    .collect { userId -> if (userId != null) session.syncTimezone(deviceZone()) }
            }
            launch {
                resumed
                    .distinctUntilChanged()
                    .drop(1)
                    .filter { it }
                    .collect {
                        session.refreshUser()
                        session.syncTimezone(deviceZone())
                    }
            }
        }
    }
}
