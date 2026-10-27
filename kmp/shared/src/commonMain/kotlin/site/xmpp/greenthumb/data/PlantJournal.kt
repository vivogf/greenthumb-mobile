package site.xmpp.greenthumb.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import site.xmpp.greenthumb.core.network.AccountSession
import site.xmpp.greenthumb.core.network.ApiError
import site.xmpp.greenthumb.core.network.CARE_NOTE_MAX_LENGTH
import site.xmpp.greenthumb.core.network.CareEventDto
import site.xmpp.greenthumb.core.network.CareEventType
import site.xmpp.greenthumb.core.network.GreenThumbApi
import site.xmpp.greenthumb.core.network.SessionSuperseded
import site.xmpp.greenthumb.core.storage.CareEventEntity
import site.xmpp.greenthumb.core.storage.GreenThumbDb

/**
 * Care journal of one account (architecture §5.5).
 *
 * Room holds the last server copy of each plant's events; [observe] reads it,
 * so the journal stays readable offline. [refresh] pulls every page and
 * replaces the plant's rows in one transaction; a failure leaves the cache.
 *
 * Notes are written to Room only after the server returns the created row.
 * An offline [addNote] throws [ApiError.Network] / [ApiError.Timeout] and
 * leaves nothing behind: notes never enter the plant mutation queue, so
 * reconnecting alone cannot send one (VAL-JOURNAL-018).
 *
 * Automatic entries (`water`, `fertilize`, …) come from plant updates on the
 * server; the offline watering queue of [PlantRepository] therefore produces
 * exactly one server event when it replays.
 */
public class PlantJournal internal constructor(
    private val db: GreenThumbDb,
    private val api: GreenThumbApi,
    private val session: AccountSession,
    private val isClosed: () -> Boolean,
) {
    private val lock = Mutex()

    public fun observe(plantId: String): Flow<List<CareEventDto>> =
        flow { emitAll(db.careEvents().observeForPlant(plantId)) }
            .map { rows -> rows.map { it.toDto() } }
            .catch { if (!isClosed()) throw it }

    public suspend fun refresh(plantId: String) {
        lock.withLock {
            val stamp = session.current()
            val events = mutableListOf<CareEventDto>()
            var cursor: String? = null
            var pages = 0
            do {
                val page = api.listEvents(plantId, before = cursor)
                events += page.events
                cursor = page.nextCursor
                pages++
            } while (cursor != null && pages < MAX_PAGES)
            ensureCurrent(stamp)
            db.careEvents().replaceForPlant(
                plantId,
                events.filter { it.plantId == plantId }.map { it.toEntity() },
            )
        }
    }

    /**
     * Sends a note and caches the server row. Blank or over-limit text is
     * rejected before any request ([IllegalArgumentException]).
     */
    public suspend fun addNote(plantId: String, text: String): CareEventDto {
        val note = text.trim()
        require(note.isNotEmpty()) { "note is blank" }
        require(note.length <= CARE_NOTE_MAX_LENGTH) { "note is longer than $CARE_NOTE_MAX_LENGTH" }
        return lock.withLock {
            val stamp = session.current()
            val created = api.addNote(plantId, note)
            ensureCurrent(stamp)
            db.careEvents().upsert(created.toEntity())
            created
        }
    }

    /**
     * Deletes a note on the server, then from the cache. 404 means it is
     * already gone. Automatic entries are refused without a request.
     */
    public suspend fun deleteNote(plantId: String, eventId: String) {
        lock.withLock {
            val cached = db.careEvents().getById(eventId)
            require(cached == null || cached.type == CareEventType.NOTE) { "only notes can be deleted" }
            val stamp = session.current()
            try {
                api.deleteEvent(plantId, eventId)
            } catch (error: ApiError.Client) {
                if (error.status != 404) throw error
            }
            ensureCurrent(stamp)
            db.careEvents().deleteById(eventId)
        }
    }

    private suspend fun ensureCurrent(stamp: Long) {
        if (!session.isCurrent(stamp)) throw SessionSuperseded()
    }

    private companion object {
        /** 10 × 100 entries; older history stays on the server. */
        const val MAX_PAGES = 10
    }
}

internal fun CareEventDto.toEntity(): CareEventEntity = CareEventEntity(
    id = id,
    plantId = plantId,
    userId = userId,
    type = type,
    occurredOn = occurredOn,
    note = note,
    photoId = photoId,
    createdAt = createdAt,
)

internal fun CareEventEntity.toDto(): CareEventDto = CareEventDto(
    id = id,
    plantId = plantId,
    userId = userId,
    type = type,
    occurredOn = occurredOn,
    note = note,
    photoId = photoId,
    createdAt = createdAt,
)
