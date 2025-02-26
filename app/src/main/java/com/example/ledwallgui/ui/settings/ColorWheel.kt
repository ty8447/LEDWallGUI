package com.example.ledwallgui.ui.settings

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class ColorWheel(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private val paint: Paint = Paint()
    private val borderPaint: Paint = Paint() // For the gray border of the preview dot
    private var circleX: Float = 0f
    private var circleY: Float = 0f
    private var selectedColor: Int = Color.RED
    private var radius: Float = 0f
    private var centerX: Float = 0f
    private var centerY: Float = 0f
    private var previousHue: Float = -1f // Track the last selected hue
    private var previousSaturation: Float = -1f // Track the last selected saturation
    private var isDragging: Boolean = false // Added to track dragging state

    // Callback interface to notify when the color is selected
    interface OnColorSelectedListener {
        fun onColorSelected(color: Int)
    }

    private var onColorSelectedListener: OnColorSelectedListener? = null

    init {
        paint.isAntiAlias = true
        paint.style = Paint.Style.FILL

        borderPaint.isAntiAlias = true
        borderPaint.style = Paint.Style.FILL
        borderPaint.color = Color.LTGRAY // Gray border
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        centerX = width / 2f
        centerY = height / 2f
        radius = Math.min(width, height) / 2f

        drawColorWheel(canvas)

        // Draw the border for the preview dot
        canvas.drawCircle(circleX, circleY, 24f, borderPaint) // Slightly larger than the dot radius

        // Draw the small circle (preview dot) on top with the selected color
        paint.shader = null // Remove shader for solid color
        paint.color = selectedColor
        canvas.drawCircle(circleX, circleY, 20f, paint) // Dot radius is 20f
    }

    private fun drawColorWheel(canvas: Canvas) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val tempCanvas = Canvas(bitmap)

        // Create a sweep gradient for hue
        val hueShader: Shader = SweepGradient(
            centerX, centerY,
            intArrayOf(
                Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN,
                Color.BLUE, Color.MAGENTA, Color.RED
            ), null
        )

        // Create a radial gradient for saturation
        val saturationShader: Shader = RadialGradient(
            centerX, centerY, radius,
            Color.WHITE, Color.TRANSPARENT, Shader.TileMode.CLAMP
        )

        // Combine the shaders using ComposeShader
        val composedShader = ComposeShader(hueShader, saturationShader, PorterDuff.Mode.MULTIPLY)

        paint.shader = composedShader
        tempCanvas.drawCircle(centerX, centerY, radius, paint)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val deltaX = event.x - centerX
        val deltaY = event.y - centerY
        val distance = Math.sqrt((deltaX * deltaX + deltaY * deltaY).toDouble()).toFloat()

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // Start dragging only if the touch is inside the wheel
                if (distance <= radius) {
                    isDragging = true
                    updatePickerPosition(event.x, event.y, distance)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    // Update the picker position even if the finger moves outside the wheel
                    val constrainedDistance = distance.coerceAtMost(radius)
                    val constrainedX = centerX + deltaX * (constrainedDistance / distance)
                    val constrainedY = centerY + deltaY * (constrainedDistance / distance)

                    updatePickerPosition(constrainedX, constrainedY, constrainedDistance)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // Stop dragging
                isDragging = false
            }
        }
        return true
    }

    // Helper function to update the picker position and color
    private fun updatePickerPosition(x: Float, y: Float, distance: Float) {
        circleX = x
        circleY = y

        val angle = Math.atan2((circleY - centerY).toDouble(), (circleX - centerX).toDouble()).toFloat()
        val normalizedAngle = ((Math.toDegrees(angle.toDouble()) + 360) % 360).toFloat() // Normalize to [0, 360]
        val newHue = normalizedAngle
        val newSaturation = (distance / radius).coerceIn(0f, 1f)

        if (newHue != previousHue || newSaturation != previousSaturation) {
            previousHue = newHue
            previousSaturation = newSaturation
            selectedColor = Color.HSVToColor(floatArrayOf(newHue, newSaturation, 1f))

            // Trigger redraw
            invalidate()

            // Notify the listener with the new color
            onColorSelectedListener?.onColorSelected(selectedColor)
        }
    }

    // Set the listener from the parent activity or fragment
    fun setOnColorSelectedListener(listener: OnColorSelectedListener) {
        onColorSelectedListener = listener
    }
}
