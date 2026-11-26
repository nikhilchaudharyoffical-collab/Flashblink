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
    private var currentBeats: List<Long> = emptyList()

    private val systemRingtonePickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
                }
                if (uri != null) {
                    applyNewRingtoneDirectly(uri, SystemRingtoneHelper.getRingtoneTitle(this, uri))
                }
            }
        }

    private val customAudioPickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                val name = resolveFileName(uri)
                applyNewRingtoneDirectly(uri, name)
            }
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            updatePermissionStatus()
        }

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

        val savedSens = BeatAnalyzer.getSensitivity(this)
        val sensProgress = ((savedSens - 1.10f) * 100).toInt().coerceIn(0, 100)
        seekSensitivity.progress = sensProgress
        tvSensitivityVal.text = String.format("%.2fx", savedSens)

        val savedPulse = BeatAnalyzer.getPulseDurationMs(this)
        seekPulse.progress = (savedPulse - 30L).toInt().coerceIn(0, 150)
        tvPulseVal.text = "${savedPulse} ms"

        activeRingtoneUri = SystemRingtoneHelper.getCurrentSystemRingtoneUri(this)
        currentBeats = BeatAnalyzer.getCachedBeats(this)
        refreshSystemRingtoneDisplay()

        // Analyze current system ringtone on first launch if no cache exists
        activeRingtoneUri?.let { uri ->
            analyzeAndCacheBeats(uri, savedSens)
        }
    }

    private fun setupListeners() {
        switchEnable.setOnCheckedChangeListener { _, isChecked ->
            BeatAnalyzer.setFeatureEnabled(this, isChecked)
            tvStatus.text = if (isChecked) {
                "Status: Active for incoming calls"
            } else {
                "Status: Paused"
            }
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

        btnTestBeat.setOnClickListener {
            if (isTesting) {
                stopLiveTest()
            } else {
                startLiveTest()
            }
        }

        btnPermissions.setOnClickListener {
            requestAllRequiredPermissions()
        }

        seekSensitivity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val sens = 1.10f + (progress / 100f)
                tvSensitivityVal.text = String.format("%.2fx", sens)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                val sens = 1.10f + ((sb?.progress ?: 35) / 100f)
                BeatAnalyzer.setSensitivity(this@MainActivity, sens)
                activeRingtoneUri?.let { analyzeAndCacheBeats(it, sens) }
            }
        })

        seekPulse.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val pulse = 30L + progress
                tvPulseVal.text = "$pulse ms"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                val pulse = 30L + (sb?.progress ?: 45)
                BeatAnalyzer.setPulseDurationMs(this@MainActivity, pulse)
            }
        })
    }

    private fun applyNewRingtoneDirectly(pickedUri: Uri, displayName: String) {
        tvStatus.text = "Status: Updating system ringtone & analyzing beats..."
        uiScope.launch {
            val appliedUri = withContext(Dispatchers.IO) {
                SystemRingtoneHelper.applyAsSystemRingtone(this@MainActivity, pickedUri, displayName)
            }
            if (appliedUri != null) {
                activeRingtoneUri = appliedUri
                refreshSystemRingtoneDisplay()
                analyzeAndCacheBeats(appliedUri, BeatAnalyzer.getSensitivity(this@MainActivity))
                Toast.makeText(
                    this@MainActivity,
                    "System Ringtone updated directly!",
                    Toast.LENGTH_SHORT
                ).show()
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
            currentBeats = beats
            BeatAnalyzer.saveBeats(this@MainActivity, uri, beats)
            tvBeatStats.text = "Detected Beats: ${beats.size} pulses synced to ringtone"
            tvStatus.text = "Status: Ready — Synced with system ringtone"
        }
    }

    private fun startLiveTest() {
        val uri = activeRingtoneUri ?: SystemRingtoneHelper.getCurrentSystemRingtoneUri(this)
        if (uri == null) {
            Toast.makeText(this, "No ringtone found to test", Toast.LENGTH_SHORT).show()
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestAllRequiredPermissions()
            return
        }

        isTesting = true
        btnTestBeat.text = "Stop Live Beat Test"
        tvStatus.text = "Status: Testing ringtone + beat flash..."

        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val camId = cameraManager.cameraIdList.firstOrNull { id ->
            val chars = cameraManager.getCameraCharacteristics(id)
            chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }

        try {
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
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

        val beatsToPlay = currentBeats.ifEmpty { BeatAnalyzer.getCachedBeats(this) }
        val pulseMs = BeatAnalyzer.getPulseDurationMs(this)

        testJob = uiScope.launch(Dispatchers.Default) {
            try {
                while (isActive && isTesting) {
                    val posMs = try {
                        mediaPlayer?.currentPosition?.toLong() ?: 0L
                    } catch (_: Exception) {
                        0L
                    }
                    val startRef = SystemClock.elapsedRealtime() - posMs

                    for (beatMs in beatsToPlay) {
                        if (!isActive || !isTesting) break
                        val nowElapsed = SystemClock.elapsedRealtime() - startRef
                        val waitMs = beatMs - nowElapsed
                        if (waitMs > 0) delay(waitMs)
                        if (!isActive || !isTesting) break

                        camId?.let { try { cameraManager.setTorchMode(it, true) } catch (_: Exception) {} }
                        delay(pulseMs)
                        camId?.let { try { cameraManager.setTorchMode(it, false) } catch (_: Exception) {} }
                    }
                    delay(150L)
                }
            } finally {
                camId?.let { try { cameraManager.setTorchMode(it, false) } catch (_: Exception) {} }
            }
        }
    }

    private fun stopLiveTest() {
        isTesting = false
        testJob?.cancel()
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) { }
        mediaPlayer = null
        btnTestBeat.text = "Test Ringtone + Beat Flash"
        tvStatus.text = if (BeatAnalyzer.isFeatureEnabled(this)) {
            "Status: Active for incoming calls"
        } else {
            "Status: Paused"
        }
    }

    private fun refreshSystemRingtoneDisplay() {
        val currentUri = SystemRingtoneHelper.getCurrentSystemRingtoneUri(this)
        activeRingtoneUri = currentUri
        val title = SystemRingtoneHelper.getRingtoneTitle(this, currentUri)
        tvCurrentRingtone.text = "Active System Ringtone: $title"
    }

    private fun ensureWriteSettingsPermission(): Boolean {
        if (!SystemRingtoneHelper.canWriteSystemSettings(this)) {
            Toast.makeText(
                this,
                "Please allow 'Modify System Settings' so ringtone changes directly in phone settings",
                Toast.LENGTH_LONG
            ).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return false
        }
        return true
    }

    private fun requestAllRequiredPermissions() {
        val perms = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.READ_PHONE_STATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.READ_MEDIA_AUDIO)
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        permissionLauncher.launch(perms.toTypedArray())
        ensureWriteSettingsPermission()
    }

    private fun updatePermissionStatus() {
        val camOk = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        val phoneOk = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
        val writeOk = SystemRingtoneHelper.canWriteSystemSettings(this)

        btnPermissions.text = if (camOk && phoneOk && writeOk) {
            "All Permissions Granted ✓"
        } else {
            "Grant Required Permissions (Camera, Call, System Settings)"
        }
    }

    private fun resolveFileName(uri: Uri): String {
        var name = "Custom_Ringtone"
        val cursor: Cursor? = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) name = it.getString(idx)
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
