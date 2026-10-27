package site.xmpp.greenthumb.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import site.xmpp.greenthumb.core.network.AccountSession
import site.xmpp.greenthumb.core.network.GreenThumbApi
import site.xmpp.greenthumb.core.storage.GreenThumbDb
import site.xmpp.greenthumb.core.storage.PlantDatabases

/**
 * Открытые per-user базы этого процесса (VAL-DATA-008/009).
 *
 * [open] регистрирует инстанс, который держит UI. Выход и смена аккаунта
 * зовут [closeAndDelete]: сначала [PlantRepository.deleteDatabaseForUser]
 * (дождаться мутации, закрыть соединение), потом стереть файл вместе с
 * `-wal` / `-shm` / `-journal`. Удалять файл при живом соединении нельзя:
 * закрытие SQLite после unlink заново создаёт базу.
 *
 * Один инстанс на userId. Повторный [open] после [PlantRepository.close]
 * открывает файл заново, а не отдаёт закрытый.
 *
 * [openIfPresent] — путь читателей без сессии (виджет): файл не создаёт и
 * не открывает базу, которую [closeAndDelete] уже отбросил, пока [open]
 * (новый вход) её не вернёт.
 */
public class AccountPlantGate(
    private val openDatabase: (String) -> GreenThumbDb,
    private val deleteDatabase: (String) -> Unit,
    private val api: GreenThumbApi,
    private val session: AccountSession,
    private val databaseExists: (String) -> Boolean = { true },
) : PlantRepositoryOpener {
    private val lock = Any()
    private val open = mutableMapOf<String, PlantRepository>()
    private val discarded = mutableSetOf<String>()
    private val generation = MutableStateFlow(0L)

    /** Grows each time a repository is opened anew or a database is discarded. */
    public val changes: StateFlow<Long> = generation.asStateFlow()

    override fun open(userId: String): PlantRepository = withAccountLock(lock) {
        discarded.remove(userId)
        openLocked(userId)
    }

    /**
     * The open repository of [userId], or a new one when its file exists and was not discarded.
     * Null otherwise; no file is created.
     */
    public fun openIfPresent(userId: String): PlantRepository? = withAccountLock(lock) {
        val existing = open[userId]
        when {
            existing != null && !existing.isClosed() -> existing
            userId in discarded || !databaseExists(userId) -> null
            else -> openLocked(userId)
        }
    }

    private fun openLocked(userId: String): PlantRepository {
        val existing = open[userId]
        if (existing != null && !existing.isClosed()) {
            return existing
        }
        val repo = PlantRepository(
            userId = userId,
            db = openDatabase(userId),
            api = api,
            deleteFiles = deleteDatabase,
            session = session,
        )
        open[userId] = repo
        generation.update { it + 1 }
        return repo
    }

    /**
     * Закрыть соединение этого пользователя, если оно ещё открыто, и
     * стереть `plants_${userId}.db` вместе с журналами. Чужие файлы не
     * трогает. Повторный вызов — пустое удаление, без ошибки.
     */
    public suspend fun closeAndDelete(userId: String) {
        val repo = withAccountLock(lock) {
            discarded.add(userId)
            open.remove(userId)
        }
        try {
            if (repo != null) {
                repo.deleteDatabaseForUser(userId)
            } else {
                deleteDatabase(userId)
            }
        } finally {
            generation.update { it + 1 }
        }
    }
}

/** Прод-обвязка: expect-класс [PlantDatabases] не наследуется в common. */
public fun PlantDatabases.accountGate(
    api: GreenThumbApi,
    session: AccountSession,
): AccountPlantGate = AccountPlantGate(
    openDatabase = { userId -> open(userId) },
    deleteDatabase = { userId -> delete(userId) },
    api = api,
    session = session,
    databaseExists = { userId -> exists(userId) },
)
