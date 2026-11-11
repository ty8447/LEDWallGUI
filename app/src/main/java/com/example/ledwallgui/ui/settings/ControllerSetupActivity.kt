package com.example.ledwallgui.ui.settings

import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import java.time.LocalDateTime as BackupDateTime
import java.time.format.DateTimeFormatter as BackupDateFormatter
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Top-level setup screen, with live RTC diagnostics and an offline room visualizer. */
class ControllerSetupActivity : AppCompatActivity() {
    private lateinit var ble: GlobalBluetoothManager
    private lateinit var status: TextView
    private lateinit var connectionIndicator: TextView
    private lateinit var protectionIndicator: TextView
    private lateinit var mapIndicator: TextView
    private lateinit var rtc: TextView
    private lateinit var roomStatus: TextView
    private lateinit var preview: RoomMapView
    private lateinit var emptyMapPanel: LinearLayout
    private lateinit var editMapButton: Button
    private lateinit var restoreMapButton: Button
    private lateinit var compareMapButton: Button
    private lateinit var mapHelp: TextView
    private lateinit var measureButton: Button
    private lateinit var measureStatus: TextView
    private val measuringWalls = linkedSetOf<Int>()
    private var measuring = false
    private var highlightedWall: Int? = null
    private lateinit var dst: Switch
    private lateinit var autoDst: Switch
    private lateinit var dateInput: EditText
    private var refreshing = false
    private var reauthInProgress = false
    private var stagedBackupJson: String? = null
    private val exportDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val text = stagedBackupJson
        stagedBackupJson = null
        if (uri != null && text != null) {
            try { contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                ?: error("Could not open target file")
                status.text = "Controller configuration saved as JSON"
            } catch (e: Exception) { status.text = "Backup write failed: ${e.message}" }
        }
    }
    private val importDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val raw = try { contentResolver.openInputStream(uri)?.use { stream ->
            // Content URI streams may return partial reads. Bound input size without newer Java APIs.
            val out = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(2048)
            while (out.size() <= 32_768) {
                val count = stream.read(chunk, 0, minOf(chunk.size, 32_769 - out.size()))
                if (count < 0) break
                if (count > 0) out.write(chunk, 0, count)
            }
            val bytes = out.toByteArray()
            if (bytes.isEmpty() || bytes.size > 32_768) null else String(bytes, Charsets.UTF_8)
        }} catch (_: Exception) { null }
        val backup = raw?.let(ControllerBackup::parse)
        if (backup == null) { status.text = "Invalid or oversized LEDWall v4 backup"; return@registerForActivityResult }
        AlertDialog.Builder(this).setTitle("Restore configuration to controller?")
            .setMessage("This replaces the current room layout, DST, mode, parameters, power and brightness. The controller PIN will not change.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Restore") { _, _ ->
                lifecycleScope.launch {
                    if (!requireAccess()) return@launch
                    status.text = "Restoring configuration..."
                    val result = ControllerBackupTransport.restore(ble, backup)
                    status.text = if (result == "OK") "Restored and verified controller configuration" else result
                    if (result == "OK") { RoomSync.saveLocal(this@ControllerSetupActivity, RoomConfiguration.parse(backup.room)!!, false); refresh(true) }
                }
            }.show()
    }
    private var reconciled = false
    private val iso = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ble = GlobalBluetoothManager(applicationContext)
        setContentView(label("Controller setup locked. Enter the controller PIN to continue.", 20f).apply {
            setPadding(20.dp, 24.dp, 20.dp, 24.dp)
            setBackgroundColor(Color.rgb(18, 23, 33))
        })
        lifecycleScope.launch {
            if (!requireAccess()) return@launch
            if (isFinishing || isDestroyed) return@launch
            buildProtectedScreen()
            refresh()
        }
    }

    /** Do not instantiate the settings, restore, or mapping controls before authorization. */
    private fun buildProtectedScreen() {
        val prefs = getSharedPreferences("controller_setup", Context.MODE_PRIVATE)
        val content = LedSettingsUi.page(this, "Configuration")
        status = label("Reading connection...", 16f).also(content::addView)
        val diagnostics = LedSettingsUi.card(this, content)
        connectionIndicator = label("● Controller: checking", 16f).also(diagnostics::addView)
        protectionIndicator = label("● Setup: authorized", 16f).also(diagnostics::addView)
        mapIndicator = label("● Room map: checking", 16f).also(diagnostics::addView)
        content.addView(button("Reconnect and refresh") { refresh(force = true) })
        val securityCard = LedSettingsUi.card(this, content)
        securityCard.addView(label("Setup security and backups", 22f))
        securityCard.addView(label("Protected changes require the controller's 4-digit PIN. The PIN is stored on the Arduino, not on this tablet. Normal light controls remain available without it."))
        securityCard.addView(button("Unlock setup / create controller PIN") {
            lifecycleScope.launch {
                if (requireAccess()) status.text = "Controller setup unlocked for this connection"
            }
        })
        securityCard.addView(button("Change / reset controller PIN") {
            lifecycleScope.launch { status.text = ControllerPinAccess.change(this@ControllerSetupActivity, ble) }
        })
        securityCard.addView(label("If you forgot the PIN, connect a computer by USB and send PINRESET in Serial Monitor at 115200 baud. Remote reset without the old code is not allowed."))
        securityCard.addView(button("Back up Arduino configuration to JSON") {
            lifecycleScope.launch {
                if (!requireAccess()) return@launch
                status.text = "Reading current configuration from controller..."
                val backup = ControllerBackupTransport.read(ble)
                if (backup == null) { status.text = "Could not read a complete controller backup"; return@launch }
                stagedBackupJson = backup.json()
                val name = "LEDWall_Controller_${BackupDateTime.now().format(BackupDateFormatter.ofPattern("yyyyMMdd_HHmmss"))}.json"
                exportDocument.launch(name)
            }
        })
        securityCard.addView(label("Backups include the room map, LED count and density, clock wall setup, DST, current effect and its parameters, power and brightness. PIN and live RTC seconds are excluded."))
        val rtcCard = LedSettingsUi.card(this, content)
        rtcCard.addView(label("RTC and daylight saving", 22f))
        rtc = label("RTC: waiting for controller", 17f).also(rtcCard::addView)
        autoDst = Switch(this).apply {
            text = "Use tablet DST when synchronizing"
            isChecked = prefs.getBoolean("auto_dst", true)
            setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, active ->
                prefs.edit().putBoolean("auto_dst", active).apply()
                if (::dst.isInitialized) dst.isEnabled = !active
            }
        }
        rtcCard.addView(autoDst)
        dst = Switch(this).apply {
            text = "DST enabled (+1 hour on display)"
            setTextColor(Color.WHITE); isEnabled = !autoDst.isChecked
            setOnClickListener {
                lifecycleScope.launch {
                    if (!requireAccess()) return@launch
                    status.text = "Updating controller DST..."
                    showAck(ble.controlAndAwait("RTC", "DST", if (isChecked) "1" else "0"), "DST")
                    refresh()
                }
            }
        }
        rtcCard.addView(dst)
        rtcCard.addView(button("Sync RTC and DST from tablet") {
            lifecycleScope.launch {
                if (!requireAccess()) return@launch
                val enabled = if (autoDst.isChecked)
                    ControllerProtocol.daylightSavingActive(ZoneId.systemDefault(), Instant.now()) else dst.isChecked
                val a = ble.controlAndAwait("RTC", "DST", if (enabled) "1" else "0")
                if (a != "OK") { showAck(a, "DST"); return@launch }
                val standard = ControllerProtocol.tabletToRtcStandardTime(LocalDateTime.now(), enabled)
                showAck(ble.controlAndAwait("RTC", "Time", standard), "RTC sync")
                refresh()
            }
        })
        rtcCard.addView(label("The DS3231 stores standard local time; DST is a separate display offset. The tablet clock is used when syncing."))
        dateInput = EditText(this).apply {
            hint = "yyyy-MM-ddTHH:mm:ss (standard time)"
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            inputType = InputType.TYPE_CLASS_DATETIME
            setSingleLine(true)
        }
        rtcCard.addView(dateInput)
        rtcCard.addView(button("Set RTC standard time manually") {
            val value = dateInput.text.toString().trim()
            try { LocalDateTime.parse(value, iso) } catch (_: Exception) {
                status.text = "Use 2026-11-09T09:00:00 format"; return@button
            }
            lifecycleScope.launch {
                if (!requireAccess()) return@launch
                showAck(ble.controlAndAwait("RTC", "Time", value), "RTC"); refresh()
            }
        })
        val roomCard = LedSettingsUi.card(this, content)
        roomCard.addView(label("Strip and room configuration", 22f))
        roomCard.addView(button("Calibrate LED brightness and color by range") {
            startActivity(Intent(this, LedCalibrationActivity::class.java))
        })
        roomStatus = label("No map available", 16f).also(roomCard::addView)
        emptyMapPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(16.dp, 24.dp, 16.dp, 24.dp)
            setBackgroundColor(Color.rgb(24, 29, 39))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 265.dp)
            addView(label("No map available", 24f).apply { gravity = Gravity.CENTER })
            addView(button("Create room map") {
                startActivity(Intent(this@ControllerSetupActivity, RoomWizardActivity::class.java))
            })
            addView(button("Restore configuration") { restoreFromFile() })
        }
        roomCard.addView(emptyMapPanel)
        preview = RoomMapView(this).apply {
            visibility = View.GONE
            rotationQuarterTurns = RoomMapRotationStore.load(this@ControllerSetupActivity)
            wallHoldEnabled = true
            onWallHoldStart = { wall ->
                highlightedWall = wall
                if (ble.isConnected && ble.setupAuthenticated) {
                    ble.sendCommand("Map", "WallHold", wall.toString())
                    status.text = "Holding wall ${wall + 1} in its map color. Release to stop."
                } else status.text = "Connect and unlock to preview walls."
            }
            onWallHoldEnd = {
                highlightedWall = null
                if (ble.isConnected) ble.sendCommand("Map", "Stop", "1")
            }
            onWallTapped = { wall ->
                if (measuring) {
                    if (!measuringWalls.add(wall)) measuringWalls.remove(wall)
                    measuredWalls = measuringWalls.toSet()
                    updateMeasurementSummary()
                }
            }
        }.also { roomCard.addView(it) }
        val rotateRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rotateRow.addView(button("↶ Rotate map 90°") { rotateMap(-1) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        rotateRow.addView(button("Rotate map 90° ↷") { rotateMap(1) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        roomCard.addView(rotateRow)
        roomCard.addView(label("Rotating the drawing also rotates the compass. The north wall and clock stay unchanged until you select and save a new north wall in Room Mapping Wizard."))
        measureButton = button("Measure walls / distance marker") {
            measuring = !measuring
            measuringWalls.clear()
            preview.measuredWalls = emptySet()
            preview.measurementEnabled = measuring
            measureButton.text = if (measuring) "Finish measuring" else "Measure walls / distance marker"
            measureStatus.visibility = if (measuring) View.VISIBLE else View.GONE
            updateMeasurementSummary()
        }.also(roomCard::addView)
        measureStatus = label("Tap walls to add measurements", 15f).also {
            it.visibility = View.GONE
            roomCard.addView(it)
        }
        roomCard.addView(button("Mark distance along a wall") {
            val cfg = RoomSync.local(this)
            if (cfg == null || cfg.corners.size < 4) {
                status.text = "Create a room map first"; return@button
            }
            val walls = (cfg.corners.indices).map { i ->
                "Wall ${i + 1}–${(i + 1) % cfg.corners.size + 1}"
            }.toTypedArray()
            AlertDialog.Builder(this).setTitle("Choose a wall")
                .setItems(walls) { _, index -> askMarkerDistance(cfg, index) }.show()
        })
        roomCard.addView(button("Stop marker / restore current effect") {
            if (ble.isConnected) ble.sendCommand("Map", "Stop", "1")
        })
        editMapButton = button("Edit room map / new room map") {
            startActivity(Intent(this, RoomWizardActivity::class.java))
        }.also(roomCard::addView)
        restoreMapButton = button("Restore configuration from JSON") { restoreFromFile() }.also(roomCard::addView)
        compareMapButton = button("Compare tablet and controller configurations") {
            if (ble.isConnected) RoomSync.reconcile(this, ble) { showConfig(it) }
            else status.text = "Connect the controller first. Tablet visualization remains available offline."
        }.also(roomCard::addView)
        mapHelp = label("The diagram works without Bluetooth. A saved tablet map is never automatically overwritten or uploaded when the controller disagrees.")
            .also(roomCard::addView)
        showConfig(RoomSync.local(this))
    }

    /** A lost/denied controller session locks the whole page, not only its Save button. */
    private suspend fun requireAccess(): Boolean {
        val accepted = ControllerPinAccess.ensure(this, ble) && ble.isConnected && ble.setupAuthenticated
        if (!accepted) ControllerPinAccess.returnToSettings(this)
        return accepted
    }

    private fun restoreFromFile() {
        lifecycleScope.launch {
            if (requireAccess()) {
                importDocument.launch(arrayOf("application/json", "text/plain"))
            } else status.text = "Connect and unlock your controller to restore a configuration."
        }
    }

    override fun onStop() {
        if (::preview.isInitialized && ble.isConnected) ble.sendCommand("Map", "Stop", "1")
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // onResume fires before the asynchronous PIN check has completed.
        if (!::emptyMapPanel.isInitialized) return
        if (!ble.isConnected) {
            ControllerPinAccess.returnToSettings(this)
            return
        }
        if (!ble.setupAuthenticated) {
            if (reauthInProgress) return
            reauthInProgress = true
            lifecycleScope.launch {
                try {
                    // Never close just because the session needs a new PIN: ask first.
                    if (requireAccess()) {
                        showConfig(RoomSync.local(this@ControllerSetupActivity))
                        refresh()
                    }
                } finally { reauthInProgress = false }
            }
            return
        }
        showConfig(RoomSync.local(this))
        refresh()
    }
    private fun rotateMap(delta: Int) {
        if (!::preview.isInitialized) return
        preview.rotationQuarterTurns = RoomMapRotationStore.rotate(this, delta)
        status.text = "Map rotated ${preview.rotationQuarterTurns * 90}° with its compass. Clock north has not changed."
    }

    private fun updateMeasurementSummary() {
        if (!::measureStatus.isInitialized) return
        val cfg = RoomSync.local(this)
        if (cfg == null || measuringWalls.isEmpty()) {
            measureStatus.text = "Tap a wall to measure it, or select adjacent walls for a combined length."
            return
        }
        val picked = measuringWalls.sorted().joinToString(" + ") { i ->
            "W${i + 1}–${(i + 1) % cfg.corners.size + 1}"
        }
        measureStatus.text = "$picked: ${DisplayUnits.formatMeters(this, cfg.measuredLengthMeters(measuringWalls))}" +
            if (cfg.contiguousSelectedWalls(measuringWalls)) " (connected segment)" else " (separate walls)"
    }

    private fun askMarkerDistance(cfg: RoomConfiguration, wall: Int) {
        val unitsFeet = DisplayUnits.feet(this)
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = if (unitsFeet) "Feet from wall start" else "Meters from wall start"
        }
        AlertDialog.Builder(this)
            .setTitle("Mark Wall ${wall + 1}–${(wall + 1) % cfg.corners.size + 1}")
            .setMessage("Wall length: ${DisplayUnits.formatMeters(this, cfg.wallLengthMeters(wall))}. Illuminate one LED at the requested distance (the nearest LED).")
            .setView(input).setNegativeButton("Cancel", null)
            .setPositiveButton("Illuminate") { _, _ ->
                val requested = input.text.toString().toDoubleOrNull()
                val meters = requested?.let { if (unitsFeet) it / 3.280839895 else it }
                val led = meters?.let { cfg.ledOnWall(wall, it) }
                if (led == null) status.text = "Distance must be within this wall"
                else if (!ble.isConnected || !ble.setupAuthenticated) status.text = "Connect and unlock controller first"
                else {
                    status.text = "Marking LED $led at ${DisplayUnits.formatMeters(this, meters ?: 0.0)}. Press Stop marker when finished."
                    ble.sendCommand("Map", "Measure", led.toString())
                }
            }.show()
    }

    private fun refresh(force: Boolean = false) {
        if (refreshing) return
        lifecycleScope.launch {
            refreshing = true
            try {
                status.text = "Checking Bluetooth connection..."
                connectionIndicator.text = "● Controller: connecting..."
                protectionIndicator.text = if (ble.setupAuthenticated) "● Setup: authorized" else "● Setup: locked"
                val connected = if (force) ble.ensureConnected(12000) else ble.isConnected || ble.ensureConnected(6000)
                if (!connected) {
                    connectionIndicator.text = "● Controller: offline"
                    status.text = "Not connected. Stored tablet configuration is available offline; connect to read the RTC."
                    rtc.text = "Controller clock: unavailable (no BLE connection)"
                    return@launch
                }
                connectionIndicator.text = "● Controller: connected"
                protectionIndicator.text = if (ble.setupAuthenticated) "● Setup: authorized" else "● Setup: locked"
                status.text = "Connected. Reading RTC..."
                val standard = ble.requestAndAwait("RTC", "Time")
                val shown = ble.requestAndAwait("RTC", "Now")
                val saving = ble.requestAndAwait("RTC", "DST")
                if (standard != null) dateInput.setText(standard)
                if (saving == "0" || saving == "1") dst.isChecked = saving == "1"
                rtc.text = "RTC standard: ${standard ?: "no response"}\nClock display: ${shown ?: "no response"}\nDST: ${saving ?: "no response"}"
                if (standard == null) status.text = "Controller connected, but RTC request not acknowledged. Confirm firmware and notifications."
                else status.text = "Connected and RTC responding"
                val config = RoomSync.read(ble)
                if (config != null) {
                    roomStatus.text = "Controller: ${config.ledCount} LEDs, ${config.ledsPerMeter} LEDs/m, ${config.corners.size} corners"
                    if (!reconciled) {
                        reconciled = true
                        RoomSync.reconcile(this@ControllerSetupActivity, ble) { showConfig(it) }
                    }
                } else roomStatus.text = "Controller configuration unavailable. Check firmware and BLE response."
            } finally { refreshing = false }
        }
    }
    private fun showConfig(cfg: RoomConfiguration?) {
        val hasRoom = cfg != null && cfg.corners.size >= 4
        if (::mapIndicator.isInitialized) mapIndicator.text =
            if (hasRoom) "● Tablet map: ${cfg!!.corners.size} corners · ${cfg.ledCount} LEDs" else "● Tablet map: not configured"
        emptyMapPanel.visibility = if (hasRoom) View.GONE else View.VISIBLE
        preview.visibility = if (hasRoom) View.VISIBLE else View.GONE
        editMapButton.visibility = if (hasRoom) View.VISIBLE else View.GONE
        restoreMapButton.visibility = if (hasRoom) View.VISIBLE else View.GONE
        compareMapButton.visibility = if (hasRoom) View.VISIBLE else View.GONE
        mapHelp.visibility = if (hasRoom) View.VISIBLE else View.GONE
        if (cfg == null || !hasRoom) {
            roomStatus.text = "No map available"
            return
        }
        preview.config = cfg; preview.isClosed = true; preview.onWallTapped = null; preview.onCornerTapped = null
        val sides = if (cfg.corners.size >= 4) {
            val labels = listOf("North", "East", "South", "West")
            val lengths = cfg.virtualSidesMeters()
            val groups = cfg.virtualSideGroups()
            val summary = labels.indices.joinToString("\n") { i ->
                val walls = groups[i].joinToString { w -> "W${w + 1}–${(w + 1) % cfg.corners.size + 1}" }
                "${labels[i]}: ${DisplayUnits.formatMeters(this, lengths[i])} ($walls)"
            }
            val collapsed = cfg.collapsedClockWalls().joinToString { w ->
                "W${w + 1}–${(w + 1) % cfg.corners.size + 1}"
            }
            "\nVirtual four-sided clock:\n$summary" +
                (if (collapsed.isBlank()) "" else "\nFlattened short jogs: $collapsed")
        } else ""
        roomStatus.text = "Tablet: ${cfg.ledCount} LEDs • ${cfg.ledsPerMeter} LEDs/m • ${cfg.corners.size} corners" +
            (if (RoomSync.pending(this)) " (not yet verified on controller)" else "") + sides
    }
    private fun showAck(reply: String?, operation: String) {
        status.text = when (reply) {
            "OK" -> "$operation acknowledged and saved"
            "OK_VOLATILE" -> "$operation applied but FLASH SAVE FAILED"
            null -> "$operation unavailable. Check Bluetooth connection and firmware."
            else -> "$operation rejected: $reply"
        }
    }
    private fun label(s: String, size: Float = 16f) = LedSettingsUi.text(this, s, size)
    private fun button(s: String, callback: () -> Unit) = LedSettingsUi.button(this, s, callback)
}
