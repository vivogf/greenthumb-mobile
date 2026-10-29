package site.xmpp.greenthumb.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Material3 type scale of the app (D§3.2), passed to `MaterialTheme` by [GreenThumbTheme].
 * No style is below 13 sp (TypeScaleTest): the M3 defaults for bodySmall (12) and the label styles
 * (12/11) are raised. Display, headlineLarge and titleLarge keep the M3 defaults.
 * Font: system Roboto (the mockup's ui-rounded is not shipped).
 */
val GtTypeScale: Typography = Typography().let { m3 ->
    Typography(
        displayLarge = m3.displayLarge,
        displayMedium = m3.displayMedium,
        displaySmall = m3.displaySmall,
        headlineLarge = m3.headlineLarge,
        headlineMedium = m3.headlineMedium.sized(28.sp, 34.sp),
        headlineSmall = m3.headlineSmall.sized(24.sp, 30.sp),
        titleLarge = m3.titleLarge,
        titleMedium = m3.titleMedium.sized(18.sp, 24.sp),
        titleSmall = m3.titleSmall.sized(16.sp, 22.sp, FontWeight.Medium),
        bodyLarge = m3.bodyLarge.sized(16.sp, 24.sp),
        bodyMedium = m3.bodyMedium.sized(15.sp, 22.sp),
        bodySmall = m3.bodySmall.sized(13.sp, 18.sp),
        labelLarge = m3.labelLarge.sized(15.sp, 20.sp, FontWeight.Medium),
        labelMedium = m3.labelMedium.sized(13.sp, 18.sp),
        labelSmall = m3.labelSmall.sized(13.sp, 18.sp),
    )
}

private fun TextStyle.sized(size: TextUnit, line: TextUnit, weight: FontWeight? = null): TextStyle =
    copy(fontSize = size, lineHeight = line, fontWeight = weight ?: fontWeight)

/** Text tokens outside the M3 scale. */
object GtType {
    /** Login privacy hint: owner exception to the 13 sp hint size (D§1 item 2). */
    val loginHint = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)
}

/**
 * Заголовочные стили для экранов, где Material3-дефолт (вес 400) читается
 * плоско. Стиль берёт только тот экран, который просит его явно.
 * Размеры — из RN `app/add-plant.tsx` (заголовок 18/700, секции 15/700),
 * заголовок экрана укрупнён для плотности телефона.
 */
object GtTypography {
    val screenTitle = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold)
    val sectionTitle = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold)
    val fieldLabel = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
}
