package site.xmpp.greenthumb.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate
import site.xmpp.greenthumb.core.network.PlantDto
import site.xmpp.greenthumb.core.platform.effectiveAppLanguage
import site.xmpp.greenthumb.core.storage.AppLanguage
import site.xmpp.greenthumb.core.storage.AppPreferencesStore
import site.xmpp.greenthumb.season.SeasonContext
import site.xmpp.greenthumb.season.SeasonMode
import site.xmpp.greenthumb.season.TzLatitudes
import site.xmpp.greenthumb.season.seasonModeOf

/**
 * Data of the Android home-screen widget (architecture §5.8, §8.8). The widget lives in androidApp
 * and reads only [PlantWidgetSource]: no writes, no network.
 */
public data class WidgetPlant(
    val id: String,
    val name: String,
    val lastWateredDate: String,
    val waterFrequencyDays: Int,
)

/** The signed-in (or offline) account: its season mode and the plants of its local database. */
public data class WidgetAccount(
    val userId: String,
    val seasonMode: SeasonMode,
    val plants: List<WidgetPlant>,
)

/** [account] is null when nobody is signed in. [language] is the in-app setting, null = device locale. */
public data class WidgetSnapshot(
    val language: AppLanguage?,
    val account: WidgetAccount?,
)

/** Read-only view of the current account for the widget. */
public interface PlantWidgetSource {
    /** The current snapshot, then a new one after each change of account, language or the account's plants. */
    public fun observe(): Flow<WidgetSnapshot>
}

public data class WidgetRow(
    val plantId: String,
    val name: String,
    /** Days until the next watering: negative = overdue, 0 = due today. */
    val daysUntil: Int,
)

public sealed interface WidgetUi {
    public data object SignedOut : WidgetUi

    public data object NoPlants : WidgetUi

    public data object AllWatered : WidgetUi

    public data class Due(val rows: List<WidgetRow>, val moreCount: Int) : WidgetUi
}

public const val WIDGET_MAX_ROWS: Int = 5

/**
 * Due today or overdue under the same seasonal rule as the dashboard ([wateringStatus]); most overdue
 * first, then due today, ties by name, at most [WIDGET_MAX_ROWS] rows and the rest as [WidgetUi.Due.moreCount].
 * [seasonFor] binds the account's mode to the device zone.
 */
public fun buildWidgetUi(
    snapshot: WidgetSnapshot,
    today: LocalDate,
    seasonFor: (SeasonMode) -> SeasonContext,
): WidgetUi {
    val account = snapshot.account ?: return WidgetUi.SignedOut
    if (account.plants.isEmpty()) return WidgetUi.NoPlants
    val season = seasonFor(account.seasonMode)
    val due = account.plants
        .map { plant ->
            WidgetRow(
                plantId = plant.id,
                name = plant.name,
                daysUntil = daysUntilWatering(plant.lastWateredDate, plant.waterFrequencyDays, today, season),
            )
        }
        .filter { it.daysUntil <= 0 }
        .sortedWith(
            compareBy<WidgetRow> { it.daysUntil }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                .thenBy { it.plantId },
        )
    if (due.isEmpty()) return WidgetUi.AllWatered
    return WidgetUi.Due(rows = due.take(WIDGET_MAX_ROWS), moreCount = (due.size - WIDGET_MAX_ROWS).coerceAtLeast(0))
}

/** [buildWidgetUi] for the current device date and zone. */
public fun buildWidgetUiNow(
    snapshot: WidgetSnapshot,
    latitudes: TzLatitudes,
    clocks: PlantClocks = PlantClocks(),
): WidgetUi = buildWidgetUi(snapshot, clocks.today()) { mode -> clocks.season(mode, latitudes) }

/** The in-app language, or the device locale when nothing is saved (same rule as the app UI). */
public fun widgetLanguage(saved: AppLanguage?, deviceLanguage: String): AppLanguage =
    effectiveAppLanguage(saved, deviceLanguage)

/**
 * [PlantWidgetSource] over `cached_user` (set while a session exists, cleared on sign-out, account
 * deletion and a 401 on the key) and the per-user database of [gate]. It never creates a database
 * file: an account whose file is missing or was discarded shows zero plants.
 */
public class AccountWidgetSource(
    private val settings: AppPreferencesStore,
    private val gate: AccountPlantGate,
) : PlantWidgetSource {

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observe(): Flow<WidgetSnapshot> {
        val account = settings.cachedUser
            .map { user -> user?.let { it.id to seasonModeOf(it.seasonMode) } }
            .distinctUntilChanged()
        return combine(settings.language, account, gate.changes) { language, user, _ -> language to user }
            .flatMapLatest { (language, user) ->
                if (user == null) {
                    flowOf(WidgetSnapshot(language, null))
                } else {
                    plantsOf(user.first).map { plants ->
                        WidgetSnapshot(language, WidgetAccount(user.first, user.second, plants))
                    }
                }
            }
            .distinctUntilChanged()
    }

    private fun plantsOf(userId: String): Flow<List<WidgetPlant>> = flow {
        val repo = gate.openIfPresent(userId)
        if (repo == null) {
            emit(emptyList())
        } else {
            emitAll(repo.observePlants().map { plants -> plants.map { it.toWidgetPlant() } })
        }
    }
}

private fun PlantDto.toWidgetPlant(): WidgetPlant =
    WidgetPlant(id = id, name = name, lastWateredDate = lastWateredDate, waterFrequencyDays = waterFrequencyDays)
