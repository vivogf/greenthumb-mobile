package site.xmpp.greenthumb.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertTrue
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import site.xmpp.greenthumb.core.platform.AppEnvironment
import site.xmpp.greenthumb.ui.res.Res
import site.xmpp.greenthumb.ui.res.common_cancel
import site.xmpp.greenthumb.ui.res.plantDetails_cancel
import site.xmpp.greenthumb.ui.res.plantDetails_careSettings
import site.xmpp.greenthumb.ui.res.plantDetails_save
import site.xmpp.greenthumb.ui.res.species_choose
import site.xmpp.greenthumb.ui.theme.GreenThumbTheme

/**
 * VAL-APP-016: on a 360dp-wide window, in RU and EN at font scale 1.0 and 1.3, the
 * [GtModalHeader] of the care-settings modal (Cancel · title · Save) and of the species
 * picker (title · Cancel) keeps every action label on one unclipped line, keeps the title
 * on one line and never lets the title overlap an action.
 */
@OptIn(ExperimentalTestApi::class)
class GtModalHeaderLayoutTest {

    private class HeaderSpec(
        val name: String,
        val title: StringResource,
        val leading: StringResource?,
        val trailing: StringResource?,
    )

    private val careSettings = HeaderSpec(
        "care settings", Res.string.plantDetails_careSettings, Res.string.plantDetails_cancel, Res.string.plantDetails_save,
    )
    private val speciesPicker = HeaderSpec("species picker", Res.string.species_choose, null, Res.string.common_cancel)

    private fun ComposeUiTest.bounds(tag: String): DpRect =
        onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()

    private fun ComposeUiTest.textLayout(text: String): TextLayoutResult {
        val node = onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.single()
    }

    private fun DpRect.intersects(other: DpRect): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom

    private fun checkHeader(spec: HeaderSpec, overrideTitle: String? = null) {
        val original = Locale.getDefault()
        try {
            runDesktopComposeUiTest(width = 360, height = 800) {
                var language by mutableStateOf("ru")
                var fontScale by mutableFloatStateOf(1f)
                var labels: Map<String, String> = emptyMap()
                setContent {
                    AppEnvironment(customAppLocale = language) {
                        CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                            GreenThumbTheme(darkTheme = false) {
                                GtModal(onDismissRequest = {}) {
                                    CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                                        val title = overrideTitle ?: stringResource(spec.title)
                                        val leading = spec.leading?.let { stringResource(it) }
                                        val trailing = spec.trailing?.let { stringResource(it) }
                                        labels = buildMap {
                                            put(GtModalHeaderTitleTag, title)
                                            leading?.let { put(GtModalHeaderLeadingTag, it) }
                                            trailing?.let { put(GtModalHeaderTrailingTag, it) }
                                        }
                                        GtModalHeader(
                                            title = title,
                                            leading = leading?.let { GtModalHeaderAction(it, Color.Gray) {} },
                                            trailing = trailing?.let { GtModalHeaderAction(it, Color.Green) {} },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                val failures = mutableListOf<String>()
                fun check(ok: Boolean, message: () -> String) {
                    if (!ok) failures += message()
                }
                for (lang in listOf("ru", "en")) {
                    for (scale in listOf(1f, 1.3f)) {
                        language = lang
                        fontScale = scale
                        waitForIdle()
                        val where = "${spec.name}, $lang, font scale $scale"
                        val titleBounds = bounds(GtModalHeaderTitleTag)
                        val titleLayout = textLayout(labels.getValue(GtModalHeaderTitleTag))
                        check(titleLayout.lineCount == 1) { "$where: title has ${titleLayout.lineCount} lines" }
                        for (tag in listOf(GtModalHeaderLeadingTag, GtModalHeaderTrailingTag)) {
                            val label = labels[tag] ?: continue
                            val layout = textLayout(label)
                            check(layout.lineCount == 1) { "$where: «$label» has ${layout.lineCount} lines" }
                            check(layout.getLineRight(0) <= layout.size.width + 0.5f) {
                                "$where: «$label» is clipped (text ${layout.getLineRight(0)}px, box ${layout.size.width}px)"
                            }
                            val actionBounds = bounds(tag)
                            check(!titleBounds.intersects(actionBounds)) {
                                "$where: title $titleBounds overlaps «$label» $actionBounds"
                            }
                            check(actionBounds.height >= 48.dp && actionBounds.width >= 48.dp) {
                                "$where: «$label» touch target $actionBounds is smaller than 48dp"
                            }
                        }
                    }
                }
                assertTrue(failures.isEmpty(), failures.joinToString("\n"))
            }
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun careSettingsHeaderActionsStayOnOneLineClearOfTheTitle() = checkHeader(careSettings)

    @Test
    fun speciesPickerHeaderActionStaysOnOneLineClearOfTheTitle() = checkHeader(speciesPicker)

    @Test
    fun longTitleIsEllipsizedInsteadOfPushingTheActions() =
        checkHeader(careSettings, overrideTitle = "Настройки ухода за очень длинным названием растения")
}
