package site.xmpp.greenthumb.core.platform

import kotlinx.coroutines.flow.first
import site.xmpp.greenthumb.core.storage.AppPreferencesStore
import site.xmpp.greenthumb.core.storage.SessionManager
import site.xmpp.greenthumb.core.storage.SessionState

/**
 * Обработка ротации FCM-токена с учётом холодного процесса (фикс scrutiny
 * m9-push): FCM может поднять процесс только ради нового токена —
 * MainActivity/App() не запускались, стартовая последовательность сессии
 * ещё не крутилась и [SessionManager.state] равен null.
 *
 * Поэтому СНАЧАЛА [SessionManager.startupIfNeeded] — он идемпотентен на
 * процесс (SessionManagerStartupOnceTest): уже идущий старт дожидается,
 * завершённый — не повторяется (повторный onNewToken после старта
 * приложения не крутит me-запрос заново). Сам startup вправе один раз
 * восстановить сессию сохранённым ключом (me 401 → login-recovery) — это
 * и есть восстановление из фона; СВЕРХ него фон сессию не трогает.
 *
 * По результату старта:
 * - SignedIn → отправить новый токен ([FcmTokenRotation.rotate] — тот же
 *   провод, что у включения тумблера);
 * - иначе (SignedOut/Offline/ошибка handoff) → null: шов подписки НЕ
 *   трогается — «подписка была включена для текущего аккаунта» без аккаунта
 *   не существует, а 401 от шва не должен запускать восстановление сессии
 *   из фонового колбэка.
 *
 * Язык подписки — текущий язык UI (тот же контракт, что у тумблера,
 * VAL-PUSH-007; нет значения — RN-фолбэк 'ru').
 *
 * Возвращённый исход логирует сервис; на UI не выходит (фоновый путь).
 *
 * Тестируется через локальные двойники (MockEngine + двойник шва) —
 * без live-запросов (стоп M9 на live-аккаунты).
 */
public suspend fun rotateFcmTokenAfterColdStart(
    manager: SessionManager,
    settings: AppPreferencesStore,
    rotation: FcmTokenRotation,
    token: String,
    platform: String,
): FcmTokenRotation.Outcome? {
    val state = manager.startupIfNeeded()
    if (state !is SessionState.SignedIn) return null
    val language = settings.language.first()?.wire ?: "ru"
    return rotation.rotate(token, platform, language)
}
