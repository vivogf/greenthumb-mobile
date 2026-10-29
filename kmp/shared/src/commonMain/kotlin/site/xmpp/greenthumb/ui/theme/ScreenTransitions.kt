package site.xmpp.greenthumb.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut

/** Enter and exit of one navigation step. Pop uses the same pair. */
data class ScreenTransitions(
    val enter: EnterTransition,
    val exit: ExitTransition,
)

/**
 * Fade only: a slide would uncover the window at the screen edge. Under reduce motion the screen
 * changes in one frame.
 */
fun screenTransitions(reduceMotion: Boolean): ScreenTransitions =
    if (reduceMotion) {
        ScreenTransitions(EnterTransition.None, ExitTransition.None)
    } else {
        ScreenTransitions(
            enter = fadeIn(tween(Motion.ScreenEnterMs)),
            exit = fadeOut(tween(Motion.ScreenExitMs)),
        )
    }
