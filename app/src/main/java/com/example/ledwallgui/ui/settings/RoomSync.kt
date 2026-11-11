package com.example.ledwallgui.ui.settings

import android.content.Context
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/** Bluetooth is authoritative unless the user explicitly selects their tablet's saved map. */
object RoomSync {
    private const val PREF = "room_config_cache_v3"
    private const val KEY = "layout"
    private const val DIRTY = "not_uploaded"

    fun local(context: Context): RoomConfiguration? =
        RoomConfiguration.parse(context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, null))

    fun pending(context: Context): Boolean =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(DIRTY, false)

    fun saveLocal(context: Context, cfg: RoomConfiguration, pendingUpload: Boolean) {
        require(cfg.validate() == null) { "Cannot persist an invalid room" }
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(KEY, cfg.encode()).putBoolean(DIRTY, pendingUpload).apply()
    }

    suspend fun read(ble: GlobalBluetoothManager): RoomConfiguration? {
        if (!ble.ensureConnected()) return null
        val fields = ble.requestAndAwait("Cfg", "Info")?.split(',') ?: return null
        if (fields.size != 5) return null
        val values = fields.map { it.toIntOrNull() ?: return null }
        val num = values[4]
        if (num !in 0..RoomConfiguration.MAX_CORNERS) return null
        val corners = mutableListOf<RoomCorner>()
        for (i in 0 until num) {
            val reply = ble.requestAndAwait("Map", "$i")?.split(',') ?: return null
            if (reply.size != 2) return null
            val led = reply[0].toIntOrNull() ?: return null
            val turn = reply[1].toIntOrNull() ?: return null
            if (turn !in 0..1) return null
            corners.add(RoomCorner(led, turn == 1))
        }
        return RoomConfiguration(values[0], values[1], values[2], values[3], corners)
            .takeIf { it.validate() == null }
    }

    suspend fun push(ble: GlobalBluetoothManager, cfg: RoomConfiguration): String {
        cfg.validate()?.let { return it }
        if (!ble.ensureConnected()) return "Not connected to controller"
        if (!ble.setupAuthenticated) return "Controller setup PIN required before uploading"
        suspend fun write(type: String, name: String, value: String): String? =
            ble.controlAndAwait(type, name, value)
        val settings = write("Cfg", "Set", "${cfg.ledCount},${cfg.ledsPerMeter},${cfg.startLed},${cfg.northSegment}")
        if (settings != "OK") return if (settings == "OK_VOLATILE")
            "Controller settings applied but NOT saved to flash" else "Settings rejected: ${settings ?: "no response"}"
        if (cfg.corners.isEmpty()) {
            val cleared = write("Map", "Clear", "1")
            if (cleared != "OK") return "Could not clear stored map: ${cleared ?: "no response"}"
        } else {
            if (write("Map", "Begin", "${cfg.startLed}") != "OK") return "Could not start map transfer"
            cfg.corners.forEachIndexed { i, corner ->
                val response = write("Map", "Corner", "$i,${corner.led},${if (corner.inside) 1 else 0}")
                if (response != "OK") return "Corner ${i + 1} rejected: ${response ?: "no response"}"
            }
            val applied = write("Map", "Apply", "${cfg.corners.size},${cfg.northSegment}")
            if (applied != "OK") return if (applied == "OK_VOLATILE")
                "Room map applied but NOT saved to flash" else "Map apply failed: ${applied ?: "no response"}"
        }
        val verify = read(ble)
        return if (verify == cfg) "OK" else "Read-back verification failed; tablet configuration retained"
    }

    /** Call only after a real connection. Never silently overwrite either saved layout. */
    fun reconcile(activity: AppCompatActivity, ble: GlobalBluetoothManager, onResolved: (RoomConfiguration) -> Unit = {}) {
        if (!ble.isConnected) return
        activity.lifecycleScope.launch {
            val remote = read(ble) ?: return@launch // Old firmware does not implement Config v3.
            if (activity.isFinishing || activity.isDestroyed) return@launch
            val tablet = local(activity)
            if (tablet == null) {
                saveLocal(activity, remote, false)
                onResolved(remote)
            } else if (tablet == remote) {
                if (pending(activity)) saveLocal(activity, remote, false)
                onResolved(remote)
            } else {
                AlertDialog.Builder(activity)
                    .setTitle("Different LED room configurations")
                    .setMessage("The tablet and Arduino hold different LED counts or maps. Which should we keep?\n\nTablet: ${tablet.ledCount} LEDs, ${tablet.corners.size} corners\nController: ${remote.ledCount} LEDs, ${remote.corners.size} corners")
                    .setPositiveButton("Upload tablet") { _, _ ->
                        activity.lifecycleScope.launch {
                            if (!ControllerPinAccess.ensure(activity, ble)) return@launch
                            val outcome = push(ble, tablet)
                            if (outcome == "OK") { saveLocal(activity, tablet, false); onResolved(tablet) }
                            else AlertDialog.Builder(activity).setMessage(outcome).setPositiveButton("OK", null).show()
                        }
                    }
                    .setNeutralButton("Use controller") { _, _ ->
                        saveLocal(activity, remote, false)
                        onResolved(remote)
                    }
                    .setNegativeButton("Later", null)
                    .show()
            }
        }
    }
}
