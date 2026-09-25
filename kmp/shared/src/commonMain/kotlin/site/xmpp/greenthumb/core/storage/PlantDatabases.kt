package site.xmpp.greenthumb.core.storage

/**
 * Открытие и удаление per-user базы `plants_${userId}.db`.
 *
 * androidMain: `databases/` приложения (`Context.getDatabasePath`).
 * jvmMain: каталог харнесса `~/.greenthumb` (тесты — свой каталог через
 * `JvmPlantDatabases`).
 *
 * [delete] стирает файл вместе с `-wal` / `-shm` / `-journal`. Чужие
 * `plants_*.db` не трогает. Вызывающий закрывает инстанс сам, до [delete]:
 * opener инстанс не держит (`isOpen` нет в общем Room API).
 */
public expect class PlantDatabases(appContext: Any) {
    public fun open(userId: String): GreenThumbDb

    public fun delete(userId: String)

    /** Абсолютный путь файла `plants_${userId}.db` (без создания). */
    public fun absolutePath(userId: String): String
}
