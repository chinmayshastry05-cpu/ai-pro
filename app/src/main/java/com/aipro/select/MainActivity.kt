package com.aipro.select

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private var attempts = 0
    private val handler = Handler(Looper.getMainLooper())
    private var uiShown = false
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Intent.ACTION_SEND == intent?.action) { handleShare(intent); return }
        if (isServiceEnabled()) {
            // permission already on: opening the app means "capture now"
            tryCapture()
        } else {
            showEnableUI()
        }
    }

    override fun onResume() {
        super.onResume()
        // came back from the Accessibility settings screen with permission now on
        if (uiShown && isServiceEnabled()) tryCapture()
    }

    private var holdShown = false

    private fun tryCapture() {
        val svc = CaptureService.instance
        if (svc != null) {
            // Draw a real first frame immediately so the system splash/starting window
            // clears right away, then hold this branded screen over the ~1-2s capture
            // wait. SelectActivity closes us the moment it is on screen, so the splash
            // icon card can never linger on top of the selection overlay.
            showHoldScreen()
            Shared.closeMain = { finish() }
            svc.captureNow()
            handler.postDelayed({ finish() }, 8000) // safety net if capture fails
        } else if (attempts++ < 12) {
            // service is enabled but the system is still binding it after a cold start
            handler.postDelayed({ tryCapture() }, 250)
        } else {
            showEnableUI()
            if (::status.isInitialized) status.text = "AI Pro is starting up. Tap once more."
        }
    }

    private fun showHoldScreen() {
        if (holdShown || uiShown) return
        holdShown = true
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
    }

    private fun showEnableUI() {
        uiShown = true
        val d = resources.displayMetrics.density
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER_HORIZONTAL
        root.setBackgroundColor(Color.parseColor("#0B0B0E"))
        val pad = (24 * d).toInt()
        root.setPadding(pad, pad * 2, pad, pad)

        val title = ShimmerTextView(this)
        title.text = "AI PRO"
        title.textSize = 34f
        title.gravity = Gravity.CENTER
        title.setTypeface(null, android.graphics.Typeface.BOLD)
        title.letterSpacing = 0.2f
        root.addView(title)

        status = TextView(this)
        status.textSize = 16f
        status.gravity = Gravity.CENTER
        status.setTextColor(Color.WHITE)
        status.setPadding(0, pad, 0, pad)
        status.text = "One-time setup: turn on the AI Pro permission. After that, just opening this app captures the screen."
        root.addView(status)

        val enable = Button(this)
        enable.text = "Enable AI Pro"
        enable.setTextColor(Color.parseColor("#F5A623"))
        enable.setBackgroundResource(R.drawable.btn_gold)
        enable.setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        root.addView(enable)

        val how = TextView(this)
        how.text = "\nOther ways to start a capture (optional):\n- Edge panel: pin \"Capture now\" in the Tasks/Apps panel.\n- Side button hold: Samsung's free RegiStar app, or set AI Pro as your default assistant.\n- Quick Settings tile, or long-press the app icon."
        how.textSize = 14f
        how.setTextColor(Color.parseColor("#AAAAAA"))
        how.setPadding(0, pad, 0, 0)
        root.addView(how)
        setContentView(root)
    }

    private fun isServiceEnabled(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return s.contains("$packageName/.CaptureService") || s.contains("$packageName/com.aipro.select.CaptureService")
    }

    private fun handleShare(intent: Intent) {
        val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        val bmp = uri?.let { contentResolver.openInputStream(it)?.use { s -> BitmapFactory.decodeStream(s) } }
        if (bmp != null) {
            Shared.shot = bmp
            startActivity(Intent(this, SelectActivity::class.java))
        }
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
