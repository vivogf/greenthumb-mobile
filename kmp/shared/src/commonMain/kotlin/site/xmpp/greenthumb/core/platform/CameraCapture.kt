package site.xmpp.greenthumb.core.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** State of the in-app camera as the platform reports it to the smart camera. */
sealed interface CameraStatus {
    /** Waiting for the permission answer or for the camera to bind. */
    data object Starting : CameraStatus

    /** The preview runs and the shutter works; [hasFlash] is false on devices without a flash unit. */
    data class Ready(val hasFlash: Boolean) : CameraStatus

    /** The camera permission was refused; [permanent] when the system no longer shows its dialog. */
    data class Denied(val permanent: Boolean) : CameraStatus

    /** No camera, or the camera could not start: the screen falls back to the gallery and the system camera. */
    data object Unavailable : CameraStatus
}

/**
 * The shutter of a running [CameraCapture]. The platform attaches the function that takes a photo while
 * the camera is bound and detaches it when the camera is released; without a camera [take] returns
 * [PickResult.Cancelled].
 */
class CameraShutter {
    private var taker: (suspend (flash: Boolean) -> PickResult)? = null

    fun attach(take: suspend (flash: Boolean) -> PickResult) {
        taker = take
    }

    fun detach(take: suspend (flash: Boolean) -> PickResult) {
        if (taker === take) taker = null
    }

    /**
     * One photo with the flash on or off. A camera result carries [PickResult.Picked.capturePath] like
     * [pickImageKeepingCapture]: the caller owns the file and deletes it with [deleteCaptureFile].
     */
    suspend fun take(flash: Boolean): PickResult = taker?.invoke(flash) ?: PickResult.Cancelled
}

/**
 * Live camera preview filling [modifier]. It asks for the camera permission when it appears, binds the
 * back camera while it is in composition and its lifecycle is started, attaches the shot to [shutter],
 * and reports every state change through [onStatus]. Leaving composition releases the camera.
 *
 * androidMain — CameraX (`Preview` + `ImageCapture`); jvmMain — no camera, always [CameraStatus.Unavailable].
 */
@Composable
expect fun CameraCapture(shutter: CameraShutter, onStatus: (CameraStatus) -> Unit, modifier: Modifier)

/** Opens the system settings page of this app (camera permission after a permanent denial). */
expect fun openAppSettings()
