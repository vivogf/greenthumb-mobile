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
import site.xmpp.greenthumb.core.network.GreenThumbApi
import site.xmpp.greenthumb.core.network.PHOTO_CAPTION_MAX_LENGTH
import site.xmpp.greenthumb.core.network.SessionSuperseded
import site.xmpp.greenthumb.core.network.TimelinePhotoDto
import site.xmpp.greenthumb.core.storage.GreenThumbDb
import site.xmpp.greenthumb.core.storage.PlantPhotoEntity

/**
 * Photo timeline of one account (architecture §5.5).
 *
 * Room holds the last server copy of each plant's photos, so the timeline
 * stays readable offline. An upload is written to Room only after the server
 * returns the created row (with its `photo` journal entry); an offline
 * [addPhoto] throws and leaves nothing behind, so reconnecting alone never
 * uploads a photo (VAL-TIMELINE-014).
 */
public class PlantTimeline internal constructor(
    private val db: GreenThumbDb,
    private val api: GreenThumbApi,
    private val session: AccountSession,
    private val isClosed: () -> Boolean,
) {
    private val lock = Mutex()

    public fun observe(plantId: String): Flow<List<TimelinePhotoDto>> =
        flow { emitAll(db.plantPhotos().observeForPlant(plantId)) }
            .map { rows -> rows.map { it.toDto() } }
            .catch { if (!isClosed()) throw it }

    public suspend fun refresh(plantId: String) {
        lock.withLock {
            val stamp = session.current()
            val photos = api.listPhotos(plantId)
            ensureCurrent(stamp)
            db.plantPhotos().replaceForPlant(plantId, photos.map { it.toEntity(plantId) })
        }
    }

    /**
     * Uploads one photo (a JPEG data URI) with an optional caption. A blank
     * caption is not sent; an over-limit one is rejected before any request.
     */
    public suspend fun addPhoto(plantId: String, photoDataUri: String, caption: String?): TimelinePhotoDto {
        val text = caption?.trim()?.takeIf { it.isNotEmpty() }
        require(text == null || text.length <= PHOTO_CAPTION_MAX_LENGTH) {
            "caption is longer than $PHOTO_CAPTION_MAX_LENGTH"
        }
        return lock.withLock {
            val stamp = session.current()
            val created = api.addPhoto(plantId, photoDataUri, text)
            ensureCurrent(stamp)
            val photo = created.photo()
            db.plantPhotos().upsert(photo.toEntity(plantId))
            created.event?.let { db.careEvents().upsert(it.toEntity()) }
            photo
        }
    }

    /** Deletes a photo on the server, then it and its journal entry from the cache. 404 means already gone. */
    public suspend fun deletePhoto(plantId: String, photoId: String) {
        lock.withLock {
            val stamp = session.current()
            try {
                api.deletePhoto(plantId, photoId)
            } catch (error: ApiError.Client) {
                if (error.status != 404) throw error
            }
            ensureCurrent(stamp)
            db.plantPhotos().deleteById(photoId)
            db.careEvents().deleteByPhotoId(photoId)
        }
    }

    private suspend fun ensureCurrent(stamp: Long) {
        if (!session.isCurrent(stamp)) throw SessionSuperseded()
    }
}

internal fun TimelinePhotoDto.toEntity(plantId: String): PlantPhotoEntity = PlantPhotoEntity(
    id = id,
    plantId = plantId,
    url = url,
    takenOn = takenOn,
    caption = caption,
    createdAt = createdAt,
)

internal fun PlantPhotoEntity.toDto(): TimelinePhotoDto = TimelinePhotoDto(
    id = id,
    url = url,
    takenOn = takenOn,
    caption = caption,
    createdAt = createdAt,
)
