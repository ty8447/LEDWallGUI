package com.example.ledwallgui.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

/** Android 14 compliant: samples the camera while the wall panel is visible; dims its window. */
class TabletMotionWake(private val activity: AppCompatActivity) {
    private val handler = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    @Volatile private var active = false
    private var defaultBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    private var lastFrame = 0L
    private var previous: IntArray? = null
    private var consecutiveMotion = 0
    private var lastWake = 0L
    companion object {
        const val PREF_DIM_TIMEOUT_SECONDS = "motion_dim_timeout_seconds"
        const val DEFAULT_DIM_TIMEOUT_SECONDS = 30
        val DIM_CHOICES_SECONDS = intArrayOf(10, 20, 30, 60, 120, 300, 600)
        fun timeoutSeconds(context: Context): Int =
            context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)
                .getInt(PREF_DIM_TIMEOUT_SECONDS, DEFAULT_DIM_TIMEOUT_SECONDS)
                .coerceIn(10, 3600)
    }
    // Window brightness has an Android-defined lower limit; this is a near-minimum visible display.
    private val dimRunnable = Runnable { if (active) changeBrightness(0.01f) }

    fun start() {
        if (active) return
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.w("TabletMotionWake", "Camera permission not granted")
            return
        }
        active = true
        defaultBrightness = activity.window.attributes.screenBrightness
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        scheduleDim()
        val future = ProcessCameraProvider.getInstance(activity)
        future.addListener({
            if (!active) return@addListener
            try {
                provider = future.get()
                val cameraPrefs = activity.getSharedPreferences("camera_prefs", Context.MODE_PRIVATE)
                val facing = cameraPrefs.getInt("currentCameraSelector", CameraSelector.LENS_FACING_FRONT)
                val selector = if (facing == CameraSelector.LENS_FACING_BACK) CameraSelector.DEFAULT_BACK_CAMERA
                    else CameraSelector.DEFAULT_FRONT_CAMERA
                val useCase = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis = useCase
                useCase.setAnalyzer(executor) { analyzeFrame(it) }
                provider?.bindToLifecycle(activity as LifecycleOwner, selector, useCase)
            } catch (e: Exception) {
                Log.e("TabletMotionWake", "Camera unavailable on this tablet", e)
                stop()
            }
        }, ContextCompat.getMainExecutor(activity))
    }

    private fun analyzeFrame(image: ImageProxy) {
        try {
            if (!active) return
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastFrame < 400) return // ~2.5 fps, low CPU and battery use
            lastFrame = now
            val yPlane = image.planes[0]
            val buffer = yPlane.buffer
            val width = image.width
            val height = image.height
            val samples = IntArray(16 * 12)
            var k = 0
            for (sy in 0 until 12) for (sx in 0 until 16) {
                val x = (sx * width + width / 2) / 16
                val y = (sy * height + height / 2) / 12
                val offset = y * yPlane.rowStride + x * yPlane.pixelStride
                samples[k++] = if (offset >= 0 && offset < buffer.limit()) buffer.get(offset).toInt() and 0xFF else 0
            }
            val last = previous
            previous = samples
            if (last != null) {
                var changed = 0
                var totalSigned = 0
                for (i in samples.indices) {
                    val d = samples[i] - last[i]
                    totalSigned += d
                    if (abs(d) > 22) changed++
                }
                // Ignore global exposure changes; require repeated spatial movement.
                val exposureJump = abs(totalSigned.toDouble() / samples.size) > 30.0
                if (!exposureJump && changed >= 23) consecutiveMotion++ else consecutiveMotion = 0
                if (consecutiveMotion >= 2 && now - lastWake > 1200) {
                    lastWake = now
                    consecutiveMotion = 0
                    handler.post { if (active) wake() }
                }
            }
        } catch (e: Exception) {
            Log.w("TabletMotionWake", "Frame analysis failed", e)
        } finally { image.close() }
    }

    fun wake() {
        if (!active) return
        changeBrightness(defaultBrightness)
        scheduleDim()
    }
    private fun scheduleDim() {
        handler.removeCallbacks(dimRunnable)
        handler.postDelayed(dimRunnable, timeoutSeconds(activity) * 1000L)
    }
    private fun changeBrightness(value: Float) {
        activity.window.attributes = activity.window.attributes.also { it.screenBrightness = value }
    }
    fun stop() {
        active = false
        handler.removeCallbacks(dimRunnable)
        analysis?.clearAnalyzer()
        analysis?.let { provider?.unbind(it) }
        analysis = null
        previous = null
        changeBrightness(defaultBrightness)
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    fun close() {
        stop()
        executor.shutdownNow()
    }
}
