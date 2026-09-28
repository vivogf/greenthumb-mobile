package site.xmpp.greenthumb.core.platform

import kotlinx.coroutines.CancellationException

/**
 * Ротация FCM-токена (Stage 9 п.4, фича kmp-push-offline-routing): сервер
 * держит токен, выданный в момент подписки, и после ротации токена (обновление
 * приложения, восстановление, смена данных устройства) доставка на старый
 * токен молча ломается — отказ доставки, выявленный в M9.
 *
 * Правило обновления (утверждено описанием фичи):
 * - подписка была включена для текущего аккаунта → отправить НОВЫЙ токен
 *   ([PushSubscriptions.subscribe] — тот же провод, что у включения тумблера);
 * - подписка НЕ была включена → НИЧЕГО не делать: фоновая ротация не включает
 *   push самовольно (пользовательское решение, не событие токена);
 * - ошибка статуса или отправки → push НЕ включается «незаметно»: исход
 *   [Outcome.Failed] наверх (сервис пишет в лог); следующее включение
 *   тумблера подпишет свежий токен ([PushTokens.requestSubscribe] берёт
 *   текущий токен заново).
 *
 * Авторитетный источник «включена ли подписка» — сервер ([PushSubscriptions.status]
 * по cookie-сессии текущего аккаунта). Вызывающий (GtFirebaseMessagingService)
 * проверяет живую сессию ДО вызова: без сессии статус дал бы 401 и шов
 * восстановления сессии из фонового колбэка не запускается.
 *
 * Тестируется через локальный сетевой шов (двойник [PushSubscriptions]) —
 * без live-запросов (стоп M9 на live-аккаунты).
 */
public class FcmTokenRotation(private val subscriptions: PushSubscriptions?) {

    /** Исход ротации (сервис пишет в лог; на UI не выходит — фоновый путь). */
    public sealed class Outcome {
        /** Подписка была включена — серверу отправлен новый токен. */
        public data object Updated : Outcome()

        /**
         * Подписка не была включена (или шва сети нет — desktop-харнесс):
         * push самовольно не включается, ничего не отправлено.
         */
        public data object NotSubscribed : Outcome()

        /**
         * Ошибка статуса/отправки: push НЕ включён и НЕ обновлён (не «незаметно
         * включаем»); [message] — для лога сервиса.
         */
        public data class Failed(public val message: String) : Outcome()
    }

    /**
     * Обработать ротацию: [token] — новый токен FCM, [platform] — значение
     * провода (`"android"`/`"ios"`), [language] — текущий язык UI (тот же
     * контракт, что у включения тумблера, VAL-PUSH-007).
     */
    public suspend fun rotate(token: String, platform: String, language: String): Outcome {
        val subs = subscriptions ?: return Outcome.NotSubscribed
        // «Включена ли подписка для текущего аккаунта» решает сервер. Ошибка
        // статуса — НЕ «нет»: неопределённость не превращается во включение.
        val enabled = try {
            subs.status()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            return Outcome.Failed(error.message ?: error.toString())
        }
        if (!enabled) return Outcome.NotSubscribed
        return try {
            subs.subscribe(token, platform, language)
            Outcome.Updated
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            Outcome.Failed(error.message ?: error.toString())
        }
    }
}
