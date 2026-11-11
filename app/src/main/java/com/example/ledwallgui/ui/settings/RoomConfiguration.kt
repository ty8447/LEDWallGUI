package com.example.ledwallgui.ui.settings

import kotlin.math.abs
import kotlin.math.hypot

/** Serializable, platform-independent room model. Strip indices run in wiring order. */
data class RoomCorner(val led: Int, val inside: Boolean)
data class RoomConfiguration(
    val ledCount: Int = 1165,
    val ledsPerMeter: Int = 60,
    val startLed: Int = 0,
    val northSegment: Int = 0,
    val corners: List<RoomCorner> = emptyList()
) {
    companion object {
        const val MAX_LEDS = 1165 // Current preallocated nRF52840 firmware capacity
        const val MAX_CORNERS = 16
        fun parse(raw: String?): RoomConfiguration? {
            val fields = raw?.split('|') ?: return null
            if (fields.size != 5) return null
            val a = fields.take(4).map { it.toIntOrNull() ?: return null }
            val cs = if (fields[4].isBlank()) emptyList() else fields[4].split(';').map {
                val pair = it.split(',')
                if (pair.size != 2) return null
                RoomCorner(pair[0].toIntOrNull() ?: return null, when (pair[1]) {
                    "1" -> true; "0" -> false; else -> return null
                })
            }
            return RoomConfiguration(a[0], a[1], a[2], a[3], cs).takeIf { it.validate() == null }
        }
    }

    fun encode(): String = "$ledCount|$ledsPerMeter|$startLed|$northSegment|" +
        corners.joinToString(";") { "${it.led},${if (it.inside) 1 else 0}" }

    fun progress(led: Int): Int = (led - startLed + ledCount) % ledCount
    fun ledAt(progress: Int): Int = (startLed + progress) % ledCount
    fun lengthMeters(): Double = ledCount.toDouble() / ledsPerMeter

    fun validate(): String? {
        if (ledCount !in 60..MAX_LEDS) return "LED count must be 60–$MAX_LEDS for this firmware"
        if (ledsPerMeter !in 1..240) return "LEDs per meter must be 1–240"
        if (startLed !in 0 until ledCount) return "Start LED must be inside the active strip"
        if (corners.size > MAX_CORNERS) return "Maximum $MAX_CORNERS corners"
        if (corners.isEmpty()) return if (northSegment != 0) "North wall must be 1 for an unmapped strip" else null
        if (corners.size < 4) return "At least four corners are required"
        if (northSegment !in corners.indices) return "Select an existing north wall"
        if (corners[0].led != startLed) return "First corner must be the selected start LED"
        var previous = -1
        for (corner in corners) {
            if (corner.led !in 0 until ledCount) return "Corner LED is out of range"
            val current = progress(corner.led)
            if (current <= previous) return "Corners must proceed in strip order without overlapping"
            previous = current
        }
        return null
    }

    fun segmentLedLengths(): List<Int> = corners.mapIndexed { i, c ->
        val end = if (i == corners.lastIndex) ledCount else progress(corners[i + 1].led)
        end - progress(c.led)
    }
    private fun headings(): List<Int> {
        var heading = 0
        return corners.indices.map { i ->
            val current = heading
            val next = (i + 1) % corners.size
            heading = (heading + if (corners[next].inside) 3 else 1) % 4
            current
        }
    }
    /** Rotate the physical path so the selected north wall runs east along the top edge. */
    private fun relativeHeading(raw: Int): Int {
        val northHeading = headings().getOrElse(northSegment) { 0 }
        return (raw - northHeading + 4) % 4
    }

    /** Each edge has a measured length. Outside corners turn clockwise, inside counter-clockwise. */
    fun geometry(): List<Pair<Double, Double>> {
        if (corners.isEmpty()) return emptyList()
        val lengths = segmentLedLengths()
        val points = mutableListOf(0.0 to 0.0)
        var x = 0.0; var y = 0.0; var heading = 0 // E,S,W,N on screen
        for (i in lengths.indices) {
            val metres = lengths[i].toDouble() / ledsPerMeter
            when (heading) {
                0 -> x += metres
                1 -> y += metres
                2 -> x -= metres
                else -> y -= metres
            }
            points.add(x to y)
            val next = (i + 1) % corners.size
            heading = (heading + if (corners[next].inside) 3 else 1) % 4
        }
        val northHeading = headings().getOrElse(northSegment) { 0 }
        return points.map { (px, py) -> when (northHeading) {
            1 -> py to -px
            2 -> -px to -py
            3 -> -py to px
            else -> px to py
        } }
    }

    /** Floor area from the calibrated orthogonal path, in square meters.
     * This is an estimate: LED density and corner placement are not laser measurements.
     */
    fun areaSquareMeters(): Double {
        if (corners.size < 4) return 0.0
        val pts = geometry()
        return kotlin.math.abs(pts.zipWithNext().sumOf { (a, b) ->
            a.first * b.second - b.first * a.second
        }) / 2.0
    }

    fun wallLengthMeters(index: Int): Double =
        segmentLedLengths().getOrElse(index) { 0 }.toDouble() / ledsPerMeter

    /** LED at the chosen physical distance along one wall, never outside that wall. */
    fun ledOnWall(wall: Int, meters: Double): Int? {
        if (wall !in corners.indices || !meters.isFinite() || meters < 0) return null
        val length = segmentLedLengths()[wall]
        if (length <= 0 || meters * ledsPerMeter >= length) return null
        return (corners[wall].led + (meters * ledsPerMeter).toInt()) % ledCount
    }

    /** True when chosen walls form a single contiguous section on the physical perimeter. */
    fun contiguousSelectedWalls(selected: Set<Int>): Boolean {
        if (selected.isEmpty() || selected.any { it !in corners.indices }) return false
        if (selected.size == corners.size) return true
        var starts = 0
        for (i in corners.indices) if (i in selected && ((i + corners.size - 1) % corners.size) !in selected) starts++
        return starts == 1
    }

    fun measuredLengthMeters(selected: Set<Int>): Double =
        selected.filter { it in corners.indices }.sumOf { wallLengthMeters(it) }

    fun closureErrorMeters(): Double {
        val p = geometry().lastOrNull() ?: return 0.0
        return hypot(p.first, p.second)
    }

    /** Minor inset jogs stay background-colored in clock mode. */
    private fun significantThreshold(): Int =
        ((segmentLedLengths().maxOrNull() ?: 0) / 7).coerceAtLeast(2)

    fun collapsedClockWalls(): List<Int> = segmentLedLengths().indices.filter { index ->
        index != northSegment && segmentLedLengths()[index] < significantThreshold()
    }

    /** The first significant perpendicular turn determines wiring direction. */
    private fun clockDirection(): Int {
        if (corners.size < 4) return 1
        val directions = headings()
        val lengths = segmentLedLengths()
        val northHeading = directions[northSegment]
        for (step in 1 until corners.size) {
            val index = (northSegment + step) % corners.size
            if (lengths[index] < significantThreshold()) continue
            when ((directions[index] - northHeading + 4) % 4) {
                1 -> return 1
                3 -> return -1
            }
        }
        return 1
    }

    private fun clockSide(index: Int): Int {
        val delta = (headings()[index] - headings()[northSegment] + 4) % 4
        return if (clockDirection() == 1) delta else (4 - delta) % 4
    }

    /** Physical walls classified N,E,S,W without changing their LED order. */
    fun virtualSideGroups(): List<List<Int>> {
        val groups = MutableList(4) { mutableListOf<Int>() }
        if (corners.size < 4) return groups
        val collapsed = collapsedClockWalls().toSet()
        for (k in corners.indices) {
            val index = (northSegment + k) % corners.size
            if (index !in collapsed) groups[clockSide(index)].add(index)
        }
        return groups
    }

    fun virtualSidesMeters(): List<Double> = virtualSideGroups().map { indices ->
        indices.sumOf { segmentLedLengths()[it] }.toDouble() / ledsPerMeter
    }

    /** Ordered physical clock path. North starts at the selected wall, not LED zero. */
    fun clockLedPath(): List<Int> {
        if (corners.size < 4) return (0 until ledCount).map(::ledAt)
        val lengths = segmentLedLengths()
        val collapsed = collapsedClockWalls().toSet()
        return (0 until corners.size).flatMap { step ->
            val wall = (northSegment + step) % corners.size
            if (wall in collapsed) emptyList() else
                (0 until lengths[wall]).map { led -> (corners[wall].led + led) % ledCount }
        }
    }

    /** 12, 3, 6, 9 o'clock at each virtual wall's *actual* physical midpoint.
     * The interpolation follows only the ordered non-flattened LED path.
     */
    fun clockTick(minute: Int): Int {
        if (corners.size < 4) return (startLed + (minute.toLong() * ledCount / 60).toInt()) % ledCount
        val path = clockLedPath()
        if (path.isEmpty()) return startLed
        val lengths = segmentLedLengths()
        val collapsed = collapsedClockWalls().toSet()
        val starts = IntArray(4) { -1 }
        val counts = IntArray(4)
        var phase = 0
        var leadingNorth = 0
        var trailingNorth = 0
        var valid = true
        var progress = 0
        for (step in corners.indices) {
            val wall = (northSegment + step) % corners.size
            if (wall in collapsed) continue
            val side = clockSide(wall)
            if (side == 0) {
                when {
                    phase == 0 -> leadingNorth += lengths[wall]
                    phase >= 3 -> { phase = 4; trailingNorth += lengths[wall] }
                    else -> valid = false
                }
            } else if (phase == 4 || side < phase || side > phase + 1) valid = false
            else phase = side
            if (starts[side] == -1) starts[side] = progress
            counts[side] += lengths[wall]
            progress += lengths[wall]
        }
        valid = valid && (1..3).all { starts[it] >= 0 && counts[it] > 0 }
        val anchors = IntArray(4)
        if (valid) {
            anchors[0] = ((leadingNorth - trailingNorth) / 2 + path.size) % path.size
            for (side in 1..3) anchors[side] = (starts[side] + counts[side] / 2) % path.size
        } else {
            anchors[0] = lengths[northSegment] / 2 % path.size
            for (side in 1..3) anchors[side] = (anchors[0] + side * path.size / 4) % path.size
        }
        val normalized = ((minute % 60) + 60) % 60
        val quadrant = normalized / 15
        val a = anchors[quadrant]
        val distance = (anchors[(quadrant + 1) % 4] + path.size - a) % path.size
        val offset = (normalized % 15) * distance / 15
        return path[(a + offset) % path.size]
    }

    fun clockNorthLed(): Int = clockTick(0)
}

/** Pure rotation helpers: drawing rotation never rewires or changes the saved north wall. */
object RoomMapOrientation {
    fun turns(value: Int): Int = ((value % 4) + 4) % 4
    fun point(point: Pair<Double, Double>, quarterTurns: Int): Pair<Double, Double> =
        when (turns(quarterTurns)) {
            1 -> -point.second to point.first
            2 -> -point.first to -point.second
            3 -> point.second to -point.first
            else -> point
        }
    /** The cardinal printed at a screen position (top, right, bottom, left). */
    fun compassAt(side: Int, quarterTurns: Int): String =
        listOf("N", "E", "S", "W")[(turns(side) - turns(quarterTurns) + 4) % 4]
}
