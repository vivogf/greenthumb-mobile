package site.xmpp.greenthumb.core.platform

import androidx.compose.runtime.Composable

/**
 * Takes the system Back press while [enabled] and calls [onBack] instead. When disabled, Back goes on to
 * the next handler (the navigation graph, then the activity).
 */
@Composable
public expect fun SystemBackHandler(enabled: Boolean, onBack: () -> Unit)
