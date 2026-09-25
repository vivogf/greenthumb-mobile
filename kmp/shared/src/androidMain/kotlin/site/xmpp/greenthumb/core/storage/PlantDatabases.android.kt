package site.xmpp.greenthumb.core.storage

import android.content.Context
import androidx.room.Room
import java.io.File

/**
 * Android-actual: файл в `databases/` пакета (`getDatabasePath`), имя
 * `plants_${userId}.db`. sqlite-bundled пишет `-wal`/`-shm` рядом.
 */
public actual class PlantDatabases actual constructor(appContext: Any) {
    private val delegate = AndroidPlantDatabases((appContext as Context).applicationContext)

    actual fun open(userId: String): GreenThumbDb = delegate.open(userId)

    actual fun delete(userId: String) = delegate.delete(userId)

    actual fun absolutePath(userId: String): String = delegate.absolutePath(userId)
}

/**
 * Инжектируемый android-opener (applicationContext).
 * Инстанс не кэшируется: вызывающий владеет им и закрывает до [delete].
 * `RoomDatabase.isOpen` есть только в android-варианте Room — не используем.
 */
public class AndroidPlantDatabases(
    private val appContext: Context,
) {
    public fun open(userId: String): GreenThumbDb {
        val dbFile = databaseFile(userId)
        dbFile.parentFile?.mkdirs()
        return Room.databaseBuilder<GreenThumbDb>(
            context = appContext,
            name = dbFile.absolutePath,
        ).openGreenThumb()
    }

    public fun delete(userId: String) {
        val dir = databaseFile(userId).parentFile
        if (dir != null) {
            plantDatabaseRelatedFileNames(userId).forEach { fileName ->
                File(dir, fileName).delete()
            }
            // Room/SQLite lock. deleteDatabase его не трогает, а имя
            // содержит userId — после выхода его не должно быть в ls.
            File(dir, plantDatabaseFileName(userId) + ".lck").delete()
        }
        appContext.deleteDatabase(plantDatabaseFileName(userId))
    }

    public fun absolutePath(userId: String): String = databaseFile(userId).absolutePath

    private fun databaseFile(userId: String): File =
        appContext.getDatabasePath(plantDatabaseFileName(userId))
}
