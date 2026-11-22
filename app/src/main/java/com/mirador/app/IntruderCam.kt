package com.mirador.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Photo discrète avec la caméra avant quand un mauvais code est saisi. */
object IntruderCam {

    @Volatile private var busy = false

    fun dir(c: Context) = File(c.filesDir, "intrus").apply { mkdirs() }

    fun photos(c: Context): List<File> =
        dir(c).listFiles { f -> f.name.endsWith(".jpg") }?.sortedByDescending { it.lastModified() }.orEmpty()

    fun hasCamera(c: Context) = c.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun capture(c: Context) {
        if (busy || !hasCamera(c)) return
        busy = true
        val app = c.applicationContext
        val thread = HandlerThread("intrus").apply { start() }
        val h = Handler(thread.looper)
        var device: CameraDevice? = null
        var reader: ImageReader? = null
        fun finish() {
            runCatching { device?.close() }
            runCatching { reader?.close() }
            thread.quitSafely()
            busy = false
        }
        runCatching {
            val cm = app.getSystemService(CameraManager::class.java)
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            } ?: cm.cameraIdList.first()
            val r = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 2)
            reader = r
            var frames = 0
            r.setOnImageAvailableListener({ ir ->
                val img = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
                frames++
                // Les premières images servent à régler l'exposition
                if (frames == 6) {
                    val buf = img.planes[0].buffer
                    val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
                    val name = "intrus_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.FRANCE).format(Date()) + ".jpg"
                    runCatching { File(dir(app), name).writeBytes(bytes) }
                    Store.addLog(app, "intrusions", "📷 Photo de l'intrus enregistrée")
                    img.close()
                    finish()
                } else {
                    img.close()
                }
            }, h)
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    device = cam
                    @Suppress("DEPRECATION")
                    cam.createCaptureSession(listOf(r.surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            runCatching {
                                val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                    addTarget(r.surface)
                                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                }.build()
                                s.setRepeatingRequest(req, null, h)
                            }.onFailure { finish() }
                        }

                        override fun onConfigureFailed(s: CameraCaptureSession) = finish()
                    }, h)
                }

                override fun onDisconnected(cam: CameraDevice) = finish()
                override fun onError(cam: CameraDevice, error: Int) = finish()
            }, h)
            // Sécurité : on libère la caméra quoi qu'il arrive
            h.postDelayed({ if (busy) finish() }, 6000)
        }.onFailure { finish() }
    }
}
