package com.example.ledwallgui.ui.settings

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Consistent, scrollable LED controller setup pages on narrow phones and landscape tablets. */
internal object LedSettingsUi {
    const val BACKGROUND = 0xFF121721.toInt()
    const val PANEL = 0xFF202B3B.toInt()
    const val SUBTEXT = 0xFFB4C5D8.toInt()

    fun Activity.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    fun page(activity: Activity, title: String): LinearLayout {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BACKGROUND)
            setPadding(activity.dp(18), activity.dp(12), activity.dp(18), activity.dp(32))
        }
        activity.setContentView(ScrollView(activity).apply {
            isFillViewport = true
            addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
        header(activity, root, title)
        return root
    }

    fun header(activity: Activity, parent: LinearLayout, title: String) {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = Button(activity).apply {
            text = "‹"
            textSize = 28f
            setTextColor(Color.WHITE)
            isAllCaps = false
            contentDescription = "Back"
            minWidth = 0; minimumWidth = 0
            setOnClickListener { activity.finish() }
            background = rounded(0xFF2B3547.toInt(), 12f * activity.resources.displayMetrics.density)
        }
        row.addView(back, LinearLayout.LayoutParams(activity.dp(54), activity.dp(54)))
        val text = TextView(activity).apply {
            this.text = title
            textSize = 27f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(activity.dp(12), 0, 0, 0)
        }
        row.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        parent.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(66)))
    }

    fun card(activity: Activity, parent: LinearLayout): LinearLayout {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(14), activity.dp(14), activity.dp(14), activity.dp(14))
            background = rounded(PANEL, activity.dp(16).toFloat())
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, activity.dp(8), 0, activity.dp(10))
        parent.addView(card, lp)
        return card
    }

    fun text(activity: Activity, value: String, sizeSp: Float = 16f, muted: Boolean = false): TextView =
        TextView(activity).apply {
            text = value
            textSize = sizeSp
            setTextColor(if (muted) SUBTEXT else Color.WHITE)
            setPadding(activity.dp(2), activity.dp(6), activity.dp(2), activity.dp(8))
        }

    fun button(activity: Activity, text: String, action: () -> Unit): Button = Button(activity).apply {
        this.text = text
        isAllCaps = false
        textSize = 16f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = rounded(0xFF354B65.toInt(), activity.dp(12).toFloat())
        minHeight = activity.dp(56)
        setOnClickListener { action() }
    }

    fun addButton(activity: Activity, parent: LinearLayout, text: String, action: () -> Unit): Button {
        val b = button(activity, text, action)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(58))
        lp.setMargins(0, activity.dp(5), 0, activity.dp(5))
        parent.addView(b, lp)
        return b
    }

    fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }
}
