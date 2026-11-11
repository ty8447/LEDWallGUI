import com.example.ledwallgui.ui.WeatherSchedule as W

fun main() {
    val now = 9_000_000L
    var n = 0
    fun test(name: String, verify: () -> Unit) { verify(); n++; println("PASS: $name") }
    test("initial fetch") { check(W.due(now, 0, 0, false)) }
    test("recent success prevents periodic fetch") { check(!W.due(now, now-60_000, now-60_000, false)) }
    test("mode selection forces a fresh location even after recent success") {
        check(W.due(now, now-60_000, now-60_000, true))
    }
    test("normal refresh at exactly 30 minutes") {
        check(W.due(now, now-W.POLL_MS, now-W.POLL_MS, false))
    }
    test("last failed attempt backs off two minutes") {
        check(!W.due(now, now-W.POLL_MS-5, now-30_000, false))
        check(W.due(now, now-W.POLL_MS-5, now-W.RETRY_MS, false))
    }
    test("fresh request overrides failure backoff") {
        check(W.due(now, now-W.POLL_MS-5, now-2_000, true))
    }
    test("failed forced refresh retries even when old weather is recent") {
        check(!W.due(now, now-30_000, now-20_000, false, true))
        check(W.due(now, now-30_000, now-W.RETRY_MS, false, true))
    }
    test("last success next update") { check(W.nextDue(now, 0, false) == now + W.POLL_MS) }
    test("failed request next retry") { check(W.nextDue(now-100, now, true) == now + W.RETRY_MS) }
    test("first run scheduled immediately") { check(W.nextDue(0, 0, false) == null) }
    test("empty success with recent failure shows retry") { check(W.nextDue(0, now, true) == now + W.RETRY_MS) }
    println("$n weather scheduling tests passed")
}
