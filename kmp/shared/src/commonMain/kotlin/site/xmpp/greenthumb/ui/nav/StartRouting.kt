package site.xmpp.greenthumb.ui.nav

import site.xmpp.greenthumb.core.storage.SessionState

/**
 * Куда ведёт стартовая маршрутизация (Stage 6 п.2). Решение — чистая функция
 * [resolveStartRoute] (порт RN `app/index.tsx`), поверхности — в [App].
 */
public sealed class StartRoute {
    /**
     * Сессия ещё крутится или флаг интро ещё не прочитан: индикатор на фоне
     * сплеша, БЕЗ редиректа (RN `ready = !loading && introSeen !== null`).
     */
    public data object Loading : StartRoute()

    /** Есть пользователь (вход или офлайн-кэш) → вкладка «Растения». */
    public data object Dashboard : StartRoute()

    /** Нет пользователя, интро не видели → карусель welcome. */
    public data object Welcome : StartRoute()

    /** Нет пользователя, интро видели → экран входа. */
    public data object Login : StartRoute()

    /**
     * Экран «ключ не найден» (Stage 3 п.6, VAL-HANDOFF-IMP-002) — KMP-состояние
     * вне RN-матрицы; рисуется вне графа, кнопки ввода ключа/создания аккаунта —
     * Stage 7 (screen-login).
     */
    public data object KeyNotFound : StartRoute()

    /**
     * Провал импорта handoff (VAL-HANDOFF-IMP-005) — KMP-состояние вне
     * RN-матрицы; экран ошибки с ключом для копирования, вне графа.
     */
    public data class HandoffImportFailed(public val recoveryKey: String) : StartRoute()
}

/**
 * Стартовая маршрутизация В ТОЧНОСТИ как RN `app/index.tsx:43-51` — порядок
 * проверок важен:
 *
 * 1. Гейт готовности: сессия не решена ([SessionState] == null) или флаг
 *    интро ещё не прочитан ([introSeen] == null) → [StartRoute.Loading] —
 *    индикатор, без редиректа (как RN `ready`).
 * 2. Пользователь → [StartRoute.Dashboard] — ПЕРЕД проверкой интро: если
 *    проверять интро первым, мигрировавший пользователь (user есть, интро
 *    когда-то видено через handoff) попадает в карусель вместо своих растений
 *    (пин из плана Stage 6 п.2). Офлайн-сессия — тоже пользователь
 *    ([SessionState.Offline] несёт cached_user).
 * 3. Нет пользователя: интро не видели → [StartRoute.Welcome]; видели →
 *    [StartRoute.Login].
 *
 * [SessionState.KeyNotFound]/[SessionState.HandoffImportFailed] — KMP-состояния
 * вне RN-матрицы (Stage 3): свои поверхности вне графа, переходов сессии не
 * добавляют.
 */
public fun resolveStartRoute(state: SessionState?, introSeen: Boolean?): StartRoute {
    // 1. Гейт готовности (RN ready): без редиректа, индикатор решает вызывающий.
    if (state == null || introSeen == null) return StartRoute.Loading
    return when (state) {
        // 2. Пользователь — ПЕРВЫМ (мигрировавший не попадает в интро).
        is SessionState.SignedIn, is SessionState.Offline -> StartRoute.Dashboard
        // KMP-состояния Stage 3 — свои поверхности вне RN-матрицы.
        SessionState.KeyNotFound -> StartRoute.KeyNotFound
        is SessionState.HandoffImportFailed -> StartRoute.HandoffImportFailed(state.recoveryKey)
        // 3. Нет пользователя: интро решает между welcome и login.
        SessionState.SignedOut -> if (introSeen) StartRoute.Login else StartRoute.Welcome
    }
}

/** id пользователя для поверхностей с данными; null — пользователя нет. */
public fun SessionState?.sessionUserIdOrNull(): String? = when (this) {
    is SessionState.SignedIn -> user.id
    is SessionState.Offline -> user.id
    else -> null
}
