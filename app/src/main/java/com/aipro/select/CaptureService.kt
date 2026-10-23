package com.aipro.select

import android.accessibilityservice.AccessibilityService
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.ImageView
import android.widget.Toast
import com.aipro.select.gif.AnimatedGifEncoder
import java.io.ByteArrayOutputStream
import kotlin.math.abs

class CaptureService : AccessibilityService() {

    companion object { @Volatile var instance: CaptureService? = null }
    private val ui = Handler(Looper.getMainLooper())

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onServiceConnected() { instance = this }

    fun captureNow() {
        // Grab the visible app's real text BEFORE our overlay opens - WhatsApp etc.
        // expose exact Unicode text, so copy works in every language with zero OCR error.
        Shared.nodeTexts = dumpNodeTexts()
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                val hw = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                screenshot.hardwareBuffer.close()
                val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                if (bmp != null) {
                    Shared.shot = bmp
                    val i = Intent(this@CaptureService, SelectActivity::class.java)
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(i)
                } else {
                    Toast.makeText(this@CaptureService, "Capture failed", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onFailure(errorCode: Int) {
                Toast.makeText(this@CaptureService, "Capture blocked ($errorCode)", Toast.LENGTH_SHORT).show()
            }
        })
    }

    /** Text-bearing nodes (leaf TextViews etc.) with their screen bounds.
     *  Walks the topmost application window that is NOT ours - our transparent
     *  launch activity may briefly be the active root and it has no text. */
    private fun dumpNodeTexts(): List<Pair<Rect, String>> {
        val out = LinkedHashMap<String, Pair<Rect, String>>()
        val root: AccessibilityNodeInfo = try {
            val wins = windows
            val target = wins?.firstOrNull { w ->
                w.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                    try { w.root?.packageName?.toString() != packageName } catch (e: Exception) { false }
            }
            target?.root ?: rootInActiveWindow
        } catch (e: Exception) { null } ?: return emptyList()
        val stack = ArrayList<AccessibilityNodeInfo>()
        stack.add(root)
        val r = Rect()
        while (stack.isNotEmpty()) {
            val n = stack.removeAt(stack.size - 1)
            try {
                if (n.isVisibleToUser) {
                    val t = n.text?.toString()?.trim()
                    if (!t.isNullOrEmpty() && (n.childCount == 0 || n.className?.contains("TextView") == true)) {
                        n.getBoundsInScreen(r)
                        if (r.width() > 6 && r.height() > 6) out["${r.left},${r.top},${r.right},${r.bottom}|$t"] = Pair(Rect(r), t)
                    }
                    for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
                }
            } catch (e: Exception) { }
            if (n !== root) try { n.recycle() } catch (e: Exception) { }
        }
        return out.values.toList()
    }

    /** Capture after a delay - used after the launch activity exits, so the
     *  screenshot catches the underlying app, not our own window. */
    fun captureDelayed(ms: Long) {
        ui.postDelayed({ captureNow() }, ms)
    }

    private fun shotOnce(cb: (Bitmap?) -> Unit) {
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                val hw = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                screenshot.hardwareBuffer.close()
                cb(hw?.copy(Bitmap.Config.ARGB_8888, false))
            }
            override fun onFailure(errorCode: Int) { cb(null) }
        })
    }

    /** Floating always-on-top image; drag to move, tap to close. */
    fun showPin(bmp: Bitmap) {
        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val iv = ImageView(this)
            iv.setImageBitmap(bmp)
            iv.setBackgroundColor(0xFF101014.toInt())
            val w = (resources.displayMetrics.widthPixels * 0.45f).toInt()
            val h = w * bmp.height / bmp.width
            val lp = WindowManager.LayoutParams(w, h,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT)
            lp.gravity = Gravity.TOP or Gravity.START
            lp.x = 40; lp.y = 220
            var dx = 0f; var dy = 0f; var ox = 0; var oy = 0; var moved = false
            iv.setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { dx = e.rawX; dy = e.rawY; ox = lp.x; oy = lp.y; moved = false }
                    MotionEvent.ACTION_MOVE -> {
                        if (abs(e.rawX - dx) > 12 || abs(e.rawY - dy) > 12) moved = true
                        lp.x = ox + (e.rawX - dx).toInt()
                        lp.y = oy + (e.rawY - dy).toInt()
                        wm.updateViewLayout(iv, lp)
                    }
                    MotionEvent.ACTION_UP -> if (!moved) wm.removeView(iv)
                }
                true
            }
            wm.addView(iv, lp)
        } catch (e: Exception) {
            Toast.makeText(this, "Pin failed", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }
}
