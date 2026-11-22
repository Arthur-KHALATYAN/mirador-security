package com.mirador.app

import android.app.AppOpsManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process

/**
 * Journal des accès au micro et à la caméra (par n'importe quelle appli).
 * Alerte immédiatement si le micro ou la caméra s'active écran éteint.
 */
class SensorMonitorService : Service() {

    companion object {
        private const val NOTIF_ID = 9001

        fun start(c: Context) {
            runCatching { c.startForegroundService(Intent(c, SensorMonitorService::class.java)) }
        }

        fun stop(c: Context) {
            c.stopService(Intent(c, SensorMonitorService::class.java))
        }

        fun hasUsageAccess(c: Context): Boolean {
            val ops = c.getSystemService(AppOpsManager::class.java) ?: return false
            val mode = if (Build.VERSION.SDK_INT >= 29) {
                ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
            } else {
                @Suppress("DEPRECATION")
                ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
            }
            return mode == AppOpsManager.MODE_ALLOWED
        }

        private val FOREGROUND_EVENT: Int =
            if (Build.VERSION.SDK_INT >= 29) UsageEvents.Event.ACTIVITY_RESUMED
            else @Suppress("DEPRECATION") UsageEvents.Event.MOVE_TO_FOREGROUND

        /** Dernière appli passée au premier plan (nécessite l'accès aux données d'utilisation). */
        fun foregroundApp(c: Context): String? {
            val usm = c.getSystemService(UsageStatsManager::class.java) ?: return null
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - 30 * 60 * 1000L, now) ?: return null
            val e = UsageEvents.Event()
            var last: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.eventType == FOREGROUND_EVENT) last = e.packageName
            }
            return last
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var cameraCb: CameraManager.AvailabilityCallback? = null
    private var audioCb: AudioManager.AudioRecordingCallback? = null
    private val camerasInUse = mutableSetOf<String>()
    private var micActive = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = Notifier.serviceNotification(this, "Surveillance du micro et de la caméra active", Intent(this, SensorLogActivity::class.java))
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
        register()
        return START_STICKY
    }

    private fun register() {
        if (cameraCb == null) {
            val cb = object : CameraManager.AvailabilityCallback() {
                override fun onCameraUnavailable(cameraId: String) {
                    if (camerasInUse.isEmpty()) log("Caméra", true)
                    camerasInUse.add(cameraId)
                }

                override fun onCameraAvailable(cameraId: String) {
                    if (camerasInUse.remove(cameraId) && camerasInUse.isEmpty()) log("Caméra", false)
                }
            }
            getSystemService(CameraManager::class.java)?.registerAvailabilityCallback(cb, main)
            cameraCb = cb
        }
        if (audioCb == null) {
            val am = getSystemService(AudioManager::class.java) ?: return
            val cb = object : AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
                    val active = !configs.isNullOrEmpty()
                    if (active != micActive) {
                        micActive = active
                        log("Micro", active)
                    }
                }
            }
            micActive = am.activeRecordingConfigurations.isNotEmpty()
            am.registerAudioRecordingCallback(cb, main)
            audioCb = cb
        }
    }

    private fun log(type: String, start: Boolean) {
        val screenOff = getSystemService(PowerManager::class.java)?.isInteractive == false
        val pkg = runCatching { foregroundApp(this) }.getOrNull()
        val app = pkg?.let { AppScanner.label(packageManager, it) }
        Store.addSensorEvent(this, SensorEvent(System.currentTimeMillis(), type, start, app, screenOff))
        if (start && screenOff) {
            Notifier.alert(
                this, type.hashCode(),
                "⚠️ $type activé écran éteint !",
                "Une appli utilise le ${type.lowercase()} alors que le téléphone est verrouillé. Dernière appli active : ${app ?: "inconnue"}. Touchez pour voir le journal.",
                Intent(this, SensorLogActivity::class.java),
            )
        }
    }

    override fun onDestroy() {
        cameraCb?.let { getSystemService(CameraManager::class.java)?.unregisterAvailabilityCallback(it) }
        audioCb?.let { getSystemService(AudioManager::class.java)?.unregisterAudioRecordingCallback(it) }
        cameraCb = null
        audioCb = null
        super.onDestroy()
    }
}
