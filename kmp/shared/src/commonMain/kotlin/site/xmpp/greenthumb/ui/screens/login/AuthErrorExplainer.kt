package site.xmpp.greenthumb.ui.screens.login

import site.xmpp.greenthumb.core.network.ApiError

/**
 * Маппинг ошибки auth в пользовательский текст — порт RN `explainAuthError`
 * из staged P2/P3 (`app/(auth)/login.tsx:61-76`).
 *
 * История (CLAUDE.md 2026-06-14 + 2026-04-30): любой не-200 от
 * login-recovery показывался как «Invalid recovery key» — квота Neon
 * отвечает 400, и пользователи считали свой ключ невалидным, будучи
 * залоченными из живого аккаунта. Правило:
 *
 * - явный 401 на ВХОДЕ (`isLogin = true`) — единственный случай «неверный
 *   ключ» (ключ ввёл пользователь, сервер его отверг);
 * - 401 на СОЗДАНИИ (`isLogin = false`) — «сервис недоступен»: ключ
 *   пользователь не вводил, сбои создания — сервисные;
 * - Network/Timeout — «ошибка сети» (обе ветки);
 * - прочие 4xx (400-квота, 429) и 5xx — «сервис недоступен», НИКОГДА
 *   «неверный ключ».
 *
 * Функция чистая: локализованные тексты передаёт вызов (`stringResource`
 * решает композиция), тест фиксирует сам маппинг ([AuthErrorMappingTest]).
 * Фолбэк для не-[ApiError] исключений — на вызывающем (RN `error?.message ||
 * t('common.error')`).
 */
internal fun explainAuthError(
    error: ApiError,
    isLogin: Boolean,
    invalidKeyText: String,
    networkErrorText: String,
    serviceUnavailableText: String,
): String = when {
    error is ApiError.Unauthorized && isLogin -> invalidKeyText
    error is ApiError.Network || error is ApiError.Timeout -> networkErrorText
    else -> serviceUnavailableText
}

/**
 * Адрес поддержки — из RN `lib/constants.ts:24` (SUPPORT_EMAIL): приватная
 * заметка choose-режима (`privacy.loginHint` + mailto-ссылка, RN
 * login.tsx:382-406). Литерал константы, не секрет (публичен в RN-репо).
 */
internal const val SUPPORT_EMAIL: String = "greenthumb.taunt861@passmail.net"
