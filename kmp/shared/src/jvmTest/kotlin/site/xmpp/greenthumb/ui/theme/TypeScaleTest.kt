package site.xmpp.greenthumb.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** VAL-LOOK-007 / D§3.2: no text style of the app is smaller than 13 sp. */
class TypeScaleTest {

    @Test
    fun noStyleBelow13sp() {
        val report = StringBuilder()
        for ((name, style) in materialStyles(GtTypeScale) + customStyles()) {
            report.append("$name=${style.fontSize} ")
            assertTrue(style.fontSize.isSp, "$name has no sp font size")
            assertTrue(style.fontSize.value >= 13f, "$name is ${style.fontSize}, below 13 sp")
        }
        println("TypeScaleTest sizes: $report")
    }

    @Test
    fun all_fifteen_material_styles_are_checked() {
        assertEquals(15, materialStyles(GtTypeScale).size)
    }

    @Test
    fun scale_matches_design_table() {
        assertStyle(GtTypeScale.headlineMedium, 28.sp, 34.sp)
        assertStyle(GtTypeScale.headlineSmall, 24.sp, 30.sp)
        assertStyle(GtTypeScale.titleMedium, 18.sp, 24.sp)
        assertStyle(GtTypeScale.titleSmall, 16.sp, 22.sp, FontWeight.Medium)
        assertStyle(GtTypeScale.bodyLarge, 16.sp, 24.sp)
        assertStyle(GtTypeScale.bodyMedium, 15.sp, 22.sp)
        assertStyle(GtTypeScale.labelLarge, 15.sp, 20.sp, FontWeight.Medium)
        assertStyle(GtTypeScale.bodySmall, 13.sp, 18.sp)
        assertStyle(GtTypeScale.labelMedium, 13.sp, 18.sp)
        assertStyle(GtTypeScale.labelSmall, 13.sp, 18.sp)
        assertStyle(GtType.loginHint, 14.sp, 20.sp)
    }

    @Test
    fun theme_passes_the_type_scale_to_material() {
        var seen: Typography? = null
        compose {
            GreenThumbTheme(darkTheme = true) { seen = MaterialTheme.typography }
        }
        assertSame(GtTypeScale, assertNotNull(seen, "typography not composed"))
    }
}

private fun assertStyle(style: TextStyle, size: TextUnit, line: TextUnit, weight: FontWeight? = null) {
    assertEquals(size, style.fontSize)
    assertEquals(line, style.lineHeight)
    if (weight != null) assertEquals(weight, style.fontWeight)
}

private fun materialStyles(t: Typography): List<Pair<String, TextStyle>> = listOf(
    "displayLarge" to t.displayLarge,
    "displayMedium" to t.displayMedium,
    "displaySmall" to t.displaySmall,
    "headlineLarge" to t.headlineLarge,
    "headlineMedium" to t.headlineMedium,
    "headlineSmall" to t.headlineSmall,
    "titleLarge" to t.titleLarge,
    "titleMedium" to t.titleMedium,
    "titleSmall" to t.titleSmall,
    "bodyLarge" to t.bodyLarge,
    "bodyMedium" to t.bodyMedium,
    "bodySmall" to t.bodySmall,
    "labelLarge" to t.labelLarge,
    "labelMedium" to t.labelMedium,
    "labelSmall" to t.labelSmall,
)

private fun customStyles(): List<Pair<String, TextStyle>> = listOf(
    "GtTypography.screenTitle" to GtTypography.screenTitle,
    "GtTypography.sectionTitle" to GtTypography.sectionTitle,
    "GtTypography.fieldLabel" to GtTypography.fieldLabel,
    "GtType.loginHint" to GtType.loginHint,
)

private class TypeApplier : AbstractApplier<Unit>(Unit) {
    override fun insertBottomUp(index: Int, instance: Unit) = Unit
    override fun insertTopDown(index: Int, instance: Unit) = Unit
    override fun move(from: Int, to: Int, count: Int) = Unit
    override fun remove(index: Int, count: Int) = Unit
    override fun onClear() = Unit
}

private fun compose(content: @Composable () -> Unit) = runBlocking {
    withTimeout(5_000) {
        val recomposer = Recomposer(coroutineContext)
        val composition = Composition(TypeApplier(), recomposer)
        val job = launch { recomposer.runRecomposeAndApplyChanges() }
        try {
            composition.setContent(content)
        } finally {
            composition.dispose()
            recomposer.cancel()
            job.cancel()
        }
    }
}
