package com.nikhil.beatflash

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class FlashBeatService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var flashJob: Job? = null
    private lateinit var cameraManager: CameraManager
    private var backCameraId: String? = null

    companion object {
        const val ACTION_START = "com.nikhil.beatflash.START_FLASH"
        const val ACTION_STOP = "com.nikhil.beatflash.STOP_FLASH"
        private const val CHANNEL_ID = "beat_flash_channel"
        private const val NOTIF_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        backCameraId = findRearFlashCameraId()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopBlinkingAndSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (!BeatAnalyzer.isFeatureEnabled(this)) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForeground(NOTIF_ID, buildNotification())
                startBeatSyncLoop()
            }
        }
        return START_NOT_STICKY
    }

    private fun startBeatSyncLoop() {
        flashJob?.cancel()
        val beats = BeatAnalyzer.getCachedBeats(this)
        val pulseMs = BeatAnalyzer.getPulseDurationMs(this)
        val loopDurationMs = (beats.lastOrNull() ?: 3000L) + 350L

        flashJob = serviceScope.launch {
            try {
                while (isActive) {
                    val cycleStart = SystemClock.elapsedRealtime()
                    for (beatOffset in beats) {
                        if (!isActive) break
                        val now = SystemClock.elapsedRealtime() - cycleStart
                        val waitMs = beatOffset - now
                        if (waitMs > 0) delay(waitMs)

                        if (!isActive) break
                        setTorch(true)
                        delay(pulseMs)
                        setTorch(false)
                    }
                    val remaining = loopDurationMs - (SystemClock.elapsedRealtime() - cycleStart)
                    if (remaining > 0 && isActive) delay(remaining)
                }
            } finally {
                setTorch(false)
            }
        }
    }

    private fun stopBlinkingAndSelf() {
        flashJob?.cancel()
        setTorch(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun setTorch(enabled: Boolean) {
        val id = backCameraId ?: return
        try {
            cameraManager.setTorchMode(id, enabled)
        } catch (_: Exception) { }
    }

    private fun findRearFlashCameraId(): String? {
        return try {
            cameraManager.cameraIdList.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                val hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                hasFlash && facing == CameraCharacteristics.LENS_FACING_BACK
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Incoming Call Beat Flash",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Beat Flash Active")
            .setContentText("Syncing flashlight with incoming ringtone beat")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        flashJob?.cancel()
        setTorch(false)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
