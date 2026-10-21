package com.aipro.select

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import com.aipro.select.gif.AnimatedGifEncoder
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ArrayBlockingQueue

/**
 * Records one screen region via MediaProjection into an animated GIF.
 * Frames are cropped and stream-encoded live, so memory stays flat at any length.
 * The stop capsule is a small overlay window placed outside the recorded region.
 */
class GifRecorderService : Service() {
    private val ui = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var pump: HandlerThread? = null
    private var encThread: Thread? = null
    private var stopView: TextView? = null
    @Volatile private var stopping = false
    @Volatile private var frames = 0
    private var startedAt = 0L
    private val maxMs = 30000L

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        when (intent.action) {
            "stop" -> { finishRecording(true); return START_NOT_STICKY }
            "cancel" -> { finishRecording(false); return START_NOT_STICKY }
        }
        val code = intent.getIntExtra("code", Activity.RESULT_CANCELED)
        @Suppress("DEPRECATION") val data: Intent? = intent.getParcelableExtra("data")
        val rect = Rect(intent.getIntExtra("left", 0), intent.getIntExtra("top", 0),
            intent.getIntExtra("right", 0), intent.getIntExtra("bottom", 0))
        val targetW = intent.getIntExtra("targetW", 480)
        val frameMs = intent.getLongExtra("frameMs", 100L)
        if (code != Activity.RESULT_OK || data == null || rect.width() < 8 || rect.height() < 8) {
            Shared.recListener?.invoke(null); stopSelf(); return START_NOT_STICKY
        }

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("rec", "Screen recording", NotificationManager.IMPORTANCE_LOW))
        startForeground(41, Notification.Builder(this, "rec")
            .setContentText("AI Pro is recording a GIF")
            .setSmallIcon(android.R.drawable.presence_video_online).build())

        val pm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = pm.getMediaProjection(code, data)
        projection = mp
        val dm = resources.displayMetrics
        val vw = dm.widthPixels / 2; val vh = dm.heightPixels / 2
        val rd = ImageReader.newInstance(vw, vh, PixelFormat.RGBA_8888, 2)
        reader = rd
        mp.registerCallback(object : MediaProjection.Callback() {}, ui)
        vd = mp.createVirtualDisplay("aipro", vw, vh, dm.densityDpi / 2,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, rd.surface, null, null)

        Shared.gifFirstFrame = null
        Shared.gifResult = null
        startedAt = SystemClockElapsed()
        showStopCapsule(rect)
        startPipeline(rect, vw, vh, targetW, frameMs)
        ui.postDelayed({ finishRecording(true) }, maxMs)
        tickCounter()
        return START_STICKY
    }

    private fun SystemClockElapsed() = android.os.SystemClock.elapsedRealtime()

    private val queue = ArrayBlockingQueue<Bitmap>(3)
    private var outFile: File? = null

    private fun startPipeline(rect: Rect, vw: Int, vh: Int, targetW: Int, frameMs: Long) {
        val dm = resources.displayMetrics
        val l = rect.left * vw / dm.widthPixels
        val t = rect.top * vh / dm.heightPixels
        val r = rect.right * vw / dm.widthPixels
        val b = rect.bottom * vh / dm.heightPixels

        val dir = File(cacheDir, "img"); dir.mkdirs()
        val f = File(dir, "aipro_rec_" + System.currentTimeMillis() + ".gif")
        outFile = f
        val enc = AnimatedGifEncoder()
        val fos = FileOutputStream(f)
        enc.start(fos)
        enc.setRepeat(0)
        enc.setDelay(frameMs.toInt())
        encThread = Thread {
            try {
                while (true) {
                    val bmp = queue.take()
                    if (bmp.width == 1 && bmp.height == 1) break  // poison pill
                    enc.addFrame(bmp)
                    if (Shared.gifFirstFrame == null) Shared.gifFirstFrame = bmp.copy(bmp.config ?: Bitmap.Config.ARGB_8888, false)
                    bmp.recycle()
                }
                enc.finish()
            } catch (e: Exception) { }
            try { fos.close() } catch (e: Exception) { }
        }.also { it.start() }

        val pt = HandlerThread("gifpump"); pt.start(); pump = pt
        val ph = Handler(pt.looper)
        val step = object : Runnable {
            override fun run() {
                if (stopping) return
                try {
                    val img = reader?.acquireLatestImage()
                    if (img != null) {
                        val plane = img.planes[0]
                        val pad = plane.rowStride / plane.pixelStride - img.width
                        val full = Bitmap.createBitmap(img.width + pad, img.height, Bitmap.Config.ARGB_8888)
                        full.copyPixelsFromBuffer(plane.buffer)
                        img.close()
                        val cw = (r - l).coerceIn(2, full.width - l)
                        val ch = (b - t).coerceIn(2, full.height - t)
                        var frame = Bitmap.createBitmap(full, l, t, cw, ch)
                        if (full != frame) full.recycle()
                        if (frame.width > targetW) {
                            val nh = frame.height * targetW / frame.width
                            val sc = Bitmap.createScaledBitmap(frame, targetW, nh, true)
                            if (sc != frame) frame.recycle()
                            frame = sc
                        }
                        queue.offer(frame)  // drop when the encoder is behind
                        frames++
                    }
                } catch (e: Exception) { }
                if (!stopping) ph.postDelayed(this, frameMs)
            }
        }
        ph.post(step)
    }

    private fun tickCounter() {
        if (stopping) return
        val el = (SystemClockElapsed() - startedAt) / 1000
        stopView?.text = "■ Stop · $el / 30 secs"
        ui.postDelayed({ tickCounter() }, 500)
    }

    private fun showStopCapsule(rect: Rect) {
        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val d = resources.displayMetrics.density
            val tv = TextView(this)
            tv.text = "■ Stop · 0 / 30 secs"
            tv.setTextColor(Color.WHITE)
            tv.textSize = 14f
            val pad = (14 * d).toInt()
            tv.setPadding(pad, pad / 2, pad, pad / 2)
            tv.setBackgroundResource(R.drawable.toolbar_bg)
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT)
            // keep the capsule outside the recorded region so it never lands in the GIF
            val capH = (48 * d).toInt()
            val bottomY = resources.displayMetrics.heightPixels - (96 * d).toInt() - capH
            val fitsBelow = bottomY >= rect.bottom + (8 * d).toInt()
            lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            lp.y = if (fitsBelow) bottomY else (rect.top - capH - (8 * d).toInt()).coerceAtLeast((24 * d).toInt())
            tv.setOnClickListener { finishRecording(true) }
            wm.addView(tv, lp)
            stopView = tv
        } catch (e: Exception) { }
    }

    @Synchronized
    private fun finishRecording(save: Boolean) {
        if (stopping) return
        stopping = true
        ui.removeCallbacksAndMessages(null)
        try { pump?.quitSafely() } catch (e: Exception) { }
        try { vd?.release() } catch (e: Exception) { }
        try { reader?.close() } catch (e: Exception) { }
        try { projection?.stop() } catch (e: Exception) { }
        try { stopView?.let { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } } catch (e: Exception) { }
        // flush the encoder, then publish
        Thread {
            try {
                queue.put(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))  // poison pill
                encThread?.join(20000)
            } catch (e: Exception) { }
            var ok = false
            val f = outFile
            if (save && f != null && f.isFile && f.length() > 800 && frames > 0) {
                Shared.gifResult = f.name
                ok = true
            } else {
                try { f?.delete() } catch (e: Exception) { }
            }
            ui.post {
                Shared.recListener?.invoke(null)
                if (ok) {
                    val i = Intent(this, GifResultActivity::class.java)
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(i)
                } else if (save) {
                    Toast.makeText(this, "GIF recording failed", Toast.LENGTH_SHORT).show()
                }
                stopSelf()
            }
        }.start()
    }

    override fun onDestroy() {
        stopping = true
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
