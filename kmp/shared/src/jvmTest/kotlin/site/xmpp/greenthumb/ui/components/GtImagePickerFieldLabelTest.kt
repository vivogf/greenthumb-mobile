package site.xmpp.greenthumb.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.width
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.jetbrains.compose.resources.stringResource
import site.xmpp.greenthumb.core.platform.AppEnvironment
import site.xmpp.greenthumb.ui.res.Res
import site.xmpp.greenthumb.ui.res.addPlant_addPhoto
import site.xmpp.greenthumb.ui.theme.GreenThumbTheme

/**
 * VAL-LOOK-018 (jvm part): the empty photo box shows «Добавить фото» / "Add photo" on one unclipped,
 * centred line in ru and en at font scale 1.0 and 1.3 on a 360 dp window.
 */
@OptIn(ExperimentalTestApi::class)
class GtImagePickerFieldLabelTest {

    @Test
    fun add_photo_label_is_one_centred_line() = runDesktopComposeUiTest(width = 360, height = 640) {
        var language by mutableStateOf("ru")
        var fontScale by mutableFloatStateOf(1f)
        var label = ""
        setContent {
            AppEnvironment(customAppLocale = language) {
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                    GreenThumbTheme(darkTheme = true) {
                        label = stringResource(Res.string.addPlant_addPhoto)
                        GtImagePickerField(
                            hasImage = false,
                            label = label,
                            sourceTitle = "Source",
                            cameraLabel = "Camera",
                            galleryLabel = "Gallery",
                            removeLabel = "Remove",
                            cancelLabel = "Cancel",
                            onPickRequested = {},
                            onRemove = {},
                        )
                    }
                }
            }
        }
        for (lang in listOf("ru", "en")) for (scale in listOf(1f, 1.3f)) {
            language = lang
            fontScale = scale
            waitForIdle()
            val case = "$lang @ $scale"
            val textNode = onNodeWithText(label, useUnmergedTree = true)
            val results = mutableListOf<TextLayoutResult>()
            textNode.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
            val layout = results.single()
            assertEquals(1, layout.lineCount, "one line: $case")
            assertFalse(layout.isLineEllipsized(0), "no ellipsis: $case")
            val t = textNode.getUnclippedBoundsInRoot()
            val glyphWidth = layout.getLineRight(0) - layout.getLineLeft(0)
            assertTrue(glyphWidth <= t.width.value + 0.5f, "line fits the text node, not clipped: $case ($glyphWidth > ${t.width})")
            val box = onNodeWithContentDescription(label).getUnclippedBoundsInRoot()
            assertEquals(((box.left + box.right) / 2).value, ((t.left + t.right) / 2).value, 1f, "centred: $case")
            assertFalse(t.left < box.left || t.right > box.right, "label inside the box: $case")
        }
    }
}
