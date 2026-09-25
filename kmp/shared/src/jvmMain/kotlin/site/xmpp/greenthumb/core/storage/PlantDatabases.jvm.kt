package site.xmpp.greenthumb.core.storage

import androidx.room.Room
import java.io.File

/**
 * JVM-actual для desktop-харнесса: `~/.greenthumb/plants_${userId}.db`
 * (тот же каталог, что AppSettings/SecureStore). Тесты конструируют
 * [JvmPlantDatabases] со своим каталогом.
 */
public actual class PlantDatabases actual constructor(appContext: Any) {
    private val delegate = JvmPlantDatabases(defaultStorageDir())

    actual fun open(userId: String): GreenThumbDb = delegate.open(userId)

    actual fun delete(userId: String) = delegate.delete(userId)

    actual fun absolutePath(userId: String): String = delegate.absolutePath(userId)
}

/**
 * Инжектируемый jvm-opener. [directory] — каталог файлов `plants_*.db`,
 * не родительский путь внутри имени.
 *
 * Инстанс не кэшируется: вызывающий владеет им и закрывает до [delete].
 * Два открытых инстанса на один файл не поддерживаются.
 */
public class JvmPlantDatabases(
    private val directory: File,
) {
    public fun open(userId: String): GreenThumbDb {
        directory.mkdirs()
        return Room.databaseBuilder<GreenThumbDb>(
            name = absolutePath(userId),
        ).openGreenThumb()
    }

    public fun delete(userId: String) {
        plantDatabaseRelatedFileNames(userId).forEach { fileName ->
            File(directory, fileName).delete()
        }
        File(directory, plantDatabaseFileName(userId) + ".lck").delete()
    }

    public fun absolutePath(userId: String): String =
        File(directory, plantDatabaseFileName(userId)).absolutePath
}
