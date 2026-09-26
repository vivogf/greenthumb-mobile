package site.xmpp.greenthumb

import io.ktor.client.engine.HttpClientEngineFactory
import site.xmpp.greenthumb.core.network.AccountSession
import site.xmpp.greenthumb.core.network.ApiClient
import site.xmpp.greenthumb.core.network.ApiError
import site.xmpp.greenthumb.core.network.GreenThumbApi
import site.xmpp.greenthumb.core.network.PlatformEngine
import site.xmpp.greenthumb.core.network.SessionRecoveryProvider
import site.xmpp.greenthumb.core.platform.PushTokens
import site.xmpp.greenthumb.core.storage.AppSettings
import site.xmpp.greenthumb.core.storage.LegacyHandoff
import site.xmpp.greenthumb.core.storage.SecureStore
import site.xmpp.greenthumb.core.storage.SecureStoreKeys
import site.xmpp.greenthumb.core.storage.SessionManager
import site.xmpp.greenthumb.core.storage.isUpdateInstall

/**
 * Точка сборки сессии (Stage 3 п.4): по одному инстансу каждого хранилища
 * (DataStore-файлы уникальны на процесс — kmp-toolchain.md, дополнение m3)
 * + клиент с M3-провайдером. UI (Stage 6) берёт настройки из этого же графа
 * (один DataStore на файл).
 *
 * Платформенные actual'ы ([SecureStore]/[AppSettings]/[LegacyHandoff])
 * конструирует ТОЧКА ВХОДА (androidMain: applicationContext из MainActivity;
 * jvmMain: каталог харнесса) и передаёт в [create]; expect-классы не имеют
 * default-конструктора в commonMain.
 *
 * Шов Stage 2 п.7 ([SessionRecoveryProvider]): провайдер читает ключ прямо
 * из SecureStore ([SecureStoreKeys.RECOVERY_KEY] — SessionManager хранит его
 * там же) и на второй 401 решает через [SessionManager.onAuthError] (явный
 * 401 → ключ + cached_user чистятся; initSession решает экран входа).
 */
public class SessionGraph private constructor(
    public val secure: SecureStore,
    public val settings: AppSettings,
    public val handoff: LegacyHandoff,
    engine: HttpClientEngineFactory<*>,
) {
    public val accountSession: AccountSession = AccountSession()

    public val client: ApiClient = ApiClient(engine, Recovery(), accountSession)

    public val api: GreenThumbApi = GreenThumbApi(client)

    /** Создан в [create] до первого запроса (сессия не живёт до startup). */
    public lateinit var manager: SessionManager
        private set

    /**
     * Push-подсистема (M7 screen-enable-notifications — первое объявление
     * expect; активная реализация M9). Один инстанс на процесс — та же
     * дисциплина, что у [api]/[manager]: экраны берут её отсюда, тесты
     * подменяют весь объект ([PushTokens] открыт).
     */
    public val push: PushTokens = PushTokens()

    public companion object {
        /**
         * Сборка графа; manager последним (ссылка Recovery на manager уже
         * замкнута лениво). Факт «установка — обновление» ([isUpdateInstall])
         * читается здесь, ОДИН раз на создание графа, до крутящегося
         * [SessionManager.startup] (Stage 3 п.6): конструкторный факт, не
         * реактивное состояние.
         */
        public fun create(
            secure: SecureStore,
            settings: AppSettings,
            handoff: LegacyHandoff,
            /**
             * Стирает базу ушедшего пользователя (VAL-DATA-008). Точка входа
             * подставляет [site.xmpp.greenthumb.data.AccountPlantGate.closeAndDelete]
             * после сборки графа: гейт сам зависит от [api] и [accountSession].
             * Лямбда не зовётся из [create].
             */
            deleteUserDatabase: suspend (String) -> Unit = {},
        ): SessionGraph {
            val graph = SessionGraph(secure, settings, handoff, PlatformEngine.default())
            graph.manager = SessionManager(
                graph.secure,
                graph.settings,
                graph.handoff,
                graph.api,
                isUpdateInstall(),
                onSessionEnded = { graph.client.endSession() },
                deleteUserDatabase = deleteUserDatabase,
            )
            return graph
        }
    }

    /** Шов M2→M3 (Stage 2 п.7): ключ из SecureStore; второй 401 → onAuthError. */
    private inner class Recovery : SessionRecoveryProvider {
        override suspend fun getRecoveryKey(): String? =
            secure.get(SecureStoreKeys.RECOVERY_KEY)

        override suspend fun onSessionReset() {
            manager.onAuthError(ApiError.Unauthorized)
        }
    }
}
