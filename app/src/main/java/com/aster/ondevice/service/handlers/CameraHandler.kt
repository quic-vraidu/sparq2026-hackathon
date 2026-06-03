package com.aster.ondevice.service.handlers

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.aster.ondevice.data.model.Command
import com.aster.ondevice.data.model.CommandResult
import com.aster.ondevice.service.CommandHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

private const val TAG = "CameraHandler"

@Singleton
class CameraHandler @Inject constructor(
    @ApplicationContext private val context: Context
) : CommandHandler {

    override fun supportedActions() = listOf("take_photo", "record_video")

    override suspend fun handle(command: Command): CommandResult = when (command.action) {
        "take_photo"   -> takePhoto(command)
        "record_video" -> CommandResult.ok(mapOf("note" to "Use take_photo for Phase 1 on-device; video recording requires CameraX in service lifecycle"))
        else           -> CommandResult.err("Unknown: ${command.action}")
    }

    private suspend fun takePhoto(cmd: Command): CommandResult = suspendCancellableCoroutine { cont ->
        val cameraFace = cmd.params["camera"]?.jsonPrimitive?.content ?: "back"
        val quality    = cmd.params["quality"]?.jsonPrimitive?.content?.toIntOrNull() ?: 75
        val executor   = Executors.newSingleThreadExecutor()

        val selector = if (cameraFace == "front")
            CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setJpegQuality(quality)
            .build()

        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                // LifecycleRegistry.currentState must be set on the main thread.
                // This addListener callback runs on the main executor, so it's safe here.
                val lifecycleOwner = object : LifecycleOwner {
                    val registry = LifecycleRegistry(this)
                    override val lifecycle: Lifecycle get() = registry
                }.also { it.registry.currentState = Lifecycle.State.STARTED }

                val provider = providerFuture.get()
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, selector, capture)
                val file = File(context.getExternalFilesDir("photos"), "photo_${System.currentTimeMillis()}.jpg")
                val opts = ImageCapture.OutputFileOptions.Builder(file).build()
                capture.takePicture(opts, executor, object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(out: ImageCapture.OutputFileResults) {
                        provider.unbindAll()
                        executor.shutdown()
                        val bytes = file.readBytes()
                        val b64   = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        cont.resume(CommandResult.ok(mapOf(
                            "path"   to file.absolutePath,
                            "base64" to b64,
                            "size"   to bytes.size
                        )))
                    }
                    override fun onError(e: ImageCaptureException) {
                        provider.unbindAll()
                        executor.shutdown()
                        cont.resume(CommandResult.err("Photo capture failed: ${e.message}"))
                    }
                })
            } catch (e: Exception) {
                executor.shutdown()
                cont.resume(CommandResult.err("Camera error: ${e.message}"))
            }
        }, ContextCompat.getMainExecutor(context))
    }
}
