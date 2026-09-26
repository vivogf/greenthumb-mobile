package site.xmpp.greenthumb.ui.login

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import site.xmpp.greenthumb.core.platform.AppEnvironment
import site.xmpp.greenthumb.ui.screens.login.buildImportantMessage
import site.xmpp.greenthumb.ui.screens.login.buildSaveKeyWarningMessage
import site.xmpp.greenthumb.ui.theme.GreenThumbTheme

/**
 * Склейка предупреждающих сообщений login (пин оркестратора screen-login):
 *
 * RN-экран (`login.tsx:287-292`, `:322-330`) рендерит ФРАГМЕНТЫ в один Text:
 * `importantText` + ` ' ' + importantSaveIt (bold) + importantNoRestore` и
 * `saveKeyWarningText` + `saveKeyWarningOnly (bold) + saveKeyWarningAccess +
 * saveKeyWarningOnly-подобный lost (bold) + '.'`.
 *
 * RN `login.importantNoRestore` в ru НАЧИНАЕТСЯ С ПРОБЕЛА (" — это…"), en —
 * без пробела ("— it's…"). CMP-ресурсы сохраняют значения 1:1 (i18n_check.py
 * сверяет тексты с RN-локалью), но НИЧЕГО не гарантирует рантайм-ридеру:
 * нельзя полагаться на сохранение начального пробела строкового ресурса —
 * пробел/отступ между фрагментами задаётся на Kotlin-стороне. Ресурс
 * `login_importantNoRestore` в ru читается с trim'ом (Compose Resources не
 * гарантируют ведущий пробел) — поэтому Kotlin-склейка ставит ОДИН пробел
 * перед фрагментом, когда фрагмент не начинается с пунктуационного разделителя.
 */
@OptIn(ExperimentalTestApi::class)
class ImportantMessageTest {

    private var originalLocale: Locale? = null

    @AfterTest
    fun restoreLocale() {
        originalLocale?.let { Locale.setDefault(it) }
        originalLocale = null
    }

    private fun rememberLocale() {
        if (originalLocale == null) originalLocale = Locale.getDefault()
    }

    // ------------------------------------------------------------------
    // Чистая склейка: пробел после текста и между save-it / no-restore
    // ------------------------------------------------------------------

    @Test
    fun buildImportant_insertsSingleSpaceBetweenFragments() {
        val m = buildImportantMessage(
            importantText = "TEXT.",
            saveIt = "SAVE IT",
            noRestore = "— NO RESTORE.",
        )
        assertEquals(
            "TEXT. SAVE IT — NO RESTORE.",
            m,
            "один пробел между фрагментами: no-restore с тире не добавляет лишнего",
        )
    }

    @Test
    fun buildImportant_ruNoRestore_leadingSpaceFromResourceIsTrimmedNotDoubled() {
        // RN-значение ru начинается с пробела: склейка НЕ должна дать двойного
        // (один ставим на Kotlin-стороне, начальный пробел фрагмента отрезаем).
        val m = buildImportantMessage(
            importantText = "Т.",
            saveIt = "СОХРАНИТЕ",
            noRestore = " — единственный способ.",
        )
        assertEquals("Т. СОХРАНИТЕ — единственный способ.", m, "ведущий пробел фрагмента не удваивается")
    }

    @Test
    fun buildSaveKeyWarning_fragmentsJoinedWithSpacesAndFinalDot() {
        val m = buildSaveKeyWarningMessage(
            warningText = "This is your",
            onlyWay = "only way",
            access = "to access your account. If you lose it, your plants data will be",
            lost = "lost forever",
        )
        assertEquals(
            "This is your only way to access your account. If you lose it, your plants data will be lost forever.",
            m,
            "три фрагмента через пробел + точка в конце (RN login.tsx:330)",
        )
    }

    // ------------------------------------------------------------------
    // Рантайм-значения ресурсов: локализованная склейка в композиции
    // ------------------------------------------------------------------

    @Test
    fun ru_importantMessage_fromRuntimeResources_hasSingleSpaces() = try {
        rememberLocale()
        runDesktopComposeUiTest {
            setContent {
                AppEnvironment(customAppLocale = "ru") {
                    GreenThumbTheme(darkTheme = false) {
                        Column {
                            // Склейка на Kotlin-стороне решает пробелы явно.
                            Text(buildImportantMessage())
                            Text(buildSaveKeyWarningMessage())
                        }
                    }
                }
            }

            // Ресурсы — ru; ведущий пробел ru-фрагмента не удваивается и не
            // теряется (ровно один пробел перед «— это единственный способ»).
            val important =
                """После создания аккаунта вы получите уникальный ключ восстановления. """ +
                    """Сохраните его в менеджере паролей или в надёжном месте — это единственный способ """ +
                    """восстановить аккаунт. Мы не сможем восстановить доступ без него."""
            onNodeWithText(important).assertExists()
            val warning =
                """Это единственный способ получить доступ к аккаунту. Если вы его потеряете, """ +
                    """данные о растениях будут утеряны навсегда."""
            onNodeWithText(warning).assertExists()
        }
    } finally {
        restoreLocale()
    }

    @Test
    fun en_importantMessage_fromRuntimeResources_hasSingleSpaces() = try {
        rememberLocale()
        runDesktopComposeUiTest {
            setContent {
                AppEnvironment(customAppLocale = "en") {
                    GreenThumbTheme(darkTheme = false) {
                        Column {
                            Text(buildImportantMessage())
                            Text(buildSaveKeyWarningMessage())
                        }
                    }
                }
            }
            val important =
                """After creating your account, you'll receive a unique recovery key. """ +
                    """Save it in a password manager or a safe place — it's the only way to recover """ +
                    """your account. We cannot restore access without it."""
            onNodeWithText(important).assertExists()
            val warning =
                """This is your only way to access your account. If you lose it, your plants """ +
                    """data will be lost forever."""
            onNodeWithText(warning).assertExists()
        }
    } finally {
        restoreLocale()
    }
}
