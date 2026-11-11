package com.example.ledwallgui.ui.settings

import com.google.gson.Gson

/** Portable backup. No PIN, BLE identity, or RTC clock seconds are exported. */
data class LedCalibrationRange(
    val start: Int, val end: Int, val brightness: Int, val lift: Int,
    val red: Int, val green: Int, val blue: Int
) {
    fun csv(): String = listOf(start, end, brightness, lift, red, green, blue).joinToString(",")
    fun valid(maxLeds: Int) = start in 0 until maxLeds && end in start until maxLeds &&
        brightness in 50..150 && lift in 0..16 &&
        red in 50..150 && green in 50..150 && blue in 50..150
    companion object {
        fun parse(csv: String): LedCalibrationRange? {
            val values = csv.split(',').mapNotNull { it.toIntOrNull() }
            if (values.size != 7) return null
            return LedCalibrationRange(values[0], values[1], values[2], values[3],
                values[4], values[5], values[6])
        }
    }
}

data class ControllerBackup(
    val kind: String = "ledwall-controller-backup",
    val version: Int = 4,
    val room: String = "",
    val dst: Int = 0,
    val mode: String = "Color",
    val power: Boolean = false,
    val brightness: Int = 100,
    val parameters: Map<String, String> = emptyMap(),
    val manualClockWalls: List<ControllerProtocol.Wall> = emptyList(),
    // Null = old pre-calibration backup: do not erase correction profiles on restore.
    val ledCalibration: List<LedCalibrationRange>? = null
) {
    fun validate(): String? {
        if (kind != "ledwall-controller-backup" || version != 4) return "Not an LEDWall v4 configuration file"
        if (RoomConfiguration.parse(room) == null) return "Room layout is invalid"
        if (dst !in 0..1 || brightness !in 0..100) return "Invalid DST or brightness"
        if (mode.isBlank() || mode.length > 60 || mode.contains(':') || mode.contains('|')) return "Invalid mode"
        if (parameters.size > 12 || parameters.any { (k, v) ->
                k !in setOf("Color", "SColor", "Speed", "Frequency", "Division",
                    "Sensitivity", "Reaction", "Pattern", "Direction", "Bounce", "Palette", "Fade") ||
                v.length > 32 || v.any { it == '{' || it == '}' || it == ':' || it == '|' }
            }) return "Invalid LED mode parameters"
        val maxLeds = RoomConfiguration.parse(room)?.ledCount ?: return "Invalid strip count"
        if (ledCalibration != null) {
            if (ledCalibration.size > 12 || ledCalibration.any { !it.valid(maxLeds) })
                return "Invalid LED correction range"
            for (i in ledCalibration.indices) for (j in 0 until i) {
                val a=ledCalibration[i];val b=ledCalibration[j]
                if (a.start <= b.end && b.start <= a.end) return "Overlapping correction ranges"
            }
        }
        if (manualClockWalls.isNotEmpty() && ControllerProtocol.validate(manualClockWalls) != null)
            return "Invalid manual clock walls"
        return null
    }
    fun json(): String { require(validate() == null); return Gson().toJson(this) }
    companion object {
        fun parse(raw: String): ControllerBackup? {
            if (raw.length > 32_768) return null
            return try { Gson().fromJson(raw, ControllerBackup::class.java)?.takeIf { it.validate() == null } }
            catch (_: Exception) { null }
        }
    }
}

object ControllerBackupTransport {
    suspend fun read(ble: GlobalBluetoothManager): ControllerBackup? {
        val room = RoomSync.read(ble) ?: return null
        val dst = ble.requestAndAwait("RTC", "DST")?.toIntOrNull()?.takeIf { it in 0..1 } ?: return null
        val live = ble.refreshState() ?: return null
        val walls = mutableListOf<ControllerProtocol.Wall>()
        if (ble.requestAndAwait("Wall", "State") == "1") {
            for (i in 0 until 4) {
                val tokens = ble.requestAndAwait("Wall", "$i")?.split(',') ?: return null
                if (tokens.size != 3) return null
                val first = tokens[0].toIntOrNull() ?: return null
                val count = tokens[1].toIntOrNull() ?: return null
                val reversed = tokens[2].toIntOrNull() ?: return null
                if (reversed !in 0..1) return null
                walls.add(ControllerProtocol.Wall(first, count, reversed == 1))
            }
        }
        val corrections = mutableListOf<LedCalibrationRange>()
        val calibrationCount = ble.requestAndAwait("Cal", "Count")?.toIntOrNull()
        if (calibrationCount != null && calibrationCount in 0..12) {
            for (i in 0 until calibrationCount) {
                val range = ble.requestAndAwait("Cal", "$i")?.let(LedCalibrationRange::parse)
                    ?: return null
                corrections.add(range)
            }
        }
        return ControllerBackup(room = room.encode(), dst = dst, mode = live.mode,
            power = live.power, brightness = live.brightness,
            parameters = live.parameters, manualClockWalls = walls,
            ledCalibration = if (calibrationCount == null) null else corrections)
            .takeIf { it.validate() == null }
    }

    suspend fun restore(ble: GlobalBluetoothManager, backup: ControllerBackup): String {
        backup.validate()?.let { return it }
        if (!ble.setupAuthenticated) return "Enter controller setup PIN before restoring"
        val room = RoomConfiguration.parse(backup.room) ?: return "Invalid backup room layout"
        val prior = read(ble) // Retained for diagnostic rollback guidance; not auto-restored.
        val result = RoomSync.push(ble, room)
        if (result != "OK") return "Room mapping restore failed: $result"
        val dst = ble.controlAndAwait("RTC", "DST", backup.dst.toString())
        if (dst != "OK") return "Room saved, but DST restore failed: $dst"
        if (backup.manualClockWalls.isNotEmpty()) {
            for ((index, wall) in backup.manualClockWalls.withIndex()) {
                val resultWall = ble.controlAndAwait("Wall", "$index", "${wall.firstLed},${wall.count},${if (wall.reversed) 1 else 0}")
                if (resultWall != "OK") return "Wall ${index + 1} rejected: $resultWall"
            }
            if (ble.controlAndAwait("Wall", "Apply", "1") != "OK") return "Manual clock wall commit failed"
        }
        // Old backups have null calibration and intentionally retain the new controller's profiles.
        if (backup.ledCalibration != null) {
            if (ble.controlAndAwait("Cal", "Clear", "1") != "OK") return "Calibration reset failed"
            for ((index, correction) in backup.ledCalibration.withIndex()) {
                if (ble.controlAndAwait("Cal", "Save", "$index,${correction.csv()}") != "OK")
                    return "LED calibration correction ${index + 1} restore failed"
            }
        }
        if (ble.controlAndAwait("Mode", "None", backup.mode) != "OK") return "Mode restore failed"
        for ((name, value) in backup.parameters) {
            if (ble.controlAndAwait("Parameter", name, value) != "OK") return "LED parameter $name restore failed"
        }
        if (ble.controlAndAwait("Brightness", "None", backup.brightness.toString()) != "OK") return "Brightness restore failed"
        if (ble.controlAndAwait("Power", "None", if (backup.power) "1" else "0") != "OK") return "Power restore failed"
        val verifyRoom = RoomSync.read(ble)
        val verifyDst = ble.requestAndAwait("RTC", "DST")?.toIntOrNull()
        val verifyLive = ble.refreshState()
        val verifyCal = if (backup.ledCalibration != null) {
            val size = ble.requestAndAwait("Cal", "Count")?.toIntOrNull()
            if (size != backup.ledCalibration.size) false else backup.ledCalibration.indices.all { index ->
                ble.requestAndAwait("Cal", "$index") == backup.ledCalibration[index].csv()
            }
        } else true
        if (!verifyCal || verifyRoom != room || verifyDst != backup.dst ||
            verifyLive?.power != backup.power || verifyLive?.brightness != backup.brightness || verifyLive?.mode != backup.mode ||
            backup.parameters.any { (key, value) -> verifyLive?.parameters?.get(key) != value }) {
            return "Read-back differs from backup. Confirm LED state and retry carefully. Prior config: ${prior?.room?.let { RoomConfiguration.parse(it)?.ledCount } ?: "unknown"} LEDs"
        }
        return "OK"
    }
}
