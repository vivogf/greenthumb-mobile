package site.xmpp.greenthumb.core.platform

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Size
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * CameraX actual (architecture §10.1): the back camera with [Preview] shown by [CameraXViewfinder] and
 * [ImageCapture] writing `cacheDir/gt-pick/gt-diag-*.jpg` — the same kept-capture files as
 * [pickImageKeepingCapture], so [deleteCaptureFile] and [deleteStaleCaptures] cover them.
 *
 * Preview and capture both use the sensor's 4:3 (3:4 in portrait), and the viewfinder fits the preview into
 * [modifier] without cropping, so the frame on screen is the frame of the shot.
 *
 * Speed of the shot (M11c item 2): the capture asks for about 1600×1200 instead of the full sensor (the app keeps
 * 800 px anyway, so a 12 MP JPEG only costs encode, write and decode time), and uses zero shutter lag where the
 * camera supports it (CameraX itself falls back to a normal capture while the flash is on).
 *
 * [CameraShutter.zoomRatio] is applied after every bind and on every change; the camera's maximum zoom goes out with
 * [CameraStatus.Ready].
 *
 * The camera is bound to the composition's lifecycle owner (the navigation entry): it stops when the app
 * goes to the background and is unbound when the screen leaves composition.
 */
@Composable
actual fun CameraCapture(shutter: CameraShutter, onStatus: (CameraStatus) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val report by rememberUpdatedState(onStatus)
    var granted by remember { mutableStateOf(hasCameraPermission(context)) }
    var surfaceRequest by remember { mutableStateOf<SurfaceRequest?>(null) }
    var boundCamera by remember { mutableStateOf<Camera?>(null) }

    LaunchedEffect(Unit) {
        if (granted) return@LaunchedEffect
        val activity = AppActivityHolder.activity
        val answer = activity != null && requestPermission(activity, Manifest.permission.CAMERA)
        granted = answer || hasCameraPermission(context)
        if (!granted) {
            // Right after a refusal the system shows no rationale only when it will not ask again.
            val permanent = activity == null ||
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
            report(CameraStatus.Denied(permanent))
        }
    }

    // The permission can be granted in the app settings while the camera screen waits in the background.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && !granted && hasCameraPermission(context)) granted = true
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (granted) {
        DisposableEffect(lifecycleOwner) {
            report(CameraStatus.Starting)
            val providerFuture = ProcessCameraProvider.getInstance(context)
            val sensorFrame = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .build()
            val preview = Preview.Builder().setResolutionSelector(sensorFrame).build()
            preview.setSurfaceProvider { request -> surfaceRequest = request }
            var imageCapture: ImageCapture? = null
            val take: suspend (Boolean) -> PickResult = { flash ->
                imageCapture?.let { takePicture(context, it, flash) } ?: PickResult.Cancelled
            }
            var provider: ProcessCameraProvider? = null
            var cameraState: LiveData<CameraState>? = null
            var zoomState: LiveData<ZoomState>? = null
            var hasFlash = false
            var maxZoom = 1f
            var failed = false
            val stateObserver = Observer<CameraState> { state ->
                if (state.error?.type == CameraState.ErrorType.CRITICAL) {
                    failed = true
                    shutter.detach(take)
                    report(CameraStatus.Unavailable)
                }
            }
            val zoomObserver = Observer<ZoomState> { state ->
                if (!failed && state.maxZoomRatio != maxZoom) {
                    maxZoom = state.maxZoomRatio
                    report(CameraStatus.Ready(hasFlash = hasFlash, maxZoom = maxZoom))
                }
            }
            var disposed = false
            providerFuture.addListener(
                {
                    if (disposed) return@addListener
                    try {
                        val cameras = providerFuture.get()
                        provider = cameras
                        if (!cameras.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                            report(CameraStatus.Unavailable)
                            return@addListener
                        }
                        val capture = ImageCapture.Builder()
                            .setCaptureMode(captureModeFor(cameras.getCameraInfo(CameraSelector.DEFAULT_BACK_CAMERA)))
                            .setResolutionSelector(captureSize)
                            .build()
                        imageCapture = capture
                        val camera = cameras.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            capture,
                        )
                        camera.cameraControl.setZoomRatio(shutter.zoomRatio)
                        boundCamera = camera
                        cameraState = camera.cameraInfo.cameraState.also { it.observe(lifecycleOwner, stateObserver) }
                        shutter.attach(take)
                        hasFlash = camera.cameraInfo.hasFlashUnit()
                        maxZoom = camera.cameraInfo.zoomState.value?.maxZoomRatio ?: 1f
                        report(CameraStatus.Ready(hasFlash = hasFlash, maxZoom = maxZoom))
                        zoomState = camera.cameraInfo.zoomState.also { it.observe(lifecycleOwner, zoomObserver) }
                    } catch (_: Exception) {
                        // No camera hardware, CameraX init failure or a bind error: the gallery fallback takes over.
                        report(CameraStatus.Unavailable)
                    }
                },
                ContextCompat.getMainExecutor(context),
            )
            onDispose {
                disposed = true
                cameraState?.removeObserver(stateObserver)
                zoomState?.removeObserver(zoomObserver)
                shutter.detach(take)
                boundCamera = null
                provider?.unbind(preview, *listOfNotNull(imageCapture).toTypedArray())
                preview.setSurfaceProvider(null)
                surfaceRequest = null
            }
        }
    }

    LaunchedEffect(boundCamera) {
        val camera = boundCamera ?: return@LaunchedEffect
        // A superseded ratio fails its future; the next value is already on its way.
        snapshotFlow { shutter.zoomRatio }.collect { ratio -> camera.cameraControl.setZoomRatio(ratio) }
    }

    Box(modifier) {
        surfaceRequest?.let { request ->
            CameraXViewfinder(
                surfaceRequest = request,
                modifier = Modifier.matchParentSize(),
                alignment = Alignment.Center,
                contentScale = ContentScale.Fit,
            )
        }
    }
}

/** The shot: about 1600×1200 in the sensor's 4:3, the closest larger size first so 4× zoom keeps 800 px of detail. */
private val captureSize: ResolutionSelector = ResolutionSelector.Builder()
    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
    .setResolutionStrategy(
        ResolutionStrategy(Size(1600, 1200), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
    )
    .build()

private fun captureModeFor(info: CameraInfo): Int =
    if (info.isZslSupported) ImageCapture.CAPTURE_MODE_ZERO_SHUTTER_LAG else ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY

private fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

/**
 * One shot into a kept capture file. CameraX cannot stop a running capture: when the caller is gone by
 * the time the file is written, the file is deleted here. A camera closed mid-shot (the app went to the
 * background) counts as a cancelled shot, not as an error.
 */
private suspend fun takePicture(context: Context, imageCapture: ImageCapture, flash: Boolean): PickResult {
    imageCapture.flashMode = if (flash) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
    val file = newKeptCaptureFile(context)
    return suspendCancellableCoroutine { continuation ->
        val callback = object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                val bytes = runCatching { file.readBytes() }.getOrNull()
                if (bytes == null || bytes.isEmpty()) {
                    file.delete()
                    if (continuation.isActive) continuation.resume(PickResult.Cancelled)
                    return
                }
                if (!continuation.isActive) {
                    file.delete()
                    return
                }
                // The file belongs to the caller only once the result is delivered; a cancellation in between deletes it.
                continuation.resume(PickResult.Picked(bytes, capturePath = file.absolutePath)) { _, _, _ -> file.delete() }
            }

            override fun onError(exception: ImageCaptureException) {
                file.delete()
                if (!continuation.isActive) return
                if (exception.imageCaptureError == ImageCapture.ERROR_CAMERA_CLOSED) {
                    continuation.resume(PickResult.Cancelled)
                } else {
                    continuation.resumeWith(Result.failure(exception))
                }
            }
        }
        try {
            imageCapture.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), Dispatchers.IO.asExecutor(), callback)
        } catch (failure: RuntimeException) {
            file.delete()
            if (continuation.isActive) continuation.resumeWith(Result.failure(failure))
        }
    }
}

actual fun openAppSettings() {
    val activity = AppActivityHolder.activity ?: return
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.packageName, null))
    runCatching { activity.startActivity(intent) }
}
