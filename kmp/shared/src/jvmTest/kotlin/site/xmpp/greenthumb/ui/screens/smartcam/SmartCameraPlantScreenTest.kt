package site.xmpp.greenthumb.ui.screens.smartcam

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import site.xmpp.greenthumb.core.network.ApiError
import site.xmpp.greenthumb.core.network.DiagnosisCauseDto
import site.xmpp.greenthumb.core.network.DiagnosisResponse
import site.xmpp.greenthumb.core.platform.AppEnvironment
import site.xmpp.greenthumb.core.platform.CameraShutter
import site.xmpp.greenthumb.core.platform.CameraStatus
import site.xmpp.greenthumb.core.platform.PickResult
import site.xmpp.greenthumb.core.platform.PickSource
import site.xmpp.greenthumb.core.platform.paintedJpeg
import site.xmpp.greenthumb.ui.PlainImageLoaderRule
import site.xmpp.greenthumb.ui.screens.diagnosis.DiagnosisJournalSaver
import site.xmpp.greenthumb.ui.screens.diagnosis.PlantDiagnoser
import site.xmpp.greenthumb.ui.theme.GreenThumbTheme
import site.xmpp.greenthumb.ui.theme.Sizes

/**
 * VAL-CAM11-019/020/021 and VAL-CARD-018 on screen with a fake camera, ru and en: «Что с растением?» shows the
 * viewfinder in «Болезнь» with a chip of the plant name and its own ✕; the after-shot screen keeps the chip and says
 * that the plant's care is taken into account; the result saves straight to that plant's journal; ✕ on the chip
 * makes a plant-less check with «Это моё растение?». «Проверить на болезни» starts on the after-shot screen with
 * the cover, and a failed cover download falls back to the viewfinder with the chip and a message.
 */
@OptIn(ExperimentalTestApi::class)
class SmartCameraPlantScreenTest {
    @get:Rule
    val imageLoader = PlainImageLoaderRule()

    private val originalLocale: Locale = Locale.getDefault()

    @AfterTest
    fun tearDown() {
        Locale.setDefault(originalLocale)
    }

    private val basil = SmartCameraPlant(id = "p-1", name = "Базилик", photoUrl = "")
    private val others = listOf(basil, SmartCameraPlant(id = "p-2", name = "Монстера", photoUrl = ""))

    private class Harness {
        val plantChecks = mutableListOf<Pair<String, String?>>()
        val plainChecks = mutableListOf<String?>()
        val saves = mutableListOf<Triple<String, String, String>>()
        val shots = mutableListOf<Boolean>()
        val picks = mutableListOf<PickSource>()
        var closed = 0
        val sends: Int get() = plantChecks.size + plainChecks.size

        private val response = DiagnosisResponse(listOf(DiagnosisCauseDto("Overwatering", "high", "Wet soil.", "Water less.")), "Too much water.")
        val plantDiagnoser = PlantDiagnoser { id, _, note, _ ->
            plantChecks += id to note
            response
        }
        val diagnoser = StandaloneDiagnoser { _, note, _ ->
            plainChecks += note
            response
        }
        val journal = DiagnosisJournalSaver { id, uri, caption -> saves += Triple(id, uri, caption) }

        fun camera(): @Composable (CameraShutter, (CameraStatus) -> Unit, Modifier) -> Unit = { shutter, onStatus, modifier ->
            LaunchedEffect(shutter) {
                shutter.attach { flash -> shots += flash; PickResult.Picked(paintedJpeg(300, 400)) }
                onStatus(CameraStatus.Ready(hasFlash = true))
            }
            Box(modifier.background(Color.DarkGray))
        }
    }

    private fun ComposeUiTest.show(
        h: Harness,
        locale: String,
        plant: SmartCameraPlant = basil,
        initialPhoto: (suspend () -> ByteArray)? = null,
        fontScale: Float? = null,
    ) {
        setContent {
            AppEnvironment(customAppLocale = locale) {
                GreenThumbTheme(darkTheme = true) {
                    val screen = @Composable {
                        SmartCameraScreen(
                            initialMode = SmartCameraMode.Disease,
                            identifier = null,
                            diagnoser = h.diagnoser,
                            onClose = { h.closed++ },
                            onAddToMine = {},
                            myPlants = others,
                            journal = h.journal,
                            pick = { source -> h.picks += source; PickResult.Picked(paintedJpeg(400, 300)) },
                            deleteCapture = {},
                            camera = h.camera(),
                            plant = plant,
                            plantDiagnoser = h.plantDiagnoser,
                            initialPhoto = initialPhoto,
                        )
                    }
                    if (fontScale == null) {
                        screen()
                    } else {
                        val base = LocalDensity.current
                        CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                            Box(Modifier.width(384.dp).fillMaxHeight()) { screen() }
                        }
                    }
                }
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.node(tag: String) = onNode(hasTestTag(tag), useUnmergedTree = true)

    private fun ComposeUiTest.exists(matcher: SemanticsMatcher) =
        onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.exists(tag: String) = exists(hasTestTag(tag))

    private fun ComposeUiTest.waitFor(matcher: SemanticsMatcher) = waitUntil(timeoutMillis = TIMEOUT) { exists(matcher) }

    private fun ComposeUiTest.bounds(tag: String): Rect = node(tag).fetchSemanticsNode().boundsInRoot

    private fun ComposeUiTest.description(tag: String): String =
        node(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString().orEmpty()

    private fun ComposeUiTest.selected(tag: String): Boolean =
        node(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected) == true

    private fun ComposeUiTest.tap(tag: String) {
        waitUntilAtLeastOneExists(hasTestTag(tag), TIMEOUT)
        node(tag).performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
    }

    private fun tagged(tag: String, text: String) = hasTestTag(tag) and (hasText(text) or hasAnyDescendant(hasText(text)))

    private fun ComposeUiTest.texts(): List<String> =
        onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes().flatMap { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
                node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        }

    private fun ComposeUiTest.assertTouchTargets() {
        val minPx = with(density) { Sizes.touch.toPx() } - 0.5f
        val small = onAllNodes(hasClickAction()).fetchSemanticsNodes().filter { it.size.width < minPx || it.size.height < minPx }
        assertTrue(small.isEmpty(), "targets below 48 dp: ${small.map { it.config }}")
    }

    private fun chipOnTheViewfinder(locale: String, clear: String) = runDesktopComposeUiTest {
        val h = Harness()
        show(h, locale)
        waitUntilAtLeastOneExists(hasTestTag("smartcam_shutter"), TIMEOUT)
        assertTrue(selected("smartcam_mode_disease"), "«Болезнь» is selected")
        assertTrue(exists(tagged("smartcam_plantChip", "Базилик")), "[$locale] chip with the name: ${texts()}")
        assertEquals(clear, description("smartcam_plantChipClear"))
        val close = bounds("smartcam_close")
        val flash = bounds("smartcam_flash")
        val chip = bounds("smartcam_plantChip")
        assertTrue(chip.left >= close.right && chip.right <= flash.left, "the chip sits between ✕ and the flash: $close $chip $flash")
        assertTrue(chip.center.y in close.top..close.bottom, "the chip is on the top row")
        assertTouchTargets()
        assertEquals(0, h.sends)
    }

    @Test
    fun ru_chip_on_the_viewfinder() = chipOnTheViewfinder("ru", "Убрать растение «Базилик»")

    @Test
    fun en_chip_on_the_viewfinder() = chipOnTheViewfinder("en", "Remove “Базилик” from the check")

    @Test
    fun aLongNameAtFontScale13On384DpKeepsTheChipBetweenTheButtons() {
        val long = SmartCameraPlant(id = "p-1", name = "Фикус Бенджамина пёстрый на подоконнике в спальне", photoUrl = "")
        for (locale in listOf("ru", "en")) {
            runDesktopComposeUiTest {
                val h = Harness()
                show(h, locale, plant = long, fontScale = 1.3f)
                waitUntilAtLeastOneExists(hasTestTag("smartcam_shutter"), TIMEOUT)
                val close = bounds("smartcam_close")
                val flash = bounds("smartcam_flash")
                val chip = bounds("smartcam_plantChip")
                val clear = bounds("smartcam_plantChipClear")
                assertTrue(chip.left >= close.right && chip.right <= flash.left, "[$locale] $close $chip $flash")
                assertTrue(clear.right <= chip.right + 0.5f && clear.left >= chip.left - 0.5f, "[$locale] ✕ stays inside the chip")
                assertTouchTargets()
            }
        }
    }

    private fun plantCheck(locale: String, care: String, chosen: String, save: String, saved: String, prefix: String) =
        runDesktopComposeUiTest {
            val h = Harness()
            show(h, locale)
            tap("smartcam_shutter")
            waitFor(hasTestTag("smartcam_aftershot"))
            assertTrue(exists(tagged("smartcam_plantChip", "Базилик")), "the chip stays after the shot")
            assertTrue(exists(tagged("smartcam_plantCare", care)), "[$locale] ${texts()}")
            node("smartcam_note").performTextInput("Жёлтые листья")
            assertEquals(0, h.sends, "nothing is sent before «Проверить растение»")
            tap("smartcam_send")
            waitFor(hasTestTag("diagnosis_result"))
            assertEquals(listOf<Pair<String, String?>>("p-1" to "Жёлтые листья"), h.plantChecks)
            assertTrue(h.plainChecks.isEmpty())
            assertTrue(exists(tagged("smartcam_chosenPlant", chosen)), "[$locale] ${texts()}")
            assertTrue(exists(tagged("diagnosis_saveJournal", save)), "direct journal save")
            assertTrue(!exists("smartcam_isMine"), "no «Это моё растение?» for a plant check")
            assertTrue(!exists("smartcam_notInList"))
            tap("diagnosis_saveJournal")
            waitFor(tagged("diagnosis_saveJournal", saved))
            tap("diagnosis_saveJournal")
            val (id, uri, caption) = h.saves.single()
            assertEquals("p-1", id)
            assertTrue(uri.startsWith("data:image/jpeg;base64,"))
            assertEquals("$prefix: Overwatering (${if (locale == "ru") "Скорее всего" else "Most likely"})", caption)
            assertEquals(1, h.sends, "no second diagnosis")
        }

    @Test
    fun ru_plant_check_saves_to_the_plant_journal() = plantCheck(
        "ru", care = "Учтём уход за «Базилик»", chosen = "Сохраним в журнал растения «Базилик»",
        save = "Сохранить в журнал растения", saved = "Сохранено в журнал", prefix = "Что с растением?",
    )

    @Test
    fun en_plant_check_saves_to_the_plant_journal() = plantCheck(
        "en", care = "The care history of “Базилик” is taken into account", chosen = "Will be saved to the journal of “Базилик”",
        save = "Save to plant journal", saved = "Saved to journal", prefix = "Plant check",
    )

    @Test
    fun theChipCrossMakesAPlantLessCheckWithIsThisMyPlant() = runDesktopComposeUiTest {
        val h = Harness()
        show(h, "ru")
        tap("smartcam_plantChipClear")
        assertTrue(!exists("smartcam_plantChip"), "the chip is gone")
        assertTrue(selected("smartcam_mode_disease"), "«Болезнь» stays")
        assertEquals(0, h.sends, "✕ on the chip sends nothing")
        tap("smartcam_gallery")
        waitFor(hasTestTag("smartcam_aftershot"))
        assertTrue(!exists("smartcam_plantCare"))
        tap("smartcam_send")
        waitFor(hasTestTag("diagnosis_result"))
        assertEquals(listOf<String?>(null), h.plainChecks)
        assertTrue(h.plantChecks.isEmpty())
        assertTrue(exists(tagged("smartcam_isMine", "Это моё растение?")))
        assertTrue(!exists("diagnosis_saveJournal"), "no direct journal save")
    }

    @Test
    fun closeOnThePlantCameraLeavesWithoutSending() = runDesktopComposeUiTest {
        val h = Harness()
        show(h, "en")
        tap("smartcam_close")
        assertEquals(1, h.closed)
        assertEquals(0, h.sends)
    }

    @Test
    fun theCoverStartsOnTheAfterShotScreenWithoutViewfinderOrPicker() = runDesktopComposeUiTest {
        val h = Harness()
        show(h, "ru", initialPhoto = { paintedJpeg(300, 400) })
        waitFor(hasTestTag("smartcam_photo"))
        assertTrue(!exists("smartcam_viewfinder"), "no live viewfinder")
        assertTrue(exists(tagged("smartcam_plantChip", "Базилик")))
        assertTrue(exists(tagged("smartcam_send", "Проверить растение")))
        assertTrue(h.picks.isEmpty() && h.shots.isEmpty(), "no picker and no shot")
        assertEquals(0, h.sends)
        tap("smartcam_send")
        waitFor(hasTestTag("diagnosis_result"))
        assertEquals(listOf<Pair<String, String?>>("p-1" to null), h.plantChecks)
        assertTrue(exists(hasTestTag("diagnosis_saveJournal")))
    }

    @Test
    fun aFailedCoverDownloadOpensTheCameraWithTheChipAndAMessage() {
        for ((locale, message) in listOf(
            "ru" to "Не удалось загрузить фото. Снимите новое.",
            "en" to "Could not load the photo. Take a new one.",
        )) {
            runDesktopComposeUiTest {
                val h = Harness()
                show(h, locale, initialPhoto = { throw ApiError.Network })
                waitFor(tagged("smartcam_photoLoadError", message))
                assertTrue(exists("smartcam_viewfinder"), "[$locale] the camera opens")
                assertTrue(selected("smartcam_mode_disease"))
                assertTrue(exists(tagged("smartcam_plantChip", "Базилик")))
                assertEquals(0, h.sends, "no diagnose request")
                tap("smartcam_gallery")
                waitFor(hasTestTag("smartcam_aftershot"))
                assertTrue(!exists("smartcam_photoLoadError"))
            }
        }
    }

    private companion object {
        const val TIMEOUT = 10_000L
    }
}
