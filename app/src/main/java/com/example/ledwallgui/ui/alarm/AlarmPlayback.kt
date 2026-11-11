package com.example.ledwallgui.ui.alarm

import com.example.ledwallgui.ui.settings.ControllerLiveState
import com.example.ledwallgui.ui.settings.GlobalBluetoothManager

/** One ordered, acknowledged BLE transaction. The controller, not the display, owns the mode. */
object AlarmPlayback {
    private val safeParameterNames = setOf("Color", "SColor", "Speed", "Frequency", "Division",
        "Sensitivity", "Reaction", "Pattern", "Direction", "Bounce", "Palette", "Fade",
        "Spawn", "Decay", "Length", "Size", "Collision")

    fun snapshot(state: ControllerLiveState): Map<String, String> =
        linkedMapOf("Name" to state.mode, "Brightness" to state.brightness.toString(),
            "Power" to if (state.power) "1" else "0").apply { putAll(state.parameters) }

    suspend fun capture(ble: GlobalBluetoothManager): Map<String, String>? =
        ble.refreshState()?.let(::snapshot)

    /** Returns an error when a write wasn't acknowledged. Never silently claim playback. */
    suspend fun apply(ble: GlobalBluetoothManager, saved: Map<String, String>): String? {
        val mode = saved["Name"]?.takeIf { it.isNotBlank() && !it.contains(':') && !it.contains('{') }
            ?: return "No valid effect captured"
        if (!ble.ensureConnected(8000)) return "Controller is not connected"
        val power = saved["Power"]?.let { it != "0" } ?: true
        // Set power first, otherwise an alarm scheduled while lights were Off would stay invisible.
        if (ble.controlAndAwait("Power", "None", if (power) "1" else "0") != "OK")
            return "Controller did not acknowledge power"
        if (ble.controlAndAwait("Mode", "None", mode) != "OK")
            return "Controller did not acknowledge mode $mode"
        for ((name, raw) in saved) {
            if (name !in safeParameterNames) continue
            val value = raw.trim().removePrefix("{").removeSuffix("}")
            if (value.isEmpty() || value.length > 64 || value.any { it == ':' || it == '{' || it == '}' }) continue
            val result = ble.controlAndAwait("Parameter", name, value)
            if (result != "OK") return "Could not apply $name: ${result ?: "no response"}"
        }
        saved["Brightness"]?.toIntOrNull()?.coerceIn(0, 100)?.let { level ->
            if (ble.controlAndAwait("Brightness", "None", level.toString()) != "OK")
                return "Controller did not acknowledge brightness"
        }
        // Ensure all clients get the updated state and parameters rather than stale UI values.
        ble.refreshState()
        return null
    }
}
