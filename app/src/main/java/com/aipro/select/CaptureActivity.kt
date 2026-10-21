package com.aipro.select

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.Toast

/**
 * Launch pad for edge panel / QS tile / assistant / shortcut triggers.
 * Shows a brief branded opaque screen so the launch really takes focus -
 * that is what makes Samsung's edge panel close itself (a transparent
 * activity that vanishes instantly leaves the panel open). After the
 * hold we exit and capture once the screen is back to the underlying app.
 */
class CaptureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val svc = CaptureService.instance
        if (svc == null) {
            Toast.makeText(this, "Turn on AI Pro in Accessibility first", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        val d = resources.displayMetrics.density
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER
        root.setBackgroundColor(Color.parseColor("#0B0B0E"))
        val title = ShimmerTextView(this)
        title.text = "AI PRO"
        title.textSize = 26f
        title.gravity = Gravity.CENTER
        title.setTypeface(null, android.graphics.Typeface.BOLD)
        title.letterSpacing = 0.25f
        root.addView(title)
        setContentView(root)

        window.decorView.postDelayed({
            finish()
            svc.captureDelayed(550) // let our exit + the panel's close animation finish first
        }, 650)
    }
}
