package com.aipro.select

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileInputStream

/** Result screen after a GIF recording: animated preview + Pin / Share / Save / Close. */
class GifResultActivity : Activity() {

    private var file: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val name = Shared.gifResult
        val f = name?.let { File(File(cacheDir, "img"), it) }
        if (f == null || !f.isFile) { finish(); return }
        file = f
        val d = resources.displayMetrics.density
        fun dp(v: Float) = (v * d).toInt()

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.BLACK)

        val iv = ImageView(this)
        try {
            val dr: Drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(f))
            iv.setImageDrawable(dr)
            if (dr is AnimatedImageDrawable) { dr.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE; dr.start() }
        } catch (e: Exception) { }
        val ivp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 0, 1f)
        ivp.gravity = Gravity.CENTER_HORIZONTAL
        ivp.topMargin = dp(48f); ivp.bottomMargin = dp(12f)
        iv.layoutParams = ivp
        iv.adjustViewBounds = true
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        root.addView(iv)

        val meta = TextView(this)
        meta.text = f.name + "  ·  " + String.format("%.2f MB", f.length() / 1048576f)
        meta.setTextColor(Color.parseColor("#AAAAAA"))
        meta.textSize = 13f
        meta.gravity = Gravity.CENTER
        root.addView(meta)

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER
        val bp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        bp.topMargin = dp(14f); bp.bottomMargin = dp(28f)
        bar.layoutParams = bp
        fun action(label: String, gold: Boolean, fn: () -> Unit) {
            val t = TextView(this)
            t.text = label
            t.textSize = 15f
            t.setTextColor(if (gold) Color.parseColor("#F5A623") else Color.WHITE)
            t.gravity = Gravity.CENTER
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            t.layoutParams = lp
            t.setOnClickListener { fn() }
            bar.addView(t)
        }
        action("Pin", false) { pinFirstFrame() }
        action("Share", false) { share() }
        action("Save", true) { save() }
        action("Close", false) { finish() }
        root.addView(bar)
        setContentView(root)
    }

    private fun pinFirstFrame() {
        val bmp = Shared.gifFirstFrame
        if (bmp == null) { Toast.makeText(this, "Pin failed", Toast.LENGTH_SHORT).show(); return }
        CaptureService.instance?.showPin(bmp)
        Toast.makeText(this, "Pinned", Toast.LENGTH_SHORT).show()
    }

    private fun share() {
        val f = file ?: return
        val uri = ImageProvider.uriFor(f.name)
        val i = Intent(Intent.ACTION_SEND)
        i.type = "image/gif"
        i.putExtra(Intent.EXTRA_STREAM, uri)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(i, "Share GIF"))
    }

    private fun save() {
        val f = file ?: return
        try {
            val v = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "AIPro_" + f.name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/gif")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AI Pro")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { out ->
                    FileInputStream(f).use { it.copyTo(out) }
                }
                Toast.makeText(this, "GIF saved to Gallery", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Save failed", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Save failed", Toast.LENGTH_SHORT).show()
        }
    }
}
