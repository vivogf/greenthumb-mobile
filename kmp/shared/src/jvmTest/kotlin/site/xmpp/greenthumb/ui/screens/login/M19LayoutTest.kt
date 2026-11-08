package site.xmpp.greenthumb.ui.screens.login

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Density
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.Locale
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import site.xmpp.greenthumb.core.platform.AppEnvironment
import site.xmpp.greenthumb.core.platform.LocalRecoveryKeyVault
import site.xmpp.greenthumb.core.platform.PushOutcome
import site.xmpp.greenthumb.core.platform.PushTokens
import site.xmpp.greenthumb.ui.screens.profile.ProfileRowTags
import site.xmpp.greenthumb.ui.screens.profile.ProfileScreen
import site.xmpp.greenthumb.ui.theme.GreenThumbTheme

/**
 * M19 layout rules of the recovery-key sign-in and Profile, at a 384 dp phone width,
 * in ru and en, at font scale 1.0 and 1.3:
 * - sign-in by key has one Back control (chevron with a label), its ink on the card content edge;
 * - the key placeholder stays on one line; the key field offers the Done action;
 * - with the keyboard open (viewport 553 dp) the field and «Sign In» scroll into view;
 * - «Key not found» uses the same card and scrolls;
 * - Profile rows: 24 dp icons on one start line, labels on one start line 40 dp later,
 *   one label text size, rows at least 48 dp tall; season options do not clip.
 */
@OptIn(ExperimentalTestApi::class)
class M19LayoutTest {

    private var originalLocale: Locale? = null

    @AfterTest
    fun restoreLocale() {
        originalLocale?.let { Locale.setDefault(it) }
        originalLocale = null
    }

    private val pxPerDp = WIDTH_PX / 384f

    private val combos = listOf("ru" to 1.0f, "ru" to 1.3f, "en" to 1.0f, "en" to 1.3f)

    private fun render(
        lang: String,
        scale: Float,
        heightDp: Int,
        content: @Composable () -> Unit,
        block: ComposeUiTest.() -> Unit,
    ) {
        if (originalLocale == null) originalLocale = Locale.getDefault()
        Locale.setDefault(Locale(lang))
        runDesktopComposeUiTest(width = WIDTH_PX, height = (heightDp * pxPerDp).toInt()) {
            setContent {
                AppEnvironment(customAppLocale = lang) {
                    CompositionLocalProvider(LocalDensity provides Density(pxPerDp, scale)) {
                        GreenThumbTheme(darkTheme = true) {
                            CompositionLocalProvider(LocalRecoveryKeyVault provides FakeVault(isSupported = true)) {
                                content()
                            }
                        }
                    }
                }
            }
            waitForIdle()
            block()
        }
    }

    private fun ComposeUiTest.nodes(tag: String): List<SemanticsNode> =
        onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes()

    private fun ComposeUiTest.node(matcher: androidx.compose.ui.test.SemanticsMatcher): SemanticsNode =
        onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().first()

    private fun SemanticsNode.layout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        return results.first()
    }

    private fun dp(px: Float) = px / pxPerDp

    private fun assertNear(expectedDp: Float, actualPx: Float, what: String) {
        assertTrue(abs(expectedDp - dp(actualPx)) <= 1f, "$what: expected $expectedDp dp, got ${dp(actualPx)} dp")
    }

    private fun keySession() = vaultSession(mutableListOf()) { respond("{}", HttpStatusCode.NotFound) }

    @Test
    fun keyModeHasOneLabelledBackControlOnTheCardContentEdge() {
        for ((lang, scale) in combos) {
            val back = if (lang == "ru") "Назад" else "Back"
            render(lang, scale, 853, { LoginScreen(session = keySession(), initialMode = LoginMode.Login) }) {
                val texts = onAllNodes(hasText(back), useUnmergedTree = true).fetchSemanticsNodes()
                assertEquals(0, texts.size, "$lang $scale: no text «$back» link under the form")
                val control = node(hasContentDescription(back) and hasClickAction())
                val card = nodes(LoginTags.CARD).single().boundsInRoot
                // 48 dp box, 24 dp chevron: the chevron ink starts on the 24 dp content edge.
                assertNear(24f - 12f, control.boundsInRoot.left - card.left, "$lang $scale back box start")
                assertNear(48f, control.boundsInRoot.width, "$lang $scale back box width")
            }
        }
    }

    @Test
    fun createModeHasOneLabelledBackControl() {
        for ((lang, scale) in combos) {
            val back = if (lang == "ru") "Назад" else "Back"
            render(lang, scale, 853, { LoginScreen(session = keySession(), initialMode = LoginMode.Create) }) {
                assertEquals(0, onAllNodes(hasText(back), useUnmergedTree = true).fetchSemanticsNodes().size)
                assertEquals(1, onAllNodes(hasContentDescription(back) and hasClickAction()).fetchSemanticsNodes().size)
            }
        }
    }

    @Test
    fun keyPlaceholderStaysOnOneLineAndFieldOffersDone() {
        for ((lang, scale) in combos) {
            render(lang, scale, 853, { LoginScreen(session = keySession(), initialMode = LoginMode.Login) }) {
                val placeholder = node(hasText("xxxxxxxx", substring = true) and !hasSetTextAction())
                assertEquals(1, placeholder.layout().lineCount, "$lang $scale placeholder lines")
                val field = node(hasSetTextAction())
                assertEquals(ImeAction.Done, field.config.getOrNull(SemanticsProperties.ImeAction), "$lang $scale ime action")
            }
        }
    }

    @Test
    fun keyHeaderIsCentredInTheCard() {
        for ((lang, scale) in combos) {
            val title = if (lang == "ru") "С возвращением" else "Welcome Back"
            render(lang, scale, 853, { LoginScreen(session = keySession(), initialMode = LoginMode.Login) }) {
                val card = nodes(LoginTags.CARD).single().boundsInRoot
                val t = node(hasText(title)).boundsInRoot
                assertNear(0f, t.center.x - card.center.x, "$lang $scale title centre")
            }
        }
    }

    @Test
    fun withKeyboardOpenTheFieldAndSignInScrollIntoView() {
        for ((lang, scale) in combos) {
            val signIn = if (lang == "ru") "Войти" else "Sign In"
            // 853 dp screen minus a 300 dp keyboard.
            render(lang, scale, 553, { LoginScreen(session = keySession(), initialMode = LoginMode.Login) }) {
                onNode(hasSetTextAction()).performScrollTo().assertIsDisplayed()
                onNode(hasText(signIn) and hasClickAction()).performScrollTo().assertIsDisplayed()
            }
        }
    }

    @Test
    fun keyNotFoundUsesTheLoginCardAndScrolls() {
        for ((lang, scale) in combos) {
            val create = if (lang == "ru") "Создать аккаунт" else "Create new account"
            render(lang, scale, 553, { KeyNotFoundSurface(onEnterLogin = {}, onEnterCreate = {}) }) {
                assertEquals(1, nodes(LoginTags.CARD).size, "$lang $scale card")
                onNode(hasText(create) and hasClickAction()).performScrollTo().assertIsDisplayed()
            }
        }
    }

    @Test
    fun profileRowsShareIconSizeStartLinesAndTextSize() {
        for ((lang, scale) in combos) {
            val json = headersOf("Content-Type", "application/json")
            val session = vaultSession(mutableListOf()) { request ->
                when (request.url.encodedPath) {
                    "/api/auth/login-recovery" -> respond(
                        """{"user":{"id":4242,"name":"Тест Ray","recovery_key":"$VAULT_TEST_KEY","notification_time":"09:00","season_mode":"auto","created_at":"2026-10-27T00:00:00.000Z"}}""",
                        HttpStatusCode.OK,
                        json,
                    )
                    else -> respond("{}", HttpStatusCode.NotFound, json)
                }
            }
            runBlocking { session.signInWithRecoveryKey(VAULT_TEST_KEY) }
            val heightDp = if (scale > 1.1f) 2600 else 2000
            render(lang, scale, heightDp, {
                ProfileScreen(session = session, settings = VaultFakeSettings(), push = OnPush(), pushLanguage = { lang }, onOpenProgress = {})
            }) {
                waitUntilAtLeastOneExists(hasTestTag(ProfileRowTags.ROW), 10_000)
                val icons = nodes(ProfileRowTags.ICON)
                val labels = nodes(ProfileRowTags.LABEL)
                val rows = nodes(ProfileRowTags.ROW)
                // bell, time (empty slot), test, language, theme, key, QR, regenerate, progress, season,
                // about, bug, sign out, delete.
                assertTrue(icons.size >= 13, "$lang $scale icon slots: ${icons.size}")
                assertEquals(icons.size, labels.size, "$lang $scale one label per icon slot")
                val iconStart = icons.first().boundsInRoot.left
                icons.forEach { icon ->
                    assertNear(24f, icon.boundsInRoot.width, "$lang $scale icon width")
                    assertNear(24f, icon.boundsInRoot.height, "$lang $scale icon height")
                    assertNear(0f, icon.boundsInRoot.left - iconStart, "$lang $scale icon start")
                }
                labels.forEach { label ->
                    assertNear(40f, label.boundsInRoot.left - iconStart, "$lang $scale label start")
                }
                val sizes = labels.map { it.layout().layoutInput.style.fontSize }.toSet()
                assertEquals(1, sizes.size, "$lang $scale one label text size: $sizes")
                rows.forEach { row -> assertTrue(dp(row.boundsInRoot.height) >= 47.5f, "$lang $scale row height ${dp(row.boundsInRoot.height)}") }
                val seasonTexts = nodes("season_text")
                assertEquals(4, seasonTexts.size, "$lang $scale season options")
                seasonTexts.forEach {
                    val l = it.layout()
                    // didOverflowWidth is unreliable for centred one-line text; compare the ink width instead.
                    val ink = l.getLineRight(0) - l.getLineLeft(0)
                    assertTrue(
                        l.lineCount == 1 && ink <= l.size.width + 1 && !l.didOverflowHeight,
                        "$lang $scale season label clipped: ${l.layoutInput.text} w=${l.didOverflowWidth} h=${l.didOverflowHeight} size=${l.size} max=${l.layoutInput.constraints}",
                    )
                }
            }
        }
    }

    private class OnPush : PushTokens() {
        override suspend fun requestSubscribe(language: String): PushOutcome = PushOutcome.Denied
        override suspend fun subscriptionStatus(): Boolean = true
        override suspend fun unsubscribe() {}
        override suspend fun sendLocalTestNotification() {}
    }

    private companion object {
        const val WIDTH_PX = 1080
    }
}
