package com.example.ledwallgui.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.ledwallgui.MainActivity
import com.example.ledwallgui.ui.settings.ConnectionActivity
import com.example.ledwallgui.ui.settings.ControllerSetupActivity
import com.example.ledwallgui.ui.settings.GlobalBluetoothManager
import com.example.ledwallgui.ui.settings.RoomSync
import kotlinx.coroutines.launch

/** First-run walkthrough, shown after clean install or Reset Application. */
class WelcomeTourActivity : AppCompatActivity() {
    companion object {
        const val PREF = "settings_prefs"
        const val DONE = "welcome_complete_v59"
    }
    private data class Slide(val icon:String,val title:String,val text:String)
    private val slides = listOf(
        Slide("✦", "Welcome to LED Wall", "Control your bedroom lighting, room clock and weather directly from this tablet. Made by Cole Rabe."),
        Slide("◈", "Lighting controls", "The main dashboard changes brightness, power and effects. The controller shares live changes with connected devices."),
        Slide("◎", "Modes and effects", "Choose colors, speeds, audio reactions, clock faces and weather animations. Each mode remembers its own settings."),
        Slide("◷", "Alarms", "Capture the exact mode, color and brightness you want. An alarm sends those saved settings to the Arduino when it fires."),
        Slide("⚙", "Settings", "Connect Bluetooth, choose display dimming, manage privacy options, calibrate your room, and see app information from Settings."),
        Slide("⌁", "Connect your controller", "Connect to Room LED Controller. If there is no saved map, we will help set up LED density, discover the strip length, map corners, sync the RTC, and calibrate brightness.")
    )
    private var slide=0
    private var openedConnection=false
    private lateinit var heading:TextView
    private lateinit var body:TextView
    private lateinit var art:TextView
    private lateinit var page:TextView
    private lateinit var next:Button
    private lateinit var previous:Button
    private val ble by lazy { GlobalBluetoothManager(applicationContext) }
    private fun dp(v:Int)= (v*resources.displayMetrics.density).toInt()
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor=0xFF111927.toInt()
        val background=GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xFF101726.toInt(),0xFF122E49.toInt(),0xFF101521.toInt()))
        val outer=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            gravity=Gravity.CENTER_HORIZONTAL
            setPadding(dp(26),dp(26),dp(26),dp(26))
            this.background=background
        }
        setContentView(outer)
        page=label("",14f,0xFF92B8D5.toInt())
        outer.addView(page)
        val panel=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            gravity=Gravity.CENTER
            setPadding(dp(23),dp(24),dp(23),dp(24))
            this.background=GradientDrawable().apply {
                setColor(0xFF26394E.toInt());cornerRadius=dp(22).toFloat()
                setStroke(dp(1),0xFF45749B.toInt())
            }
        }
        outer.addView(panel,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f).apply {
            topMargin=dp(25);bottomMargin=dp(25)
        })
        art=label("",70f,0xFF7EDFF5.toInt());panel.addView(art)
        heading=label("",29f,Color.WHITE).apply { typeface=Typeface.DEFAULT_BOLD }
        panel.addView(heading)
        body=label("",18f,0xFFD5E2F1.toInt());panel.addView(body)
        val controls=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        outer.addView(controls,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(60)))
        previous=button("Back").apply { setOnClickListener { if (slide>0) {slide--;refresh()} else finishTour() } }
        next=button("Next").apply {setOnClickListener {
            if(slide==slides.lastIndex) openConnection() else {slide++;refresh()}
        }}
        controls.addView(previous,LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.MATCH_PARENT,1f).apply {rightMargin=dp(9)})
        controls.addView(next,LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.MATCH_PARENT,2f))
        val skip=label("Skip the tour",14f,0xFFB3C3D4.toInt()).apply {
            setPadding(0,dp(18),0,dp(6));setOnClickListener {finishTour()}
        }
        outer.addView(skip)
        refresh()
    }
    private fun label(text:String,size:Float,color:Int)=TextView(this).apply {
        this.text=text;textSize=size;setTextColor(color);gravity=Gravity.CENTER
        setPadding(dp(8),dp(9),dp(8),dp(9))
    }
    private fun button(text:String)=Button(this).apply {
        this.text=text;isAllCaps=false;textSize=17f;setTextColor(Color.WHITE)
        background=GradientDrawable().apply {setColor(0xFF295D85.toInt());cornerRadius=dp(14).toFloat()}
    }
    private fun refresh() {
        val item=slides[slide]
        page.text="LED WALL • STEP ${slide+1} / ${slides.size}"
        art.animate().cancel();art.alpha=0f;art.translationY=dp(15).toFloat()
        art.text=item.icon;heading.text=item.title;body.text=item.text
        art.animate().alpha(1f).translationY(0f).setDuration(350).start()
        previous.text=if(slide==0) "Close" else "Back"
        next.text=if(slide==slides.lastIndex) "Connect Bluetooth" else "Next"
    }
    private fun openConnection() {
        openedConnection=true
        startActivity(Intent(this,ConnectionActivity::class.java))
    }
    override fun onResume() {
        super.onResume()
        if (!openedConnection) return
        openedConnection=false
        if (!ble.isConnected) return
        lifecycleScope.launch {
            val config=RoomSync.read(ble)
            if (isFinishing || isDestroyed) return@launch
            if (config==null || config.corners.size<4) {
                AlertDialog.Builder(this@WelcomeTourActivity)
                    .setTitle("Controller connected • Let's set up your room")
                    .setMessage("No completed room map was found. The setup wizard will help you set LEDs per meter, map each physical corner and calibrate brightness. The Configuration page also syncs the RTC and DST. You'll need the controller setup PIN.")
                    .setPositiveButton("Start setup") { _, _ ->
                        complete()
                        startActivity(Intent(this@WelcomeTourActivity,ControllerSetupActivity::class.java))
                        finish()
                    }.setNegativeButton("Later") { _, _ ->finishTour() }.show()
            } else finishTour()
        }
    }
    private fun complete() {
        getSharedPreferences(PREF,MODE_PRIVATE).edit().putBoolean(DONE,true).remove("welcome_force_v59").apply()
    }
    private fun finishTour() {
        complete()
        startActivity(Intent(this,MainActivity::class.java))
        finish()
    }
}
