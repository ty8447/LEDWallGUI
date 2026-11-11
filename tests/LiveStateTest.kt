import com.example.ledwallgui.ui.settings.ControllerLiveState
fun main() {
    var n = 0
    fun t(name: String, run: () -> Unit) { run(); n++; println("PASS $name") }
    t("controller core snapshot") { check(ControllerLiveState.parseCore("12|1|75|Color") == ControllerLiveState(12,true,75,"Color")) }
    t("mode including spaces") { check(ControllerLiveState.parseCore("3|0|0|Audio reactive")?.mode == "Audio reactive") }
    t("invalid brightness") { check(ControllerLiveState.parseCore("1|0|120|Color") == null) }
    t("invalid power") { check(ControllerLiveState.parseCore("1|2|100|Color") == null) }
    t("no mode") { check(ControllerLiveState.parseCore("1|1|10|") == null) }
    t("parameters snapshot") {
        val p = ControllerLiveState.parseParameters("14|Alternating colors|Color=255,0,0|SColor=0,0,255|Speed=20")
        check(p?.parameters?.get("Color") == "255,0,0")
        check(p?.parameters?.get("Speed") == "20")
    }
    t("single parameter broadcast") {
        val p = ControllerLiveState.parseParameter("15|Rainbow|Speed|24")
        check(p?.first?.first == 15L && p.second == "24")
    }
    t("truncated parameter malformed") { check(ControllerLiveState.parseParameter("15|Rainbow|Speed") == null) }
    t("invalid revision") { check(ControllerLiveState.parseCore("a|1|20|Color") == null) }
    println("$n live state tests passed")
}
