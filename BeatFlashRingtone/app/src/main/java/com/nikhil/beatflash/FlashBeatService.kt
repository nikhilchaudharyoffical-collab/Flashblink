package com.nikhil.beatflash

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class FlashBeatService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var flashJob: Job? = null
    private lateinit var cameraManager: CameraManager
    private var backCameraId: String? = null

    companion object {
        const val ACTION_START = "com.nikhil.beatflash.START_FLASH"
        const val ACTION_STOP = "com.nikhil.beatflash.STOP_FLASH"
        const val EXTRA_RING_START_ELAPSED = "ring_start_elapsed"
        private const val CHANNEL_ID = "beat_flash_channel"
        private const val NOTIF_ID = 1001
        private const val LATE_BEAT_TOLERANCE_MS = 120L
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
                    stopBlinkingAndSelf()
                    return START_NOT_STICKY
                }
                startForeground(NOTIF_ID, buildNotification())
                val ringStart = intent.getLongExtra(EXTRA_RING_START_ELAPSED, SystemClock.elapsedRealtime())
                startBeatSyncLoop(ringStart)
            }
        }
        return START_NOT_STICKY
    }

    private fun startBeatSyncLoop(ringStartElapsed: Long) {
        flashJob?.cancel()
        val ringtoneUri = SystemRingtoneHelper.getCurrentSystemRingtoneUri(this)
        val beats = BeatAnalyzer.getCachedBeats(this, ringtoneUri).filter { it >= 0L }
        val durationMs = ringtoneUri?.let(::getRingtoneDurationMs) ?: 0L
        val usableBeats = beats.filter { durationMs <= 0L || it < durationMs }
        val pulseMs = BeatAnalyzer.getPulseDurationMs(this).coerceIn(30L, 180L)

        // Never invent a metronome pattern: it looks like sync but cannot match the selected ringtone.
        if (usableBeats.isEmpty() || durationMs <= 0L) {
            stopBlinkingAndSelf()
            return
        }

        flashJob = serviceScope.launch {
            try {
                var nextCycle = 0L
                var beatIndex = 0
                while (isActive) {
                    val beatOffset = usableBeats[beatIndex]
                    val deadline = ringStartElapsed + nextCycle * durationMs + beatOffset
                    val waitMs = deadline - SystemClock.elapsedRealtime()
                    if (waitMs > 0L) delay(waitMs)

                    if (!isActive) break
                    val lateness = SystemClock.elapsedRealtime() - deadline
                    // Skip stale torch events instead of showing an obviously delayed flash.
                    if (lateness <= LATE_BEAT_TOLERANCE_MS) {
                        setTorch(true)
                        delay(pulseMs)
                        setTorch(false)
                    }

                    beatIndex++
                    if (beatIndex >= usableBeats.size) {
                        beatIndex = 0
                        nextCycle++
                    }
                }
            } finally {
                setTorch(false)
            }
        }
    }

    private fun getRingtoneDurationMs(uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(this, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            try { retriever.release() } catch (_: Exception) { }
        }
    }

    private fun stopBlinkingAndSelf() {
        flashJob?.cancel()
        setTorch(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    private fun setTorch(enabled: Boolean) {
        val id = backCameraId ?: return
        try { cameraManager.setTorchMode(id, enabled) } catch (_: Exception) { }
    }

    private fun findRearFlashCameraId(): String? = try {
        cameraManager.cameraIdList.firstOrNull { id ->
            val chars = cameraManager.getCameraCharacteristics(id)
            chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
    } catch (_: Exception) { null }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Incoming Call Beat Flash", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Beat Flash Active")
            .setContentText("Syncing flashlight with incoming ringtone beat")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    override fun onDestroy() {
        flashJob?.cancel()
        setTorch(false)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
