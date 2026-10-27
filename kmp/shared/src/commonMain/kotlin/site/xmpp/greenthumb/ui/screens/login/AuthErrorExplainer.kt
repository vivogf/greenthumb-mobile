package site.xmpp.greenthumb.ui.screens.login

import site.xmpp.greenthumb.core.network.ApiError

/**
 * Маппинг ошибки auth в пользовательский текст — порт RN `explainAuthError`
 * из staged P2/P3 (`app/(auth)/login.tsx:61-76`).
 *
 * Ответы login-recovery: 401 — ключ в формате UUID, но аккаунта с ним нет;
 * 400 — строка не UUID (или пустое тело). Сбои базы (self-hosted Postgres)
 * приходят как 5xx, поэтому 400 на входе — это ошибка в ключе, а не в сервисе.
 * Правило:
 *
 * - 401 или 400 на ВХОДЕ (`isLogin = true`) — «неверный ключ» (ключ ввёл
 *   пользователь, сервер его отверг); стереть сохранённый ключ может только
 *   401 (SessionManager), 400 его не трогает;
 * - 401 и 400 на СОЗДАНИИ (`isLogin = false`) — «сервис недоступен»: ключ
 *   пользователь не вводил;
 * - Network/Timeout — «ошибка сети» (обе ветки);
 * - 429, прочие 4xx и 5xx — «сервис недоступен».
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
    error is ApiError.Client && error.status == 400 && isLogin -> invalidKeyText
    error is ApiError.Network || error is ApiError.Timeout -> networkErrorText
    else -> serviceUnavailableText
}

/**
 * Адрес поддержки — из RN `lib/constants.ts:24` (SUPPORT_EMAIL): приватная
 * заметка choose-режима (`privacy.loginHint` + mailto-ссылка, RN
 * login.tsx:382-406). Литерал константы, не секрет (публичен в RN-репо).
 */
internal const val SUPPORT_EMAIL: String = "greenthumb.taunt861@passmail.net"
