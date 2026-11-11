package com.example.ledwallgui.ui

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.ledwallgui.R
import com.example.ledwallgui.ui.settings.GlobalBluetoothManager
import kotlin.concurrent.thread
import kotlin.math.sqrt

/** Tablet microphone supplies a low-rate RMS envelope, never raw recorded audio. */
class AudioReactiveService : Service() {
    @Volatile private var running = false
    private var captureThread: Thread? = null
    private var recording: AudioRecord? = null
    private lateinit var bluetooth: GlobalBluetoothManager

    override fun onCreate() {
        super.onCreate()
        bluetooth = GlobalBluetoothManager(applicationContext)
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(
            "led_audio", "LED microphone", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e("AudioReactive", "RECORD_AUDIO permission is missing")
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val notification = Notification.Builder(this, "led_audio")
                .setContentTitle("LED sound reactive mode")
                .setContentText("Microphone level drives your LED wall")
                .setSmallIcon(R.drawable.ic_bluetooth)
                .setOngoing(true)
                .build()
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(12, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else startForeground(12, notification)
        } catch (e: Exception) {
            Log.e("AudioReactive", "Could not start microphone foreground service", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) {
            running = true
            val prefs = getSharedPreferences("led_audio", MODE_PRIVATE)
            val previous = com.example.ledwallgui.ui.modes.ModeManager.currentMode
            if (previous.isNotBlank() && previous != "Audio reactive") {
                prefs.edit().putString("previous_mode", previous).apply()
            }
            if (intent?.action == "com.example.ledwallgui.ACTIVATE_AUDIO") {
                // An explicit user action may select audio mode; reconnecting must not.
                com.example.ledwallgui.ui.modes.ModeManager.currentMode = "Audio reactive"
                bluetooth.setLedMode("Audio reactive")
            }
            captureThread = thread(name = "led-mic", isDaemon = true) { captureLoop() }
        }
        return START_NOT_STICKY
    }

    @Suppress("MissingPermission")
    private fun captureLoop() {
        val sampleRate = 16000
        val minSize = AudioRecord.getMinBufferSize(sampleRate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minSize <= 0) {
            Log.e("AudioReactive", "Tablet microphone does not support 16kHz mono PCM: $minSize")
            stopSelf(); return
        }
        val bufferSize = maxOf(minSize * 2, 4096)
        try {
            val record = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
            recording = record
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e("AudioReactive", "AudioRecord failed to initialize")
                stopSelf(); return
            }
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Log.e("AudioReactive", "Microphone privacy toggle or another app may block recording")
                stopSelf(); return
            }
            val samples = ShortArray(640)
            var smoothed = 0f
            var noiseFloor = 0f
            var adaptivePeak = 6f
            var lastSend = 0L
            while (running && !Thread.currentThread().isInterrupted) {
                val n = record.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                if (n <= 0) {
                    if (n < 0) Log.w("AudioReactive", "AudioRecord read error $n")
                    continue
                }
                var sum = 0.0
                for (i in 0 until n) {
                    val f = samples[i].toDouble() / 32768.0
                    sum += f * f
                }
                // Track an adaptive noise floor. A steady fan/music background
                // approaches zero output; transient peaks drive the LEDs.
                val raw = (sqrt(sum / n) * 900.0).toFloat().coerceIn(0f, 100f)
                if (noiseFloor == 0f) noiseFloor = raw
                val floorRate = if (raw < noiseFloor) 0.14f else 0.012f
                noiseFloor += floorRate * (raw - noiseFloor)
                val transient = (raw - noiseFloor - 1.2f).coerceAtLeast(0f)
                adaptivePeak = maxOf(transient, adaptivePeak * 0.993f, 4f)
                val normalized = (transient / adaptivePeak * 100f).coerceIn(0f, 100f)
                smoothed = 0.35f * smoothed + 0.65f * normalized
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastSend >= 50) {
                    if (bluetooth.liveState.value?.mode == "Audio reactive") {
                        bluetooth.setAudioLevel(if (smoothed < 3f) 0 else smoothed.toInt())
                    }
                    lastSend = now
                }
            }
        } catch (e: Exception) {
            Log.e("AudioReactive", "Microphone capture error", e)
            stopSelf()
        } finally {
            try { recording?.stop() } catch (_: Exception) {}
            try { recording?.release() } catch (_: Exception) {}
            recording = null
        }
    }

    override fun onDestroy() {
        running = false
        captureThread?.interrupt()
        try { recording?.stop() } catch (_: Exception) {}
        captureThread = null
        bluetooth.setAudioLevel(0)
        val previous = getSharedPreferences("led_audio", MODE_PRIVATE)
            .getString("previous_mode", "Color") ?: "Color"
        // If mic was turned off by the user, resume their previous light effect.
        if (!getSharedPreferences("settings_prefs", MODE_PRIVATE).getBoolean("mic_enabled", false) &&
            bluetooth.liveState.value?.mode == "Audio reactive") {
            com.example.ledwallgui.ui.modes.ModeManager.currentMode = previous
            bluetooth.setLedMode(previous)
        }
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
