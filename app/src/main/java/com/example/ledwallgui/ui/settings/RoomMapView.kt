package com.example.ledwallgui.ui.settings

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.max

/**
 * Proportional room drawing, with a separate, non-overlapping label/callout rail.
 * Wall taps are disabled unless a wizard explicitly requests north-wall selection.
 */
class RoomMapView(context: Context) : View(context) {
    var config = RoomConfiguration(); set(value) { field = value; invalidate() }
    var pending: Int? = null; set(value) { field = value; invalidate() }
    var showClock = true; set(value) { field = value; invalidate() }
    var isClosed = true; set(value) { field = value; requestLayout(); invalidate() }
    /** Rotates drawing AND compass together, without mutating strip configuration. */
    var rotationQuarterTurns = 0; set(value) { field = RoomMapOrientation.turns(value); invalidate() }
    var selectedCorner: Int? = null; set(value) { field = value; invalidate() }
    var northSelectionEnabled = false; set(value) { field = value; invalidate() }
    var cornerSelectionEnabled = false; set(value) { field = value; invalidate() }
    var onWallTapped: ((Int) -> Unit)? = null
    var measurementEnabled = false; set(value) { field = value; invalidate() }
    var measuredWalls: Set<Int> = emptySet(); set(value) { field = value; invalidate() }

    var onCornerTapped: ((Int) -> Unit)? = null
    /** Enabled for the saved-map visualizer, not for the mapping wizard. */
    var wallHoldEnabled = false
    var onWallHoldStart: ((Int) -> Unit)? = null
    var onWallHoldEnd: (() -> Unit)? = null
    private var heldWall: Int? = null

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val palette = intArrayOf(0xFFFF8316.toInt(), 0xFF05BFF7.toInt(), 0xFFBD44FF.toInt(),
        0xFFFFE840.toInt(), 0xFF21DAAA.toInt(), 0xFFE85688.toInt(), 0xFF3FA4FF.toInt(), 0xFFFFB877.toInt())
    private val touchCorners = mutableListOf<Pair<Float, Float>>()
    private val touchWalls = mutableListOf<Pair<Pair<Float, Float>, Pair<Float, Float>>>()
    private val density get() = resources.displayMetrics.density
    private fun d(dp: Float) = dp * density

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1)
        val rows = ((config.corners.size.coerceAtLeast(4) + 1) / 2)
        // Larger layouts get their own callout rows instead of squeezing captions over corners.
        val height = max((w * .95f).toInt(), d(380f + rows * 43f).toInt())
        setMeasuredDimension(w, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(0xFF18202B.toInt())
        touchCorners.clear(); touchWalls.clear()
        val cfg = config
        val fullGeometry = cfg.geometry()
        val unrotated = if (isClosed) fullGeometry else fullGeometry.take(cfg.corners.size)
        val raw = unrotated.map { RoomMapOrientation.point(it, rotationQuarterTurns) }
        if (raw.isEmpty()) {
            textPaint.color = Color.LTGRAY
            textPaint.textSize = d(18f)
            canvas.drawText("No map available", width / 2f, height / 2f, textPaint)
            return
        }

        val pendingPoint = if (!isClosed && pending != null && cfg.corners.isNotEmpty()) {
            val gap = (cfg.progress(pending!!) - cfg.progress(cfg.corners.last().led)).coerceAtLeast(0)
                .toDouble() / cfg.ledsPerMeter
            var heading = 0
            for (j in 1 until cfg.corners.size) heading = (heading + if (cfg.corners[j].inside) 3 else 1) % 4
            var northHeading = 0
            for (j in 1..cfg.northSegment.coerceAtMost(cfg.corners.lastIndex))
                northHeading = (northHeading + if (cfg.corners[j].inside) 3 else 1) % 4
            heading = (heading - northHeading + 4) % 4
            val origin = unrotated.last()
            val natural = when (heading) {
                0 -> (origin.first + gap) to origin.second
                1 -> origin.first to (origin.second + gap)
                2 -> (origin.first - gap) to origin.second
                else -> origin.first to (origin.second - gap)
            }
            RoomMapOrientation.point(natural, rotationQuarterTurns)
        } else null
        val visible = raw + listOfNotNull(pendingPoint)
        val xs = visible.map { it.first }; val ys = visible.map { it.second }
        val xMin = xs.minOrNull() ?: 0.0; val xMax = xs.maxOrNull() ?: 1.0
        val yMin = ys.minOrNull() ?: 0.0; val yMax = ys.maxOrNull() ?: 1.0
        val count = if (isClosed) cfg.corners.size else (raw.size - 1).coerceAtLeast(0)
        val rowCount = (count + 1) / 2
        val legendTop = height - d(47f + rowCount * 43f)
        val drawingBottom = legendTop - d(24f)
        val hMargin = d(50f)
        val yMargin = d(76f)
        val scale = minOf(
            ((width - 2f * hMargin).coerceAtLeast(25f)).toDouble() / max(.1, xMax - xMin),
            ((drawingBottom - 2f * yMargin).coerceAtLeast(25f)).toDouble() / max(.1, yMax - yMin)
        ).toFloat()
        val midX = ((xMin + xMax) / 2).toFloat()
        val midY = ((yMin + yMax) / 2).toFloat()
        fun pt(p: Pair<Double, Double>): Pair<Float, Float> =
            (width / 2f + (p.first.toFloat() - midX) * scale) to
                (drawingBottom / 2f + (p.second.toFloat() - midY) * scale)
        val pts = raw.map(::pt)
        val lengths = cfg.segmentLedLengths()
        if (isClosed && cfg.corners.size >= 4) {
            val collapsed = cfg.collapsedClockWalls().joinToString(", ") { "Wall ${it + 1}" }
            contentDescription = if (collapsed.isBlank()) "Room map, ${cfg.corners.size} walls" else
                "Room map. Short clock jogs flattened: $collapsed"
        }

        // Draw the perimeter first; no wall text is placed on the diagram itself.
        for (i in 0 until count) {
            val a = pts[i]; val b = pts[i + 1]
            val c = palette[i % palette.size]
            line.color = c
            line.strokeWidth = when {
                heldWall == i -> d(14f)
                i in measuredWalls -> d(11f)
                isClosed && i == cfg.northSegment -> d(9f)
                else -> d(6f)
            }
            canvas.drawLine(a.first, a.second, b.first, b.second, line)
            touchWalls.add(a to b)
            // Short outward callout arrow at the wall midpoint; legend below has the text.
            val mx = (a.first + b.first) / 2; val my = (a.second + b.second) / 2
            val len = hypot(b.first - a.first, b.second - a.second).coerceAtLeast(1f)
            val dx = (b.second - a.second) / len * d(15f)
            val dy = (a.first - b.first) / len * d(15f)
            line.strokeWidth = d(1.4f)
            canvas.drawLine(mx, my, mx + dx, my + dy, line)
            fill.color = c
            canvas.drawCircle(mx + dx, my + dy, d(3f), fill)
        }
        pts.take(cfg.corners.size).forEachIndexed { i, p ->
            touchCorners.add(p)
            fill.color = if (i == selectedCorner && cornerSelectionEnabled) Color.YELLOW else Color.GREEN
            canvas.drawCircle(p.first, p.second, d(12f), fill)
            textPaint.color = Color.BLACK
            textPaint.textSize = d(11f)
            textPaint.isFakeBoldText = true
            canvas.drawText("${i + 1}", p.first, p.second + d(4f), textPaint)
        }
        if (pendingPoint != null) {
            val a = pts.last(); val b = pt(pendingPoint)
            line.color = Color.RED
            line.strokeWidth = d(3f)
            line.pathEffect = DashPathEffect(floatArrayOf(d(9f), d(8f)), 0f)
            canvas.drawLine(a.first, a.second, b.first, b.second, line)
            line.pathEffect = null
            fill.color = Color.RED
            canvas.drawCircle(b.first, b.second, d(11f), fill)
        }
        if (isClosed && cfg.corners.size >= 4) {
            textPaint.color = 0xFF71DDE9.toInt()
            textPaint.isFakeBoldText = true
            textPaint.textSize = d(20f)
            canvas.drawText(RoomMapOrientation.compassAt(0, rotationQuarterTurns), width / 2f, d(29f), textPaint)
            canvas.drawText(RoomMapOrientation.compassAt(2, rotationQuarterTurns), width / 2f, drawingBottom - d(6f), textPaint)
            canvas.drawText(RoomMapOrientation.compassAt(3, rotationQuarterTurns), d(21f), drawingBottom / 2f, textPaint)
            canvas.drawText(RoomMapOrientation.compassAt(1, rotationQuarterTurns), width - d(21f), drawingBottom / 2f, textPaint)
        }
        if (isClosed && cfg.corners.size >= 4) {
            textPaint.color = 0xFFE0EEF7.toInt()
            textPaint.textSize = d(14f)
            textPaint.isFakeBoldText = true
            val areaMeters = cfg.areaSquareMeters()
            val area = if (DisplayUnits.feet(context))
                String.format(java.util.Locale.US, "%.2f ft²", areaMeters * 10.7639104167)
                else String.format(java.util.Locale.US, "%.2f m²", areaMeters)
            canvas.drawText("Approx. floor area: $area", width / 2f, d(54f), textPaint)
        }
        // Dedicated callout rail below the scaled map. Independent rows avoid label/corner overlaps.
        fill.color = 0xFF243044.toInt()
        canvas.drawRoundRect(RectF(d(7f), legendTop, width - d(7f), height - d(6f)), d(12f), d(12f), fill)
        textPaint.isFakeBoldText = true
        textPaint.color = Color.WHITE
        textPaint.textSize = d(13f)
        val legendTitle = if (isClosed && cfg.corners.size >= 4 && showClock)
            "NORTH · Wall ${cfg.northSegment + 1}–${(cfg.northSegment + 1) % cfg.corners.size + 1} · 12 o'clock"
        else "WALL LENGTHS · LED DENSITY ${cfg.ledsPerMeter}/m"
        while (textPaint.measureText(legendTitle) > width - d(28f) && textPaint.textSize > d(10f))
            textPaint.textSize -= d(.5f)
        canvas.drawText(legendTitle, width / 2f, legendTop + d(23f), textPaint)
        for (i in 0 until count) {
            val col = i % 2
            val row = i / 2
            val cw = (width - d(36f)) / 2
            val cx = d(20f) + col * (cw + d(2f))
            val cy = legendTop + d(42f + row * 43f)
            val c = palette[i % palette.size]
            fill.color = if (northSelectionEnabled && cfg.northSegment == i) 0xFF436078.toInt() else 0xFF2C3A4E.toInt()
            canvas.drawRoundRect(RectF(cx, cy, cx + cw, cy + d(34f)), d(7f), d(7f), fill)
            fill.color = c
            canvas.drawRoundRect(RectF(cx + d(7f), cy + d(8f), cx + d(12f), cy + d(26f)), d(2f), d(2f), fill)
            textPaint.isFakeBoldText = false
            textPaint.color = Color.WHITE
            textPaint.textSize = d(12f)
            textPaint.textAlign = Paint.Align.LEFT
            val meters = lengths.getOrElse(i) { 0 }.toDouble() / cfg.ledsPerMeter
            val label = "W${i + 1}–${(i + 1) % cfg.corners.size + 1} · ${DisplayUnits.formatMeters(context, meters)}"
            while (textPaint.measureText(label) > cw - d(26f) && textPaint.textSize > d(9f))
                textPaint.textSize -= d(.5f)
            canvas.save()
            canvas.clipRect(cx + d(15f), cy, cx + cw - d(3f), cy + d(34f))
            canvas.drawText(label, cx + d(18f), cy + d(22f), textPaint)
            canvas.restore()
            textPaint.textAlign = Paint.Align.CENTER
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (wallHoldEnabled && isClosed && config.corners.size >= 4 &&
            !cornerSelectionEnabled && !northSelectionEnabled && !measurementEnabled) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val nearest = touchWalls.indices.minByOrNull { i ->
                        distanceToWall(event.x, event.y, touchWalls[i])
                    }
                    if (nearest != null && distanceToWall(event.x, event.y, touchWalls[nearest]) <= d(30f)) {
                        heldWall = nearest
                        invalidate()
                        onWallHoldStart?.invoke(nearest)
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (heldWall != null) {
                        heldWall = null
                        invalidate()
                        onWallHoldEnd?.invoke()
                        parent?.requestDisallowInterceptTouchEvent(false)
                        if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                        return true
                    }
                }
            }
        }
        if (!isClosed || config.corners.size < 4 || (!cornerSelectionEnabled && !northSelectionEnabled && !measurementEnabled))
            return super.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) return true
        if (event.actionMasked != MotionEvent.ACTION_UP) return super.onTouchEvent(event)
        val x = event.x; val y = event.y
        val threshold = d(28f)
        if (cornerSelectionEnabled) {
            val corner = touchCorners.indices.minByOrNull { i ->
                hypot(x - touchCorners[i].first, y - touchCorners[i].second)
            }
            if (corner != null && hypot(x - touchCorners[corner].first, y - touchCorners[corner].second) <= threshold) {
                onCornerTapped?.invoke(corner); performClick(); return true
            }
        }
        if (northSelectionEnabled || measurementEnabled) {
            val wallIndex = touchWalls.indices.minByOrNull { i -> distanceToWall(x, y, touchWalls[i]) }
            if (wallIndex != null && distanceToWall(x, y, touchWalls[wallIndex]) <= threshold) {
                onWallTapped?.invoke(wallIndex); performClick(); return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun distanceToWall(x: Float, y: Float, wall: Pair<Pair<Float, Float>, Pair<Float, Float>>): Float {
        val (a, b) = wall
        val vx = b.first - a.first; val vy = b.second - a.second
        val length2 = vx * vx + vy * vy
        val t = if (length2 < 1f) 0f else ((x - a.first) * vx + (y - a.second) * vy) / length2
        val c = t.coerceIn(0f, 1f)
        return hypot(x - a.first - c * vx, y - a.second - c * vy)
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}
