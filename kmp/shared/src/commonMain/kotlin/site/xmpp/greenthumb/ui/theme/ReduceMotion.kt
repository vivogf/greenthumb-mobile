package site.xmpp.greenthumb.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/** True when the user turned system animations off. Provided by [GreenThumbTheme]. */
val LocalReduceMotion = staticCompositionLocalOf { false }

/** Reads the platform setting; Android follows the developer/accessibility animation scales. */
@Composable
expect fun systemReduceMotion(): Boolean

/** Animator or transition scale 0 means the user wants no animation. */
fun isReduceMotion(animatorDurationScale: Float, transitionAnimationScale: Float): Boolean =
    animatorDurationScale == 0f || transitionAnimationScale == 0f
