package com.example.ledwallgui.ui.settings

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Pure logic matching the nRF52840 protocol; independently testable on a normal JVM. */
object ControllerProtocol {
    const val LED_COUNT = 1165
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
    data class Wall(val firstLed: Int, val count: Int, val reversed: Boolean)

    fun validate(walls: List<Wall>): String? {
        if (walls.size != 4) return "Exactly four walls are required"
        for ((index, w) in walls.withIndex()) {
            if (w.firstLed !in 0 until LED_COUNT || w.count < 15 || w.count > LED_COUNT - w.firstLed)
                return "Wall ${index + 1} is outside LEDs 0–1164 or has fewer than 15 LEDs"
        }
        for (i in 0 until 4) for (j in 0 until i) {
            val a = walls[i]; val b = walls[j]
            if (a.firstLed < b.firstLed + b.count && b.firstLed < a.firstLed + a.count)
                return "Wall ${i + 1} overlaps wall ${j + 1}"
        }
        return null
    }

    fun minuteTicks(walls: List<Wall>): List<Int> {
        require(validate(walls) == null)
        return walls.flatMap { w -> (0 until 15).map { slot ->
            val displacement = slot * w.count / 15
            if (w.reversed) w.firstLed + w.count - 1 - displacement else w.firstLed + displacement
        } }
    }

    fun daylightSavingActive(zone: ZoneId, instant: Instant): Boolean =
        zone.rules.getDaylightSavings(instant).seconds > 0

    /** Firmware stores standard local time and adds one hour for DST on display. */
    fun tabletToRtcStandardTime(local: LocalDateTime, daylightSaving: Boolean): String =
        local.minusHours(if (daylightSaving) 1 else 0).format(formatter)

    fun rtcCommand(type: String, name: String, value: String) = "$type:$name:{$value}"
}
