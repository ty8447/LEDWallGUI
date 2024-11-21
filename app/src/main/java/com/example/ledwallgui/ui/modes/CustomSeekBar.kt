package com.example.ledwallgui // Make sure the package is correct

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.widget.SeekBar
import androidx.core.text.color

class CustomSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SeekBar(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = Rect()

    init {
        paint.color = Color.WHITE // Set the text color
        paint.textSize = 32f // Set the text size
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Get the current progress value
        val progress = progress

        // Convert progress to a string
        val progressText = progress.toString()

        // Measure the text bounds
        paint.getTextBounds(progressText, 0, progressText.length, rect)

        // Calculate the x and y coordinates for the text
        val x = width / 2f - rect.centerX()
        val y = height / 2f - rect.centerY()

        // Draw the text on the canvas
        canvas.drawText(progressText, x, y, paint)
    }
}