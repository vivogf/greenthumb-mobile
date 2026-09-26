package site.xmpp.greenthumb.ui.screens.login

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.stringResource
import site.xmpp.greenthumb.ui.res.Res
import site.xmpp.greenthumb.ui.res.login_importantNoRestore
import site.xmpp.greenthumb.ui.res.login_importantSaveIt
import site.xmpp.greenthumb.ui.res.login_importantText
import site.xmpp.greenthumb.ui.res.login_saveKeyWarningAccess
import site.xmpp.greenthumb.ui.res.login_saveKeyWarningLost
import site.xmpp.greenthumb.ui.res.login_saveKeyWarningOnly
import site.xmpp.greenthumb.ui.res.login_saveKeyWarningText

/**
 * Склейка предупреждающих сообщений login — пин оркестратора screen-login:
 *
 * RN-экран (`login.tsx:287-292`, `:322-330`) рендерит ФРАГМЕНТЫ внутри одного
 * Text: `importantText + ' ' + importantSaveIt(bold) + importantNoRestore` и
 * `saveKeyWarningText + saveKeyWarningOnly(bold) + ' ' + saveKeyWarningAccess
 * + ' ' + saveKeyWarningLost(bold) + '.'`.
 *
 * RN `login.importantNoRestore` в ru НАЧИНАЕТСЯ С ПРОБЕЛА (" — это…"), en —
 * без пробела ("— it's…"). CMP-ресурсы сохраняют значения 1:1 (i18n_check.py
 * сверяет тексты), но полагаться на сохранение начального пробела
 * рантайм-ридером НЕЛЬЗЯ — пробел/отступ на стыке фрагментов задаётся на
 * Kotlin-стороне ([ImportantMessageTest] фиксирует оба поведения).
 */

/**
 * Important-сообщение create-режима: [importantText] + пробел + [saveIt] +
 * [noRestore]. Начальный пробел [noRestore] (ru-ресурс) отрезается: склейка
 * ставит ровно один пробел, если фрагмент не начинается с тире-разделителя.
 */
internal fun buildImportantMessage(
    importantText: String,
    saveIt: String,
    noRestore: String,
): String = "$importantText ${saveIt.trimStart()}${fragmentLead(noRestore)}"

/**
 * Сообщение предупреждения show-key: [warningText] + [onlyWay] + [access] +
 * [lost] + точка (RN login.tsx:322-330 — точка после bold-фрагмента).
 */
internal fun buildSaveKeyWarningMessage(
    warningText: String,
    onlyWay: String,
    access: String,
    lost: String,
): String = "$warningText ${onlyWay.trimStart()} ${access.trimStart()} ${lost.trimStart()}."

/** Пробел перед фрагментом, если он не начинается с тире-разделителя. */
private fun fragmentLead(fragment: String): String {
    val trimmed = fragment.trimStart()
    return if (trimmed.startsWith("—")) " $trimmed" else trimmed
}

// ---------------------------------------------------------------------------
// Composable-обёртки: ресурсы текущей локали → чистая склейка
// ---------------------------------------------------------------------------

/** Important-сообщение из ресурсов (create-режим, RN login.tsx:287-292). */
@Composable
internal fun buildImportantMessage(): String = buildImportantMessage(
    importantText = stringResource(Res.string.login_importantText),
    saveIt = stringResource(Res.string.login_importantSaveIt),
    noRestore = stringResource(Res.string.login_importantNoRestore),
)

/** Предупреждение show-key из ресурсов (RN login.tsx:322-330). */
@Composable
internal fun buildSaveKeyWarningMessage(): String = buildSaveKeyWarningMessage(
    warningText = stringResource(Res.string.login_saveKeyWarningText),
    onlyWay = stringResource(Res.string.login_saveKeyWarningOnly),
    access = stringResource(Res.string.login_saveKeyWarningAccess),
    lost = stringResource(Res.string.login_saveKeyWarningLost),
)
