package site.xmpp.greenthumb.ui.screens.smartcam

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import site.xmpp.greenthumb.core.network.SpeciesCandidateDto
import site.xmpp.greenthumb.core.platform.PickResult
import site.xmpp.greenthumb.core.platform.paintedJpeg
import site.xmpp.greenthumb.ui.screens.addplant.AddPlantDraft
import site.xmpp.greenthumb.ui.screens.species.SpeciesIdentifier
import site.xmpp.greenthumb.ui.theme.GreenThumbTheme
import site.xmpp.greenthumb.ui.theme.Sizes

/** VAL-CAM-003/006/008 on screen: modes and hints without requests, the plant result, the square draft photo. */
@OptIn(ExperimentalTestApi::class)
class SmartCameraScreenTest {

    private fun textOf(test: androidx.compose.ui.test.ComposeUiTest, tag: String): String =
        test.onNode(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.Text)?.joinToString { it.text }.orEmpty()

    @Test
    fun modesSwitchTheHintAndTheResultHandsASquarePhotoToAddPlant() = runDesktopComposeUiTest {
        var identifyCalls = 0
        var closed = 0
        var draft: AddPlantDraft? = null
        val identifier = SpeciesIdentifier { _, _ ->
            identifyCalls++
            listOf(
                SpeciesCandidateDto("Monstera deliciosa", listOf("Swiss cheese plant"), 0.83),
                SpeciesCandidateDto("Philodendron bipinnatifidum", emptyList(), 0.31),
                SpeciesCandidateDto("Epipremnum aureum", emptyList(), 0.04),
                SpeciesCandidateDto("Rhaphidophora tetrasperma", emptyList(), 0.01),
            )
        }
        setContent {
            GreenThumbTheme(darkTheme = false) {
                SmartCameraScreen(
                    initialMode = SmartCameraMode.Plant,
                    identifier = identifier,
                    diagnoser = null,
                    onClose = { closed++ },
                    onAddToMine = { draft = it },
                    pick = { PickResult.Picked(paintedJpeg(400, 200)) },
                )
            }
        }
        val plantHint = textOf(this, "smartcam_hint")
        assertTrue(plantHint.contains("Pl@ntNet"), plantHint)
        onNode(hasTestTag("smartcam_mode_plant")).assertHeightIsAtLeast(Sizes.touch)
        onNode(hasTestTag("smartcam_mode_disease")).performClick()
        val diseaseHint = textOf(this, "smartcam_hint")
        assertNotEquals(plantHint, diseaseHint)
        assertTrue(diseaseHint.contains("OpenAI"), diseaseHint)
        onNode(hasTestTag("smartcam_camera")).assertExists()
        onNode(hasTestTag("smartcam_gallery")).assertExists()
        onNode(hasTestTag("smartcam_mode_plant")).performClick()
        waitForIdle()
        assertEquals(0, identifyCalls)

        onNode(hasTestTag("smartcam_gallery")).performClick()
        waitUntilAtLeastOneExists(hasTestTag("smartcam_candidate_0"), 5_000)
        assertEquals(1, identifyCalls)
        onNode(hasTestTag("smartcam_candidate_2")).assertExists()
        onNode(hasTestTag("smartcam_candidate_3")).assertDoesNotExist()
        onNode(hasTestTag("identify_poweredBy"), useUnmergedTree = true).assertExists()
        assertEquals(3, onAllNodesWithTag("smartcam_dotFilled_0", useUnmergedTree = true).fetchSemanticsNodes().size)
        val texts = onAllNodesWithTag("smartcam_screen").fetchSemanticsNodes().flatMap { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        } + listOf(0, 1, 2).map { textOf(this, "smartcam_similarity_$it") }
        assertFalse(texts.any { it.contains('%') || Regex("0[.,]\\d").containsMatchIn(it) }, texts.toString())

        onNode(hasTestTag("smartcam_addToMine")).performScrollTo().performClick()
        val handed = assertNotNull(draft)
        assertEquals("Monstera deliciosa", handed.candidate.scientificName)
        val image = ImageIO.read(ByteArrayInputStream(handed.photoJpeg))
        assertEquals(image.width, image.height, "the cover is centre-cropped to a square")

        onNode(hasTestTag("smartcam_retake")).performScrollTo().performClick()
        waitUntilAtLeastOneExists(hasTestTag("smartcam_gallery"), 5_000)
        assertTrue(textOf(this, "smartcam_hint").contains("Pl@ntNet"))
        assertEquals(1, identifyCalls)
        assertEquals(0, closed)
    }
}
