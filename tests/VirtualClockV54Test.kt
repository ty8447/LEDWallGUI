import com.example.ledwallgui.ui.settings.RoomConfiguration
import com.example.ledwallgui.ui.settings.RoomCorner

fun main() {
    val map = RoomConfiguration(ledCount = 520, ledsPerMeter = 60, startLed = 0,
        corners = listOf(
            RoomCorner(0, false), RoomCorner(150, false), RoomCorner(225, false),
            RoomCorner(245, true), RoomCorner(260, false), RoomCorner(410, false),
            RoomCorner(485, false), RoomCorner(505, true)))
    check(map.validate() == null)
    check(map.collapsedClockWalls() == listOf(2,3,6,7)) { map.collapsedClockWalls().toString() }
    check(map.virtualSideGroups() == listOf(listOf(0),listOf(1),listOf(4),listOf(5))) {
        map.virtualSideGroups().toString()
    }
    check(listOf(0,15,30,45).map(map::clockTick) == listOf(75,187,335,447)) {
        listOf(0,15,30,45).map(map::clockTick).toString()
    }
    check(map.clockTick(60) == map.clockTick(0))
    check(map.virtualSidesMeters().sum() < map.lengthMeters()) // Short jogs flatten only for clock.
    check(map.segmentLedLengths().sum() == map.ledCount) // All LEDs still physically mapped.
    println("PASS virtual four-sided clock and short-jog flattening")
}
