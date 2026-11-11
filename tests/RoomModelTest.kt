import com.example.ledwallgui.ui.settings.RoomConfiguration
import com.example.ledwallgui.ui.settings.RoomCorner
import kotlin.math.abs

fun main() {
    var count = 0
    fun test(name: String, block: () -> Unit) { block(); count++; println("PASS $name") }
    val rectangle = RoomConfiguration(
        ledCount = 300, ledsPerMeter = 60, startLed = 0, northSegment = 0,
        corners = listOf(RoomCorner(0, false), RoomCorner(100, false), RoomCorner(150, false), RoomCorner(250, false)))
    test("rectangle valid") { check(rectangle.validate() == null) }
    test("rectangle physical side lengths") { check(rectangle.segmentLedLengths() == listOf(100, 50, 100, 50)) }
    test("geometry closure") { check(abs(rectangle.closureErrorMeters()) < 1e-7) }
    test("virtual compass rectangle") { check(rectangle.virtualSidesMeters().map { (it * 60).toInt() } == listOf(100,50,100,50)) }
    test("12 oclock at north segment midpoint") { check(rectangle.clockNorthLed() == 50) }
    test("3 oclock at one quarter perimeter") { check(rectangle.clockTick(15) == 125) }
    test("12 oclock minute wraps") { check(rectangle.clockTick(60) == rectangle.clockNorthLed()) }
    test("codec roundtrip") { check(RoomConfiguration.parse(rectangle.encode()) == rectangle) }
    val wrapped = rectangle.copy(startLed=275,
        corners=listOf(RoomCorner(275,false),RoomCorner(75,false),RoomCorner(125,false),RoomCorner(225,false)))
    test("start offset wraps physical index") { check(wrapped.validate() == null) }
    test("wrapped step distances") { check(wrapped.segmentLedLengths() == listOf(100,50,100,50)) }
    test("wrapped full perimeter returns to start") { check(wrapped.ledAt(300) == 275) }
    test("cannot exceed physical perimeter") { check(wrapped.ledAt(301) == 276) }
    test("duplicate or reversed corners rejected") {
        check(wrapped.copy(corners = listOf(RoomCorner(275,false),RoomCorner(125,false),RoomCorner(75,false),RoomCorner(225,false))).validate() != null)
    }
    test("too many LEDs rejected") { check(rectangle.copy(ledCount=1166).validate() != null) }
    test("too few LEDs rejected") { check(rectangle.copy(ledCount=59).validate() != null) }
    test("bad LED density rejected") { check(rectangle.copy(ledsPerMeter=0).validate() != null) }
    test("north must be existing segment") { check(rectangle.copy(northSegment=5).validate() != null) }
    test("exact byte for byte stored map") { check(RoomConfiguration.parse(wrapped.encode())!!.encode() == wrapped.encode()) }
    test("no map valid for default count") { check(RoomConfiguration().validate() == null) }
    test("partial map not saveable") { check(rectangle.copy(corners=rectangle.corners.take(2)).validate() != null) }
    println("$count tests passed")
}
