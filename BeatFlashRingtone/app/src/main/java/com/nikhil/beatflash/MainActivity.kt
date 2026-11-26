package com.nikhil.beatflash

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    private lateinit var switchEnable: SwitchMaterial
    private lateinit var tvCurrentRingtone: TextView
    private lateinit var tvBeatStats: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvSensitivityVal: TextView
    private lateinit var tvPulseVal: TextView
    private lateinit var seekSensitivity: SeekBar
    private lateinit var seekPulse: SeekBar
    private lateinit var btnPickSystem: Button
    private lateinit var btnPickCustom: Button
    private lateinit var btnTestBeat: Button
    private lateinit var btnPermissions: Button

    private val uiScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var testJob: Job? = null
    private var mediaPlayer: MediaPlayer? = null
    private var isTesting = false
    private var activeRingtoneUri: Uri? = null
    private var currentBeats: List<BeatEvent> = emptyList()

    private val systemRingtonePickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
                }
                if (uri != null) applyNewRingtoneDirectly(uri, SystemRingtoneHelper.getRingtoneTitle(this, uri))
            }
        }

    private val customAudioPickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) applyNewRingtoneDirectly(uri, resolveFileName(uri))
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { updatePermissionStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        setupListeners()
        loadInitialState()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
        refreshSystemRingtoneDisplay()
    }

    private fun bindViews() {
        switchEnable = findViewById(R.id.switchEnable)
        tvCurrentRingtone = findViewById(R.id.tvCurrentRingtone)
        tvBeatStats = findViewById(R.id.tvBeatStats)
        tvStatus = findViewById(R.id.tvStatus)
        tvSensitivityVal = findViewById(R.id.tvSensitivityVal)
        tvPulseVal = findViewById(R.id.tvPulseVal)
        seekSensitivity = findViewById(R.id.seekSensitivity)
        seekPulse = findViewById(R.id.seekPulse)
        btnPickSystem = findViewById(R.id.btnPickSystem)
        btnPickCustom = findViewById(R.id.btnPickCustom)
        btnTestBeat = findViewById(R.id.btnTestBeat)
        btnPermissions = findViewById(R.id.btnPermissions)
    }

    private fun loadInitialState() {
        switchEnable.isChecked = BeatAnalyzer.isFeatureEnabled(this)
        val sensitivity = BeatAnalyzer.getSensitivity(this)
        seekSensitivity.progress = ((sensitivity - 1.10f) * 100).toInt().coerceIn(0, 100)
        tvSensitivityVal.text = String.format("%.2fx", sensitivity)
        val pulse = BeatAnalyzer.getPulseDurationMs(this)
        seekPulse.progress = (pulse - 30L).toInt().coerceIn(0, 150)
        tvPulseVal.text = "$pulse ms"
        activeRingtoneUri = SystemRingtoneHelper.getCurrentSystemRingtoneUri(this)
        activeRingtoneUri?.let { uri ->
            currentBeats = BeatAnalyzer.getCachedBeats(this, uri)
            if (currentBeats.isEmpty()) analyzeAndCacheBeats(uri, sensitivity)
        }
        refreshSystemRingtoneDisplay()
    }

    private fun setupListeners() {
        switchEnable.setOnCheckedChangeListener { _, checked ->
            BeatAnalyzer.setFeatureEnabled(this, checked)
            tvStatus.text = if (checked) "Status: Active for incoming calls" else "Status: Paused"
        }
        btnPickSystem.setOnClickListener {
            stopLiveTest()
            if (!ensureWriteSettingsPermission()) return@setOnClickListener
            val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE)
                putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Select System Ringtone")
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, activeRingtoneUri)
            }
            systemRingtonePickerLauncher.launch(intent)
        }
        btnPickCustom.setOnClickListener {
            stopLiveTest()
            if (!ensureWriteSettingsPermission()) return@setOnClickListener
            customAudioPickerLauncher.launch(arrayOf("audio/*"))
        }
        btnTestBeat.setOnClickListener { if (isTesting) stopLiveTest() else startLiveTest() }
        btnPermissions.setOnClickListener { requestAllRequiredPermissions() }

        seekSensitivity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                tvSensitivityVal.text = String.format("%.2fx", 1.10f + progress / 100f)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                val value = 1.10f + ((sb?.progress ?: 35) / 100f)
                BeatAnalyzer.setSensitivity(this@MainActivity, value)
                activeRingtoneUri?.let { analyzeAndCacheBeats(it, value) }
            }
        })
        seekPulse.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                tvPulseVal.text = "${30L + progress} ms base"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                BeatAnalyzer.setPulseDurationMs(this@MainActivity, 30L + (sb?.progress ?: 45))
            }
        })
    }

    private fun applyNewRingtoneDirectly(pickedUri: Uri, displayName: String) {
        tvStatus.text = "Status: Updating system ringtone & analyzing beats..."
        uiScope.launch {
            val applied = withContext(Dispatchers.IO) {
                SystemRingtoneHelper.applyAsSystemRingtone(this@MainActivity, pickedUri, displayName)
            }
            if (applied != null) {
                activeRingtoneUri = applied
                currentBeats = emptyList()
                refreshSystemRingtoneDisplay()
                analyzeAndCacheBeats(applied, BeatAnalyzer.getSensitivity(this@MainActivity))
                Toast.makeText(this@MainActivity, "System ringtone updated", Toast.LENGTH_SHORT).show()
            } else {
                tvStatus.text = "Status: Failed to update system ringtone (Check Modify System Settings)"
            }
        }
    }

    private fun analyzeAndCacheBeats(uri: Uri, sensitivity: Float) {
        tvBeatStats.text = "Analyzing audio beats..."
        uiScope.launch {
            val beats = withContext(Dispatchers.IO) {
                BeatAnalyzer.analyzeRingtoneBeats(this@MainActivity, uri, sensitivity)
            }
            if (activeRingtoneUri != uri) return@launch
            currentBeats = beats
            BeatAnalyzer.saveBeats(this@MainActivity, uri, beats)
            tvBeatStats.text = if (beats.isEmpty()) "No reliable beats detected for this ringtone" else
                "Detected Beats: ${beats.size} · intensity-aware sync"
            tvStatus.text = if (beats.isEmpty()) "Status: Beat analysis unavailable for this ringtone" else
                "Status: Ready — synced beat data cached"
        }
    }

    private fun startLiveTest() {
        val uri = activeRingtoneUri ?: SystemRingtoneHelper.getCurrentSystemRingtoneUri(this)
        if (uri == null) {
            Toast.makeText(this, "No ringtone found to test", Toast.LENGTH_SHORT).show()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestAllRequiredPermissions()
            return
        }
        val beats = currentBeats.ifEmpty { BeatAnalyzer.getCachedBeats(this, uri) }
        if (beats.isEmpty()) {
            Toast.makeText(this, "Analyze the selected ringtone before testing", Toast.LENGTH_SHORT).show()
            return
        }
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
            val characteristics = cameraManager.getCameraCharacteristics(id)
            characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
        if (cameraId == null) {
            Toast.makeText(this, "No rear camera flash available", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                setDataSource(this@MainActivity, uri)
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Audio playback error: ${e.message}", Toast.LENGTH_SHORT).show()
            stopLiveTest()
            return
        }

        isTesting = true
        btnTestBeat.text = "Stop Live Beat Test"
        tvStatus.text = "Status: Testing ringtone + intensity-aware flash..."
        val configuredPulse = BeatAnalyzer.getPulseDurationMs(this).coerceIn(30L, 180L)
        testJob = uiScope.launch(Dispatchers.Default) {
            try {
                while (isActive && isTesting) {
                    for (beat in beats) {
                        if (!isActive || !isTesting) break
                        // Re-read MediaPlayer's playback head while waiting, so scheduler drift does not accumulate.
                        while (isActive && isTesting) {
                            val position = try { mediaPlayer?.currentPosition?.toLong() ?: break } catch (_: Exception) { break }
                            if (position >= beat.timeMs) break
                            delay((beat.timeMs - position).coerceAtMost(20L).coerceAtLeast(1L))
                        }
                        if (!isActive || !isTesting) break
                        val pulseMs = ((35f + beat.intensity * 75f) * configuredPulse / 75f)
                            .toLong().coerceIn(30L, 180L)
                        try { cameraManager.setTorchMode(cameraId, true) } catch (_: Exception) { }
                        delay(pulseMs)
                        try { cameraManager.setTorchMode(cameraId, false) } catch (_: Exception) { }
                    }
                    if (isActive && isTesting) {
                        val lastBeat = beats.last().timeMs
                        // Wait for MediaPlayer's loop point rather than adding a fixed sleep that drifts.
                        while (isActive && isTesting) {
                            val position = try { mediaPlayer?.currentPosition?.toLong() ?: break } catch (_: Exception) { break }
                            if (position < lastBeat) break
                            delay(20L)
                        }
                    }
                }
            } finally {
                try { cameraManager.setTorchMode(cameraId, false) } catch (_: Exception) { }
            }
        }
    }

    private fun stopLiveTest() {
        isTesting = false
        testJob?.cancel()
        try { mediaPlayer?.stop(); mediaPlayer?.release() } catch (_: Exception) { }
        mediaPlayer = null
        if (::btnTestBeat.isInitialized) btnTestBeat.text = "Test Ringtone + Beat Flash"
        if (::tvStatus.isInitialized) tvStatus.text = if (BeatAnalyzer.isFeatureEnabled(this))
            "Status: Active for incoming calls" else "Status: Paused"
    }

    private fun refreshSystemRingtoneDisplay() {
        val uri = SystemRingtoneHelper.getCurrentSystemRingtoneUri(this)
        activeRingtoneUri = uri
        tvCurrentRingtone.text = "Active System Ringtone: ${SystemRingtoneHelper.getRingtoneTitle(this, uri)}"
    }

    private fun ensureWriteSettingsPermission(): Boolean {
        if (!SystemRingtoneHelper.canWriteSystemSettings(this)) {
            Toast.makeText(this, "Please allow 'Modify System Settings' so ringtone changes directly in phone settings", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
            return false
        }
        return true
    }

    private fun requestAllRequiredPermissions() {
        val permissions = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        } else permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        permissionLauncher.launch(permissions.toTypedArray())
        ensureWriteSettingsPermission()
    }

    private fun updatePermissionStatus() {
        val cameraOk = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val phoneOk = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        btnPermissions.text = if (cameraOk && phoneOk && SystemRingtoneHelper.canWriteSystemSettings(this))
            "All Permissions Granted ✓" else "Grant Required Permissions (Camera, Call, System Settings)"
    }

    private fun resolveFileName(uri: Uri): String {
        var name = "Custom_Ringtone"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) name = cursor.getString(index)
            }
        }
        return name
    }

    override fun onDestroy() {
        stopLiveTest()
        uiScope.cancel()
        super.onDestroy()
    }
}
