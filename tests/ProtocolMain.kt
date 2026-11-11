import com.example.ledwallgui.ui.settings.ControllerProtocol as P
import java.time.*

fun main() {
    val w = listOf(P.Wall(0,290,false),P.Wall(290,290,false),P.Wall(580,290,false),P.Wall(870,295,false))
    check(P.validate(w) == null)
    val ticks = P.minuteTicks(w)
    check(ticks.size == 60 && ticks.toSet().size == 60)
    check(ticks.all { it in 0 until P.LED_COUNT })
    check(ticks[0] == 0 && ticks[15] == 290 && ticks[30] == 580 && ticks[45] == 870)
    check(P.validate(w.toMutableList().apply { this[1] = P.Wall(289, 290, false) })?.contains("overlaps") == true)
    check(P.validate(w.toMutableList().apply { this[2] = P.Wall(1160, 40, false) }) != null)
    check(P.validate(w.toMutableList().apply { this[2] = P.Wall(580, 14, false) }) != null)
    val reversed = w.toMutableList().apply { this[1] = P.Wall(290,290,true) }
    check(P.validate(reversed) == null)
    check(P.minuteTicks(reversed)[15] == 579)
    val zone = ZoneId.of("America/New_York")
    check(P.daylightSavingActive(zone, Instant.parse("2026-07-01T12:00:00Z")))
    check(!P.daylightSavingActive(zone, Instant.parse("2026-12-01T12:00:00Z")))
    check(P.tabletToRtcStandardTime(LocalDateTime.of(2026,7,1,16,30,0),true) == "2026-07-01T15:30:00")
    check(P.tabletToRtcStandardTime(LocalDateTime.of(2026,12,1,16,30,0),false) == "2026-12-01T16:30:00")
    check(P.rtcCommand("RTC","DST","1") == "RTC:DST:{1}")
    println("PASS: Four-wall mapping, bounds, non-overlap, direction, DST, RTC wire protocol")
}
