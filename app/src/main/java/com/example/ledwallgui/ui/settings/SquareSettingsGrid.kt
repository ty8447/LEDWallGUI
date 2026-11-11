package com.example.ledwallgui.ui.settings

import android.content.Context
import android.content.res.Configuration
import android.util.AttributeSet
import android.view.ViewGroup

/**
 * ScrollView-friendly square Settings tile grid.
 * Landscape retains the original four-across arrangement. Portrait uses two
 * columns, which preserves a practical tap target on narrow devices.
 */
class SquareSettingsGrid @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ViewGroup(context, attrs) {
    private val gap: Int = (12f * resources.displayMetrics.density + 0.5f).toInt()
    private var cellSize = 0
    private var columnCount = 4

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        columnCount = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 4 else 2
        val available = (width - paddingLeft - paddingRight - gap * (columnCount - 1)).coerceAtLeast(0)
        cellSize = available / columnCount
        val exactCell = MeasureSpec.makeMeasureSpec(cellSize, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility != GONE) child.measure(exactCell, exactCell)
        }
        val rows = if (childCount == 0) 0 else (childCount + columnCount - 1) / columnCount
        val height = paddingTop + paddingBottom + rows * cellSize + (rows - 1).coerceAtLeast(0) * gap
        setMeasuredDimension(resolveSize(width, widthMeasureSpec), height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            val x = paddingLeft + (i % columnCount) * (cellSize + gap)
            val y = paddingTop + (i / columnCount) * (cellSize + gap)
            child.layout(x, y, x + cellSize, y + cellSize)
        }
    }

    override fun generateDefaultLayoutParams(): LayoutParams =
        LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)

    override fun generateLayoutParams(attrs: AttributeSet): LayoutParams = LayoutParams(context, attrs)

    override fun generateLayoutParams(p: LayoutParams): LayoutParams = LayoutParams(p)

    override fun checkLayoutParams(p: LayoutParams): Boolean = p is LayoutParams
}
