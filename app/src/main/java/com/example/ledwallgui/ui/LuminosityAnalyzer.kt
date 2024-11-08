package com.example.ledwallgui.ui

import android.os.Handler
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

class LuminosityAnalyzer(
    private val listener: (Double) -> Unit,
    private val keepScreenOn: () -> Unit,
    private val resetInactivityTimer: () -> Unit,
    private val handler: Handler,
    private val threshold: Double
) : ImageAnalysis.Analyzer {



    private var previousLuma = 0.0

    override fun analyze(image: ImageProxy) {
        // Calculate the average luma value from the image
        val buffer = image.planes[0].buffer
        val data = buffer.toByteArray() // Use toByteArray() extension function
        val totalLuma = data.sumOf { it.toInt() and 0xFF } // Access elements using index
        val luma = totalLuma.toDouble() / data.size
        val lumaDifference = Math.abs(luma - previousLuma)

        if (lumaDifference > threshold) {
            handler.post {
                Log.d("MotionDetectionService", "Raw Luma: $luma")
                Log.d("MotionDetectionService", "Luma Difference: $lumaDifference")
            }

                Log.d("MotionDetectionService", "Motion detected (luma < threshold)")
                keepScreenOn()
                resetInactivityTimer()
            }



        listener(luma) // Call the listener with the calculated luma value
        previousLuma = luma // Update previous luma value
        image.close() // Close the image proxy when finished
    }

    private fun ByteBuffer.toByteArray(): ByteArray {
        rewind()    // Rewind the buffer to zero
        val data = ByteArray(remaining())
        get(data)   // Copy the buffer into a byte array
        return data
    }
}