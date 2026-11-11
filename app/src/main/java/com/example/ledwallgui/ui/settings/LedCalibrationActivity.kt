package com.example.ledwallgui.ui.settings

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Non-destructive visual correction workflow. Controller flash remains authoritative. */
class LedCalibrationActivity : AppCompatActivity() {
    private val ble by lazy { GlobalBluetoothManager(applicationContext) }
    private val room by lazy { RoomSync.local(this) }
    private lateinit var root: LinearLayout
    private lateinit var notice: TextView
    private val stored = mutableListOf<Correction>()
    private var ledCount = RoomConfiguration.MAX_LEDS
    private var editor: Correction? = null
    private var editorIndex = -1
    private var page = Page.LIST
    private var cursor = 0
    private var startPoint: Int? = null
    private var endPoint: Int? = null
    private var channel = Channel.WHITE
    private var level = 4
    private var after = true
    private var jogWholeStrip = true
    private var previewJob: Job? = null
    private var selectionJob: Job? = null
    private var fullStripJob: Job? = null
    private val bleCommandLock = Mutex()
    private var saving = false
    private var authorizedUi = false
    private var reauthInProgress = false
    private var saveIndicator: TextView? = null
    private var previewRevision = 0
    private var testRevision = 0

    private enum class Page { LIST, EDIT, START, END }
    private enum class Channel(val label: String) { RED("Red"), GREEN("Green"), BLUE("Blue"), WHITE("White") }
    private data class Correction(
        var first: Int = 0, var last: Int = 0,
        var gain: Int = 100, var lift: Int = 0,
        var red: Int = 100, var green: Int = 100, var blue: Int = 100
    ) {
        fun encode(index: Int) = "$index,$first,$last,$gain,$lift,$red,$green,$blue"
        companion object {
            fun fromResponse(raw: String): Correction? {
                val v = raw.split(',').map { it.trim().toIntOrNull() }
                if (v.size != 7 || v.any { it == null }) return null
                return Correction(v[0]!!, v[1]!!, v[2]!!, v[3]!!, v[4]!!, v[5]!!, v[6]!!)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LedSettingsUi.page(this, "LED Calibration")
        notice = LedSettingsUi.text(this, "Connecting to controller…", 16f)
        root.addView(notice)
        lifecycleScope.launch {
            if (!ble.isConnected || !ControllerPinAccess.ensure(this@LedCalibrationActivity, ble)) {
                ControllerPinAccess.returnToSettings(this@LedCalibrationActivity)
                return@launch
            }
            authorizedUi = true
            reload()
        }
    }

    private fun newPage(title: String, info: String) {
        saveIndicator = null
        root.removeAllViews()
        LedSettingsUi.header(this, root, title)
        notice = LedSettingsUi.text(this, info, 15f)
        root.addView(notice)
    }

    private fun button(parent: LinearLayout, title: String, action: () -> Unit) =
        LedSettingsUi.addButton(this, parent, title, action)

    private fun countInRange(first: Int, last: Int): Int =
        if (ledCount <= 0) 0 else Math.floorMod(last - first, ledCount) + 1
    private fun includes(range: Correction, led: Int): Boolean =
        if (range.first <= range.last) led in range.first..range.last
        else led >= range.first || led <= range.last
    private fun overlaps(a: Correction, b: Correction): Boolean =
        includes(a, b.first) || includes(a, b.last) || includes(b, a.first) || includes(b, a.last)
    private fun message(s: String) { notice.text = s }
    private fun dp(n: Int): Int = (n * resources.displayMetrics.density).toInt()
    private suspend fun stopPreview() {
        previewJob?.cancel(); selectionJob?.cancel(); fullStripJob?.cancel()
        if (ble.isConnected && ble.setupAuthenticated) {
            bleCommandLock.withLock { ble.controlAndAwait("Cal", "Stop", "1") }
        }
    }

    private suspend fun reload() {
        if (!ble.isConnected || !ble.setupAuthenticated) { message("Connect and unlock controller setup first."); return }
        ledCount = room?.ledCount ?: ble.requestAndAwait("Cfg", "Info")?.substringBefore(',')?.toIntOrNull()
            ?: RoomConfiguration.MAX_LEDS
        val number = ble.requestAndAwait("Cal", "Count")?.toIntOrNull()
        if (number == null || number !in 0..12) {
            newPage("LED Calibration", "Cannot read saved calibrations. Arduino V9.3 or newer is required for guided preview.")
            return
        }
        stored.clear()
        for (i in 0 until number) {
            val raw = ble.requestAndAwait("Cal", "$i")
            val correction = raw?.let(Correction::fromResponse)
            if (correction == null) {
                newPage("LED Calibration", "Could not read calibration ${i + 1}; no values were changed.")
                return
            }
            stored.add(correction)
        }
        showList()
    }

    private fun showList() {
        page = Page.LIST
        editor = null
        newPage("LED Calibration", "${stored.size} of 12 saved correction ranges. Select a range to edit or test it; nothing changes permanently until Save.")
        val description = LedSettingsUi.card(this, root)
        description.addView(LedSettingsUi.text(this,
            "Match brightness and RGB tint between sections. Start at 4% intensity, compare Before and After, then save only when it looks right.", 15f, true))
        button(description, "+ Add calibration range") {
            if (stored.size >= 12) { message("Maximum 12 calibration ranges reached."); return@button }
            editorIndex = -1
            editor = Correction(last = (ledCount - 1).coerceAtLeast(0))
            showEditor()
        }
        if (stored.isEmpty()) {
            val empty = LedSettingsUi.card(this, root)
            empty.addView(LedSettingsUi.text(this, "No calibration ranges saved yet. Add one to select LEDs and tune their output.", 17f))
        }
        addFullStripTestCard()
        stored.forEachIndexed { index, item ->
            val card = LedSettingsUi.card(this, root)
            card.addView(LedSettingsUi.text(this,
                "Range ${index + 1}: LEDs ${item.first} → ${item.last}  (${countInRange(item.first, item.last)} LEDs${if (item.last < item.first) ", wraps through zero" else ""})", 19f))
            card.addView(LedSettingsUi.text(this,
                "Brightness ${item.gain}%  ·  Lift +${item.lift}  ·  RGB ${item.red}/${item.green}/${item.blue}%", 14f, true))
            button(card, "Edit / compare range ${index + 1}") {
                editorIndex = index; editor = item.copy(); showEditor()
            }
            button(card, "Delete range ${index + 1}") {
                AlertDialog.Builder(this).setTitle("Delete calibration ${index + 1}?")
                    .setMessage("This removes the saved brightness and color correction for LEDs ${item.first}–${item.last}. The room layout is unchanged.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ ->
                        lifecycleScope.launch {
                            val response = ble.controlAndAwait("Cal", "Delete", "$index")
                            if (response == "OK") reload() else message("Delete failed: ${response ?: "no reply"}")
                        }
                    }.show()
            }
        }
    }

    /** Preview a low-brightness test even when no calibration range is selected. */
    private fun addFullStripTestCard() {
        val panel = LedSettingsUi.card(this, root)
        panel.addView(LedSettingsUi.text(this, "Test dim · entire LED strip", 20f))
        panel.addView(LedSettingsUi.text(this,
            "Check your saved corrections at low brightness, without selecting or changing any range.", 15f, true))
        val colors = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        panel.addView(colors)
        for (choice in Channel.entries) {
            colors.addView(LedSettingsUi.button(this,
                if (channel == choice) "✓ ${choice.label}" else choice.label) {
                channel = choice
                showList()
                queueFullStripPreview()
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        val intensityLabel = LedSettingsUi.text(this, "Test brightness: $level%", 16f)
        panel.addView(intensityLabel)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        panel.addView(row)
        val slider = SeekBar(this).apply { max = 99; progress = level - 1 }
        fun applyBrightness(newValue: Int) {
            level = newValue.coerceIn(1, 100)
            slider.progress = level - 1
            intensityLabel.text = "Test brightness: $level%"
            queueFullStripPreview()
        }
        row.addView(LedSettingsUi.button(this, "−1%") { applyBrightness(level - 1) },
            LinearLayout.LayoutParams(dp(65), dp(52)))
        row.addView(slider, LinearLayout.LayoutParams(0, dp(52), 1f))
        row.addView(LedSettingsUi.button(this, "+1%") { applyBrightness(level + 1) },
            LinearLayout.LayoutParams(dp(65), dp(52)))
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) applyBrightness(progress + 1)
            }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
        button(panel, "Preview entire strip at $level% / refresh") { queueFullStripPreview() }
        button(panel, "Stop test / restore current effect") {
            lifecycleScope.launch {
                stopPreview()
                message("Test stopped. Controller returned to its selected effect.")
            }
        }
    }

    private fun sampleRgb(): Triple<Int, Int, Int> {
        val brightness = (level * 255 / 100).coerceIn(1, 255)
        return Triple(
            if (channel == Channel.WHITE || channel == Channel.RED) brightness else 0,
            if (channel == Channel.WHITE || channel == Channel.GREEN) brightness else 0,
            if (channel == Channel.WHITE || channel == Channel.BLUE) brightness else 0)
    }

    private fun queueFullStripPreview() {
        if (page != Page.LIST || saving || ledCount < 1) return
        previewJob?.cancel(); selectionJob?.cancel()
        testRevision++
        if (fullStripJob?.isActive == true) return
        fullStripJob = lifecycleScope.launch {
            while (page == Page.LIST && !saving) {
                val version = testRevision
                val chosenChannel = channel
                val chosenLevel = level
                val (red, green, blue) = sampleRgb()
                val result = bleCommandLock.withLock {
                    ble.controlAndAwait("Cal", "Preview", "0,${ledCount - 1},$red,$green,$blue")
                }
                currentCoroutineContext().ensureActive()
                if (version == testRevision) {
                    if (result != "OK") message("Test dim failed: ${result ?: "controller did not respond"}")
                    else message("Testing entire strip: ${chosenChannel.label} at $chosenLevel%. Saved corrections are active.")
                    return@launch
                }
                delay(35) // Newest value wins; keep the BLE command queue short.
            }
        }
    }

    private fun showEditor() {
        val correction = editor ?: return
        page = Page.EDIT
        newPage(if (editorIndex < 0) "New Calibration" else "Edit Calibration ${editorIndex + 1}",
            "Select the LED range, preview a color, adjust it, and toggle Before / After. Unsaved settings are previewed without changing the controller's flash.")
        button(root, "‹ Back to saved calibrations") { lifecycleScope.launch { stopPreview(); showList() } }

        val rangeCard = LedSettingsUi.card(this, root)
        rangeCard.addView(LedSettingsUi.text(this,
            "LED range: ${correction.first} → ${correction.last} (${countInRange(correction.first, correction.last)} LEDs${if (correction.last < correction.first) ", through zero" else ""})", 20f))
        val cfg = room
        if (cfg != null && cfg.corners.size >= 4) {
            rangeCard.addView(LedSettingsUi.text(this, "Optional: tap a wall to select its entire LED range", 14f, true))
            val map = RoomMapView(this).apply {
                config = cfg
                isClosed = true
                showClock = false
                northSelectionEnabled = true // Enables wall taps without modifying saved north.
                onWallTapped = { wall ->
                    val first = cfg.corners[wall].led
                    val last = (cfg.corners[(wall + 1) % cfg.corners.size].led + cfg.ledCount - 1) % cfg.ledCount
                    correction.first = first; correction.last = last
                    lifecycleScope.launch { stopPreview(); showEditor() }
                }
            }
            rangeCard.addView(map)
        }
        button(rangeCard, "Select start and end LEDs (jog on strip)") {
            lifecycleScope.launch { stopPreview(); startPoint = null; endPoint = null; cursor = correction.first; showJog(Page.START) }
        }

        val sample = LedSettingsUi.card(this, root)
        sample.addView(LedSettingsUi.text(this, "Preview color", 20f))
        for (pair in listOf(Channel.RED to Channel.GREEN, Channel.BLUE to Channel.WHITE)) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            sample.addView(row)
            pair.toList().forEach { color ->
                val b = LedSettingsUi.button(this, if (channel == color) "✓ ${color.label}" else color.label) {
                    channel = color; showEditor(); queuePreview()
                }
                row.addView(b, LinearLayout.LayoutParams(0, dp(52), 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
            }
        }
        val intensity = LedSettingsUi.text(this, "Test brightness: $level%", 17f)
        sample.addView(intensity)
        val intensityRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        sample.addView(intensityRow)
        val minus = LedSettingsUi.button(this, "−") {
            level = (level - 1).coerceAtLeast(1); intensity.text = "Test brightness: $level%"; queuePreview()
        }
        intensityRow.addView(minus, LinearLayout.LayoutParams(dp(58), dp(52)))
        val previewLevel = SeekBar(this).apply {
            max = 99; progress = level - 1
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, progress: Int, user: Boolean) {
                    if (user) { level = progress + 1; intensity.text = "Test brightness: $level%"; queuePreview() }
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        intensityRow.addView(previewLevel, LinearLayout.LayoutParams(0, dp(52), 1f))
        val plus = LedSettingsUi.button(this, "+") {
            level = (level + 1).coerceAtMost(100); previewLevel.progress = level - 1
            intensity.text = "Test brightness: $level%"; queuePreview()
        }
        intensityRow.addView(plus, LinearLayout.LayoutParams(dp(58), dp(52)))
        val beforeAfterButton = button(sample,
            if (after) "✓ AFTER: apply sliders (tap for BEFORE)" else "✓ BEFORE: no range correction (tap for AFTER)") {
            after = !after; showEditor(); queuePreview()
        }
        button(sample, "Turn preview ON / refresh") { queuePreview() }
        button(sample, "Stop preview / restore current mode") { lifecycleScope.launch { stopPreview(); message("Previous LED mode restored") } }

        val tuning = LedSettingsUi.card(this, root)
        tuning.addView(LedSettingsUi.text(this, "Brightness and color adjustments", 20f))
        val liveTuning: () -> Unit = {
            // Slider changes always show the actual unsaved result, not the BEFORE baseline.
            after = true
            beforeAfterButton.text = "✓ AFTER: apply sliders (tap for BEFORE)"
            queuePreview()
        }
        addTuning(tuning, "Brightness gain", 0, 150, { correction.gain }, { correction.gain = it }, liveTuning)
        addTuning(tuning, "Low-level lift", 0, 16, { correction.lift }, { correction.lift = it }, liveTuning)
        addTuning(tuning, "Red gain", 0, 150, { correction.red }, { correction.red = it }, liveTuning)
        addTuning(tuning, "Green gain", 0, 150, { correction.green }, { correction.green = it }, liveTuning)
        addTuning(tuning, "Blue gain", 0, 150, { correction.blue }, { correction.blue = it }, liveTuning)
        button(tuning, "Reset sliders to neutral") {
            correction.gain = 100; correction.lift = 0
            correction.red = 100; correction.green = 100; correction.blue = 100
            showEditor(); queuePreview()
        }
        button(tuning, "Save calibration to controller") { saveEditor() }
    }

    private fun addTuning(parent: LinearLayout, title: String, min: Int, max: Int,
                          get: () -> Int, put: (Int) -> Unit, previewChanged: () -> Unit) {
        val suffix = if (title == "Low-level lift") "" else "%"
        val label = LedSettingsUi.text(this, "$title: ${get()}$suffix", 16f)
        parent.addView(label)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        parent.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val slider = SeekBar(this).apply {
            this.max = max - min
            progress = (get() - min).coerceIn(0, max - min)
        }
        fun changeBy(delta: Int) {
            val next = (get() + delta).coerceIn(min, max)
            if (next == get()) return
            put(next)
            slider.progress = next - min
            label.text = "$title: ${get()}$suffix"
            previewChanged()
        }
        row.addView(LedSettingsUi.button(this, "−1") { changeBy(-1) },
            LinearLayout.LayoutParams(dp(56), dp(52)))
        row.addView(slider, LinearLayout.LayoutParams(0, dp(52), 1f))
        row.addView(LedSettingsUi.button(this, "+1") { changeBy(1) },
            LinearLayout.LayoutParams(dp(56), dp(52)))
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, user: Boolean) {
                if (user) {
                    put(min + value)
                    label.text = "$title: ${get()}$suffix"
                    previewChanged()
                }
            }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
    }

    /** Conflated live preview: latest slider settings are sent after each BLE acknowledgement,
     * including continuous dragging, without buffering a growing queue of stale commands. */
    private fun queuePreview() {
        val c = editor ?: return
        if (page != Page.EDIT || saving || c.first !in 0 until ledCount || c.last !in 0 until ledCount) return
        fullStripJob?.cancel()
        previewRevision++
        if (previewJob?.isActive == true) return
        previewJob = lifecycleScope.launch {
            while (page == Page.EDIT && !saving) {
                val version = previewRevision
                val snapshot = editor?.copy() ?: return@launch
                val chosenChannel = channel
                val chosenLevel = level
                val chosenAfter = after
                val (r, g, b) = sampleRgb()
                val args = "${snapshot.first},${snapshot.last},$r,$g,$b,${if (chosenAfter) 1 else 0},${snapshot.gain},${snapshot.lift},${snapshot.red},${snapshot.green},${snapshot.blue}"
                val result = bleCommandLock.withLock { ble.controlAndAwait("Cal", "Compare", args) }
                currentCoroutineContext().ensureActive()
                if (version == previewRevision) {
                    if (result != "OK") message("Live preview failed: ${result ?: "controller did not respond"}. Firmware must support 0% gains.")
                    else message("LIVE ${if (chosenAfter) "AFTER" else "BEFORE"} · ${chosenChannel.label} $chosenLevel% · LEDs ${snapshot.first} → ${snapshot.last} (unsaved)")
                    return@launch
                }
                delay(35)
            }
        }
    }

    private fun saveEditor() {
        val c = editor?.copy() ?: return
        if (saving) return
        if (c.first !in 0 until ledCount || c.last !in 0 until ledCount) {
            message("Choose a valid LED range before saving."); return
        }
        if (editorIndex < 0 && stored.size >= 12) { message("Maximum 12 calibration ranges."); return }
        if (stored.indices.any { it != editorIndex && overlaps(c, stored[it]) }) {
            message("This range overlaps an existing calibration. Edit or delete that range first."); return
        }
        val index = if (editorIndex < 0) stored.size else editorIndex
        saving = true
        message("Saving calibration ${index + 1} to Arduino…")
        saveIndicator?.text = "⏳ Saving calibration ${index + 1} to Arduino…"
        lifecycleScope.launch {
            try {
                previewJob?.cancel(); selectionJob?.cancel(); fullStripJob?.cancel()
                val result = bleCommandLock.withLock {
                    // Stop unsaved previews before writing; keep controller writes ordered.
                    val stopReply = ble.controlAndAwait("Cal", "Stop", "1")
                    if (stopReply != "OK") "ERR_STOP:${stopReply ?: "timeout"}"
                    else ble.controlAndAwait("Cal", "Save", c.encode(index))
                }
                if (result != "OK") {
                    val failure = "Save NOT completed: ${result ?: "controller did not respond"}. Check firmware and connection."
                    message(failure)
                    saveIndicator?.text = "✕ $failure"
                    if (!isFinishing && !isDestroyed) AlertDialog.Builder(this@LedCalibrationActivity)
                        .setTitle("Calibration was NOT saved").setMessage(failure)
                        .setPositiveButton("OK", null).show()
                    return@launch
                }
                message("Arduino acknowledged Save. Reading back calibration ${index + 1}…")
                val confirmed = bleCommandLock.withLock {
                    val count = ble.requestAndAwait("Cal", "Count")?.toIntOrNull()
                    val saved = ble.requestAndAwait("Cal", "$index")?.let(Correction::fromResponse)
                    count != null && count >= index + 1 && saved == c
                }
                if (!confirmed) {
                    val failure = "Arduino replied OK, but the values read back did not match. Do not assume the correction is saved."
                    message(failure)
                    saveIndicator?.text = "✕ $failure"
                    if (!isFinishing && !isDestroyed) AlertDialog.Builder(this@LedCalibrationActivity)
                        .setTitle("Could not verify save").setMessage(failure)
                        .setPositiveButton("OK", null).show()
                    return@launch
                }
                reload()
                message("✓ Saved and verified on Arduino: LEDs ${c.first} → ${c.last}. Test Dim to compare the strip.")
                if (!isFinishing && !isDestroyed) {
                    AlertDialog.Builder(this@LedCalibrationActivity)
                        .setTitle("Calibration saved and verified")
                        .setMessage("The Arduino confirmed calibration ${index + 1}, and all values matched when read back.\n\nLEDs ${c.first} → ${c.last}\nBrightness ${c.gain}% · RGB ${c.red}/${c.green}/${c.blue}%")
                        .setPositiveButton("OK", null).show()
                }
            } finally {
                saving = false
            }
        }
    }

    private fun showJog(stage: Page) {
        page = stage
        newPage(if (stage == Page.START) "Choose Start LED" else "Choose End LED",
            "Jog the RED dot to the correct physical LED. Confirmed points stay GREEN. Use single steps near the target.")
        button(root, "‹ Back without changing range") { lifecycleScope.launch { stopPreview(); showEditor() } }
        val sample = LedSettingsUi.card(this, root)
        sample.addView(LedSettingsUi.text(this,
            "Low-brightness preview. Confirmed LEDs are green; the moving LED is red. Choose the reference color and fine-tune brightness while you jog.", 15f, true))
        for (pair in listOf(Channel.RED to Channel.GREEN, Channel.BLUE to Channel.WHITE)) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            sample.addView(line)
            pair.toList().forEach { item ->
                line.addView(LedSettingsUi.button(this, if (channel == item) "✓ ${item.label}" else item.label) {
                    channel = item; showJog(stage)
                }, LinearLayout.LayoutParams(0, dp(48), 1f))
            }
        }
        val intensityLabel = LedSettingsUi.text(this, "Preview intensity: $level%", 16f)
        sample.addView(intensityLabel)
        val intensityRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        sample.addView(intensityRow)
        val intensitySlider = SeekBar(this).apply { max = 99; progress = level - 1 }
        fun adjust(delta: Int) {
            level = (level + delta).coerceIn(1, 100)
            intensitySlider.progress = level - 1
            intensityLabel.text = "Preview intensity: $level%"
            queueJog()
        }
        intensityRow.addView(LedSettingsUi.button(this, "−1%") { adjust(-1) }, LinearLayout.LayoutParams(dp(65), dp(52)))
        intensityRow.addView(intensitySlider, LinearLayout.LayoutParams(0, dp(52), 1f))
        intensityRow.addView(LedSettingsUi.button(this, "+1%") { adjust(1) }, LinearLayout.LayoutParams(dp(65), dp(52)))
        intensitySlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) { level = progress + 1; intensityLabel.text = "Preview intensity: $level%"; queueJog() }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        button(sample, if (jogWholeStrip) "✓ Preview entire strip" else "✓ Preview selected arc") {
            jogWholeStrip = !jogWholeStrip; showJog(stage)
        }
        val card = LedSettingsUi.card(this, root)
        val summary = LedSettingsUi.text(this,
            "${if (stage == Page.START) "START" else "END"} · LED $cursor of ${ledCount - 1}", 21f)
        card.addView(summary)
        val meter = LedRangeTrackView(this).apply {
            count = ledCount; moving = cursor; confirmedStart = startPoint; confirmedEnd = endPoint
        }
        card.addView(meter, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(94)))
        fun jog(delta: Int) {
            cursor = Math.floorMod(cursor + delta, ledCount)
            summary.text = "${if (stage == Page.START) "START" else "END"} · LED $cursor of ${ledCount - 1}"
            meter.moving = cursor; meter.invalidate(); queueJog()
        }
        for (steps in listOf(-25 to 25, -5 to 5, -1 to 1)) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            card.addView(row)
            val left = LedSettingsUi.button(this, "${steps.first}") { jog(steps.first) }
            row.addView(left, LinearLayout.LayoutParams(0, dp(54), 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
            val right = LedSettingsUi.button(this, "+${steps.second}") { jog(steps.second) }
            row.addView(right, LinearLayout.LayoutParams(0, dp(54), 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
        }
        button(card, if (stage == Page.START) "Confirm GREEN start point" else "Confirm GREEN end point") {
            if (stage == Page.START) {
                startPoint = cursor; endPoint = null; cursor = cursor.coerceAtMost(ledCount - 1)
                jogWholeStrip = false // Show the growing, inclusive path after fixing the start.
                showJog(Page.END)
                queueJog()
            } else {
                endPoint = cursor
                val c = editor ?: return@button
                c.first = startPoint ?: c.first; c.last = endPoint ?: c.last
                lifecycleScope.launch { stopPreview(); showEditor() }
            }
        }
        queueJog()
    }

    private fun queueJog() {
        selectionJob?.cancel()
        selectionJob = lifecycleScope.launch {
            delay(75)
            val val8 = (level * 255 / 100).coerceIn(1, 255)
            val r = if (channel == Channel.WHITE || channel == Channel.RED) val8 else 0
            val g = if (channel == Channel.WHITE || channel == Channel.GREEN) val8 else 0
            val b = if (channel == Channel.WHITE || channel == Channel.BLUE) val8 else 0
            val args = "${startPoint ?: ledCount},${endPoint ?: ledCount},$cursor,$r,$g,$b,${if (jogWholeStrip) 2 else 1}"
            val result = bleCommandLock.withLock { ble.controlAndAwait("Cal", "Jog", args) }
            if (result != "OK") message("LED jog not available: ${result ?: "no reply"}. Requires Arduino V9.3.")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (page == Page.LIST) super.onBackPressed()
        else lifecycleScope.launch { stopPreview(); if (page == Page.EDIT) showList() else showEditor() }
    }

    override fun onResume() {
        super.onResume()
        if (!authorizedUi || reauthInProgress || (ble.isConnected && ble.setupAuthenticated)) return
        reauthInProgress = true
        lifecycleScope.launch {
            try {
                if (ControllerPinAccess.ensure(this@LedCalibrationActivity, ble)) reload()
                else ControllerPinAccess.returnToSettings(this@LedCalibrationActivity)
            } finally { reauthInProgress = false }
        }
    }

    override fun onStop() {
        previewJob?.cancel(); selectionJob?.cancel(); fullStripJob?.cancel()
        if (ble.isConnected && ble.setupAuthenticated) ble.sendCommand("Cal", "Stop", "1")
        super.onStop()
    }
}

/** Graphical guide to the saved range and live LED selection without changing room geometry. */
private class LedRangeTrackView(context: android.content.Context) : View(context) {
    var count = 1
    var moving = 0
    var confirmedStart: Int? = null
    var confirmedEnd: Int? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val margin = 22f * resources.displayMetrics.density
        val centerY = height * .55f
        val width = (width - margin * 2).coerceAtLeast(1f)
        p.color = 0xFF435168.toInt(); p.strokeWidth = 12f * resources.displayMetrics.density
        canvas.drawLine(margin, centerY, margin + width, centerY, p)
        fun x(led: Int) = margin + width * led.coerceIn(0, count - 1) / (count - 1).coerceAtLeast(1)
        if (confirmedStart != null && confirmedEnd != null) {
            p.color = 0xFF2B946F.toInt()
            if (confirmedEnd!! >= confirmedStart!!) {
                canvas.drawLine(x(confirmedStart!!), centerY, x(confirmedEnd!!), centerY, p)
            } else {
                canvas.drawLine(x(confirmedStart!!), centerY, x(count - 1), centerY, p)
                canvas.drawLine(x(0), centerY, x(confirmedEnd!!), centerY, p)
            }
        }
        for (confirmed in listOfNotNull(confirmedStart, confirmedEnd)) {
            p.color = Color.GREEN; canvas.drawCircle(x(confirmed), centerY, 10f * resources.displayMetrics.density, p)
        }
        p.color = Color.RED; canvas.drawCircle(x(moving), centerY, 10f * resources.displayMetrics.density, p)
        p.textSize = 12f * resources.displayMetrics.scaledDensity; p.color = Color.WHITE
        canvas.drawText("0", margin, centerY + 28f * resources.displayMetrics.density, p)
        p.textAlign = Paint.Align.RIGHT
        canvas.drawText("${count - 1}", margin + width, centerY + 28f * resources.displayMetrics.density, p)
        p.textAlign = Paint.Align.LEFT
    }
}
