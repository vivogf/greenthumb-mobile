package site.xmpp.greenthumb.data

import kotlinx.serialization.Serializable
import site.xmpp.greenthumb.core.network.ApiClient
import site.xmpp.greenthumb.core.network.ApiError
import site.xmpp.greenthumb.core.network.CareEventDto
import site.xmpp.greenthumb.core.network.CareEventType
import site.xmpp.greenthumb.core.storage.CareEventEntity
import site.xmpp.greenthumb.core.storage.PlantEntity

/** Journal entry types the user can delete through [PlantRepository.deleteCareEntry]. */
public val DELETABLE_CARE_ENTRY_TYPES: Set<String> = setOf(
    CareEventType.WATER,
    CareEventType.FERTILIZE,
    CareEventType.REPOT,
    CareEventType.PRUNE,
)

/**
 * Body of a queued `delete_event`: the whole entry, so a refused delete can put it back.
 * The plant snapshot of the same row restores the card date.
 */
@Serializable
public data class DeleteEventPayload(val event: CareEventDto)

/** Event id of a `delete_event` payload; null for a broken one. */
public fun deletedEventIdOf(payload: String): String? =
    runCatching { ApiClient.json.decodeFromString<DeleteEventPayload>(payload).event.id }.getOrNull()

/**
 * A server older than 0.8.2 deletes only notes and answers 409 for a care entry. The same status comes
 * for a photo entry, but the app never sends those.
 */
public fun isOldServerRefusal(error: Throwable): Boolean = error is ApiError.Client && error.status == 409

/**
 * The plant after deleting [entry], by the server rule (`lastDateAfterDelete`): only a fertilize, repot or
 * prune entry that holds the current card date moves it, to the newest [remaining] entry of that type or null.
 * Water keeps its date: it is required, and Postpone moves it without an entry.
 */
internal fun PlantEntity.afterEntryDelete(entry: CareEventEntity, remaining: List<CareEventEntity>): PlantEntity {
    val current = when (entry.type) {
        CareEventType.FERTILIZE -> lastFertilizedDate
        CareEventType.REPOT -> lastRepottedDate
        CareEventType.PRUNE -> lastPrunedDate
        else -> return this
    }
    if (current == null || current.take(10) != entry.occurredOn) return this
    val next = remaining.filter { it.type == entry.type && it.id != entry.id }.maxOfOrNull { it.occurredOn }
    return when (entry.type) {
        CareEventType.FERTILIZE -> copy(lastFertilizedDate = next)
        CareEventType.REPOT -> copy(lastRepottedDate = next)
        else -> copy(lastPrunedDate = next)
    }
}
