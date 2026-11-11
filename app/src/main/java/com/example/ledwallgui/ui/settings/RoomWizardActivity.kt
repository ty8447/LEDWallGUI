package com.example.ledwallgui.ui.settings

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Room mapping v5.2: discover physical count, mark corners, and refine a closed map. */
class RoomWizardActivity : AppCompatActivity() {
    private lateinit var ble: GlobalBluetoothManager
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var drawing: RoomMapView
    private lateinit var position: TextView
    private lateinit var northLabel: TextView
    private lateinit var cornerLabel: TextView
    private lateinit var densityInput: EditText
    private lateinit var stripTypeInput: Spinner
    private lateinit var channelOrderInput: Spinner
    private val chipsets = listOf("WS2815 (12V)", "WS2812B (5V)", "WS2813 (5V)", "SK6812 RGB (5V)")
    private val colorOrders = listOf("GRB", "RGB", "BRG", "BGR", "RBG", "GBR")
    private lateinit var setupControls: LinearLayout
    private lateinit var calibrationControls: LinearLayout
    private lateinit var mappingControls: LinearLayout
    private lateinit var walkingControls: LinearLayout
    private lateinit var refinementControls: LinearLayout
    private var config = RoomConfiguration()
    private val draft = mutableListOf<RoomCorner>()
    private var pendingPhysical = 0
    private var progress = 0
    private var calibrationLed = 0
    private var started = false
    private var closed = false
    private var selectedCorner = 1
    private var north = 0
    private var previewAllowed = false
    private val transport = Mutex()
    private var previewRevision = 0
    private var previewJob: Job? = null
    private var selectingNorth = false
    private var northChosen = false

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
    private fun label(value: String, size: Float = 16f) = TextView(this).apply {
        text = value; textSize = size; setTextColor(Color.WHITE)
        setPadding(0, 8.dp, 0, 8.dp)
    }
    private fun button(value: String, action: () -> Unit) = LedSettingsUi.button(this, value, action)
    private fun append(parent: LinearLayout, child: View) {
        parent.addView(child, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    private fun row(parent: LinearLayout, vararg views: View) {
        val linear = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        views.forEach { linear.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
        append(parent, linear)
    }
    private fun section(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    /** Symmetric jog controls: -1/-5/-10 | live LED number | +1/+5/+10. */
    private fun jogPad(parent: LinearLayout, center: TextView, callback: (Int) -> Unit) {
        val pad = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listOf(1, 5, 10).forEach { amount ->
            val negative = button("−$amount") { callback(-amount) }
            val positive = button("+$amount") { callback(amount) }
            left.addView(negative, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 58.dp))
            right.addView(positive, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 58.dp))
        }
        center.gravity = Gravity.CENTER
        center.setTextColor(Color.WHITE)
        center.textSize = 17f
        center.setBackgroundColor(Color.rgb(37, 52, 70))
        pad.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        pad.addView(center, LinearLayout.LayoutParams(0, 174.dp, 1.4f))
        pad.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        append(parent, pad)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ble = GlobalBluetoothManager(applicationContext)
        config = RoomSync.local(this) ?: RoomConfiguration()
        north = config.northSegment
        setContentView(label("Room mapping locked. Enter the controller PIN to continue.", 20f).apply {
            setPadding(20.dp, 24.dp, 20.dp, 24.dp)
            setBackgroundColor(Color.rgb(18, 23, 33))
        })
        lifecycleScope.launch {
            if (!requireAccess()) return@launch
            if (isFinishing || isDestroyed) return@launch
            buildScreen()
            status.text = "The controller is only changed permanently after you save the completed room."
            val settings = ble.requestAndAwait("Cfg", "Strip")?.split(',')
            val type = settings?.getOrNull(0)?.toIntOrNull()
            val order = settings?.getOrNull(1)?.toIntOrNull()
            if (type != null && type in chipsets.indices) stripTypeInput.setSelection(type)
            if (order != null && order in colorOrders.indices) channelOrderInput.setSelection(order)
        }
    }

    private suspend fun requireAccess(): Boolean {
        val accepted = ControllerPinAccess.ensure(this, ble) && ble.isConnected && ble.setupAuthenticated
        if (!accepted) ControllerPinAccess.returnToSettings(this)
        return accepted
    }

    private fun buildScreen() {
        root = LedSettingsUi.page(this, "Room Mapping Wizard")
        status = label("", 15f).also { append(root, it) }

        setupControls = section().also { append(root, it) }
        append(setupControls, label("1. Strip setup", 21f))
        append(setupControls, label("Choose the density printed on your LED strip. We will find the LED count by jogging to the last lit LED, rather than asking you to guess it."))
        append(setupControls, label("LEDs per meter"))
        densityInput = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSingleLine(true); setText(config.ledsPerMeter.toString()); setTextColor(Color.WHITE)
        }.also { append(setupControls, it) }
        append(setupControls, label("LED chipset (choose the actual physical strip)"))
        stripTypeInput = Spinner(this).apply {
            adapter = ArrayAdapter(this@RoomWizardActivity, android.R.layout.simple_spinner_dropdown_item, chipsets)
            setSelection(0)
        }.also { append(setupControls, it) }
        append(setupControls, label("RGB data channel order (use preview to check red, green and blue)"))
        channelOrderInput = Spinner(this).apply {
            adapter = ArrayAdapter(this@RoomWizardActivity, android.R.layout.simple_spinner_dropdown_item, colorOrders)
            setSelection(0)
        }.also { append(setupControls, it) }
        append(setupControls, label("WS2815 uses 12V; WS2812B/WS2813/SK6812 RGB use 5V. This setting changes data timing/channel order, never the strip's supply voltage.", 13f))
        append(setupControls, button("Save strip type and channel order") {
            lifecycleScope.launch {
                if (!requireAccess()) return@launch
                if (saveStripFormat()) status.text = "Strip type and RGB order saved to controller flash. Test each primary color next."
            }
        })
        row(setupControls,
            button("Test RED") { testPrimaryColor(24, 0, 0) },
            button("Test GREEN") { testPrimaryColor(0, 24, 0) },
            button("Test BLUE") { testPrimaryColor(0, 0, 24) })
        append(setupControls, button("Stop RGB preview") {
            lifecycleScope.launch { if (ble.isConnected) ble.controlAndAwait("Cal", "Stop", "1") }
        })
        append(setupControls, button("Find the last LED →") { startCalibration() })
        if (RoomSync.local(this) != null) {
            append(setupControls, button("Use previously calibrated ${config.ledCount}-LED strip") { useKnownLength() })
            if (config.corners.size >= 4) {
                append(setupControls, button("Fine-tune existing ${config.corners.size}-wall map") { editSavedMap() })
            }
        }

        calibrationControls = section().apply { visibility = View.GONE }.also { append(root, it) }
        append(calibrationControls, label("2. Find the final physical LED", 21f))
        append(calibrationControls, label("A red LED will move in wiring order. Jog until the last LED on your physical strip is lit, then confirm. If you go beyond the strip and the red light disappears, move backward."))
        val calibrationPosition = label("LED 0", 18f)
        jogPad(calibrationControls, calibrationPosition) { d ->
            jogCalibration(d)
            calibrationPosition.text = "Testing LED\n$calibrationLed"
        }
        append(calibrationControls, button("This is the LAST physical LED ✓") { completeCalibration() })
        append(calibrationControls, label("This confirmation is necessary: the Arduino can address an LED, but cannot sense whether a physical LED exists at that index."))

        mappingControls = section().apply { visibility = View.GONE }.also { append(root, it) }
        append(mappingControls, label("3. Map the room corners", 21f))
        append(mappingControls, label("Green LEDs are confirmed corners. Red is the moving LED. When the scan completes one known strip loop, the map closes automatically."))
        position = label("", 18f)
        drawing = RoomMapView(this).also { map ->
            map.rotationQuarterTurns = RoomMapRotationStore.load(this)
            map.onWallTapped = { wall -> if (closed && selectingNorth) chooseNorth(wall) }
            map.onCornerTapped = { corner -> if (closed && !selectingNorth) selectCorner(corner) }
            append(mappingControls, map)
        }
        row(mappingControls,
            button("↶ Rotate 90°") { rotateMap(-1) },
            button("Rotate 90° ↷") { rotateMap(1) })
        append(mappingControls, label("Rotation changes only how the map and compass look. Select north below to change the clock's 12 o'clock wall."))
        walkingControls = section().also { append(mappingControls, it) }
        jogPad(walkingControls, position) { delta -> jog(delta) }
        append(walkingControls, button("Confirm corner at red LED") { confirmCorner() })
        row(walkingControls, button("Undo corner") { undo() }, button("Start mapping again") { restartMapping() })

        refinementControls = section().apply { visibility = View.GONE }.also { append(mappingControls, it) }
        append(refinementControls, label("4. Refine the completed room", 21f))
        append(refinementControls, label("Every wall has a color and a label in the legend below the map. Tap a numbered corner to edit that corner. Wall taps cannot change north unless you explicitly enter North Selection."))
        row(refinementControls, button("‹ Corner") { selectCorner((selectedCorner - 1 + draft.size) % draft.size) }, button("Corner ›") { selectCorner((selectedCorner + 1) % draft.size) })
        cornerLabel = label("", 17f)
        jogPad(refinementControls, cornerLabel) { delta -> refine(delta) }
        append(refinementControls, button("Change selected corner's inside / outside turn") { changeTurn() })
        northLabel = label("North not selected", 17f).also { append(refinementControls, it) }
        append(refinementControls, button("Select north wall on map") { beginNorthSelection() })
        append(refinementControls, button("Test wall colors on controller") { stageAll(true) })
        append(refinementControls, button("Save room map to tablet and Arduino") { save() })
        append(refinementControls, label("The diagram is proportional to the LED lengths and density. The north wall is the clock's 12 o'clock side. Saving requires a controller setup PIN."))
    }

    private fun density(): Int? {
        val value = densityInput.text.toString().toIntOrNull()
        if (value == null || value !in 1..240) { status.text = "Enter LEDs per meter between 1 and 240"; return null }
        return value
    }
    private suspend fun saveStripFormat(): Boolean {
        val type = stripTypeInput.selectedItemPosition
        val order = channelOrderInput.selectedItemPosition
        val result = ble.controlAndAwait("Cfg", "Strip", "$type,$order")
        if (result != "OK") {
            status.text = "Could not save strip type/order: ${result ?: "no reply"}. Requires Arduino V9.3."
            return false
        }
        return true
    }
    private fun testPrimaryColor(red: Int, green: Int, blue: Int) {
        lifecycleScope.launch {
            if (!requireAccess() || !saveStripFormat()) return@launch
            val count = ble.requestAndAwait("Cfg", "Info")?.substringBefore(',')?.toIntOrNull()
                ?: config.ledCount
            val end = (count - 1).coerceIn(0, RoomConfiguration.MAX_LEDS - 1)
            val response = ble.controlAndAwait("Cal", "Preview", "0,$end,$red,$green,$blue")
            status.text = if (response == "OK") "Previewing one RGB channel at approximately 9% for wiring verification."
                else "RGB preview failed: ${response ?: "no response"}"
        }
    }
    private fun startCalibration() {
        val ledDensity = density() ?: return
        config = config.copy(ledsPerMeter = ledDensity)
        calibrationLed = 0
        lifecycleScope.launch {
            if (!requireAccess()) return@launch
            if (!saveStripFormat()) return@launch
            if (ble.controlAndAwait("Map", "Begin", "0") != "OK") {
                status.text = "Could not start calibration. Flash V8.2 firmware to the Arduino."; return@launch
            }
            previewAllowed = true
            setupControls.visibility = View.GONE
            calibrationControls.visibility = View.VISIBLE
            status.text = "Testing LED 0. Move toward the last physical LED."
        }
    }
    private fun jogCalibration(delta: Int) {
        calibrationLed = (calibrationLed + delta).coerceIn(0, RoomConfiguration.MAX_LEDS - 1)
        if (previewAllowed) ble.sendCommand("Map", "Preview", "$calibrationLed")
    }
    private fun completeCalibration() {
        val count = RoomWizardLogic.calibratedCount(calibrationLed)
        if (count == null) {
            status.text = "The final LED index must indicate 60–${RoomConfiguration.MAX_LEDS} LEDs. Confirm the final lit LED."; return
        }
        config = RoomConfiguration(ledCount = count, ledsPerMeter = config.ledsPerMeter)
        status.text = "Calibrated $count LEDs. Now locate your starting room corner."
        calibrationControls.visibility = View.GONE
        enterMapping()
    }
    private fun useKnownLength() {
        val density = density() ?: return
        config = config.copy(ledsPerMeter = density, corners = emptyList(), northSegment = 0)
        lifecycleScope.launch {
            if (!requireAccess()) return@launch
            if (!saveStripFormat()) return@launch
            previewAllowed = true
            enterMapping()
        }
    }
    private fun editSavedMap() {
        val saved = RoomSync.local(this) ?: return
        if (saved.corners.size < 4 || saved.validate() != null) return
        config = saved
        draft.clear(); draft.addAll(saved.corners)
        north = saved.northSegment
        started = true; closed = true
        selectedCorner = 1.coerceAtMost(draft.lastIndex)
        pendingPhysical = saved.startLed; progress = saved.ledCount
        setupControls.visibility = View.GONE
        calibrationControls.visibility = View.GONE
        mappingControls.visibility = View.VISIBLE
        walkingControls.visibility = View.GONE
        refinementControls.visibility = View.VISIBLE
        selectingNorth = false
        northChosen = true
        status.text = "Editing saved map. Tap a corner to refine it. Use Select north wall to change the clock orientation."
        redraw()
        lifecycleScope.launch {
            if (!requireAccess()) return@launch
            previewAllowed = true
            stageAll(true)
        }
    }

    private fun enterMapping() {
        pendingPhysical = 0; progress = 0; started = false; closed = false; north = 0
        draft.clear(); previewRevision++; previewJob?.cancel(); selectingNorth = false; northChosen = false
        setupControls.visibility = View.GONE
        calibrationControls.visibility = View.GONE
        mappingControls.visibility = View.VISIBLE
        walkingControls.visibility = View.VISIBLE
        refinementControls.visibility = View.GONE
        if (previewAllowed && ble.isConnected) {
            lifecycleScope.launch {
                val range = ble.controlAndAwait("Map", "Range", "${config.ledCount}")
                if (range != "OK") status.text = "V8.2 firmware required to preview the calibrated LED count (response: $range)"
                ble.sendCommand("Map", "Preview", "$pendingPhysical")
            }
        }
        redraw()
    }
    private fun jog(delta: Int) {
        if (closed) return
        if (!started) {
            pendingPhysical = ((pendingPhysical + delta) % config.ledCount + config.ledCount) % config.ledCount
            if (previewAllowed) ble.sendCommand("Map", "Preview", "$pendingPhysical")
        } else {
            val minProgress = config.progress(draft.last().led) + 1
            progress = (progress + delta).coerceIn(minProgress, config.ledCount)
            pendingPhysical = config.ledAt(progress)
            if (RoomWizardLogic.loopComplete(progress, config.ledCount, draft.size)) { finishLoop(); return }
            if (previewAllowed) ble.sendCommand("Map", "Preview", "$pendingPhysical")
        }
        redraw()
    }
    private fun confirmCorner() {
        if (closed) return
        if (!started) {
            config = config.copy(startLed = pendingPhysical)
            draft.add(RoomCorner(pendingPhysical, false)); started = true
            progress = 1; pendingPhysical = config.ledAt(progress)
            status.text = "Starting corner locked in green. Jog to the next physical corner."
            stageAll(false); redraw(); return
        }
        if (progress == config.ledCount) {
            if (draft.size >= 4) finishLoop() else status.text = "Confirm at least four corners first"
            return
        }
        if (draft.size >= RoomConfiguration.MAX_CORNERS) { status.text = "Maximum number of corners reached"; return }
        val led = pendingPhysical
        chooseTurn("Corner ${draft.size + 1}: follow the arrow that matches your turn") { inside ->
            draft.add(RoomCorner(led, inside))
            progress = config.progress(led) + 1
            pendingPhysical = config.ledAt(progress)
            if (RoomWizardLogic.loopComplete(progress, config.ledCount, draft.size)) finishLoop()
            else { stageAll(false); redraw() }
        }
    }
    private fun finishLoop() {
        if (draft.size < 4) { status.text = "At least four corners are required"; return }
        closed = true; north = 0; selectedCorner = 1.coerceAtMost(draft.lastIndex)
        selectingNorth = true
        walkingControls.visibility = View.GONE
        refinementControls.visibility = View.VISIBLE
        status.text = "Loop complete: ${config.ledCount} LEDs. Please tap the wall that faces NORTH. Wall taps are active only for this step."
        stageAll(true); redraw()
    }
    private fun chooseTurn(title: String, callback: (Boolean) -> Unit) {
        val choices = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(8.dp, 10.dp, 8.dp, 10.dp) }
        val dialog = AlertDialog.Builder(this).setTitle(title).setView(choices).setNegativeButton("Cancel", null).create()
        for (inside in listOf(true, false)) {
            val picture = CornerTurnView(this, inside)
            choices.addView(picture, LinearLayout.LayoutParams(0, 160.dp, 1f))
            picture.setOnClickListener { dialog.dismiss(); callback(inside) }
        }
        dialog.show()
        dialog.window?.setLayout((minOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) * .88f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    private fun rotateMap(delta: Int) {
        if (!::drawing.isInitialized) return
        drawing.rotationQuarterTurns = RoomMapRotationStore.rotate(this, delta)
        status.text = "Map rotated ${drawing.rotationQuarterTurns * 90}°. Compass rotated with the room; clock north remains on Wall ${north + 1}."
    }

    private fun beginNorthSelection() {
        if (!closed) return
        selectingNorth = true
        northChosen = false
        status.text = "North selection: tap the correct wall segment on the drawing."
        redraw()
    }
    private fun selectCorner(index: Int) {
        if (!closed || draft.isEmpty()) return
        selectedCorner = (index + draft.size) % draft.size
        redraw()
    }
    private fun chooseNorth(index: Int) {
        if (!closed || !selectingNorth || index !in draft.indices) return
        north = index
        selectingNorth = false
        northChosen = true
        status.text = "North / 12 o'clock: Wall ${index + 1}–${(index + 1) % draft.size + 1}. The compass and clock preview now follow this wall. Save to update the Arduino."
        redraw()
    }
    private fun refine(delta: Int) {
        if (!closed || draft.isEmpty()) return
        val changed = RoomWizardLogic.movedCorner(workingConfig(), selectedCorner, delta)
        if (changed == null) { status.text = "Corner cannot move past an adjacent corner or outside the LED loop"; return }
        config = config.copy(startLed = changed.startLed)
        draft.clear(); draft.addAll(changed.corners)
        status.text = "Corner ${selectedCorner + 1} moved to LED ${changed.corners[selectedCorner].led}; wall preview refreshed."
        stageAll(true); redraw()
    }
    private fun changeTurn() {
        if (!closed || draft.isEmpty()) return
        chooseTurn("Corner ${selectedCorner + 1}: choose the turn direction") { inside ->
            draft[selectedCorner] = draft[selectedCorner].copy(inside = inside)
            status.text = "Corner turn updated; check the measured room shape."
            stageAll(true); redraw()
        }
    }
    private fun undo() {
        if (draft.isEmpty()) return
        if (draft.size == 1) {
            draft.clear(); started = false; progress = 0; pendingPhysical = config.startLed
        } else {
            val last = draft.removeAt(draft.lastIndex)
            progress = config.progress(last.led); pendingPhysical = last.led
        }
        stageAll(false); redraw()
    }
    private fun restartMapping() {
        draft.clear(); started = false; closed = false; selectingNorth = false; northChosen = false; progress = 0; pendingPhysical = 0
        stageAll(false); redraw()
    }
    private fun workingConfig() = config.copy(corners = draft.toList(), northSegment = north)
    private fun redraw() {
        if (!::drawing.isInitialized) return
        val cfg = workingConfig()
        drawing.config = cfg; drawing.isClosed = closed
        drawing.showClock = !selectingNorth
        drawing.northSelectionEnabled = closed && selectingNorth
        drawing.cornerSelectionEnabled = closed && !selectingNorth
        drawing.pending = if (closed) null else pendingPhysical
        drawing.selectedCorner = if (closed) selectedCorner else null
        position.text = when {
            closed -> "${cfg.corners.size} corners • ${DisplayUnits.formatMeters(this, cfg.lengthMeters())} perimeter"
            !started -> "Locate starting corner • red LED $pendingPhysical"
            else -> "Red LED $pendingPhysical • $progress/${config.ledCount} around loop • ${draft.size} confirmed"
        }
        if (closed) {
            val wall = north + 1
            northLabel.text = if (selectingNorth)
                "Tap the NORTH wall on the map now (selection mode)." else
                "North / 12 o'clock: Wall $wall–${wall % draft.size + 1}. Tap Select north wall to change."
            cornerLabel.text = "Corner ${selectedCorner + 1}\nLED ${draft[selectedCorner].led}"
        }
    }
    /** Rebuild temporary LEDs; never writes flash until Save. */
    private fun stageAll(wallColors: Boolean) {
        if (!previewAllowed || !ble.isConnected) return
        if (draft.isEmpty()) {
            ble.sendCommand("Map", "Begin", "$pendingPhysical")
            return
        }
        val snapshot = draft.toList(); val start = config.startLed
        val selected = pendingPhysical; val count = config.ledCount
        val revision = ++previewRevision
        previewJob?.cancel()
        previewJob = lifecycleScope.launch {
            delay(140) // Collapse rapid corner nudges into one coherent controller preview.
            transport.withLock {
                if (!ble.isConnected || revision != previewRevision) return@withLock
                if (ble.controlAndAwait("Map", "Range", "$count") != "OK") {
                    status.text = "Arduino V8.2 required for live wall preview"; return@withLock
                }
                if (ble.controlAndAwait("Map", "Begin", "$start") != "OK") return@withLock
                for ((index, corner) in snapshot.withIndex()) {
                    if (revision != previewRevision) return@withLock
                    val response = ble.controlAndAwait("Map", "Corner", "$index,${corner.led},${if (corner.inside) 1 else 0}")
                    if (response != "OK") { status.text = "Could not preview corner ${index + 1}: $response"; return@withLock }
                }
                if (revision != previewRevision) return@withLock
                if (wallColors) ble.controlAndAwait("Map", "Test", "1")
                else ble.sendCommand("Map", "Preview", "$selected")
            }
        }
    }
    private fun stopPreview() {
        ++previewRevision
        previewJob?.cancel(); previewJob = null
        if (ble.isConnected) ble.sendCommand("Map", "Stop", "1")
    }
    private fun save() {
        if (!closed) { status.text = "Complete the room loop before saving"; return }
        if (!northChosen) {
            status.text = "First tap the room wall that faces north to set the 12 o'clock side."
            beginNorthSelection()
            return
        }
        val proposal = workingConfig()
        proposal.validate()?.let { status.text = it; return }
        val geometryWarning = if (proposal.closureErrorMeters() > .25)
            "\nMeasured turns leave a ${"%.2f".format(proposal.closureErrorMeters())} m drawing gap. The LED order remains valid." else ""
        AlertDialog.Builder(this).setTitle("Save room configuration?")
            .setMessage("${proposal.ledCount} LEDs at ${proposal.ledsPerMeter} LEDs/m, ${proposal.corners.size} corners and north Wall ${proposal.northSegment + 1}.\n$geometryWarning")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                RoomSync.saveLocal(this, proposal, true)
                lifecycleScope.launch {
                    if (!ble.isConnected) { status.text = "Saved on tablet. Upload after reconnecting."; return@launch }
                    if (!requireAccess()) return@launch
                    stopPreview()
                    val result = RoomSync.push(ble, proposal)
                    if (result == "OK") {
                        RoomSync.saveLocal(this@RoomWizardActivity, proposal, false)
                        status.text = "Saved to controller flash and verified."
                        AlertDialog.Builder(this@RoomWizardActivity)
                            .setTitle("Match LED brightness and colors?")
                            .setMessage("Your room is mapped. You can now test low brightness and tune individual walls or LED ranges. This is optional and can be changed later in Configuration.")
                            .setNegativeButton("Later", null)
                            .setPositiveButton("Calibrate LEDs") { _, _ ->
                                startActivity(android.content.Intent(this@RoomWizardActivity, LedCalibrationActivity::class.java))
                            }.show()
                    } else status.text = "$result. Tablet copy retained for retry."
                }
            }.show()
    }
    private var reauthInProgress = false
    override fun onResume() {
        super.onResume()
        if (!::mappingControls.isInitialized) return
        if (!ble.isConnected) {
            ControllerPinAccess.returnToSettings(this)
        } else if (!ble.setupAuthenticated && !reauthInProgress) {
            reauthInProgress = true
            lifecycleScope.launch {
                try {
                    // Restore authorization instead of silently kicking out of setup.
                    if (!requireAccess()) return@launch
                } finally { reauthInProgress = false }
            }
        }
    }
    override fun onStop() { stopPreview(); super.onStop() }
}

/** Corner turn buttons with unmistakable travel arrows. */
private class CornerTurnView(context: Context, private val inside: Boolean) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 9f; strokeCap = Paint.Cap.ROUND }
    init { isClickable = true; contentDescription = if (inside) "Inside corner, arrow turns left" else "Outside corner, arrow turns right" }
    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.rgb(36, 43, 55))
        val mid = width / 2f
        paint.color = if (inside) 0xFF2ED7DD.toInt() else 0xFFFFB451.toInt()
        val y = height * .35f
        canvas.drawLine(mid - 47, y, mid + 5, y, paint)
        val tipY = if (inside) y - 36 else y + 36
        canvas.drawLine(mid + 5, y, mid + 5, tipY, paint)
        paint.strokeWidth = 5f
        // Direction-of-travel arrow at the end of the turn.
        if (inside) {
            canvas.drawLine(mid + 5, tipY, mid - 9, tipY + 17, paint)
            canvas.drawLine(mid + 5, tipY, mid + 19, tipY + 17, paint)
        } else {
            canvas.drawLine(mid + 5, tipY, mid - 9, tipY - 17, paint)
            canvas.drawLine(mid + 5, tipY, mid + 19, tipY - 17, paint)
        }
        paint.color = Color.WHITE; paint.textAlign = Paint.Align.CENTER; paint.textSize = 25f
        canvas.drawText(if (inside) "↶ Inside" else "↷ Outside", mid, height * .87f, paint)
        paint.textAlign = Paint.Align.LEFT; paint.strokeWidth = 9f
    }
}
