package com.example.ledwallgui.ui.modes

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
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.ledwallgui.ui.settings.ControllerPinAccess
import com.example.ledwallgui.ui.settings.GlobalBluetoothManager
import com.example.ledwallgui.ui.settings.RoomConfiguration
import com.example.ledwallgui.ui.settings.RoomSync
import kotlinx.coroutines.launch
import java.util.Locale

/** Bounded, graphical animation sandbox. No arbitrary scripts execute on the controller. */
class AnimationStudioActivity : AppCompatActivity() {
    private data class Stroke(var start: Int, var end: Int, var red: Int, var green: Int, var blue: Int)
    private data class Keyframe(var duration: Int = 350, var crossfade: Boolean = false,
                                val strokes: MutableList<Stroke> = mutableListOf())

    private val ble by lazy { GlobalBluetoothManager(applicationContext) }
    private val ledCount by lazy { (RoomSync.local(this)?.ledCount ?: RoomConfiguration.MAX_LEDS).coerceIn(1, RoomConfiguration.MAX_LEDS) }
    private val frames = mutableListOf<Keyframe>()
    private var slot = 0
    private var selectedFrame = 0
    private var selectedStroke = -1
    private var previewActive = false
    private var busy = false
    private var titleValue = "My Animation"
    private lateinit var root: LinearLayout
    private lateinit var notice: TextView
    private lateinit var name: EditText
    private lateinit var slotChooser: Spinner
    private lateinit var frameView: FrameRibbonView
    private lateinit var frameLabel: TextView
    private lateinit var strokesPanel: LinearLayout
    private lateinit var previewButton: Button
    private lateinit var saveButton: Button

    private fun dp(n: Int) = (n * resources.displayMetrics.density + .5f).toInt()
    private fun text(value: String, size: Float = 16f) = TextView(this).apply {
        text = value; textSize = size; setTextColor(Color.WHITE)
        setPadding(dp(6),dp(8),dp(6),dp(8))
    }
    private fun button(parent: LinearLayout, title: String, action: () -> Unit): Button = Button(this).apply {
        text = title; isAllCaps = false; textSize = 15f
        setOnClickListener { if (!busy) action() }
        parent.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)))
    }
    private fun section(title: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(12), dp(10), dp(12))
        setBackgroundColor(0xFF233549.toInt())
        val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        params.bottomMargin = dp(12)
        root.addView(this, params)
        addView(text(title, 19f))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scrolling = ScrollView(this).apply { isFillViewport = true; setBackgroundColor(0xFF121721.toInt()) }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(24))
        }
        scrolling.addView(root); setContentView(scrolling)
        button(root, "‹ Back to Modes") { finish() }
        root.addView(text("Animation Studio", 27f))
        root.addView(text("Draw colored LED ranges across a timeline. Four controller slots, 16 frames per animation, eight color strokes per frame. These are saved data, not executable scripts.", 15f))
        notice = text("Connect to your controller to load animations.")
        root.addView(notice)
        val slots = section("Controller animation slots")
        slotChooser = Spinner(this)
        slotChooser.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("Slot 1", "Slot 2", "Slot 3", "Slot 4"))
        slots.addView(slotChooser, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))
        name = EditText(this).apply {
            setSingleLine(true); setTextColor(Color.WHITE); setHintTextColor(0xFFB6CBDA.toInt())
            hint = "Animation name (12 characters)"; setText(titleValue)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        slots.addView(name)
        button(slots, "Load selected slot from controller") { loadSlot() }
        button(slots, "New animation in selected slot") {
            slot = slotChooser.selectedItemPosition
            frames.clear(); frames.add(Keyframe(strokes = mutableListOf(Stroke(0, (ledCount-1), 30, 140, 255))))
            selectedFrame = 0; selectedStroke = -1; name.setText("My Animation")
            buildFrameTools(); notice.text = "New unsaved animation."
        }
        button(slots, "Delete saved slot") { deleteSlot() }
        val editor = section("Frame editor")
        frameLabel = text("Frame 1 / 1")
        editor.addView(frameLabel)
        frameView = FrameRibbonView()
        editor.addView(frameView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(104)))
        button(editor, "‹ Previous frame") {
            if (frames.isNotEmpty()) {selectedFrame = (selectedFrame + frames.size - 1) % frames.size;buildFrameTools()}
        }
        button(editor, "Next frame ›") {
            if (frames.isNotEmpty()) {selectedFrame = (selectedFrame + 1) % frames.size;buildFrameTools()}
        }
        button(editor, "+ Duplicate frame") {
            if (frames.size == 16) {notice.text="Maximum 16 frames";return@button}
            val src=frames.getOrNull(selectedFrame) ?: Keyframe()
            frames.add(selectedFrame+1,src.copy(strokes=src.strokes.map {it.copy()}.toMutableList()))
            selectedFrame++;buildFrameTools()
        }
        button(editor, "Remove current frame") {
            if(frames.size<=1){notice.text="Keep at least one frame";return@button}
            frames.removeAt(selectedFrame);selectedFrame=selectedFrame.coerceAtMost(frames.lastIndex);buildFrameTools()
        }
        strokesPanel = LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
        editor.addView(strokesPanel)
        val transport = section("Preview and save")
        previewButton = button(transport, "▶ Preview on controller (not saved)") { upload(save=false) }
        button(transport, "■ Stop preview / restore previous effect") { stopPreview() }
        saveButton = button(transport, "Save animation to controller flash") { upload(save=true) }
        button(transport, "▶ Play saved animation") {
            lifecycleScope.launch {
                if(!ble.isConnected){notice.text="Connect first";return@launch}
                val position=slotChooser.selectedItemPosition
                val result=ble.controlAndAwait("Anim","Play",position.toString())
                notice.text=if(result=="OK") "Playing slot ${position+1} on the controller" else "Could not start: ${result?:"no reply"}"
                if(result=="OK") { previewActive=false; ModeManager.currentMode="Custom Animation" }
            }
        }
        frames.add(Keyframe(strokes = mutableListOf(Stroke(0, (ledCount-1), 30,140,255))))
        buildFrameTools()
        lifecycleScope.launch {
            if (ble.isConnected) {
                val existing = ble.requestAndAwait("Anim", "List")
                notice.text = if(existing!=null) "Controller slots: $existing" else "Controller did not answer. Upload firmware V9.5."
            }
        }
    }

    private fun buildFrameTools() {
        val frame=frames.getOrNull(selectedFrame) ?: return
        frameLabel.text = "Frame ${selectedFrame+1} of ${frames.size} · LED count $ledCount"
        frameView.invalidate();strokesPanel.removeAllViews()
        strokesPanel.addView(text("Frame duration: ${frame.duration} ms"))
        val duration = SeekBar(this).apply {max=4950;progress=frame.duration-50}
        strokesPanel.addView(duration)
        duration.setOnSeekBarChangeListener(change { n ->
            frame.duration = n+50
            (strokesPanel.getChildAt(0) as? TextView)?.text="Frame duration: ${frame.duration} ms"
        })
        button(strokesPanel, if (frame.crossfade) "Crossfade: ON" else "Crossfade: OFF (cut)") {
            frame.crossfade=!frame.crossfade; buildFrameTools()
        }
        for ((index,stroke) in frame.strokes.withIndex()) {
            button(strokesPanel, "Stroke ${index+1}: ${stroke.start}–${stroke.end}  " +
                String.format(Locale.US,"#%02X%02X%02X",stroke.red,stroke.green,stroke.blue)) {
                editStroke(index)
            }
        }
        button(strokesPanel, "+ Paint LED range (max 8)") {
            if(frame.strokes.size>=8){notice.text="Maximum eight ranges per frame";return@button}
            frame.strokes.add(Stroke(0,ledCount-1,255,255,255))
            editStroke(frame.strokes.lastIndex)
        }
    }
    private fun change(callback: (Int)->Unit) = object:SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(bar:SeekBar?,progress:Int,fromUser:Boolean) { if(fromUser) callback(progress) }
        override fun onStartTrackingTouch(bar:SeekBar?) {}
        override fun onStopTrackingTouch(bar:SeekBar?) {}
    }
    private fun editStroke(index:Int) {
        val frame=frames[selectedFrame]
        val stroke=frame.strokes[index]
        val layout=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(8),dp(16),0)}
        fun slider(title:String,initial:Int,maxValue:Int,onValue:(Int)->Unit) {
            val readout=text("$title: $initial")
            layout.addView(readout)
            val bar=SeekBar(this).apply {max=maxValue;progress=initial}
            layout.addView(bar)
            bar.setOnSeekBarChangeListener(change { n ->
                onValue(n);readout.text="$title: $n";frameView.invalidate()
            })
        }
        slider("Start LED",stroke.start,ledCount-1){stroke.start=it}
        slider("End LED",stroke.end,ledCount-1){stroke.end=it}
        slider("Red",stroke.red,255){stroke.red=it}
        slider("Green",stroke.green,255){stroke.green=it}
        slider("Blue",stroke.blue,255){stroke.blue=it}
        AlertDialog.Builder(this).setTitle("Paint stroke ${index+1}")
            .setView(ScrollView(this).apply{addView(layout)})
            .setNegativeButton("Remove") { _,_ -> frame.strokes.removeAt(index);buildFrameTools() }
            .setPositiveButton("Done") { _,_ -> buildFrameTools() }
            .show()
    }
    private suspend fun unlocked(): Boolean {
        if (!ble.ensureConnected()) {notice.text="Controller is disconnected";return false}
        return ControllerPinAccess.ensure(this,ble)
    }
    private fun loadSlot() {
        lifecycleScope.launch {
            if(!ble.ensureConnected()){notice.text="Connect first";return@launch}
            busy=true
            try {
                slot=slotChooser.selectedItemPosition
                val info=ble.requestAndAwait("Anim","S$slot")?.split(',')
                val total=info?.getOrNull(1)?.toIntOrNull() ?: 0
                if(total !in 1..16){notice.text="This slot is empty or unavailable";return@launch}
                val loaded=mutableListOf<Keyframe>()
                for (i in 0 until total) {
                    val data=ble.requestAndAwait("Anim","F${slot}_$i")?.split(',') ?: error("Frame $i not received")
                    val duration=data.getOrNull(0)?.toIntOrNull() ?: error("Invalid duration")
                    val fade=data.getOrNull(1)=="1"
                    val count=data.getOrNull(2)?.toIntOrNull() ?: error("Invalid stroke count")
                    if (count !in 0..8 || duration !in 50..5000) error("Invalid frame data")
                    val loadedFrame=Keyframe(duration,fade)
                    for (j in 0 until count) {
                        val v=ble.requestAndAwait("Anim","P${slot}_${i}_$j")?.split(',')?.mapNotNull(String::toIntOrNull)
                        if(v?.size!=5) error("Stroke $j not received")
                        loadedFrame.strokes.add(Stroke(v[0],v[1],v[2],v[3],v[4]))
                    }
                    loaded.add(loadedFrame)
                }
                frames.clear();frames.addAll(loaded);selectedFrame=0
                name.setText(info?.getOrNull(0) ?: "Animation")
                buildFrameTools();notice.text="Loaded slot ${slot+1} from controller"
            } catch(e:Exception) {notice.text="Load failed: ${e.message}"}
            finally { busy=false }
        }
    }
    private fun upload(save:Boolean) {
        lifecycleScope.launch {
            if (busy) return@launch
            if (!unlocked()) return@launch
            val title=name.text.toString().trim()
            if(!title.matches(Regex("[a-zA-Z0-9 _-]{1,12}"))){notice.text="Name must be 1–12 letters, digits, spaces, _ or -";return@launch}
            if(frames.isEmpty() || frames.size>16) return@launch
            busy=true;previewButton.isEnabled=false;saveButton.isEnabled=false
            val target=slotChooser.selectedItemPosition
            try {
                suspend fun step(part:String,value:String) {
                    val response=ble.controlAndAwait("Anim",part,value)
                    if(response!="OK") error("$part rejected: ${response?:"no reply"}")
                }
                notice.text="Uploading animation..."
                step("Begin","$target,$title")
                for((i,frame) in frames.withIndex()) {
                    if(frame.strokes.size>8) error("Too many strokes")
                    notice.text="Sending frame ${i+1} of ${frames.size}..."
                    step("Frame","$i,${frame.duration},${if(frame.crossfade) 1 else 0}")
                    for (p in frame.strokes) step("Paint","$i,${p.start},${p.end},${p.red},${p.green},${p.blue}")
                }
                if(save) {
                    notice.text="Writing slot ${target+1} to controller flash..."
                    step("Save",target.toString())
                    val readback=ble.requestAndAwait("Anim","S$target")?.split(',')
                    if (readback?.getOrNull(0)!=title || readback?.getOrNull(1)?.toIntOrNull()!=frames.size)
                        error("Save could not be verified")
                    notice.text="Saved and verified '${title}' in controller slot ${target+1}."
                } else {
                    step("Preview","1")
                    previewActive=true
                    notice.text="Previewing on controller. Stop preview to restore the previous effect."
                }
                slot=target
            } catch(e:Exception){notice.text="Animation transfer failed: ${e.message}"}
            finally {busy=false;previewButton.isEnabled=true;saveButton.isEnabled=true}
        }
    }
    private fun stopPreview() {
        lifecycleScope.launch {
            val response=ble.controlAndAwait("Anim","Stop","1")
            if(response=="OK")previewActive=false
            notice.text=if(response=="OK") "Previous LED effect restored" else "Stop failed: ${response?:"no reply"}"
        }
    }
    private fun deleteSlot() {
        val target=slotChooser.selectedItemPosition
        AlertDialog.Builder(this).setTitle("Delete controller slot ${target+1}?")
            .setMessage("This permanently deletes the animation stored in this slot.")
            .setNegativeButton("Cancel",null)
            .setPositiveButton("Delete") { _,_ ->
                lifecycleScope.launch {
                    if(!unlocked())return@launch
                    val result=ble.controlAndAwait("Anim","Delete",target.toString())
                    notice.text=if(result=="OK") "Deleted slot ${target+1}" else "Delete failed: ${result?:"no reply"}"
                }
            }.show()
    }
    override fun onStop() {
        if(previewActive){
            ble.sendCommand("Anim","Stop","1")
            previewActive=false
        }
        super.onStop()
    }
    private inner class FrameRibbonView : View(this@AnimationStudioActivity) {
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val frame=frames.getOrNull(selectedFrame) ?: return
            val cells=if(width<dp(400)) 48 else 72
            val cellWidth=width.toFloat()/cells
            for (i in 0 until cells) {
                val led=(i.toLong()*ledCount/cells).toInt()
                var pixel=Color.BLACK
                for(s in frame.strokes) if(if(s.start<=s.end) led in s.start..s.end else led>=s.start||led<=s.end)
                    pixel=Color.rgb(s.red,s.green,s.blue)
                paint.color=pixel
                canvas.drawRect(i*cellWidth,dp(8).toFloat(),(i+1)*cellWidth-1,dp(58).toFloat(),paint)
            }
            paint.color=0xFFA8C7DE.toInt();paint.textSize=dp(13).toFloat()
            canvas.drawText("LED 0",dp(3).toFloat(),dp(82).toFloat(),paint)
            canvas.drawText("LED ${ledCount-1}",width-dp(80).toFloat(),dp(82).toFloat(),paint)
        }
    }
}
