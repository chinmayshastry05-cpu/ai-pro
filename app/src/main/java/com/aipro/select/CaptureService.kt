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

    /** Records the given on-screen region (physical pixel coords) into an animated GIF. */
    fun captureGifBurst(rect: Rect, frames: Int, intervalMs: Long, done: (Boolean) -> Unit) {
        val list = mutableListOf<Bitmap>()
        var got = 0
        fun step() {
            shotOnce { b ->
                if (b != null) {
                    val l = rect.left.coerceIn(0, b.width - 2)
                    val t = rect.top.coerceIn(0, b.height - 2)
                    val r = rect.right.coerceIn(l + 2, b.width)
                    val bo = rect.bottom.coerceIn(t + 2, b.height)
                    var f = Bitmap.createBitmap(b, l, t, r - l, bo - t)
                    if (f.width > 480) {
                        val nh = f.height * 480 / f.width
                        f = Bitmap.createScaledBitmap(f, 480, nh, true)
                    }
                    list.add(f)
                    got++
                }
                if (got < frames) ui.postDelayed({ step() }, intervalMs) else encodeGif(list, intervalMs, done)
            }
        }
        step()
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
