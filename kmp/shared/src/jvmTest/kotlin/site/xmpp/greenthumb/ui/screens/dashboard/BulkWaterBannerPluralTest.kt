package site.xmpp.greenthumb.ui.screens.dashboard

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import site.xmpp.greenthumb.core.platform.AppEnvironment
import site.xmpp.greenthumb.ui.theme.GreenThumbTheme

/**
 * Регрессия VAL-DASH-006: success- и pending-баннеры массового полива склоняются
 * по числу растений. До первого фикса success-баннер собирал строку из плоского
 * `dashboard.plantsWatered` («растений полито») — на 2 выводил
 * «2 растений полито» вместо «2 растения полито»; pending-баннер строился из
 * плоского `dashboard.wateringAllPending` («Поливаем N растений...») — на 2
 * выводил «Поливаем 2 растений...» вместо «Поливаем 2 растения...».
 *
 * Эталонные числа 1/2/5/21/22/25 (план Stage 6 п.4, как в I18nResourcesTest):
 * 1/21 → one «растение», 2/22 → few «растения», 5/25 → many «растений».
 *
 * en НЕ меняется (осознанное решение — RN-паритет): во всех количествах
 * «N plants watered» / «Watering N plants...», включая грамматически неидеальное
 * «1 plants watered», как в RN
 * `"{count} {t('dashboard.plantsWatered')}"` / `t('dashboard.wateringAllPending', {count})`.
 */
@OptIn(ExperimentalTestApi::class)
class BulkWaterBannerPluralTest {

    private var originalLocale: Locale? = null

    @AfterTest
    fun restoreLocale() {
        // jvm-actual LocalAppLocale мутирует Locale.setDefault — глобальное
        // состояние процесса; возвращаем исходное, чтобы не течь в другие тесты.
        originalLocale?.let { Locale.setDefault(it) }
        originalLocale = null
    }

    private fun rememberLocale() {
        if (originalLocale == null) originalLocale = Locale.getDefault()
    }

    @Test
    fun ru_success_banner_declines_at_reference_counts() = try {
        rememberLocale()
        runDesktopComposeUiTest {
            setContent {
                AppEnvironment(customAppLocale = "ru") {
                    GreenThumbTheme(darkTheme = false) {
                        Column {
                            listOf(1, 2, 5, 21, 22, 25).forEach { n ->
                                BulkWaterBanner(pendingCount = null, successCount = n)
                            }
                        }
                    }
                }
            }

            onNodeWithText("1 растение полито").assertExists()
            onNodeWithText("2 растения полито").assertExists()
            onNodeWithText("5 растений полито").assertExists()
            onNodeWithText("21 растение полито").assertExists()
            onNodeWithText("22 растения полито").assertExists()
            onNodeWithText("25 растений полито").assertExists()
        }
    } finally {
        restoreLocale()
    }

    @Test
    fun en_success_banner_unchanged_at_reference_counts() = try {
        rememberLocale()
        runDesktopComposeUiTest {
            setContent {
                AppEnvironment(customAppLocale = "en") {
                    GreenThumbTheme(darkTheme = false) {
                        Column {
                            listOf(1, 2, 5, 21).forEach { n ->
                                BulkWaterBanner(pendingCount = null, successCount = n)
                            }
                        }
                    }
                }
            }

            // en-текст не менялся фиксом (во всех числах «plants watered»).
            onNodeWithText("1 plants watered").assertExists()
            onNodeWithText("2 plants watered").assertExists()
            onNodeWithText("5 plants watered").assertExists()
            onNodeWithText("21 plants watered").assertExists()
        }
    } finally {
        restoreLocale()
    }

    @Test
    fun ru_pending_banner_declines_at_reference_counts() = try {
        rememberLocale()
        runDesktopComposeUiTest {
            setContent {
                AppEnvironment(customAppLocale = "ru") {
                    GreenThumbTheme(darkTheme = false) {
                        Column {
                            listOf(1, 2, 5, 21, 22, 25).forEach { n ->
                                BulkWaterBanner(pendingCount = n, successCount = null)
                            }
                        }
                    }
                }
            }

            onNodeWithText("Поливаем 1 растение...").assertExists()
            onNodeWithText("Поливаем 2 растения...").assertExists()
            onNodeWithText("Поливаем 5 растений...").assertExists()
            onNodeWithText("Поливаем 21 растение...").assertExists()
            onNodeWithText("Поливаем 22 растения...").assertExists()
            onNodeWithText("Поливаем 25 растений...").assertExists()
        }
    } finally {
        restoreLocale()
    }

    @Test
    fun en_pending_banner_unchanged_at_reference_counts() = try {
        rememberLocale()
        runDesktopComposeUiTest {
            setContent {
                AppEnvironment(customAppLocale = "en") {
                    GreenThumbTheme(darkTheme = false) {
                        Column {
                            listOf(1, 2, 5, 21).forEach { n ->
                                BulkWaterBanner(pendingCount = n, successCount = null)
                            }
                        }
                    }
                }
            }

            // en-текст не менялся фиксом (во всех числах «plants»).
            onNodeWithText("Watering 1 plants...").assertExists()
            onNodeWithText("Watering 2 plants...").assertExists()
            onNodeWithText("Watering 5 plants...").assertExists()
            onNodeWithText("Watering 21 plants...").assertExists()
        }
    } finally {
        restoreLocale()
    }
}
