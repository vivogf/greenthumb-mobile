package site.xmpp.greenthumb.core.platform

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier

/** The desktop harness has no camera: the smart camera shows its gallery fallback. */
@Composable
actual fun CameraCapture(shutter: CameraShutter, onStatus: (CameraStatus) -> Unit, modifier: Modifier) {
    LaunchedEffect(Unit) { onStatus(CameraStatus.Unavailable) }
    Box(modifier)
}

actual fun openAppSettings() = Unit
