@file:OptIn(kotlin.io.path.ExperimentalPathApi::class)

package site.xmpp.greenthumb.ui.screens.plantdetail

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import java.util.Locale
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import site.xmpp.greenthumb.core.network.AccountSession
import site.xmpp.greenthumb.core.network.ApiClient
import site.xmpp.greenthumb.core.network.GreenThumbApi
import site.xmpp.greenthumb.core.network.NoSessionRecoveryProvider
import site.xmpp.greenthumb.core.platform.AppEnvironment
import site.xmpp.greenthumb.core.storage.JvmPlantDatabases
import site.xmpp.greenthumb.data.AccountPlantGate
import site.xmpp.greenthumb.data.PlantRepository
import site.xmpp.greenthumb.data.PlantRepositoryOpener
import site.xmpp.greenthumb.ui.PlainImageLoaderRule
import site.xmpp.greenthumb.ui.components.GtSheetTags
import site.xmpp.greenthumb.ui.theme.GreenThumbTheme

/**
 * M10b on the plant card: «Проверить на болезни» in the photo sheet (VAL-CARD-017), the
 * «Что с растением?» row text wrapping before its chevron at font scale 1.3, a saved diagnosis in
 * the journal as thumbnail + caption (VAL-CARD-014) and 48 dp targets (VAL-CARD-019).
 */
@OptIn(ExperimentalTestApi::class)
class PlantCardDiagnosisTest {
    private val originalLocale: Locale = Locale.getDefault()
    private var cleanup: (() -> Unit)? = null

    @get:Rule
    val imageLoader = PlainImageLoaderRule()

    @AfterTest
    fun tearDown() {
        Locale.setDefault(originalLocale)
        cleanup?.invoke()
        cleanup = null
    }

    private val json = headersOf("Content-Type", "application/json")
    private val cover = "data:image/jpeg;base64,/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAP//////////////////////////////////////////////////////////////////////////////////////wgALCAABAAEBAREA/8QAFBABAAAAAAAAAAAAAAAAAAAAAP/aAAgBAQABPxA="
    private val caption = "Что с растением?: Слишком много воды (Скорее всего), Мало света (Возможно)"

    private fun plant(id: String, photo: String) =
        """{"id":"$id","user_id":"73","name":"Ficus $id","location":"Kitchen","photo_url":"$photo",""" +
            """"water_frequency_days":7,"last_watered_date":"2026-09-20","notes":"",""" +
            """"created_at":"2026-09-01T10:00:00.000Z"}"""

    private val handler: MockRequestHandler = { request ->
        val path = request.url.encodedPath
        when {
            path == "/api/plants" && request.method == HttpMethod.Get ->
                respond("[${plant("p-photo", cover)},${plant("p-none", "")}]", HttpStatusCode.OK, json)
            path == "/api/plants/p-photo/photos" -> respond(
                """{"photos":[{"id":"ph-1","url":"$cover","taken_on":"2026-10-30","caption":"$caption",""" +
                    """"created_at":"2026-10-30T10:00:00.000Z"}]}""",
                HttpStatusCode.OK,
                json,
            )
            path == "/api/plants/p-photo/events" -> respond(
                """{"events":[{"id":"ev-1","plant_id":"p-photo","user_id":"73","type":"photo",""" +
                    """"occurred_on":"2026-10-30","photo_id":"ph-1","created_at":"2026-10-30T10:00:00.000Z"}],"nextCursor":null}""",
                HttpStatusCode.OK,
                json,
            )
            path.endsWith("/photos") -> respond("""{"photos":[]}""", HttpStatusCode.OK, json)
            path.endsWith("/events") -> respond("""{"events":[],"nextCursor":null}""", HttpStatusCode.OK, json)
            else -> respond("{}", HttpStatusCode.NotFound, json)
        }
    }

    private fun openRepo(): PlantRepository {
        val dir = File.createTempFile("gt-card-diagnosis", "").also { it.delete() }
        assertTrue(dir.mkdirs(), "temp dir created")
        val databases = JvmPlantDatabases(dir)
        val client = ApiClient(MockEngine(handler), NoSessionRecoveryProvider)
        val gate = AccountPlantGate(
            openDatabase = { userId -> databases.open(userId) },
            deleteDatabase = { userId -> databases.delete(userId) },
            api = GreenThumbApi(client),
            session = AccountSession(),
        )
        val repo = gate.open("73")
        cleanup = {
            runCatching { repo.close() }
            runCatching { client.close() }
            runCatching { dir.toPath().deleteRecursively() }
        }
        runBlocking { repo.refresh() }
        return repo
    }

    private fun ComposeUiTest.show(
        repo: PlantRepository,
        plantId: String,
        locale: String = "en",
        fontScale: Float = 1f,
        onCheckPhoto: (() -> Unit)? = {},
    ) {
        setContent {
            AppEnvironment(customAppLocale = locale) {
                val base = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                    GreenThumbTheme(darkTheme = true) {
                        PlantDetailScreen(
                            userId = "73",
                            plantId = plantId,
                            opener = PlantRepositoryOpener { repo },
                            onBack = {},
                            onDeleted = {},
                            deleteErrorState = PlantDeleteErrorState(),
                            onOpenDiagnosis = {},
                            onCheckPhoto = onCheckPhoto,
                        )
                    }
                }
            }
        }
        waitFor(hasText("Ficus $plantId"))
    }

    private fun ComposeUiTest.nodes(matcher: SemanticsMatcher): List<SemanticsNode> =
        onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes()

    private fun ComposeUiTest.waitFor(matcher: SemanticsMatcher, present: Boolean = true) =
        waitUntil(timeoutMillis = TIMEOUT) { nodes(matcher).isNotEmpty() == present }

    private fun ComposeUiTest.click(matcher: SemanticsMatcher) {
        val all = onAllNodes(matcher)
        all[all.fetchSemanticsNodes().size - 1].performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun ComposeUiTest.sheetLabels(): List<String> =
        nodes(hasTestTag(GtSheetTags.LABEL)).map { it.config[SemanticsProperties.Text].joinToString() }

    private fun ComposeUiTest.assertTouchTargets(where: String) {
        val min = with(density) { 48.dp.toPx() } - 0.5f
        val tappable = onAllNodes(hasClickAction()).fetchSemanticsNodes()
        assertTrue(tappable.isNotEmpty(), "$where has tappable nodes")
        // Layout size, not on-screen bounds: rows below the fold are clipped by the scroll viewport.
        val small = tappable.filter { it.size.width < min || it.size.height < min }.map { node ->
            val name = node.config.getOrElse(SemanticsProperties.TestTag) { "" }.ifEmpty {
                node.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.joinToString()
            }.ifEmpty { node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString() }
            "$name=${node.size.width}x${node.size.height}"
        }
        assertTrue(small.isEmpty(), "$where: under 48 dp: $small")
    }

    @Test
    fun photo_sheet_offers_check_for_problems_for_a_plant_with_a_photo() {
        val repo = openRepo()
        for ((locale, rows) in listOf(
            "en" to listOf("Take a new photo", "Choose from gallery", "Check for problems", "Cancel"),
            "ru" to listOf("Сделать новое фото", "Выбрать из галереи", "Проверить на болезни", "Отмена"),
        )) {
            runDesktopComposeUiTest {
                var checks = 0
                show(repo, "p-photo", locale) { checks++ }
                click(hasClickAction() and hasTestTag("plantDetail_headerPhoto"))
                waitFor(hasTestTag("plantDetail_photoCheck"))
                assertEquals(rows, sheetLabels(), locale)
                val hint = if (locale == "ru") "по этому фото" else "using this photo"
                assertTrue(nodes(hasText(hint)).isNotEmpty(), "[$locale] supporting line")
                assertTouchTargets("[$locale] photo sheet")
                click(hasClickAction() and hasTestTag("plantDetail_photoCheck"))
                waitFor(hasTestTag("plantDetail_photoCheck"), present = false)
                assertEquals(1, checks, "[$locale] check opened once")
            }
        }
    }

    @Test
    fun camera_icon_opens_the_same_sheet_with_the_check_row() {
        val repo = openRepo()
        runDesktopComposeUiTest {
            show(repo, "p-photo")
            click(hasClickAction() and hasContentDescription("Change plant photo"))
            waitFor(hasTestTag("plantDetail_photoCheck"))
        }
    }

    @Test
    fun no_check_row_without_a_photo_or_without_diagnosis() {
        val repo = openRepo()
        runDesktopComposeUiTest {
            show(repo, "p-none")
            click(hasClickAction() and hasTestTag("plantDetail_headerPhoto"))
            waitFor(hasText("Plant photo"))
            assertEquals(listOf("Take a new photo", "Choose from gallery", "Cancel"), sheetLabels())
        }
        runDesktopComposeUiTest {
            show(repo, "p-photo", onCheckPhoto = null)
            click(hasClickAction() and hasTestTag("plantDetail_headerPhoto"))
            waitFor(hasText("Plant photo"))
            assertEquals(listOf("Take a new photo", "Choose from gallery", "Cancel"), sheetLabels())
        }
    }

    @Test
    fun diagnosis_row_text_wraps_before_the_chevron_at_font_scale_1_3() {
        val repo = openRepo()
        for ((locale, line) in listOf(
            "ru" to "Сфотографируйте лист — ИИ подскажет причину и что делать",
            "en" to "Photograph a leaf — AI suggests the cause and what to do",
        )) {
            runDesktopComposeUiTest {
                show(repo, "p-photo", locale, fontScale = 1.3f)
                waitFor(hasText(line))
                val text = nodes(hasText(line)).single().boundsInRoot
                val row = nodes(hasTestTag("plantDetail_diagnosis")).single().boundsInRoot
                val chevron = nodes(hasTestTag("settingsRow_chevron") and hasAnyAncestor(hasTestTag("plantDetail_diagnosis")))
                    .single().boundsInRoot
                assertTrue(text.right <= chevron.left, "[$locale] text ${text.right} ends before chevron ${chevron.left}")
                assertTrue(chevron.right <= row.right, "[$locale] chevron stays inside the row")
            }
        }
    }

    @Test
    fun saved_diagnosis_shows_in_the_journal_with_thumbnail_and_caption() {
        val repo = openRepo()
        runDesktopComposeUiTest {
            show(repo, "p-photo", "ru")
            waitFor(hasTestTag("journal_thumb_ev-1"))
            val thumb = nodes(hasTestTag("journal_thumb_ev-1")).single().size
            val side = with(density) { 48.dp.toPx() }
            assertEquals(side, thumb.width.toFloat(), 0.5f)
            assertEquals(side, thumb.height.toFloat(), 0.5f)
            waitFor(hasTestTag("journal_caption_ev-1") and hasText(caption))
        }
    }

    @Test
    fun plant_detail_and_care_settings_touch_targets_are_at_least_48_dp() {
        val repo = openRepo()
        for (locale in listOf("ru", "en")) {
            runDesktopComposeUiTest {
                show(repo, "p-photo", locale)
                waitFor(hasTestTag("plantDetail_diagnosis"))
                assertTouchTargets("[$locale] plant detail")
                click(hasClickAction() and hasText(if (locale == "ru") "Настройки ухода" else "Care Settings"))
                waitIdleThenCheck("[$locale] care settings")
            }
        }
    }

    private fun ComposeUiTest.waitIdleThenCheck(where: String) {
        waitForIdle()
        assertTouchTargets(where)
    }

    private companion object {
        const val TIMEOUT: Long = 10_000L
    }
}
