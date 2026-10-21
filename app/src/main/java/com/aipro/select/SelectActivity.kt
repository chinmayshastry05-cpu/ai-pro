package com.aipro.select

import android.animation.ValueAnimator
import android.app.Activity
import android.app.SearchManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.*
import com.google.mlkit.nl.entityextraction.*
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

private val GOLD = Color.parseColor("#F5A623")
private val PASTELS = intArrayOf(
    Color.parseColor("#AFAFF8"), Color.parseColor("#92D3E9"), Color.parseColor("#9BEAD5"),
    Color.parseColor("#C4F8A8"), Color.parseColor("#D4EE94"), Color.parseColor("#AFAFF8"))
private val DRAG_BLUE = Color.parseColor("#A8C8F0")

class SelectActivity : Activity() {

    private var bmp: Bitmap? = null
    private var cropBmp: Bitmap? = null
    private var cropRectBmp: Rect? = null
    private lateinit var selView: SelectionView
    private lateinit var bar: LinearLayout      // icon capsule below selection
    private lateinit var textBar: LinearLayout  // text-action capsule below selection
    private lateinit var gifPill: LinearLayout  // "Create GIF" pill above selection
    private lateinit var closeChip: TextView
    private lateinit var miniCard: LinearLayout // translate output card
    private lateinit var miniText: TextView
    private var currentText = ""
    private var selViewRect: RectF? = null      // confirmed rect in view coords
    private val lineBoxes = mutableListOf<RectF>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val b = Shared.shot
        if (b == null) { finish(); return }
        bmp = b
        try {
            val modes = windowManager.defaultDisplay.supportedModes
            val best = modes.maxByOrNull { it.refreshRate }
            if (best != null) {
                val lp = window.attributes
                lp.preferredDisplayModeId = best.modeId
                window.attributes = lp
            }
        } catch (e: Exception) { }

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.TRANSPARENT)

        selView = SelectionView(this, b,
            onFreePick = { vr -> confirm(snapToText(vr), false) },
            onTap = { x, y -> tapDetect(x, y) },
            onDragStart = { hideChrome() },
            onResizeLive = { vr -> selViewRect = vr; repositionChrome() },
            onResizeEnd = { vr -> confirmResize(vr) })
        root.addView(selView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        buildGifPill(); root.addView(gifPill)
        buildBar(); root.addView(bar)
        buildTextBar(); root.addView(textBar)
        buildMiniCard(); root.addView(miniCard)
        buildCloseChip(); root.addView(closeChip)
        setContentView(root)
        // let the entry animation settle before OCR competes for CPU
        root.postDelayed({ startTapDetectOcr(b) }, 800)
    }

    // ---------- selection ----------

    private fun confirm(viewRect: RectF, fromText: Boolean) {
        val b = bmp ?: return
        val r = selView.viewToBmp(viewRect)
        cropRectBmp = r
        cropBmp = Bitmap.createBitmap(b, r.left, r.top, r.width(), r.height())
        selViewRect = viewRect
        selView.setConfirmed(viewRect)
        showChrome(fromText)
        if (fromText) readText()
    }

    /** Crop-style resize finished: recrop, replay the confirm pulse, refresh OCR if in text mode. */
    private fun confirmResize(viewRect: RectF) {
        val b = bmp ?: return
        val r = selView.viewToBmp(viewRect)
        cropRectBmp = r
        cropBmp = Bitmap.createBitmap(b, r.left, r.top, r.width(), r.height())
        selViewRect = viewRect
        selView.setConfirmed(viewRect)
        repositionChrome()
        if (textBar.visibility == View.VISIBLE) readText()
    }

    /** Move the pill + visible bar with the box while a handle is being dragged (no pop animation). */
    private fun repositionChrome() {
        val r = selViewRect ?: return
        if (gifPill.visibility == View.VISIBLE) repositionPill(gifPill, r)
        val showBar = if (textBar.visibility == View.VISIBLE) textBar else bar
        if (showBar.visibility == View.VISIBLE) repositionBar(showBar, r)
    }

    private fun repositionPill(p: View, r: RectF) {
        val parent = p.parent as View
        val pw = p.width; val ph = p.height
        if (pw == 0 || ph == 0) { p.post { repositionPill(p, r) }; return }
        val x = (r.centerX() - pw / 2f).coerceIn(dp(10f).toFloat(), parent.width - pw - dp(10f).toFloat())
        var y = r.top - ph - dp(10f)
        if (y < dp(44f)) y = (r.top + dp(8f)).coerceAtMost((parent.height - ph - dp(10f)).toFloat())
        p.translationX = x; p.translationY = y
    }

    private fun repositionBar(v: View, r: RectF) {
        val parent = v.parent as View
        val bw = v.width; val bh = v.height
        if (bw == 0 || bh == 0) { v.post { repositionBar(v, r) }; return }
        val x = (r.centerX() - bw / 2f).coerceIn(dp(10f).toFloat(), parent.width - bw - dp(10f).toFloat())
        var y = r.bottom + dp(10f)
        if (y + bh > parent.height - dp(16f)) y = r.top - bh - dp(56f)
        v.translationX = x; v.translationY = y
    }

    private fun hideChrome() {
        gifPill.visibility = View.GONE
        bar.visibility = View.GONE
        textBar.visibility = View.GONE
        miniCard.visibility = View.GONE
        currentText = ""
    }

    /** Smart Select style: after a free drag, grow the box to fully cover the text lines it touches. */
    private fun snapToText(v: RectF): RectF {
        if (lineBoxes.isEmpty()) return v
        val rb = RectF(selView.viewToBmp(v))
        var union: RectF? = null
        val tmp = RectF()
        for (lb in lineBoxes) {
            if (RectF.intersects(rb, lb)) {
                tmp.setIntersect(rb, lb)
                if (tmp.width() * tmp.height() > lb.width() * lb.height() * 0.25f) {
                    if (union == null) union = RectF(lb) else union.union(lb)
                }
            }
        }
        if (union == null) return v
        if (union.width() * union.height() > rb.width() * rb.height() * 9f) return v // exploded, ignore
        union.inset(-8f, -8f)
        union.left = union.left.coerceIn(0f, bmp!!.width - 4f)
        union.right = union.right.coerceIn(union.left + 4f, bmp!!.width.toFloat())
        union.top = union.top.coerceIn(0f, bmp!!.height - 4f)
        union.bottom = union.bottom.coerceIn(union.top + 4f, bmp!!.height.toFloat())
        return selView.bmpToView(union)
    }

    private fun tapDetect(viewX: Float, viewY: Float) {
        val p = selView.viewToBmpPoint(viewX, viewY)
        var best: RectF? = null
        for (box in lineBoxes) {
            if (box.contains(p.x, p.y)) {
                if (best == null || box.width() * box.height() < best!!.width() * best!!.height()) best = RectF(box)
            }
        }
        if (best == null) { shapeDetect(viewX, viewY); return }
        run {
            val best = best!!
            best.inset(-10f, -10f)
            best.left = best.left.coerceIn(0f, bmp!!.width - 4f)
            best.right = best.right.coerceIn(best.left + 4f, bmp!!.width.toFloat())
            best.top = best.top.coerceIn(0f, bmp!!.height - 4f)
            best.bottom = best.bottom.coerceIn(best.top + 4f, bmp!!.height.toFloat())
            confirm(selView.bmpToView(best), true)
        }
    }

    /**
     * Smart Select style tap-to-select: tap a card, icon, image or any figure on screen
     * and the whole shape gets selected. Pure on-device edge detection, no network, no
     * ML models: downscale the screenshot, find the nearest strong vertical/horizontal
     * edges around the tap that have real support (a shape boundary, not text noise),
     * and box them. The result is a normal confirmed selection - rounded border, live
     * resizable via the crop handles like any other selection.
     */
    private fun shapeDetect(viewX: Float, viewY: Float) {
        val full = bmp ?: return
        Thread {
            try {
                val w = 270
                val sc = w.toFloat() / full.width
                val h = (full.height * sc).toInt().coerceAtLeast(2)
                val small = Bitmap.createScaledBitmap(full, w, h, true)
                val px = IntArray(w * h)
                small.getPixels(px, 0, w, 0, 0, w, h)
                val lum = IntArray(w * h)
                for (i in px.indices) {
                    val c = px[i]
                    lum[i] = ((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
                }
                fun gx(x: Int, y: Int) = kotlin.math.abs(lum[y * w + x + 1] - lum[y * w + x - 1])
                fun gy(x: Int, y: Int) = kotlin.math.abs(lum[(y + 1) * w + x] - lum[(y - 1) * w + x])
                var sum = 0L; var n = 0
                var y = 1
                while (y < h - 1) { var x = 1; while (x < w - 1) { sum += gx(x, y) + gy(x, y); n++; x += 3 }; y += 3 }
                val minG = maxOf(20, (sum / maxOf(1, n)) * 2).toInt()

                val tp = selView.viewToBmpPoint(viewX, viewY)
                val tx = (tp.x * sc).toInt().coerceIn(2, w - 3)
                val ty = (tp.y * sc).toInt().coerceIn(2, h - 3)

                fun colSupport(cx: Int, y0: Int, y1: Int): Int {
                    var s2 = 0
                    var yy = y0.coerceIn(1, h - 2)
                    val ye = y1.coerceIn(1, h - 2)
                    while (yy <= ye) {
                        if (gx((cx - 1).coerceIn(1, w - 2), yy) >= minG || gx(cx, yy) >= minG || gx((cx + 1).coerceIn(1, w - 2), yy) >= minG) s2++
                        yy++
                    }
                    return s2
                }
                fun rowSupport(cy: Int, x0: Int, x1: Int): Int {
                    var s2 = 0
                    var xx = x0.coerceIn(1, w - 2)
                    val xe = x1.coerceIn(1, w - 2)
                    while (xx <= xe) {
                        if (gy(xx, (cy - 1).coerceIn(1, h - 2)) >= minG || gy(xx, cy) >= minG || gy(xx, (cy + 1).coerceIn(1, h - 2)) >= minG) s2++
                        xx++
                    }
                    return s2
                }

                fun emitBox(l: Int, t: Int, r: Int, b: Int) {
                    val bw = (r - l).toFloat(); val bh = (b - t).toFloat()
                    val padX = bw * 0.015f + 2f; val padY = bh * 0.015f + 2f
                    val rect = RectF(
                        ((l - padX) / sc).coerceIn(0f, full.width - 4f),
                        ((t - padY) / sc).coerceIn(0f, full.height - 4f),
                        ((r + padX) / sc).coerceIn(4f, full.width.toFloat()),
                        ((b + padY) / sc).coerceIn(4f, full.height.toFloat()))
                    runOnUiThread {
                        if (!isFinishing) confirm(selView.bmpToView(rect), false)
                    }
                }

                // --- Pass 1: enclosing block (thumbnails, cards, feed rows) ---
                // Find the strong FULL-WIDTH horizontal lines above and below the tap:
                // those are the boundaries of the content block, while inner detail
                // (faces, text, phone screens) never spans the width. Vertical sides
                // default to the screen edges (thumbnails bleed to the edge) unless a
                // near-full-height vertical edge says the block has real side margins.
                run {
                    val x0w = (w * 0.06f).toInt(); val x1w = (w * 0.94f).toInt()
                    val needRow = ((x1w - x0w) * 0.6f).toInt()
                    var T = -1; var B = -1
                    var y2 = ty - 3
                    while (y2 > 2) { if (gy(tx, y2) >= minG && rowSupport(y2, x0w, x1w) >= needRow) { T = y2; break }; y2-- }
                    y2 = ty + 3
                    while (y2 < h - 3) { if (gy(tx, y2) >= minG && rowSupport(y2, x0w, x1w) >= needRow) { B = y2; break }; y2++ }
                    if (T >= 0 && B > T && B - T >= 12) {
                        var L = 2; var R = w - 3
                        val needCol = ((B - T - 4) * 0.85f).toInt().coerceAtLeast(8)
                        var x2 = tx - 3
                        while (x2 > 2) { if (gx(x2, ty) >= minG && colSupport(x2, T + 2, B - 2) >= needCol) { L = x2; break }; x2-- }
                        x2 = tx + 3
                        while (x2 < w - 3) { if (gx(x2, ty) >= minG && colSupport(x2, T + 2, B - 2) >= needCol) { R = x2; break }; x2++ }
                        val bw = (R - L).toFloat(); val bh = (B - T).toFloat()
                        val aspect = bw / bh
                        if (R - L >= 12 && aspect >= 0.12f && aspect <= 8f &&
                            !(bw > w * 0.92f && bh > h * 0.9f)) {
                            emitBox(L, T, R, B)
                            return@Thread
                        }
                    }
                }

                // --- Pass 2: nearest strong edges (small objects, shapes, icons) ---
                var L = -1; var R = -1
                var x = tx - 3
                while (x > 2) { if (gx(x, ty) >= minG && colSupport(x, ty - 24, ty + 24) >= 20) { L = x; break }; x-- }
                x = tx + 3
                while (x < w - 3) { if (gx(x, ty) >= minG && colSupport(x, ty - 24, ty + 24) >= 20) { R = x; break }; x++ }
                if (L < 0 || R < 0 || R - L < 12) return@Thread

                var T = -1; var B = -1
                val needRow = ((R - L - 4) * 0.38f).toInt().coerceAtLeast(8)
                y = ty - 3
                while (y > 2) { if (gy(tx, y) >= minG && rowSupport(y, L + 2, R - 2) >= needRow) { T = y; break }; y-- }
                y = ty + 3
                while (y < h - 3) { if (gy(tx, y) >= minG && rowSupport(y, L + 2, R - 2) >= needRow) { B = y; break }; y++ }
                if (T < 0 || B < 0 || B - T < 12) return@Thread

                val bw = (R - L).toFloat(); val bh = (B - T).toFloat()
                val aspect = bw / bh
                if (aspect < 0.12f || aspect > 8f) return@Thread
                if (bw > w * 0.92f && bh > h * 0.9f) return@Thread

                emitBox(L, T, R, B)
            } catch (e: Exception) { }
        }.start()
    }

    private fun startTapDetectOcr(b: Bitmap) {
        val img = InputImage.fromBitmap(b, 0)
        val latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val deva = TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
        latin.process(img).addOnSuccessListener { r -> for (blk in r.textBlocks) for (ln in blk.lines) ln.boundingBox?.let { lineBoxes.add(RectF(it)) } }
        deva.process(img).addOnSuccessListener { r -> for (blk in r.textBlocks) for (ln in blk.lines) ln.boundingBox?.let { lineBoxes.add(RectF(it)) } }
    }

    // ---------- chrome (pill, bars, chips) ----------

    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()

    private fun buildCloseChip() {
        closeChip = TextView(this)
        closeChip.text = "✕"
        closeChip.textSize = 16f
        closeChip.setTextColor(Color.WHITE)
        closeChip.gravity = Gravity.CENTER
        closeChip.setBackgroundResource(R.drawable.chip_close)
        val s = dp(40f)
        val lp = FrameLayout.LayoutParams(s, s, Gravity.TOP or Gravity.END)
        lp.topMargin = dp(48f); lp.rightMargin = dp(14f)
        closeChip.layoutParams = lp
        closeChip.alpha = 0f
        closeChip.setOnClickListener { finish() }
        closeChip.post { closeChip.animate().alpha(1f).setDuration(250).start() }
    }

    private fun buildGifPill() {
        gifPill = LinearLayout(this)
        gifPill.orientation = LinearLayout.HORIZONTAL
        gifPill.gravity = Gravity.CENTER_VERTICAL
        gifPill.setBackgroundResource(R.drawable.pill_bg)
        val badge = TextView(this)
        badge.text = "GIF"
        badge.textSize = 9f
        badge.setTypeface(null, Typeface.BOLD)
        badge.setTextColor(Color.parseColor("#3A3A3D"))
        badge.setBackgroundResource(R.drawable.gif_badge)
        badge.setPadding(dp(5f), dp(3f), dp(5f), dp(3f))
        gifPill.addView(badge)
        val label = TextView(this)
        label.text = "Create GIF"
        label.textSize = 14.5f
        label.setTextColor(Color.WHITE)
        label.setPadding(dp(8f), 0, 0, 0)
        gifPill.addView(label)
        gifPill.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START)
        gifPill.visibility = View.GONE
        gifPill.setOnClickListener { makeGif() }
    }

    private fun chipIcon(res: Int, desc: String, f: () -> Unit): ImageView {
        val iv = ImageView(this)
        iv.setImageResource(res)
        iv.contentDescription = desc
        val s = dp(44f)
        iv.layoutParams = LinearLayout.LayoutParams(s, s)
        iv.setBackgroundResource(R.drawable.chip_bg)
        iv.scaleType = ImageView.ScaleType.CENTER_INSIDE
        val p = dp(10f)
        iv.setPadding(p, p, p, p)
        iv.setOnClickListener { f() }
        return iv
    }

    private fun buildBar() {
        bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER
        bar.setBackgroundResource(R.drawable.toolbar_bg)
        bar.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START)
        bar.addView(chipIcon(R.drawable.ic_text, "Text") { switchToTextMode() })
        bar.addView(chipIcon(R.drawable.ic_pin, "Pin") { pinImage() })
        bar.addView(chipIcon(R.drawable.ic_copy_img, "Copy image") { copyImage() })
        bar.addView(chipIcon(R.drawable.ic_share, "Share") { shareImage() })
        bar.addView(chipIcon(R.drawable.ic_save, "Save") { saveImage() })
        bar.addView(chipIcon(R.drawable.ic_search, "Search") { searchImage() })
        bar.visibility = View.GONE
    }

    private fun textBtn(t: String, f: () -> Unit): TextView {
        val tv = TextView(this)
        tv.text = t
        tv.textSize = 13.5f
        tv.setTextColor(Color.WHITE)
        tv.setPadding(dp(13f), dp(10f), dp(13f), dp(10f))
        tv.setOnClickListener { f() }
        return tv
    }

    private fun buildTextBar() {
        textBar = LinearLayout(this)
        textBar.orientation = LinearLayout.HORIZONTAL
        textBar.gravity = Gravity.CENTER
        textBar.setBackgroundResource(R.drawable.toolbar_bg)
        textBar.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START)
        textBar.addView(textBtn("Translate") { translateText() })
        textBar.addView(textBtn("Copy") { copyText() })
        textBar.addView(textBtn("Share") { shareText() })
        textBar.addView(textBtn("Search") { searchText() })
        textBar.addView(textBtn("Add event") { addEvent() })
        textBar.visibility = View.GONE
    }

    private fun buildMiniCard() {
        miniCard = LinearLayout(this)
        miniCard.orientation = LinearLayout.VERTICAL
        miniCard.setBackgroundResource(R.drawable.pill_bg)
        miniCard.setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
        miniText = TextView(this)
        miniText.textSize = 14f
        miniText.setTextColor(Color.WHITE)
        miniText.setTextIsSelectable(true)
        miniCard.addView(miniText)
        miniCard.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START)
        miniCard.visibility = View.GONE
        miniCard.setOnClickListener { miniCard.visibility = View.GONE }
    }

    private fun showChrome(fromText: Boolean) {
        val r = selViewRect ?: return
        val showBar = if (fromText) textBar else bar
        if (fromText) bar.visibility = View.GONE else textBar.visibility = View.GONE
        gifPill.scaleX = 0.6f; gifPill.scaleY = 0.6f; gifPill.alpha = 0f
        showBar.scaleX = 0.6f; showBar.scaleY = 0.6f; showBar.alpha = 0f
        gifPill.visibility = View.VISIBLE
        showBar.visibility = View.VISIBLE
        // anchor: pill above selection, bar below selection (Smart Select layout)
        gifPill.post {
            val screenW = (gifPill.parent as View).width
            val screenH = (gifPill.parent as View).height
            val pw = gifPill.width; val ph = gifPill.height
            var x = (r.centerX() - pw / 2f).coerceIn(dp(10f).toFloat(), screenW - pw - dp(10f).toFloat())
            var y = r.top - ph - dp(10f)
            if (y < dp(44f)) y = (r.top + dp(8f)).coerceAtMost((screenH - ph - dp(10f)).toFloat())
            gifPill.translationX = x; gifPill.translationY = y
            gifPill.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(220).setInterpolator(OvershootInterpolator(1.6f)).start()
        }
        showBar.post {
            val screenW = (showBar.parent as View).width
            val screenH = (showBar.parent as View).height
            val bw = showBar.width; val bh = showBar.height
            var x = (r.centerX() - bw / 2f).coerceIn(dp(10f).toFloat(), screenW - bw - dp(10f).toFloat())
            var y = r.bottom + dp(10f)
            if (y + bh > screenH - dp(16f)) y = r.top - bh - dp(56f) // flip above, under the pill
            showBar.translationX = x; showBar.translationY = y
            showBar.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(220).setStartDelay(40).setInterpolator(OvershootInterpolator(1.6f)).start()
        }
    }

    private fun makeGif() {
        val r = cropRectBmp ?: return
        val svc = CaptureService.instance
        if (svc == null) { Toast.makeText(this, "Capture service not ready", Toast.LENGTH_SHORT).show(); return }
        gifPill.alpha = 0f; bar.alpha = 0f; textBar.alpha = 0f; selView.alpha = 0f; closeChip.alpha = 0f; miniCard.alpha = 0f
        window.decorView.postDelayed({
            svc.captureGifBurst(r, 5, 900) { }
            finish()
        }, 300)
    }

    // ---------- image actions ----------

    private fun cacheCrop(name: String): Uri? {
        val c = cropBmp ?: return null
        return try {
            val f = File(cacheDir, "img/$name")
            f.parentFile?.mkdirs()
            FileOutputStream(f).use { c.compress(Bitmap.CompressFormat.PNG, 100, it) }
            ImageProvider.uriFor(name)
        } catch (e: Exception) { null }
    }

    private fun copyImage() {
        val uri = cacheCrop("ai_pro_clip.png") ?: return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newUri(contentResolver, "AI Pro image", uri))
        Toast.makeText(this, "Image copied", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun shareImage() {
        val uri = cacheCrop("ai_pro_share.png") ?: return
        val i = Intent(Intent.ACTION_SEND)
        i.type = "image/png"
        i.putExtra(Intent.EXTRA_STREAM, uri)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(i, "Share image"))
        finish()
    }

    private fun saveImage() {
        val c = cropBmp ?: return
        try {
            val name = "AIPro_" + System.currentTimeMillis() + ".png"
            val v = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AI Pro")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { c.compress(Bitmap.CompressFormat.PNG, 100, it) }
                Toast.makeText(this, "Saved to Gallery", Toast.LENGTH_SHORT).show()
                finish()
            } else Toast.makeText(this, "Save failed", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) { Toast.makeText(this, "Save failed", Toast.LENGTH_SHORT).show() }
    }

    private fun searchImage() {
        val uri = cacheCrop("ai_pro_lens.png")
        try {
            val i = Intent(Intent.ACTION_SEND)
            i.type = "image/png"
            i.putExtra(Intent.EXTRA_STREAM, uri)
            i.setPackage("com.google.android.googlequicksearchbox")
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(i)
            finish()
        } catch (e: Exception) {
            // no Google app share handler: read the text and search Google instead
            Toast.makeText(this, "Searching text on Google...", Toast.LENGTH_SHORT).show()
            val c = cropBmp ?: run { finish(); return }
            runOcrForSearch(c)
        }
    }

    private fun runOcrForSearch(crop: Bitmap) {
        val img = InputImage.fromBitmap(crop, 0)
        val latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val deva = TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
        fun go(q: String) {
            if (q.isBlank()) { Toast.makeText(this, "No text to search", Toast.LENGTH_SHORT).show(); finish(); return }
            try {
                val i = Intent(Intent.ACTION_WEB_SEARCH)
                i.putExtra(SearchManager.QUERY, q)
                startActivity(i)
            } catch (e: Exception) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))))
            }
            finish()
        }
        latin.process(img).addOnSuccessListener { lr ->
            deva.process(img).addOnSuccessListener { dr ->
                go(if (dr.text.trim().length > lr.text.trim().length) dr.text.trim() else lr.text.trim())
            }.addOnFailureListener { go(lr.text.trim()) }
        }.addOnFailureListener {
            deva.process(img).addOnSuccessListener { dr -> go(dr.text.trim()) }
                .addOnFailureListener { Toast.makeText(this, "Read failed", Toast.LENGTH_SHORT).show(); finish() }
        }
    }

    private fun pinImage() {
        val c = cropBmp ?: return
        val svc = CaptureService.instance
        if (svc == null) { Toast.makeText(this, "Capture service not ready", Toast.LENGTH_SHORT).show(); return }
        svc.showPin(c)
        Toast.makeText(this, "Pinned - drag to move, tap to close", Toast.LENGTH_SHORT).show()
        finish()
    }

    // ---------- text actions ----------

    private fun switchToTextMode() {
        bar.visibility = View.GONE
        textBar.visibility = View.VISIBLE
        showChrome(true)
        readText()
    }

    private fun readText() {
        val c = cropBmp ?: return
        Toast.makeText(this, "Reading text...", Toast.LENGTH_SHORT).show()
        runOcr(c)
    }

    private fun runOcr(crop: Bitmap) {
        val img = InputImage.fromBitmap(crop, 0)
        val latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val deva = TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
        latin.process(img).addOnSuccessListener { lr ->
            deva.process(img).addOnSuccessListener { dr ->
                finishOcr(if (dr.text.trim().length > lr.text.trim().length) dr.text else lr.text)
            }.addOnFailureListener { finishOcr(lr.text) }
        }.addOnFailureListener {
            deva.process(img).addOnSuccessListener { dr -> finishOcr(dr.text) }
                .addOnFailureListener { Toast.makeText(this, "Read failed, try again", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun finishOcr(text: String) {
        currentText = text.trim()
        if (currentText.isEmpty()) {
            currentText = ""
            Toast.makeText(this, "No text found - drag a box around the text", Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyText() {
        if (currentText.isEmpty()) { Toast.makeText(this, "Still reading - tap again in a second", Toast.LENGTH_SHORT).show(); return }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("AI Pro", currentText))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun shareText() {
        if (currentText.isEmpty()) { Toast.makeText(this, "Still reading - tap again in a second", Toast.LENGTH_SHORT).show(); return }
        val i = Intent(Intent.ACTION_SEND)
        i.type = "text/plain"
        i.putExtra(Intent.EXTRA_TEXT, currentText)
        startActivity(Intent.createChooser(i, "Share"))
        finish()
    }

    private fun searchText() {
        if (currentText.isEmpty()) { Toast.makeText(this, "Still reading - tap again in a second", Toast.LENGTH_SHORT).show(); return }
        try {
            val i = Intent(Intent.ACTION_WEB_SEARCH)
            i.putExtra(SearchManager.QUERY, currentText)
            startActivity(i)
        } catch (e: Exception) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(currentText))))
        }
    }

    private fun translateText() {
        val src = currentText
        if (src.isBlank()) { Toast.makeText(this, "Still reading - tap again in a second", Toast.LENGTH_SHORT).show(); return }
        Toast.makeText(this, "Translating (first time downloads a small model)...", Toast.LENGTH_SHORT).show()
        LanguageIdentification.getClient().identifyLanguage(src).addOnSuccessListener { lang ->
            val fromEn = lang == "en"
            val opts = TranslatorOptions.Builder()
                .setSourceLanguage(if (fromEn) TranslateLanguage.ENGLISH else TranslateLanguage.HINDI)
                .setTargetLanguage(if (fromEn) TranslateLanguage.HINDI else TranslateLanguage.ENGLISH)
                .build()
            val tr = Translation.getClient(opts)
            tr.downloadModelIfNeeded().addOnSuccessListener {
                tr.translate(src).addOnSuccessListener { out ->
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("AI Pro translation", out))
                    Toast.makeText(this, "Translated - copied to clipboard", Toast.LENGTH_SHORT).show()
                    finish()
                }.addOnFailureListener { Toast.makeText(this, "Translate failed", Toast.LENGTH_SHORT).show() }
            }.addOnFailureListener { Toast.makeText(this, "Model download failed - check internet", Toast.LENGTH_SHORT).show() }
        }.addOnFailureListener { Toast.makeText(this, "Language detect failed", Toast.LENGTH_SHORT).show() }
    }

    private fun showMini(text: String) {
        val r = selViewRect ?: return
        miniText.text = text
        miniCard.visibility = View.VISIBLE
        miniCard.post {
            val parent = miniCard.parent as View
            val mw = minOf(dp(320f), parent.width - dp(24f))
            miniCard.layoutParams.width = mw
            miniCard.requestLayout()
            miniCard.translationX = (r.centerX() - mw / 2f).coerceIn(dp(12f).toFloat(), parent.width - mw - dp(12f).toFloat())
            miniCard.translationY = (r.top - dp(70f)).coerceAtLeast(dp(48f).toFloat())
        }
    }

    private fun addEvent() {
        val text = currentText
        if (text.isBlank()) { Toast.makeText(this, "Still reading - tap again in a second", Toast.LENGTH_SHORT).show(); return }
        val ex = EntityExtraction.getClient(EntityExtractorOptions.Builder(EntityExtractorOptions.ENGLISH).build())
        fun openCal(startMillis: Long?) {
            val i = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
            i.putExtra(CalendarContract.Events.TITLE, text.lineSequence().firstOrNull()?.take(60) ?: "AI Pro event")
            i.putExtra(CalendarContract.Events.DESCRIPTION, text)
            if (startMillis != null) {
                i.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis)
                i.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, startMillis + 60 * 60 * 1000)
            }
            try { startActivity(i); finish() } catch (e: Exception) { Toast.makeText(this, "No calendar app found", Toast.LENGTH_SHORT).show() }
        }
        ex.downloadModelIfNeeded().addOnSuccessListener {
            ex.annotate(EntityExtractionParams.Builder(text).build()).addOnSuccessListener { anns ->
                var ts: Long? = null
                outer@ for (a in anns) for (e in a.entities) if (e is DateTimeEntity) { ts = e.timestampMillis; break@outer }
                openCal(ts)
            }.addOnFailureListener { openCal(null) }
        }.addOnFailureListener { openCal(null) }
    }

    override fun onDestroy() {
        Shared.shot = null
        super.onDestroy()
    }
}

class SelectionView(context: Context, private val bmp: Bitmap,
                    private val onFreePick: (RectF) -> Unit,
                    private val onTap: (Float, Float) -> Unit,
                    private val onDragStart: () -> Unit,
                    private val onResizeLive: (RectF) -> Unit,
                    private val onResizeEnd: (RectF) -> Unit) : View(context) {

    private var scale = 1f
    private var ox = 0f
    private var oy = 0f
    private var sx = -1f; private var sy = -1f; private var ex = -1f; private var ey = -1f
    private var confirmed: RectF? = null
    private var resizeHandle = -1   // 0=TL 1=T 2=TR 3=R 4=BR 5=B 6=BL 7=L
    private var insideDown = false
    private var angle = 0f
    private var edgeAlpha = 0f
    private var pulse = 1f
    private var anim: ValueAnimator? = null
    private var pulseAnim: ValueAnimator? = null
    private val dim = Paint().apply { color = 0x4D000000 }
    private val tmpPath = Path()

    // cached paints + shaders (no per-frame allocation; glow = layered strokes, no mask filters)
    private val gradReach = 300f * resources.displayMetrics.density
    private val edgeShader = LinearGradient(-gradReach, 0f, gradReach, 0f, PASTELS, null, Shader.TileMode.CLAMP)
    private val selShader = LinearGradient(-gradReach, 0f, gradReach, 0f, PASTELS, null, Shader.TileMode.CLAMP)
    private val edgeMatrix = Matrix()
    private val selMatrix = Matrix()
    private val edgeGlow2 = stroke(0f, 36)
    private val edgeGlow1 = stroke(0f, 70)
    private val edgeMain = stroke(0f, 255)
    private val selGlow2 = stroke(0f, 40)
    private val selGlow1 = stroke(0f, 80)
    private val selMain = stroke(0f, 255)
    private val dragFaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = DRAG_BLUE; style = Paint.Style.STROKE; alpha = 110
    }
    private val dragBracket = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = DRAG_BLUE; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val bmpShader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = bmpShader }
    private val bmpMatrix = Matrix()
    private var cx = 0f; private var cy = 0f
    private var edgeRect = RectF(); private var edgeRadius = 0f; private var selRadius = 0f

    private fun dpF(v: Float) = v * resources.displayMetrics.density
    private fun stroke(w: Float, a: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = w; strokeCap = Paint.Cap.ROUND; alpha = a
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        anim = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 3000
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { angle = it.animatedValue as Float; invalidate() }
            start()
        }
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 180
            addUpdateListener { edgeAlpha = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        pulseAnim?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        scale = min(w.toFloat() / bmp.width, h.toFloat() / bmp.height)
        ox = (w - bmp.width * scale) / 2f
        oy = (h - bmp.height * scale) / 2f
        cx = w / 2f; cy = h / 2f
        bmpMatrix.setScale(scale, scale)
        bmpMatrix.postTranslate(ox, oy)
        bmpShader.setLocalMatrix(bmpMatrix)
        val inset = dpF(2f)
        edgeRect = RectF(inset, inset, w - inset, h - inset)
        edgeRadius = dpF(18f)
        selRadius = dpF(24f)
        edgeMain.strokeWidth = dpF(3.2f)
        edgeGlow1.strokeWidth = dpF(6.5f)
        edgeGlow2.strokeWidth = dpF(11f)
        selMain.strokeWidth = dpF(2.8f)
        selGlow1.strokeWidth = dpF(5.5f)
        selGlow2.strokeWidth = dpF(9f)
        dragFaint.strokeWidth = dpF(1.4f)
        dragBracket.strokeWidth = dpF(3.4f)
        // gradient unit line; rotated per frame via local matrix (cheap)
        edgeShader.setLocalMatrix(edgeMatrix)
        selShader.setLocalMatrix(selMatrix)
        edgeGlow2.shader = edgeShader; edgeGlow1.shader = edgeShader; edgeMain.shader = edgeShader
        selGlow2.shader = selShader; selGlow1.shader = selShader; selMain.shader = selShader
    }

    fun viewToBmp(v: RectF): Rect {
        val l = ((v.left - ox) / scale).toInt().coerceIn(0, bmp.width - 2)
        val t = ((v.top - oy) / scale).toInt().coerceIn(0, bmp.height - 2)
        val r = ((v.right - ox) / scale).toInt().coerceIn(l + 2, bmp.width)
        val b = ((v.bottom - oy) / scale).toInt().coerceIn(t + 2, bmp.height)
        return Rect(l, t, r, b)
    }

    fun viewToBmpPoint(x: Float, y: Float): PointF = PointF((x - ox) / scale, (y - oy) / scale)
    fun bmpToView(b: RectF): RectF = RectF(ox + b.left * scale, oy + b.top * scale, ox + b.right * scale, oy + b.bottom * scale)

    fun setConfirmed(v: RectF) {
        confirmed = RectF(v); sx = -1f; resizeHandle = -1; insideDown = false
        pulseAnim?.cancel()
        pulseAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 240
            addUpdateListener { pulse = it.animatedValue as Float; invalidate() }
            start()
        }
        invalidate()
    }
    fun clearConfirmed() { confirmed = null; invalidate() }

    private fun drawGlowSet(canvas: Canvas, r: RectF, radius: Float, g2: Paint, g1: Paint, main: Paint) {
        canvas.drawRoundRect(r, radius, radius, g2)
        canvas.drawRoundRect(r, radius, radius, g1)
        canvas.drawRoundRect(r, radius, radius, main)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val dst = RectF(ox, oy, ox + bmp.width * scale, oy + bmp.height * scale)
        canvas.drawBitmap(bmp, null, dst, null)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)

        // activation frame: pastel flowing gradient hugging the screen edges
        if (edgeAlpha > 0.01f && confirmed == null && sx < 0) {
            edgeMatrix.setRotate(angle, cx, cy)
            edgeShader.setLocalMatrix(edgeMatrix)
            val a = edgeAlpha
            edgeGlow2.alpha = (36 * a).toInt(); edgeGlow1.alpha = (70 * a).toInt(); edgeMain.alpha = (255 * a).toInt()
            drawGlowSet(canvas, edgeRect, edgeRadius, edgeGlow2, edgeGlow1, edgeMain)
        }

        val dragging = sx >= 0 && ex >= 0
        val sel = confirmed ?: if (dragging) normRect() else null
        if (sel != null) {
            canvas.drawRoundRect(sel, selRadius, selRadius, bmpPaint)
            if (confirmed != null) {
                selMatrix.setRotate(angle, sel.centerX(), sel.centerY())
                selShader.setLocalMatrix(selMatrix)
                val grow = (1f - pulse) * dpF(10f)
                val pr = RectF(sel)
                pr.inset(-grow, -grow)
                selGlow2.alpha = (40 * pulse).toInt(); selGlow1.alpha = (80 * pulse).toInt(); selMain.alpha = (255 * pulse).toInt()
                drawGlowSet(canvas, pr, selRadius + grow, selGlow2, selGlow1, selMain)
            } else {
                canvas.drawRoundRect(sel, selRadius, selRadius, dragFaint)
                val arm = dpF(20f)
                tmpPath.reset()
                bracket(sel.left, sel.top, 1f, 1f, arm)
                bracket(sel.right, sel.top, -1f, 1f, arm)
                bracket(sel.left, sel.bottom, 1f, -1f, arm)
                bracket(sel.right, sel.bottom, -1f, -1f, arm)
                canvas.drawPath(tmpPath, dragBracket)
            }
        }
    }

    private fun bracket(x0: Float, y0: Float, dx: Float, dy: Float, arm: Float) {
        tmpPath.moveTo(x0 + dx * arm, y0)
        tmpPath.lineTo(x0 + selRadius * dx * 0.4f, y0)
        tmpPath.quadTo(x0, y0, x0, y0 + selRadius * dy * 0.4f)
        tmpPath.lineTo(x0, y0 + dy * arm)
    }

    private fun normRect(): RectF {
        val l = max(ox, min(sx, ex)); val t = max(oy, min(sy, ey))
        val r = min(ox + bmp.width * scale, max(sx, ex)); val b = min(oy + bmp.height * scale, max(sy, ey))
        return RectF(l, t, r, b)
    }

    private fun handleAt(x: Float, y: Float): Int {
        val c = confirmed ?: return -1
        val hr = dpF(26f)
        val pts = arrayOf(
            PointF(c.left, c.top), PointF(c.centerX(), c.top), PointF(c.right, c.top),
            PointF(c.right, c.centerY()), PointF(c.right, c.bottom), PointF(c.centerX(), c.bottom),
            PointF(c.left, c.bottom), PointF(c.left, c.centerY()))
        for (i in pts.indices) {
            if (kotlin.math.abs(x - pts[i].x) <= hr && kotlin.math.abs(y - pts[i].y) <= hr) return i
        }
        return -1
    }

    private fun applyResize(x: Float, y: Float) {
        val c = confirmed ?: return
        val minS = dpF(24f)
        val xx = x.coerceIn(ox, ox + bmp.width * scale)
        val yy = y.coerceIn(oy, oy + bmp.height * scale)
        when (resizeHandle) {
            0 -> { c.left = min(xx, c.right - minS); c.top = min(yy, c.bottom - minS) }
            1 -> { c.top = min(yy, c.bottom - minS) }
            2 -> { c.right = max(xx, c.left + minS); c.top = min(yy, c.bottom - minS) }
            3 -> { c.right = max(xx, c.left + minS) }
            4 -> { c.right = max(xx, c.left + minS); c.bottom = max(yy, c.top + minS) }
            5 -> { c.bottom = max(yy, c.top + minS) }
            6 -> { c.left = min(xx, c.right - minS); c.bottom = max(yy, c.top + minS) }
            7 -> { c.left = min(xx, c.right - minS) }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val h = handleAt(e.x, e.y)
                if (h >= 0) {
                    resizeHandle = h          // crop-style live resize; box + glow stay alive
                    onResizeLive(RectF(confirmed!!))
                } else if (confirmed != null && confirmed!!.contains(e.x, e.y)) {
                    insideDown = true         // touch inside the box: tap territory, no restart
                } else {
                    sx = e.x; sy = e.y; ex = e.x; ey = e.y
                    confirmed = null          // fresh drag outside: new selection
                    pulse = 1f
                    onDragStart()
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (resizeHandle >= 0) {
                    applyResize(e.x, e.y)
                    onResizeLive(RectF(confirmed!!))
                } else if (!insideDown && sx >= 0) {
                    ex = e.x; ey = e.y
                }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                if (resizeHandle >= 0) {
                    resizeHandle = -1
                    val r = RectF(confirmed!!)
                    invalidate()
                    onResizeEnd(r)
                    return true
                }
                if (insideDown) {
                    insideDown = false
                    onTap(e.x, e.y)
                    return true
                }
                ex = e.x; ey = e.y
                val r = normRect()
                val moved = kotlin.math.abs(ex - sx) > 16 || kotlin.math.abs(ey - sy) > 16
                if (!moved) {
                    sx = -1f; ex = -1f; invalidate()
                    onTap(e.x, e.y)
                } else if (r.width() > 30 && r.height() > 30) {
                    onFreePick(RectF(r))
                } else {
                    sx = -1f; ex = -1f; invalidate()
                }
            }
        }
        return true
    }
}
