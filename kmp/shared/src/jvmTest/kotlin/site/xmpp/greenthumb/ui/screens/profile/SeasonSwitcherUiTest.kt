package site.xmpp.greenthumb.ui.screens.profile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import site.xmpp.greenthumb.core.platform.AppEnvironment
import site.xmpp.greenthumb.core.storage.SessionState
import site.xmpp.greenthumb.season.SeasonHarness
import site.xmpp.greenthumb.season.TZ_LATITUDES_RESOURCE
import site.xmpp.greenthumb.season.TzLatitudes
import site.xmpp.greenthumb.season.decodeTzLatitudes
import site.xmpp.greenthumb.ui.res.Res
import site.xmpp.greenthumb.ui.theme.GreenThumbTheme
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * VAL-SEASON-019/020/021 (Compose half): four localized options with the current mode selected and
 * labelled for accessibility, the auto explanation for fixed device zones in ru and en, and the
 * rollback message after a failed save.
 */
@OptIn(ExperimentalTestApi::class)
class SeasonSwitcherUiTest {

    private val originalLocale: Locale = Locale.getDefault()

    @AfterTest
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    private val latitudes: TzLatitudes by lazy {
        runBlocking { decodeTzLatitudes(Res.readBytes(TZ_LATITUDES_RESOURCE).decodeToString()) }
    }

    private val tags = listOf("season_auto", "season_north", "season_south", "season_off")

    private fun checkOptions(locale: String, labels: List<String>, tropical: String) {
        SeasonHarness().use { harness ->
            runBlocking { harness.signedInWithKey() }
            runDesktopComposeUiTest {
                setContent {
                    AppEnvironment(customAppLocale = locale) {
                        GreenThumbTheme(darkTheme = false) {
                            SeasonModeSection(session = harness.session, zoneId = "Asia/Singapore", latitudes = latitudes)
                        }
                    }
                }
                waitUntilAtLeastOneExists(hasTestTag("season_explanation"))
                labels.forEach { onNodeWithText(it).assertExists() }
                onNodeWithTag("season_auto").assertIsSelected()
                tags.drop(1).forEach { onNodeWithTag(it).assertIsNotSelected() }
                tags.forEach { tag ->
                    val description = onNodeWithTag(tag).fetchSemanticsNode().config
                        .getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
                    assertTrue(!description.isNullOrBlank(), "$tag has an accessibility label")
                }
                onNodeWithText(tropical).assertExists()
            }
        }
    }

    @Test
    fun ruShowsFourOptionsWithAutoSelected() = checkOptions(
        "ru",
        listOf("Авто", "Север", "Юг", "Выкл"),
        "Авто: тропики — без сезонной поправки",
    )

    @Test
    fun enShowsFourOptionsWithAutoSelected() = checkOptions(
        "en",
        listOf("Auto", "North", "South", "Off"),
        "Auto: tropics — no seasonal adjustment",
    )

    private fun checkExplanations(locale: String, expected: Map<String, String>) {
        SeasonHarness().use { harness ->
            runBlocking { harness.signedInWithKey() }
            runDesktopComposeUiTest {
                var zone by mutableStateOf(expected.keys.first())
                setContent {
                    AppEnvironment(customAppLocale = locale) {
                        GreenThumbTheme(darkTheme = false) {
                            SeasonModeSection(session = harness.session, zoneId = zone, latitudes = latitudes)
                        }
                    }
                }
                expected.forEach { (zoneId, text) ->
                    zone = zoneId
                    waitForIdle()
                    val shown = onNodeWithTag("season_explanation").fetchSemanticsNode().config
                        .getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
                    assertEquals(text, shown, "$locale $zoneId")
                }
            }
        }
    }

    @Test
    fun ruAutoExplanationFollowsTheDeviceZone() = checkExplanations(
        "ru",
        linkedMapOf(
            "Europe/Moscow" to "Авто: северное полушарие — с ноября по февраль поливаем в 1,5 раза реже",
            "Australia/Sydney" to "Авто: южное полушарие — с мая по август поливаем в 1,5 раза реже",
            "Asia/Singapore" to "Авто: тропики — без сезонной поправки",
            "UTC" to "Авто: часовой пояс не определён — без сезонной поправки",
            "Etc/GMT+5" to "Авто: часовой пояс не определён — без сезонной поправки",
        ),
    )

    @Test
    fun enAutoExplanationFollowsTheDeviceZone() = checkExplanations(
        "en",
        linkedMapOf(
            "Europe/Moscow" to "Auto: northern hemisphere — water 1.5× less often from November to February",
            "Australia/Sydney" to "Auto: southern hemisphere — water 1.5× less often from May to August",
            "Asia/Singapore" to "Auto: tropics — no seasonal adjustment",
            "UTC" to "Auto: time zone not recognized — no seasonal adjustment",
            "Etc/GMT+5" to "Auto: time zone not recognized — no seasonal adjustment",
        ),
    )

    private fun checkRollback(locale: String, message: String) {
        SeasonHarness().use { harness ->
            runBlocking { harness.signedInWithKey() }
            harness.server.seasonStatus = HttpStatusCode.InternalServerError
            runDesktopComposeUiTest {
                setContent {
                    AppEnvironment(customAppLocale = locale) {
                        GreenThumbTheme(darkTheme = false) {
                            SeasonModeSection(session = harness.session, zoneId = "Asia/Singapore", latitudes = latitudes)
                        }
                    }
                }
                onNodeWithTag("season_south").performClick()
                waitUntilAtLeastOneExists(hasTestTag("season_error"), timeoutMillis = 5_000)
                onNodeWithText(message).assertExists()
                onNodeWithTag("season_auto").assertIsSelected()
                onNodeWithTag("season_south").assertIsNotSelected()
                assertEquals(1, harness.server.seasonBodies.size)
                val user = (harness.session.state.value as SessionState.SignedIn).user
                assertEquals("auto", user.seasonMode)

                // A later successful choice clears the message and keeps the new mode.
                harness.server.seasonStatus = HttpStatusCode.OK
                onNodeWithTag("season_north").performClick()
                waitUntil(timeoutMillis = 5_000) {
                    (harness.session.state.value as? SessionState.SignedIn)?.user?.seasonMode == "north" &&
                        harness.server.seasonBodies.size == 2
                }
                waitForIdle()
                onNodeWithTag("season_north").assertIsSelected()
                onNodeWithTag("season_error").assertDoesNotExist()
                // Auto explanation is shown only in auto mode.
                onNodeWithTag("season_explanation").assertDoesNotExist()
            }
        }
    }

    @Test
    fun ruFailedSaveRevertsAndShowsTheMessage() =
        checkRollback("ru", "Не удалось сохранить. Проверьте подключение")

    @Test
    fun enFailedSaveRevertsAndShowsTheMessage() =
        checkRollback("en", "Couldn't save. Check your connection")
}
