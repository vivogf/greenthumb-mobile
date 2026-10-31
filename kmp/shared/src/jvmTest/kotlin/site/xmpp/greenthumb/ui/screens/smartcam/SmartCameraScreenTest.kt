package site.xmpp.greenthumb.ui.screens.smartcam

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import site.xmpp.greenthumb.core.network.DiagnosisCauseDto
import site.xmpp.greenthumb.core.network.DiagnosisResponse
import site.xmpp.greenthumb.core.network.SpeciesCandidateDto
import site.xmpp.greenthumb.core.platform.AppEnvironment
import site.xmpp.greenthumb.core.platform.CameraShutter
import site.xmpp.greenthumb.core.platform.CameraStatus
import site.xmpp.greenthumb.core.platform.PickResult
import site.xmpp.greenthumb.core.platform.PickSource
import site.xmpp.greenthumb.core.platform.paintedJpeg
import site.xmpp.greenthumb.ui.screens.addplant.AddPlantDraft
import site.xmpp.greenthumb.ui.screens.species.SpeciesIdentifier
import site.xmpp.greenthumb.ui.theme.GreenThumbTheme
import site.xmpp.greenthumb.ui.theme.Sizes

/**
 * VAL-CAM11-002/003/006/007/008/009/010/011/015/016/025/026 on screen with a fake camera: the viewfinder layout
 * (✕ and flash on top, modes above the shutter row, gallery left of a centred shutter), tap and swipe mode
 * changes, the after-shot screen with one primary button and a truthful note, no request before the tap,
 * and the denied / unavailable fallbacks.
 */
@OptIn(ExperimentalTestApi::class)
class SmartCameraScreenTest {

    private class Harness {
        var identifyCalls = 0
        var diagnoseNotes = mutableListOf<String?>()
        var closed = 0
        var settingsOpened = 0
        var draft: AddPlantDraft? = null
        val shots = mutableListOf<Boolean>()
        val picks = mutableListOf<PickSource>()
        val remembered = mutableListOf<SmartCameraMode>()
        val sends: Int get() = identifyCalls + diagnoseNotes.size

        val identifier = SpeciesIdentifier { _, _ ->
            identifyCalls++
            listOf(
                SpeciesCandidateDto("Monstera deliciosa", listOf("Swiss cheese plant"), 0.83),
                SpeciesCandidateDto("Philodendron bipinnatifidum", emptyList(), 0.31),
                SpeciesCandidateDto("Epipremnum aureum", emptyList(), 0.04),
                SpeciesCandidateDto("Rhaphidophora tetrasperma", emptyList(), 0.01),
            )
        }
        val diagnoser = StandaloneDiagnoser { _, note, _ ->
            diagnoseNotes += note
            DiagnosisResponse(listOf(DiagnosisCauseDto("Overwatering", "high", "Wet soil.", "Water less.")), "Too much water.")
        }

        /** A camera that reports [status] and, when ready, takes a portrait photo. */
        fun camera(status: CameraStatus): @Composable (CameraShutter, (CameraStatus) -> Unit, Modifier) -> Unit =
            { shutter, onStatus, modifier ->
                LaunchedEffect(shutter) {
                    if (status is CameraStatus.Ready) {
                        shutter.attach { flash -> shots += flash; PickResult.Picked(paintedJpeg(300, 400)) }
                    }
                    onStatus(status)
                }
                Box(modifier.background(Color.DarkGray))
            }
    }

    private fun ComposeUiTest.show(
        h: Harness,
        locale: String = "en",
        mode: SmartCameraMode = SmartCameraMode.Plant,
        status: CameraStatus = CameraStatus.Ready(hasFlash = true),
    ) {
        setContent {
            AppEnvironment(customAppLocale = locale) {
                GreenThumbTheme(darkTheme = false) {
                    SmartCameraScreen(
                        initialMode = mode,
                        identifier = h.identifier,
                        diagnoser = h.diagnoser,
                        onClose = { h.closed++ },
                        onAddToMine = { h.draft = it },
                        pick = { source -> h.picks += source; PickResult.Picked(paintedJpeg(400, 200)) },
                        deleteCapture = {},
                        camera = h.camera(status),
                        openSettings = { h.settingsOpened++ },
                        onModeChange = { h.remembered += it },
                    )
                }
            }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.node(tag: String) = onNode(hasTestTag(tag), useUnmergedTree = true)

    private fun ComposeUiTest.exists(tag: String) =
        onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.bounds(tag: String): Rect = node(tag).fetchSemanticsNode().boundsInRoot

    /** Text of the tagged node, or of its merged subtree for a button whose label is a child. */
    private fun ComposeUiTest.textOf(tag: String): String {
        fun read(node: androidx.compose.ui.semantics.SemanticsNode) =
            node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }.orEmpty()
        return read(node(tag).fetchSemanticsNode()).ifEmpty { read(onNode(hasTestTag(tag)).fetchSemanticsNode()) }
    }

    private fun ComposeUiTest.description(tag: String): String =
        node(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString().orEmpty()

    private fun ComposeUiTest.selected(tag: String): Boolean =
        node(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected) == true

    private fun ComposeUiTest.tap(tag: String) {
        waitUntilAtLeastOneExists(hasTestTag(tag), TIMEOUT)
        node(tag).performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
    }

    /** Clickable nodes of the screen are at least 48 dp in both directions (layout size, so a node scrolled out of view counts too). */
    private fun ComposeUiTest.assertTouchTargets() {
        val minPx = with(density) { Sizes.touch.toPx() } - 0.5f
        val small = onAllNodes(hasClickAction()).fetchSemanticsNodes().filter {
            it.size.width < minPx || it.size.height < minPx
        }
        assertTrue(small.isEmpty(), "targets below 48 dp: ${small.map { it.config }}")
    }

    private fun viewfinderLayout(locale: String, plant: String, disease: String) = runDesktopComposeUiTest {
        val h = Harness()
        show(h, locale)
        waitUntilAtLeastOneExists(hasTestTag("smartcam_shutter"), TIMEOUT)
        val root = bounds("smartcam_viewfinder")
        val close = bounds("smartcam_close")
        val flash = bounds("smartcam_flash")
        val modes = bounds("smartcam_modes")
        val shutter = bounds("smartcam_shutter")
        val gallery = bounds("smartcam_gallery")
        assertTrue(close.center.x < root.center.x && close.center.y < root.center.y, "✕ is top-left: $close")
        assertTrue(flash.center.x > root.center.x && flash.center.y < root.center.y, "flash is top-right: $flash")
        assertTrue(modes.bottom <= minOf(shutter.top, gallery.top), "modes are above the shutter row: $modes / $shutter")
        assertTrue(gallery.right < shutter.left, "gallery is left of the shutter")
        assertTrue(abs(gallery.center.y - shutter.center.y) < 2f, "gallery and shutter share a row")
        val twoDp = with(density) { Sizes.touch.toPx() } / 24f
        assertTrue(abs(shutter.center.x - root.center.x) <= twoDp, "the shutter is centred: $shutter in $root")
        assertEquals(plant, textOf("smartcam_mode_plant"))
        assertEquals(disease, textOf("smartcam_mode_disease"))
        for (tag in listOf("smartcam_close", "smartcam_flash", "smartcam_gallery", "smartcam_shutter")) {
            assertTrue(description(tag).isNotBlank(), "$tag has a name in $locale")
        }
        assertTouchTargets()
        assertEquals(0, h.sends)
    }

    @Test
    fun en_viewfinder_layout() = viewfinderLayout("en", "Plant", "Problem")

    @Test
    fun ru_viewfinder_layout() = viewfinderLayout("ru", "Растение", "Болезнь")

    @Test
    fun tapAndSwipeChangeTheModeHintFrameAndShutterWithoutSending() = runDesktopComposeUiTest {
        val h = Harness()
        show(h)
        waitUntilAtLeastOneExists(hasTestTag("smartcam_shutter"), TIMEOUT)
        assertTrue(selected("smartcam_mode_plant"))
        val plantHint = textOf("smartcam_hint")
        val plantShutter = description("smartcam_shutter")
        val plantFrame = bounds("smartcam_frame")
        tap("smartcam_mode_disease")
        assertTrue(selected("smartcam_mode_disease"))
        assertFalse(selected("smartcam_mode_plant"))
        assertNotEquals(plantHint, textOf("smartcam_hint"))
        assertTrue(textOf("smartcam_hint").contains("close", ignoreCase = true), textOf("smartcam_hint"))
        assertNotEquals(plantShutter, description("smartcam_shutter"))
        val diseaseFrame = bounds("smartcam_frame")
        assertTrue(diseaseFrame.width < plantFrame.width, "«Problem» uses a smaller frame")

        node("smartcam_viewfinder").performTouchInput { swipeRight() }
        waitForIdle()
        assertTrue(selected("smartcam_mode_plant"), "a right swipe goes back to «Plant»")
        node("smartcam_viewfinder").performTouchInput { swipeLeft() }
        waitForIdle()
        assertTrue(selected("smartcam_mode_disease"), "a left swipe goes on to «Problem»")
        assertEquals(listOf(SmartCameraMode.Disease, SmartCameraMode.Plant, SmartCameraMode.Disease), h.remembered)
        assertEquals(0, h.sends)
    }

    @Test
    fun theFlashToggleIsNamedAndItsStateGoesToTheShot() = runDesktopComposeUiTest {
        val h = Harness()
        show(h)
        waitUntilAtLeastOneExists(hasTestTag("smartcam_flash"), TIMEOUT)
        val off = description("smartcam_flash")
        assertEquals(ToggleableState.Off, node("smartcam_flash").fetchSemanticsNode().config[SemanticsProperties.ToggleableState])
        tap("smartcam_flash")
        val on = description("smartcam_flash")
        assertNotEquals(off, on)
        assertEquals(ToggleableState.On, node("smartcam_flash").fetchSemanticsNode().config[SemanticsProperties.ToggleableState])
        tap("smartcam_mode_disease")
        tap("smartcam_mode_plant")
        assertEquals(on, description("smartcam_flash"), "the flash state stays while the mode changes")
        tap("smartcam_shutter")
        waitUntilAtLeastOneExists(hasTestTag("smartcam_aftershot"), TIMEOUT)
        assertEquals(listOf(true), h.shots)
    }

    @Test
    fun withoutAFlashUnitTheToggleIsHidden() = runDesktopComposeUiTest {
        val h = Harness()
        show(h, status = CameraStatus.Ready(hasFlash = false))
        waitUntilAtLeastOneExists(hasTestTag("smartcam_shutter"), TIMEOUT)
        assertFalse(exists("smartcam_flash"))
    }

    @Test
    fun plantShotShowsThePhotoOnePrimaryButtonAndThePlantNetNoteThenTheResults() = runDesktopComposeUiTest {
        val h = Harness()
        show(h)
        tap("smartcam_shutter")
        waitUntilAtLeastOneExists(hasTestTag("smartcam_aftershot"), TIMEOUT)
        assertTrue(exists("smartcam_photo"))
        assertEquals("Identify species", textOf("smartcam_send"))
        val note = textOf("smartcam_sendNote")
        assertTrue(note.contains("Pl@ntNet") && note.contains("only when you tap"), note)
        assertEquals("Retake", textOf("smartcam_reshoot"))
        assertEquals(1, onAllNodesWithTag("smartcam_send", useUnmergedTree = true).fetchSemanticsNodes().size)
        assertFalse(exists("smartcam_note"), "no note field in «Plant» mode")
        assertTouchTargets()
        assertEquals(0, h.sends, "nothing is sent before «Identify species»")

        tap("smartcam_reshoot")
        waitUntilAtLeastOneExists(hasTestTag("smartcam_shutter"), TIMEOUT)
        assertTrue(selected("smartcam_mode_plant"))
        assertEquals(0, h.sends)

        tap("smartcam_gallery")
        waitUntilAtLeastOneExists(hasTestTag("smartcam_aftershot"), TIMEOUT)
        assertEquals(listOf(PickSource.Gallery), h.picks)
        assertEquals(0, h.sends)
        tap("smartcam_send")
        waitUntilAtLeastOneExists(hasTestTag("smartcam_candidate_0"), TIMEOUT)
        assertEquals(1, h.identifyCalls)
        assertTrue(exists("smartcam_candidate_2"))
        assertFalse(exists("smartcam_candidate_3"))
        assertTrue(exists("identify_poweredBy"))
        assertEquals(3, onAllNodesWithTag("smartcam_dotFilled_0", useUnmergedTree = true).fetchSemanticsNodes().size)
        val texts = listOf(0, 1, 2).map { textOf("smartcam_similarity_$it") }
        assertFalse(texts.any { it.contains('%') || Regex("0[.,]\\d").containsMatchIn(it) }, texts.toString())

        onNode(hasTestTag("smartcam_addToMine")).performScrollTo().performClick()
        val handed = assertNotNull(h.draft)
        assertEquals("Monstera deliciosa", handed.candidate.scientificName)
        val image = ImageIO.read(ByteArrayInputStream(handed.photoJpeg))
        assertEquals(400 to 200, image.width to image.height, "the cover keeps the picked photo's aspect, no square crop")

        onNode(hasTestTag("smartcam_retake")).performScrollTo().performClick()
        waitUntilAtLeastOneExists(hasTestTag("smartcam_shutter"), TIMEOUT)
        assertTrue(selected("smartcam_mode_plant"))
        assertEquals(1, h.identifyCalls)
        assertEquals(0, h.closed)
    }

    private fun diseaseAfterShot(locale: String, send: String, field: String, ai: String, only: String) = runDesktopComposeUiTest {
        val h = Harness()
        show(h, locale, SmartCameraMode.Disease)
        tap("smartcam_shutter")
        waitUntilAtLeastOneExists(hasTestTag("smartcam_aftershot"), TIMEOUT)
        assertEquals(send, textOf("smartcam_send"))
        val note = textOf("smartcam_sendNote")
        assertTrue(note.contains(ai) && note.contains("OpenAI") && note.contains(only), "[$locale] $note")
        assertTrue(exists("smartcam_note"))
        val placeholder = onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes()
            .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { t -> t.text } }
        assertTrue(placeholder.any { it.startsWith(field) }, "[$locale] $placeholder")
        assertEquals(1, onAllNodesWithTag("smartcam_send", useUnmergedTree = true).fetchSemanticsNodes().size)
        node("smartcam_note").performTextInput("Yellow spots")
        waitForIdle()
        assertTouchTargets()
        assertEquals(0, h.sends, "typing a note sends nothing")
        tap("smartcam_send")
        waitUntilAtLeastOneExists(hasTestTag("diagnosis_result"), TIMEOUT)
        assertEquals(listOf<String?>("Yellow spots"), h.diagnoseNotes)
        assertEquals(0, h.identifyCalls)
    }

    @Test
    fun en_disease_after_shot() = diseaseAfterShot("en", "Check the plant", "What worries you?", "AI check", "only when you tap")

    @Test
    fun ru_disease_after_shot() = diseaseAfterShot("ru", "Проверить растение", "Что вас беспокоит?", "проверку ИИ", "только когда вы нажмёте")

    private fun deniedCamera(locale: String, message: String, gallery: String, settings: String) = runDesktopComposeUiTest {
        val h = Harness()
        show(h, locale, status = CameraStatus.Denied(permanent = true))
        waitUntilAtLeastOneExists(hasTestTag("smartcam_cameraMessage"), TIMEOUT)
        assertEquals(message, textOf("smartcam_cameraMessage"))
        assertEquals(gallery, textOf("smartcam_fallbackGallery"))
        assertEquals(settings, textOf("smartcam_openSettings"))
        assertFalse(exists("smartcam_shutter"))
        assertFalse(exists("smartcam_systemCamera"), "a denied camera is not worked around with the system camera")
        assertTrue(exists("smartcam_close"))
        assertTouchTargets()
        tap("smartcam_openSettings")
        assertEquals(1, h.settingsOpened)
        tap("smartcam_fallbackGallery")
        waitUntilAtLeastOneExists(hasTestTag("smartcam_aftershot"), TIMEOUT)
        assertEquals(listOf(PickSource.Gallery), h.picks)
        assertEquals(0, h.sends)
        tap("smartcam_reshoot")
        waitUntilAtLeastOneExists(hasTestTag("smartcam_cameraMessage"), TIMEOUT)
    }

    @Test
    fun en_denied_camera() = deniedCamera(
        "en",
        "No access to the camera. Allow it in the settings or choose a photo from the gallery.",
        "Choose from gallery",
        "Open settings",
    )

    @Test
    fun ru_denied_camera() = deniedCamera(
        "ru",
        "Нет доступа к камере. Разрешите его в настройках или выберите фото из галереи.",
        "Выбрать из галереи",
        "Открыть настройки",
    )

    @Test
    fun anUnavailableCameraOffersTheGalleryAndTheSystemCamera() = runDesktopComposeUiTest {
        val h = Harness()
        show(h, status = CameraStatus.Unavailable)
        waitUntilAtLeastOneExists(hasTestTag("smartcam_cameraMessage"), TIMEOUT)
        assertTrue(exists("smartcam_fallbackGallery"))
        assertFalse(exists("smartcam_openSettings"))
        tap("smartcam_systemCamera")
        waitUntilAtLeastOneExists(hasTestTag("smartcam_aftershot"), TIMEOUT)
        assertEquals(listOf(PickSource.Camera), h.picks)
        assertEquals(0, h.sends)
        tap("smartcam_reshoot")
        tap("smartcam_fallbackGallery")
        waitUntilAtLeastOneExists(hasTestTag("smartcam_aftershot"), TIMEOUT)
        assertEquals(listOf(PickSource.Camera, PickSource.Gallery), h.picks)
        assertEquals(0, h.sends)
    }

    @Test
    fun closeLeavesTheCameraWithoutSending() = runDesktopComposeUiTest {
        val h = Harness()
        show(h)
        tap("smartcam_close")
        assertEquals(1, h.closed)
        assertEquals(0, h.sends)
    }

    private companion object {
        const val TIMEOUT = 5_000L
    }
}
