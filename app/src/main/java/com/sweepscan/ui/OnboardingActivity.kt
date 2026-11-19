package com.sweepscan.ui

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.sweepscan.R
import com.sweepscan.SweepScanApp

/**
 * First start (and Ayarlar → Nasıl kullanılır): three short pages — what the app is for, how a
 * row is swept, then "try it with sample data" or "start".
 */
class OnboardingActivity : AppCompatActivity() {

    private data class Page(val icon: Int, val title: Int, val body: Int)

    private val pages = listOf(
        Page(R.drawable.ic_pin, R.string.ob1_title, R.string.ob1_body),
        Page(R.drawable.ic_rows, R.string.ob2_title, R.string.ob2_body),
        Page(R.drawable.ic_truck, R.string.ob3_title, R.string.ob3_body),
    )
    private var index = 0
    private lateinit var icon: ImageView
    private lateinit var title: TextView
    private lateinit var body: TextView
    private lateinit var dots: TextView
    private lateinit var next: MaterialButton
    private lateinit var second: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        fun px(v: Int) = (v * d).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(0xFF121418.toInt())
            setPadding(px(28), px(48), px(28), px(28))
        }
        icon = ImageView(this).apply {
            setBackgroundResource(R.drawable.task_icon_bg)
            setPadding(px(22), px(22), px(22), px(22))
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        // Icon, title and text sit together in the middle; the spacers share the free height.
        root.addView(android.view.View(this), LinearLayout.LayoutParams(1, 0, 1f))
        root.addView(icon, LinearLayout.LayoutParams(px(96), px(96)))
        title = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        root.addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(28) })
        body = TextView(this).apply {
            setTextColor(0xFFB0B8C1.toInt())
            textSize = 17f
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.25f)
        }
        root.addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(14) })
        root.addView(android.view.View(this), LinearLayout.LayoutParams(1, 0, 1.3f))
        dots = TextView(this).apply {
            setTextColor(0xFFFFB300.toInt())
            textSize = 16f
            gravity = Gravity.CENTER
            letterSpacing = 0.3f
        }
        root.addView(dots)
        next = MaterialButton(this).apply { textSize = 17f }
        root.addView(next, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(56)).apply { topMargin = px(16) })
        second = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { textSize = 15f }
        root.addView(second, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(52)).apply { topMargin = px(8) })
        setContentView(root)
        SystemBars.pad(root, top = true, bottom = true)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (index > 0) show(index - 1) else done()
            }
        })
        show(0)
    }

    private fun show(i: Int) {
        index = i
        val p = pages[i]
        // A short fade so a page change reads as a step, not a jump.
        for (v in listOf(icon, title, body)) {
            v.alpha = 0f
            v.animate().alpha(1f).setDuration(220).start()
        }
        icon.setImageResource(p.icon)
        title.setText(p.title)
        body.setText(p.body)
        dots.text = pages.indices.joinToString(" ") { if (it == i) "●" else "○" }
        val last = i == pages.lastIndex
        next.setText(if (last) R.string.ob_start else R.string.ob_next)
        next.setOnClickListener { if (last) done() else show(i + 1) }
        second.setText(if (last) R.string.ob_demo else R.string.ob_skip)
        second.setOnClickListener {
            if (last) {
                (application as SweepScanApp).setDemoMode(true) { done() }
            } else {
                done()
            }
        }
    }

    private fun done() {
        HomeActivity.setOnboarded(this)
        finish()
    }
}
