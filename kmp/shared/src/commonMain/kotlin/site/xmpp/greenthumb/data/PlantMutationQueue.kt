package site.xmpp.greenthumb.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Очередь мутаций одного растения (Stage 4 п.5).
 *
 * Ключ — plant_id (для add — временный id). Вторая мутация того же id
 * не начинает сеть, пока первая не закончила запись ответа или откат.
 * Разные id друг друга не ждут: порядок журнала между растениями держит
 * общий gate репозитория.
 */
internal class PlantMutationQueue {
    private val mapMutex = Mutex()
    private val queues = mutableMapOf<String, Mutex>()

    suspend fun <T> withPlant(plantId: String, block: suspend () -> T): T {
        val queue = mapMutex.withLock { queues.getOrPut(plantId) { Mutex() } }
        return queue.withLock { block() }
    }
}
