package site.xmpp.greenthumb.core.storage

/**
 * Имя файла Room per-user (architecture.md §7, Stage 4 п.2):
 * `plants_${user.id}.db`.
 *
 * `user.id` на проводе — строка (число сериализуется как `"24"`).
 * Имя — один сегмент пути: пустой id и разделители (`/`, `\`, `..`)
 * отвергаются, чтобы файл не вышел из каталога баз.
 *
 * Рядом SQLite создаёт `-wal` / `-shm` (и иногда `-journal`). Удаление
 * аккаунта обязано стереть все четыре имени (VAL-DATA-008/009 — фича
 * жизненного цикла; здесь только контракт имён).
 */
public fun plantDatabaseFileName(userId: String): String {
    require(isSafeDatabaseUserId(userId)) { "userId is not a safe database file name" }
    return "plants_$userId.db"
}

/** Имена файла базы и журналов SQLite в одном каталоге. */
public fun plantDatabaseRelatedFileNames(userId: String): List<String> {
    val name = plantDatabaseFileName(userId)
    return listOf(name, "$name-wal", "$name-shm", "$name-journal")
}

internal fun isSafeDatabaseUserId(userId: String): Boolean {
    if (userId.isBlank()) return false
    if (userId == "." || userId == "..") return false
    if (userId.contains("..")) return false
    return userId.none { ch ->
        ch == '/' || ch == '\\' || ch == '\u0000' || ch.isWhitespace()
    }
}
