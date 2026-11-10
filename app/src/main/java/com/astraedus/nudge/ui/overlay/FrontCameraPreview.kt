package com.astraedus.nudge.ui.overlay

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * The front camera, mirrored like a selfie, full-bleed. The ONE place Nudge talks to CameraX for a
 * picture: the MIRROR block's camera body and the "camera on block screens" backdrop
 * ([CameraBackdrop]) both draw this, so they open and close the camera by the same rules.
 *
 * It binds to its OWN lifecycle owner that follows the screen's but is only RESUMED while [open]
 * ([mirrorCameraLifecycleState]). So the camera closes the moment [open] goes false, the user walks
 * away, the redirect picker opens (it caps the screen at STARTED) or the overlay pauses, and this
 * composable leaving (a fallback, a new block, the overlay finishing) destroys the owner and
 * unbinds the preview.
 *
 * Every way the camera can fail reports through [onCameraFailed]: the provider not starting, no
 * front camera, binding throwing, or the camera reporting an error (in use by another app,
 * disabled by policy, a device error). A black feed is NOT a failure.
 *
 * Privacy: the `Preview` use case only. No ImageCapture, no ImageAnalysis, no recording; frames go
 * from the camera to this view's surface and nowhere else.
 */
@Composable
internal fun FrontCameraPreview(open: Boolean, onCameraFailed: (reason: String) -> Unit) {
    val context = LocalContext.current
    val parent = LocalLifecycleOwner.current
    val cameraOwner = remember(parent) { GatedLifecycleOwner(parent, ::mirrorCameraLifecycleState) }
    SideEffect { cameraOwner.flag = open }
    DisposableEffect(cameraOwner) {
        cameraOwner.attach()
        onDispose { cameraOwner.detach() }
    }

    val currentOnFailed by rememberUpdatedState(onCameraFailed)
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            // TextureView-backed: it blends with the scrims and the controls drawn over it like
            // any other view, where a SurfaceView punches a hole under the window.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    DisposableEffect(cameraOwner, previewView) {
        val preview = Preview.Builder().build()
        var provider: ProcessCameraProvider? = null
        var disposed = false
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            if (disposed) return@addListener
            val cameraProvider = try {
                providerFuture.get()
            } catch (e: Exception) {
                currentOnFailed("camera provider failed: ${e.javaClass.simpleName}")
                return@addListener
            }
            val selector = CameraSelector.DEFAULT_FRONT_CAMERA
            if (!runCatching { cameraProvider.hasCamera(selector) }.getOrDefault(false)) {
                currentOnFailed("no front camera")
                return@addListener
            }
            preview.setSurfaceProvider(previewView.surfaceProvider)
            val camera = try {
                cameraProvider.bindToLifecycle(cameraOwner, selector, preview)
            } catch (e: RuntimeException) {
                // IllegalStateException / IllegalArgumentException: the camera cannot take the
                // use case right now (busy, unsupported). Fall back rather than show black.
                currentOnFailed("bind failed: ${e.javaClass.simpleName}")
                return@addListener
            }
            provider = cameraProvider
            camera.cameraInfo.cameraState.observe(cameraOwner) { state ->
                val error = state.error
                if (MirrorFallback.isFailure(error?.code)) currentOnFailed("camera error ${error?.code}")
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            disposed = true
            // Only OUR use case: never unbindAll, which would reach into any other camera user.
            provider?.unbind(preview)
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = Modifier.fillMaxSize().background(Color.Black)
    )
}

/**
 * What a screen knows about the camera before trying it, read once per block. Revoking a runtime
 * permission kills the process, so neither can change under a live overlay.
 */
internal fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

internal fun hasFrontCameraFeature(context: Context): Boolean =
    context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT)
