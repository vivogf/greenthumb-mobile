package site.xmpp.greenthumb.core.platform

import androidx.compose.runtime.Composable

/**
 * Платформенный хук синхронизации локали в корне композиции (M6b,
 * VAL-I18N-007). Вызывается в теле корневого composable ([App.kt]) и в теле
 * [AppLocalizedContent] (перед key(locale)) — оба пути выполняются ДО
 * композиции локализованных детей.
 *
 * Почему не хватает activity-хуков (MainActivity.onCreate/
 * onConfigurationChanged): финальная ступень последовательности uiMode-флипов
 * (restore-доставка) мутирует локали процесса ПОСЛЕ всех колбэков и не
 * порождает рекомпозицию (Compose видит diff=0) — в этом окне свежая
 * композиция локализованного поддерева (remount вкладки) собирала
 * remembered-окружение строк от сброшенного системой LocaleList — подписи EN
 * при сохранённом ru (интермиттент; evidence r2-anomaly + красные пробы
 * M6b). Хук в теле composable выполняется на каждом таком проходе ПОСЛЕ
 * системской мутации ресурсов (композиция всегда позже) и ДО композиции
 * подписей (тело родителя раньше детей) — свежие и remount-композиции всегда
 * читают исправленное состояние.
 */
@Composable
public expect fun AppLocaleSyncRoot()
