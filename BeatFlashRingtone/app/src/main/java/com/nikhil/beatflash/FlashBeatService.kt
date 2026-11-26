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
import kotlin.math.roundToInt

class FlashBeatService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var flashJob: Job? = null
    private lateinit var cameraManager: CameraManager
    private var backCameraId: String? = null
    private var maxTorchStrength = 1

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
        val beats = BeatAnalyzer.getCachedBeats(this, ringtoneUri)
        val durationMs = ringtoneUri?.let(::getRingtoneDurationMs) ?: 0L
        val usableBeats = beats.filter { it.timeMs >= 0L && (durationMs <= 0L || it.timeMs < durationMs) }
        val configuredPulse = BeatAnalyzer.getPulseDurationMs(this).coerceIn(30L, 180L)
        if (usableBeats.isEmpty() || durationMs <= 0L) {
            stopBlinkingAndSelf()
            return
        }

        flashJob = serviceScope.launch {
            try {
                var cycle = 0L
                var index = 0
                while (isActive) {
                    val beat = usableBeats[index]
                    val deadline = ringStartElapsed + cycle * durationMs + beat.timeMs
                    val remaining = deadline - SystemClock.elapsedRealtime()
                    if (remaining > 0L) delay(remaining)
                    if (!isActive) break

                    val lateness = SystemClock.elapsedRealtime() - deadline
                    if (lateness <= LATE_BEAT_TOLERANCE_MS) {
                        val pulseMs = ((35f + beat.intensity * 75f) * configuredPulse / 75f)
                            .roundToInt().toLong().coerceIn(30L, 180L)
                        setTorchForBeat(beat.intensity)
                        delay(pulseMs)
                        setTorch(false)
                    }

                    index++
                    if (index >= usableBeats.size) {
                        index = 0
                        cycle++
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

    private fun setTorchForBeat(intensity: Float) {
        val id = backCameraId ?: return
        val level = (1 + intensity.coerceIn(0f, 1f) * (maxTorchStrength - 1)).roundToInt()
            .coerceIn(1, maxTorchStrength)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && maxTorchStrength > 1) {
            try {
                cameraManager.turnOnTorchWithStrengthLevel(id, level)
                return
            } catch (_: Exception) {
                // Some OEM camera HALs advertise strength but reject the call; fall back to binary torch.
            }
        }
        setTorch(true)
    }

    private fun setTorch(enabled: Boolean) {
        val id = backCameraId ?: return
        try { cameraManager.setTorchMode(id, enabled) } catch (_: Exception) { }
    }

    private fun findRearFlashCameraId(): String? = try {
        cameraManager.cameraIdList.firstOrNull { id ->
            val chars = cameraManager.getCameraCharacteristics(id)
            val hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            if (hasFlash && facing == CameraCharacteristics.LENS_FACING_BACK) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    maxTorchStrength = chars.get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: 1
                }
                true
            } else false
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
