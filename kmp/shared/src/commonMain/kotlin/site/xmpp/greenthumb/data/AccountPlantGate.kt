package site.xmpp.greenthumb.data

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
 */
public class AccountPlantGate(
    private val openDatabase: (String) -> GreenThumbDb,
    private val deleteDatabase: (String) -> Unit,
    private val api: GreenThumbApi,
    private val session: AccountSession,
) : PlantRepositoryOpener {
    private val lock = Any()
    private val open = mutableMapOf<String, PlantRepository>()

    override fun open(userId: String): PlantRepository = withAccountLock(lock) {
        val existing = open[userId]
        if (existing != null && !existing.isClosed()) {
            return@withAccountLock existing
        }
        val repo = PlantRepository(
            userId = userId,
            db = openDatabase(userId),
            api = api,
            deleteFiles = deleteDatabase,
            session = session,
        )
        open[userId] = repo
        repo
    }

    /**
     * Закрыть соединение этого пользователя, если оно ещё открыто, и
     * стереть `plants_${userId}.db` вместе с журналами. Чужие файлы не
     * трогает. Повторный вызов — пустое удаление, без ошибки.
     */
    public suspend fun closeAndDelete(userId: String) {
        val repo = withAccountLock(lock) { open.remove(userId) }
        if (repo != null) {
            repo.deleteDatabaseForUser(userId)
        } else {
            deleteDatabase(userId)
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
)
