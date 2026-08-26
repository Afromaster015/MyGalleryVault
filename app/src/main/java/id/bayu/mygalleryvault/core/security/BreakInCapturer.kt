@file:Suppress("DEPRECATION")

package id.bayu.mygalleryvault.core.security

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.os.Handler
import android.os.HandlerThread
import androidx.core.content.ContextCompat

/**
 * Front-camera snapshot for the break-in alert (PRD §28).
 *
 * Best-effort and failure-tolerant: any error (permission missing, camera busy,
 * hardware quirks) results in a null result so the security event is still
 * recorded without a photo instead of crashing.
 */
object BreakInCapturer {

    const val PERMISSION_CAMERA = android.Manifest.permission.CAMERA

    fun hasCameraPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, PERMISSION_CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    /** Runs on a background thread; invokes [onResult] with JPEG bytes or null. */
    @SuppressLint("MissingPermission")
    fun captureFrontPhoto(activity: Activity, onResult: (ByteArray?) -> Unit) {
        if (!hasCameraPermission(activity)) {
            onResult(null)
            return
        }
        val thread = HandlerThread("sv_breakin_cap")
        thread.start()
        val handler = Handler(thread.looper)
        handler.post {
            var camera: Camera? = null
            try {
                val frontId = findFrontCameraId()
                    ?: run { onResult(null); return@post }
                camera = Camera.open(frontId)
                val params = camera.parameters
                params.jpegQuality = 70
                try {
                    camera.parameters = params
                } catch (_: Exception) {
                }
                // Most devices require an active preview surface before takePicture;
                // a throwaway SurfaceTexture is enough for JPEG-only capture.
                val texture = SurfaceTexture(0)
                texture.setDefaultBufferSize(320, 240)
                camera.setPreviewTexture(texture)
                camera.startPreview()
                camera.takePicture(null, null, null) { data, _ ->
                    handler.post {
                        runCatching {
                            camera.stopPreview()
                            camera.release()
                        }
                        quitThread(thread)
                        onResult(data)
                    }
                }
            } catch (_: Exception) {
                runCatching { camera?.release() }
                quitThread(thread)
                onResult(null)
            }
        }
    }

    private fun quitThread(thread: HandlerThread) {
        try {
            thread.quitSafely()
        } catch (_: Exception) {
        }
    }

    private fun findFrontCameraId(): Int? {
        val info = Camera.CameraInfo()
        for (i in 0 until Camera.getNumberOfCameras()) {
            Camera.getCameraInfo(i, info)
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) return i
        }
        return null
    }
}
