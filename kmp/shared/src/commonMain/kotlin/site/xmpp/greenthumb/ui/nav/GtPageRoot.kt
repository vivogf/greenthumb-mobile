package site.xmpp.greenthumb.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import site.xmpp.greenthumb.ui.theme.greenThumbExtendedColors

/**
 * Full-size page-coloured container behind the navigation host. During a fade both screens are
 * partly transparent, and a route that is still loading draws nothing: both show this colour.
 */
@Composable
public fun GtPageRoot(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(greenThumbExtendedColors().page),
    ) {
        content()
    }
}
