package com.aipro.select

import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.app.SearchManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
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
    private var cropPathBmp: Path? = null  // closed lasso path in bmp coords (circle-to-search); null = rectangle selection
    private var selRangeBmp: List<RectF>? = null  // exact handle-drag range word boxes (bmp); null = not a range selection
    /** Confirmed highlight boxes (bmp coords) for every text confirm path (tap,
     *  handle-drag, lasso). restoreHighlight uses this for an exact restore;
     *  selRangeBmp is null for taps, so it cannot serve this role. */
    private var selHighlightBmp: List<RectF>? = null
    private lateinit var selView: SelectionView
    private lateinit var bar: LinearLayout      // icon capsule below selection
    private lateinit var textBar: LinearLayout  // text-action capsule below selection
    private lateinit var gifPill: LinearLayout  // "Create GIF" pill above selection
    private lateinit var closeChip: TextView
    private lateinit var miniCard: LinearLayout // translate output card
    private lateinit var textChip: LinearLayout // CTS-style "Copy | Translate" chip above selection
    private lateinit var miniText: TextView
    private var currentText = ""
    private var selViewRect: RectF? = null      // confirmed rect in view coords
    private val autoSelHandler = Handler(Looper.getMainLooper())
    private var autoSelRunnable: Runnable? = null
    private var userTouched = false
    private var recordMode = false
    private var recordBar: LinearLayout? = null
    private var qualityLabel: TextView? = null
    private var qualityHigh = true
    private val lineBoxes = mutableListOf<RectF>()
    private val wordBoxes = mutableListOf<RectF>()
    /** A recognized word with its text and per-character (symbol) boxes, bmp coords.
     *  From ML Kit's Element.symbols; chars[i] is the box of text[i]. Only kept
     *  when the symbol count matches the text length (else alignment is unreliable). */
    private data class WordChars(val text: String, val box: RectF, val chars: List<RectF>)
    private val wordChars = mutableListOf<WordChars>()
    /** Character-level selection from a handle drag: word + [fromChar, toChar).
     *  Null means word-level (tap, lasso, box) - Copy uses the node path. */
    private data class CharRange(val word: WordChars, val fromChar: Int, val toChar: Int)
    private var selCharRanges: List<CharRange>? = null
    /** Boundary inside a word: the gap before chars[index], 0..text.length. */
    private data class CharPos(val wc: WordChars, val index: Int)
    private var selCharAnchorA: CharPos? = null
    private var selCharAnchorB: CharPos? = null
    private val tapOcrDone = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var tapRetryPending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ocr.init(this)   // warm Tesseract (Kannada/Hindi/English) off the main thread
        warmTranslateModels()   // pre-download translation packs so Translate is instant later
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
            onFreePick = { vr, isLasso, lassoPathBmp ->
                if (isLasso) {
                    // circle-to-search: test words against the closed path, not the
                    // bounding rectangle - select only what the circle covers
                    // (a loose circle around one icon must not grab its neighbours)
                    val boxes = if (wordBoxes.isNotEmpty()) wordBoxes else lineBoxes
                    val lassoPath = if (lassoPathBmp.size > 2) {
                        Path().apply {
                            moveTo(lassoPathBmp[0].x, lassoPathBmp[0].y)
                            for (i in 1 until lassoPathBmp.size) lineTo(lassoPathBmp[i].x, lassoPathBmp[i].y)
                            close()
                        }
                    } else null
                    val covered = if (lassoPath != null) {
                        val pb = RectF(); lassoPath.computeBounds(pb, true)
                        val clip = Region(pb.left.toInt(), pb.top.toInt(), pb.right.toInt() + 1, pb.bottom.toInt() + 1)
                        val region = Region(); region.setPath(lassoPath, clip)
                        boxes.filter { region.contains(it.centerX().toInt(), it.centerY().toInt()) }
                    } else {
                        val rb = RectF(selView.viewToBmp(vr))
                        boxes.filter { RectF.intersects(it, rb) }
                    }.sortedWith(compareBy({ it.top }, { it.left }))
                    if (covered.isNotEmpty()) {
                        selAnchorA = covered.first(); selAnchorB = covered.last()
                        confirmText(vr, covered.map { RectF(selView.bmpToView(it)) }, lassoPath)
                    } else confirm(vr, false)
                } else {
                    val tu = textUnion(vr)
                    if (tu != null && tu.width() * tu.height() >= vr.width() * vr.height() * 0.35f) {
                        // rectangle text: highlight the per-word boxes in reading
                        // order (not the line boxes), so the handles sit at the
                        // first and last character ends.
                        val rb = RectF(selView.viewToBmp(tu))
                        val words = (if (wordBoxes.isNotEmpty()) wordBoxes else lineBoxes)
                            .filter { RectF.intersects(it, rb) }
                        val ordered = readingOrderOf(words) { it }
                        if (ordered.isNotEmpty()) {
                            confirmText(tu, ordered.map { RectF(selView.bmpToView(it)) })
                        } else {
                            confirmText(tu)
                        }
                    } else confirm(snapToText(vr), false)
                }
            },
            onTap = { x, y -> tapDetect(x, y) },
            onDragStart = { hideChrome() },
            onResizeLive = { vr -> selViewRect = vr; repositionChrome() },
            onResizeEnd = { vr -> confirmResize(vr) },
            onCursorMove = { whichEnd, x, y -> cursorPreview(whichEnd, x, y) },
            onCursorEnd = { whichEnd, x, y -> cursorConfirm(whichEnd, x, y) },
            onCursorDragStart = { hideChromeForCursor() },
            onCancel = { restoreChrome() })
        root.addView(selView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        buildGifPill(); root.addView(gifPill)
        buildBar(); root.addView(bar)
        buildTextBar(); root.addView(textBar)
        buildMiniCard(); root.addView(miniCard)
        buildTextChip(); root.addView(textChip)
        buildCloseChip(); root.addView(closeChip)
        setContentView(root)
        // start tap-detect OCR immediately so early taps already have boxes
        root.post { startTapDetectOcr(b) }
        // EXPERIMENTAL: idle auto-select - if nothing is touched for 2s and the
        // screen has one dominant image/photo/thumbnail, select it automatically.
        // Any touch cancels it; text-heavy screens select nothing.
        val r = Runnable {
            if (!userTouched && selViewRect == null && !isFinishing) autoSelectMainImage()
        }
        autoSelRunnable = r
        autoSelHandler.postDelayed(r, 2000)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) cancelAutoSelect()
        return super.dispatchTouchEvent(ev)
    }

    private fun cancelAutoSelect() {
        userTouched = true
        autoSelRunnable?.let { autoSelHandler.removeCallbacks(it) }
    }

    /** Pick the dominant image block on screen (main photo/thumbnail) and select it. Text screens: nothing. */
    private fun autoSelectMainImage() {
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
                val colorful = BooleanArray(w * h)
                for (i in px.indices) {
                    val c = px[i]
                    val r = c shr 16 and 0xFF; val g = c shr 8 and 0xFF; val b2 = c and 0xFF
                    lum[i] = (r * 299 + g * 587 + b2 * 114) / 1000
                    colorful[i] = maxOf(r, g, b2) - minOf(r, g, b2) > 28
                }
                fun gy(x: Int, y: Int) = kotlin.math.abs(lum[(y + 1) * w + x] - lum[(y - 1) * w + x])
                var sum = 0L; var n = 0
                var y = 1
                while (y < h - 1) { var x = 1; while (x < w - 1) { sum += gy(x, y); n++; x += 3 }; y += 3 }
                val minG = maxOf(20, (sum / maxOf(1, n)) * 2).toInt()

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
                    val padX = bw * 0.01f + 2f; val padY = bh * 0.01f + 2f
                    val rect = RectF(
                        ((l - padX) / sc).coerceIn(0f, full.width - 4f),
                        ((t - padY) / sc).coerceIn(0f, full.height - 4f),
                        ((r + padX) / sc).coerceIn(4f, full.width.toFloat()),
                        ((b + padY) / sc).coerceIn(4f, full.height.toFloat()))
                    runOnUiThread {
                        if (!isFinishing && !userTouched && selViewRect == null) confirm(selView.bmpToView(rect), false)
                    }
                }

                // Pass A: image bands between full-width strong horizontal lines
                // (feed thumbnails/cards). Pick the largest colorful band.
                val x0w = (w * 0.06f).toInt(); val x1w = (w * 0.94f).toInt()
                val need = ((x1w - x0w) * 0.6f).toInt()
                val lines = ArrayList<Int>()
                var y2 = 2
                var lastLine = -10
                while (y2 < h - 2) {
                    if (rowSupport(y2, x0w, x1w) >= need) {
                        if (y2 - lastLine > 3) lines.add(y2)
                        lastLine = y2
                    }
                    y2++
                }
                val bounds = ArrayList<Int>()
                bounds.add(2); bounds.addAll(lines); bounds.add(h - 3)
                var bestArea = 0; var bT = -1; var bB = -1
                for (i in 0 until bounds.size - 1) {
                    val T = bounds[i]; val B = bounds[i + 1]
                    val bh = B - T
                    if (bh < 20) continue
                    val bw = x1w - x0w
                    var c = 0
                    for (yy in T + 2 until B - 2) for (xx in x0w until x1w) if (colorful[yy * w + xx]) c++
                    val cf = c.toFloat() / ((B - T - 4) * (x1w - x0w))
                    val aspect = bw.toFloat() / bh
                    if (cf >= 0.5f && aspect >= 0.5f && aspect <= 6f && !(bw > w * 0.92f && bh > h * 0.9f)) {
                        val area = bw * bh
                        if (area > bestArea) { bestArea = area; bT = T; bB = B }
                    }
                }
                if (bT >= 0) { emitBox(2, bT, w - 3, bB); return@Thread }

                // Pass B: largest colorful connected component (centered photos etc.)
                val comp = IntArray(w * h) { -1 }
                val q = IntArray(w * h)
                var bestSize = 0; var bestId = -1; var ncomp = 0
                for (start in 0 until w * h) {
                    if (!colorful[start] || comp[start] >= 0) continue
                    var head = 0; var tail = 0
                    q[tail++] = start; comp[start] = ncomp; var size = 0
                    while (head < tail) {
                        val cur = q[head++]; size++
                        val cx = cur % w; val cy = cur / w
                        if (cx > 0 && colorful[cur - 1] && comp[cur - 1] < 0) { comp[cur - 1] = ncomp; q[tail++] = cur - 1 }
                        if (cx < w - 1 && colorful[cur + 1] && comp[cur + 1] < 0) { comp[cur + 1] = ncomp; q[tail++] = cur + 1 }
                        if (cy > 0 && colorful[cur - w] && comp[cur - w] < 0) { comp[cur - w] = ncomp; q[tail++] = cur - w }
                        if (cy < h - 1 && colorful[cur + w] && comp[cur + w] < 0) { comp[cur + w] = ncomp; q[tail++] = cur + w }
                    }
                    if (size > bestSize) { bestSize = size; bestId = ncomp }
                    ncomp++
                }
                if (bestId < 0 || bestSize < w * h * 0.10f) return@Thread
                var minX = w; var maxX = 0; var minY = h; var maxY = 0
                for (yy in 0 until h) for (xx in 0 until w) {
                    if (comp[yy * w + xx] == bestId) {
                        if (xx < minX) minX = xx; if (xx > maxX) maxX = xx
                        if (yy < minY) minY = yy; if (yy > maxY) maxY = yy
                    }
                }
                val bw = (maxX - minX).toFloat(); val bh = (maxY - minY).toFloat()
                val aspect = bw / bh
                val fill = bestSize / (bw * bh)
                if (aspect < 0.25f || aspect > 4f || fill < 0.5f) return@Thread
                if (bw > w * 0.92f && bh > h * 0.9f) return@Thread
                emitBox(minX, minY, maxX, maxY)
            } catch (e: Exception) { }
        }.start()
    }

    // ---------- selection ----------

    private fun confirm(viewRect: RectF, fromText: Boolean) {
        cancelAutoSelect()
        val b = bmp ?: return
        val r = selView.viewToBmp(viewRect)
        if (r.width() < 2 || r.height() < 2) return
        cropRectBmp = r
        cropPathBmp = null  // rectangle selection: textFromNodes uses the rect
        selRangeBmp = null
        selHighlightBmp = null
        selCharRanges = null; selCharAnchorA = null; selCharAnchorB = null  // box: word-level
        cropBmp = Bitmap.createBitmap(b, r.left, r.top, r.width(), r.height())
        selViewRect = viewRect
        selView.textMode = false
        selView.setTextLines(emptyList())
        textChip.visibility = View.GONE
        selView.setConfirmed(viewRect)
        showChrome(fromText)
        if (fromText) readText()
    }

    /** Circle-to-search text selection: per-line highlight, staggered wipe, Copy/Translate chip. */
    private fun confirmText(viewRect: RectF, highlight: List<RectF>? = null, lassoPath: Path? = null,
                            rangeBmp: List<RectF>? = null) {
        cancelAutoSelect()
        val b = bmp ?: return
        val r = selView.viewToBmp(viewRect)
        val rb2 = RectF(r); rb2.inset(-4f, -4f)
        val spanBoxes = (if (wordBoxes.isNotEmpty()) wordBoxes else lineBoxes)
            .filter { RectF.intersects(it, rb2) }.sortedWith(compareBy({ it.top }, { it.left }))
        if (rangeBmp != null) {
            // handle-drag range: keep the real range ends - the next handle drag
            // must start from them, not from rectangle-based first/last boxes
            selAnchorA = rangeBmp.firstOrNull(); selAnchorB = rangeBmp.lastOrNull()
        } else if (lassoPath == null) {
            // tap or rectangle: the fixed ends are the first/last boxes of the
            // highlight itself (the tapped word, or the per-word boxes in
            // reading order). spanBoxes may include neighbours for a tap
            // (expanded rect), which would put the anchor on the wrong word.
            val hb = highlight?.map { RectF(selView.viewToBmp(it)) }
            selAnchorA = hb?.firstOrNull(); selAnchorB = hb?.lastOrNull()
            // word-level - drop any character-level state
            selCharRanges = null; selCharAnchorA = null; selCharAnchorB = null
        } else {
            // lasso: anchors are the covered words
            selAnchorA = spanBoxes.firstOrNull(); selAnchorB = spanBoxes.lastOrNull()
            selCharRanges = null; selCharAnchorA = null; selCharAnchorB = null
        }
        cropRectBmp = r
        cropPathBmp = lassoPath  // non-null only for circle-to-search; textFromNodes then tests the path
        selRangeBmp = rangeBmp  // non-null only for handle-drag range; Copy/Translate then extract exactly it
        // exact confirmed highlight in bmp coords (highlight arrives in view coords);
        // the failed-drop/cancel restore path uses this, not selRangeBmp (null for taps).
        // viewToBmp returns Rect; wrap in RectF (fractional precision is kept via bmpToView).
        selHighlightBmp = highlight?.map { RectF(selView.viewToBmp(it)) }
        cropBmp = Bitmap.createBitmap(b, r.left, r.top, r.width(), r.height())
        selViewRect = viewRect
        selView.textMode = true
        selView.setTextLines(highlight ?: linesInView(viewRect))
        selView.setConfirmed(viewRect)
        showChrome(true)
        gifPill.visibility = View.GONE // CTS look: the Copy | Translate chip takes that slot
        showTextChip(viewRect)
        readText()
    }

    /** Text lines (view coords) touched by a selection, top-to-bottom like CTS highlights. */
    private fun linesInView(v: RectF): List<RectF> {
        val rb = RectF(selView.viewToBmp(v))
        return lineBoxes.filter { RectF.intersects(it, rb) }
            .map { RectF(selView.bmpToView(it)) }
            .sortedWith(compareBy({ it.top }, { it.left }))
    }

    /** Like snapToText but returns null when the drag covers no text (image stays a box). */
    private fun textUnion(v: RectF): RectF? {
        if (lineBoxes.isEmpty()) return null
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
        val u = union ?: return null
        if (u.width() * u.height() > rb.width() * rb.height() * 9f) return null
        u.inset(-8f, -8f)
        val b = bmp ?: return null
        u.left = u.left.coerceIn(0f, b.width - 4f)
        u.right = u.right.coerceIn(u.left + 4f, b.width.toFloat())
        u.top = u.top.coerceIn(0f, b.height - 4f)
        u.bottom = u.bottom.coerceIn(u.top + 4f, b.height.toFloat())
        return selView.bmpToView(u)
    }

    /** Crop-style resize finished: recrop, replay the confirm pulse, refresh OCR if in text mode. */
    private fun confirmResize(viewRect: RectF) {
        val b = bmp ?: return
        val r = selView.viewToBmp(viewRect)
        cropRectBmp = r
        cropPathBmp = null  // resize reverts to rectangle semantics
        selRangeBmp = null
        selHighlightBmp = null
        selCharRanges = null; selCharAnchorA = null; selCharAnchorB = null
        cropBmp = Bitmap.createBitmap(b, r.left, r.top, r.width(), r.height())
        selViewRect = viewRect
        selView.setConfirmed(viewRect)
        repositionChrome()
        if (selView.textMode) selView.setTextLines(linesInView(viewRect))
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
        val x = (r.centerX() - pw / 2f).coerceIn(dp(10f).toFloat(), maxOf(dp(10f).toFloat(), parent.width - pw - dp(10f).toFloat()))
        var y = r.top - ph - dp(10f)
        if (y < dp(44f)) y = (r.top + dp(8f)).coerceAtMost((parent.height - ph - dp(10f)).toFloat())
        p.translationX = x; p.translationY = y
    }

    private fun repositionBar(v: View, r: RectF) {
        val parent = v.parent as View
        val bw = v.width; val bh = v.height
        if (bw == 0 || bh == 0) { v.post { repositionBar(v, r) }; return }
        val x = (r.centerX() - bw / 2f).coerceIn(dp(10f).toFloat(), maxOf(dp(10f).toFloat(), parent.width - bw - dp(10f).toFloat()))
        var y = r.bottom + dp(if (v === textBar) 40f else 10f)  // text bar must clear the selection cursors (handles hang ~28dp below)
        if (y + bh > parent.height - dp(16f)) y = r.top - bh - dp(56f)
        v.translationX = x; v.translationY = y
    }

    private fun hideChrome() {
        gifPill.visibility = View.GONE
        bar.visibility = View.GONE
        textBar.visibility = View.GONE
        miniCard.visibility = View.GONE
        if (::textChip.isInitialized) textChip.visibility = View.GONE
        selView.textMode = false
        selView.setTextLines(emptyList())
        currentText = ""
    }

    /** Cursor-handle drag: hide only the bars/chip, keep textMode + text lines so
     *  onDraw keeps drawing the text highlight (not the image-style glow box). */
    private fun hideChromeForCursor() {
        visGifPill = gifPill.visibility
        visBar = bar.visibility
        visTextBar = textBar.visibility
        visMiniCard = miniCard.visibility
        visTextChip = if (::textChip.isInitialized) textChip.visibility else View.GONE
        gifPill.visibility = View.GONE
        bar.visibility = View.GONE
        textBar.visibility = View.GONE
        miniCard.visibility = View.GONE
        if (::textChip.isInitialized) textChip.visibility = View.GONE
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

    // word-by-word selection cursors (both ends) + magnifier support
    private var selAnchorA: RectF? = null  // first box of the current text selection (bmp)
    private var selAnchorB: RectF? = null  // last box

    private fun cursorSnap(x: Float, y: Float): RectF? {
        // search words and lines together: a word the OCR only caught at line
        // level (or vice versa) must still grab the handle, smallest box wins
        val boxes = wordBoxes + lineBoxes
        var best: RectF? = null
        for (box in boxes) {
            val hit = RectF(box); hit.inset(-6f, -6f)
            if (hit.contains(x, y) && (best == null || box.width() * box.height() < best!!.width() * best!!.height())) best = RectF(box)
        }
        if (best != null) return best
        // fallback: nearest box within about one line height of the drop point
        var nb: RectF? = null; var nd = Float.MAX_VALUE
        for (box in boxes) {
            val dx = maxOf(box.left - x, 0f, x - box.right)
            val dy = maxOf(box.top - y, 0f, y - box.bottom)
            val d = kotlin.math.hypot(dx, dy)
            if (d < nd) { nd = d; nb = box }
        }
        val nbb = nb ?: return null
        return if (nd <= nbb.height()) RectF(nbb) else null
    }

    // visibility snapshot taken when a cursor drag starts; a failed drop or
    // cancel restores exactly this, so every hidden view comes back as it was
    private var visGifPill = View.GONE
    private var visBar = View.GONE
    private var visTextBar = View.GONE
    private var visMiniCard = View.GONE
    private var visTextChip = View.GONE

    /** Put the highlight back on the confirmed selection (undo a cursor preview).
     *  Uses selHighlightBmp (exact confirmed boxes, set for tap/handle/lasso),
     *  not selRangeBmp (null for taps). Also restores both anchor pairs to the
     *  confirmed ends so the next drag starts from the right place. */
    private fun restoreHighlight() {
        val hb = selHighlightBmp
        if (hb != null) {
            selView.setTextLines(hb.map { RectF(selView.bmpToView(it)) }, animate = false)
            selAnchorA = hb.firstOrNull(); selAnchorB = hb.lastOrNull()
            val cr = selCharRanges
            if (cr != null && cr.isNotEmpty()) {
                val first = cr.first(); val last = cr.last()
                selCharAnchorA = CharPos(first.word, first.fromChar)
                selCharAnchorB = CharPos(last.word, last.toChar)
            } else {
                selCharAnchorA = null; selCharAnchorB = null
            }
        } else {
            val svr = selViewRect ?: return
            selView.setTextLines(linesInView(svr), animate = false)
        }
    }

    /** Failed drop / cancel: the selection is unchanged - restore the bars and
     *  the highlight exactly as before the drag. Unconditional and safe: the
     *  snapshot was taken at drag start, so it can only reproduce a state that
     *  already existed; with selViewRect null there is no confirmed highlight
     *  and the snapshot holds no visible selection chrome, making the call
     *  effect-free. */
    private fun restoreChrome() {
        gifPill.visibility = visGifPill
        bar.visibility = visBar
        textBar.visibility = visTextBar
        miniCard.visibility = visMiniCard
        if (::textChip.isInitialized) textChip.visibility = visTextChip
        restoreHighlight()
    }

    /** Line-aware reading order: group boxes into lines by vertical overlap
     *  (not raw top value), lines top-to-bottom, boxes left-to-right in a line.
     *  A box joins a line only if the vertical overlap exceeds half of the
     *  shorter height - tightly spaced lines that merely touch stay separate. */
    private fun <T> readingOrderOf(items: List<T>, rectOf: (T) -> RectF): List<T> {
        if (items.isEmpty()) return items
        val lines = mutableListOf<MutableList<T>>()
        for (item in items.sortedWith(compareBy({ rectOf(it).top }, { rectOf(it).left }))) {
            val r = rectOf(item)
            val rh = r.bottom - r.top
            val line = lines.firstOrNull { l ->
                val top = l.minOf { rectOf(it).top }; val bottom = l.maxOf { rectOf(it).bottom }
                val overlap = minOf(r.bottom, bottom) - maxOf(r.top, top)
                overlap > 0.5f * minOf(rh, bottom - top)
            }
            if (line == null) lines.add(mutableListOf(item)) else line.add(item)
        }
        return lines.sortedBy { l -> l.minOf { rectOf(it).top } }
            .flatMap { l -> l.sortedBy { rectOf(it).left } }
    }

    /** Reading-order word range between the fixed end and the dragged-to word.
     *  Returns (boxes, swapped): swapped is true when the dragged handle crossed
     *  the fixed end and now drives the opposite handle. */
    private fun cursorSpan(whichEnd: Int, viewX: Float, viewY: Float): Pair<List<RectF>, Boolean>? {
        val p = selView.viewToBmpPoint(viewX, viewY)
        val target = cursorSnap(p.x, p.y) ?: return null
        // same combined list as cursorSnap: the target may be a line box
        val boxes0 = wordBoxes + lineBoxes
        if (boxes0.isEmpty()) return null
        val ordered = readingOrderOf(boxes0) { it }
        val anchor = (if (whichEnd == 2) selAnchorA else selAnchorB) ?: return null
        // value compare: cursorSnap returns a copy and OCR callbacks may rebuild the lists
        var ai = ordered.indexOfFirst {
            it.left == anchor.left && it.top == anchor.top && it.right == anchor.right && it.bottom == anchor.bottom
        }
        if (ai < 0) ai = nearestBoxIndex(ordered, anchor)
        var ti = ordered.indexOfFirst {
            it.left == target.left && it.top == target.top && it.right == target.right && it.bottom == target.bottom
        }
        if (ti < 0) ti = nearestBoxIndex(ordered, target)
        val range = ordered.subList(minOf(ai, ti), maxOf(ai, ti) + 1)
        val swapped = (whichEnd == 1 && ti > ai) || (whichEnd == 2 && ti < ai)
        return Pair(range, swapped)
    }

    private fun nearestBoxIndex(ordered: List<RectF>, box: RectF): Int {
        var bi = 0; var bd = Float.MAX_VALUE
        for ((i, b) in ordered.withIndex()) {
            val dx = b.centerX() - box.centerX(); val dy = b.centerY() - box.centerY()
            val d = dx * dx + dy * dy
            if (d < bd) { bd = d; bi = i }
        }
        return bi
    }

    /** Best WordChars match for a snapped box (highest IoU >= 0.3); null when the
     *  box is line-level with no good word match - caller falls back to word-level. */
    private fun findWordChars(box: RectF): WordChars? {
        var best: WordChars? = null; var bestIoU = 0.3f
        for (wc in wordChars) {
            val iw = minOf(wc.box.right, box.right) - maxOf(wc.box.left, box.left)
            val ih = minOf(wc.box.bottom, box.bottom) - maxOf(wc.box.top, box.top)
            if (iw <= 0 || ih <= 0) continue
            val inter = iw * ih
            val union = wc.box.width() * wc.box.height() + box.width() * box.height() - inter
            val iou = if (union > 0) inter / union else 0f
            if (iou > bestIoU) { bestIoU = iou; best = wc }
        }
        return best
    }

    /** x-positions of the N+1 character boundaries, left to right. Uses the
     *  symbol edges (midpoint of inter-symbol gaps) when the symbols are sane
     *  (ordered, non-degenerate, count matches); else even division of the word
     *  width (monospace fallback - tight non-monospace digits may be off by one). */
    private fun charBoundaries(wc: WordChars): FloatArray {
        val n = wc.text.length
        val b = FloatArray(n + 1)
        // sane only with exactly one box per character, ordered and non-degenerate;
        // otherwise (e.g. digit strings with no symbols) use even-split
        var sane = n > 0 && wc.chars.size == n
        if (sane) {
            for (i in wc.chars.indices) {
                val s = wc.chars[i]
                if (s.left >= s.right) { sane = false; break }
                if (i > 0 && s.left < wc.chars[i - 1].left) { sane = false; break }
            }
        }
        if (sane) {
            b[0] = wc.box.left
            for (i in 1 until n) b[i] = (wc.chars[i - 1].right + wc.chars[i].left) / 2f
            b[n] = wc.box.right
        } else {
            for (i in 0..n) b[i] = wc.box.left + wc.box.width() * i / n
        }
        return b
    }

    /** Snap a bmp point to the nearest inter-character boundary of the word under
     *  it. Null when the word has no character data - caller uses word-level. */
    private fun snapToCharPos(bx: Float, by: Float): CharPos? {
        val target = cursorSnap(bx, by) ?: return null
        val wc = findWordChars(target) ?: return null
        val b = charBoundaries(wc)
        var bi = 0; var bd = Float.MAX_VALUE
        for (i in b.indices) {
            val d = kotlin.math.abs(b[i] - bx)
            if (d < bd) { bd = d; bi = i }
        }
        return CharPos(wc, bi)
    }

    /** Visual box for a character range: the word box clipped to the boundary x's. */
    private fun charRangeBox(cr: CharRange): RectF {
        val b = charBoundaries(cr.word)
        return RectF(b[cr.fromChar], cr.word.box.top, b[cr.toChar], cr.word.box.bottom)
    }

    /** Character-level range between the fixed end and the dragged-to boundary.
     *  Returns (visual boxes, char ranges, swapped), or null when character data
     *  is unavailable - caller falls back to the word-level cursorSpan. */
    private fun cursorSpanChar(whichEnd: Int, bx: Float, by: Float): Triple<List<RectF>, List<CharRange>, Boolean>? {
        val target = snapToCharPos(bx, by) ?: return null
        // anchor: live char anchor from an in-progress char drag/selection,
        // else convert the RectF anchor (start handle at char 0, end at char N)
        val liveAnchor = if (whichEnd == 2) selCharAnchorA else selCharAnchorB
        val aPos = liveAnchor ?: run {
            val anchorRect = (if (whichEnd == 2) selAnchorA else selAnchorB) ?: return null
            val anchorWc = findWordChars(anchorRect) ?: return null
            CharPos(anchorWc, if (whichEnd == 2) 0 else anchorWc.text.length)
        }
        // dedupe: the 5 recognizers often return the same word; without this the
        // range (and the copied text) would repeat it. Re-resolve anchor/target
        // to the deduped objects so reference equality holds below.
        fun dedupKey(wc: WordChars) =
            wc.text + "|" + wc.box.left + "," + wc.box.top + "," + wc.box.right + "," + wc.box.bottom
        val seen = HashSet<String>()
        val deduped = wordChars.filter { wc -> seen.add(dedupKey(wc)) }
        val aWc = deduped.find { dedupKey(it) == dedupKey(aPos.wc) } ?: return null
        val tWc = deduped.find { dedupKey(it) == dedupKey(target.wc) } ?: return null
        val aPosD = CharPos(aWc, aPos.index.coerceIn(0, aWc.text.length))
        val targetD = CharPos(tWc, target.index.coerceIn(0, tWc.text.length))
        val ordered = readingOrderOf(deduped) { it.box }
        // reference equality: ordered holds the same WordChars objects, reordered
        val ai = ordered.indexOfFirst { it === aWc }
        val ti = ordered.indexOfFirst { it === tWc }
        if (ai < 0 || ti < 0) return null
        val ranges = mutableListOf<CharRange>()
        if (ai == ti) {
            val from = minOf(aPosD.index, targetD.index)
            val to = maxOf(aPosD.index, targetD.index)
            if (from == to) return null  // same boundary: nothing to select
            ranges.add(CharRange(aWc, from, to))
        } else {
            val step = if (ai < ti) 1 else -1
            var i = ai
            while (true) {
                val wc = ordered[i]
                when {
                    i == ai -> if (step == 1) ranges.add(CharRange(wc, aPosD.index, wc.text.length))
                               else ranges.add(CharRange(wc, 0, aPosD.index))
                    i == ti -> if (step == 1) ranges.add(CharRange(wc, 0, targetD.index))
                               else ranges.add(CharRange(wc, targetD.index, wc.text.length))
                    else -> ranges.add(CharRange(wc, 0, wc.text.length))
                }
                if (i == ti) break
                i += step
            }
        }
        val nonEmpty = ranges.filter { it.fromChar < it.toChar }
        if (nonEmpty.isEmpty()) return null
        val swapped = if (ai == ti) {
            (whichEnd == 1 && targetD.index > aPosD.index) || (whichEnd == 2 && targetD.index < aPosD.index)
        } else {
            (whichEnd == 1 && ti > ai) || (whichEnd == 2 && ti < ai)
        }
        return Triple(nonEmpty.map { charRangeBox(it) }, nonEmpty, swapped)
    }

    private fun cursorPreview(whichEnd: Int, viewX: Float, viewY: Float) {
        val p = selView.viewToBmpPoint(viewX, viewY)
        // character-level first: the handle can stop between characters.
        // Falls back to word-level when the word has no character data.
        val cr = cursorSpanChar(whichEnd, p.x, p.y)
        if (cr != null) {
            if (cr.third) {
                // the dragged handle crossed the fixed end
                selView.flipCursorMode()
                val first = cr.second.first(); val last = cr.second.last()
                selCharAnchorA = CharPos(first.word, first.fromChar)
                selCharAnchorB = CharPos(last.word, last.toChar)
                selAnchorA = charRangeBox(first); selAnchorB = charRangeBox(last)
            }
            selView.setTextLines(cr.first.map { RectF(selView.bmpToView(it)) }, animate = false)
            return
        }
        selCharAnchorA = null; selCharAnchorB = null
        val r = cursorSpan(whichEnd, viewX, viewY) ?: return
        if (r.second) {
            // the dragged handle crossed the fixed end: it now drives the opposite handle
            selView.flipCursorMode()
            selAnchorA = r.first.first(); selAnchorB = r.first.last()
        }
        selView.setTextLines(r.first.map { RectF(selView.bmpToView(it)) }, animate = false)
    }

    private fun cursorConfirm(whichEnd: Int, viewX: Float, viewY: Float) {
        val p = selView.viewToBmpPoint(viewX, viewY)
        val cr = cursorSpanChar(whichEnd, p.x, p.y)
        if (cr != null) {
            val first = cr.second.first(); val last = cr.second.last()
            selCharAnchorA = CharPos(first.word, first.fromChar)
            selCharAnchorB = CharPos(last.word, last.toChar)
            selAnchorA = charRangeBox(first); selAnchorB = charRangeBox(last)
            selCharRanges = cr.second
            val u = RectF(cr.first.first())
            for (b in cr.first) u.union(b)
            confirmText(selView.bmpToView(u), cr.first.map { RectF(selView.bmpToView(it)) }, rangeBmp = cr.first)
            return
        }
        // word-level
        selCharRanges = null; selCharAnchorA = null; selCharAnchorB = null
        val r = cursorSpan(whichEnd, viewX, viewY)
        if (r == null) { restoreChrome(); return }  // drop too far from text: keep the old selection, bars back
        selAnchorA = r.first.first(); selAnchorB = r.first.last()
        val u = RectF(r.first.first())
        for (b in r.first) u.union(b)
        confirmText(selView.bmpToView(u), r.first.map { RectF(selView.bmpToView(it)) }, rangeBmp = r.first)
    }

    private fun tapDetect(viewX: Float, viewY: Float) {
        val p = selView.viewToBmpPoint(viewX, viewY)
        var best: RectF? = null
        for (box in wordBoxes + lineBoxes) {
            val hit = RectF(box)
            hit.inset(-6f, -6f) // forgiving hit-test: tight Indic line boxes miss edge taps
            if (hit.contains(p.x, p.y)) {
                if (best == null || box.width() * box.height() < best!!.width() * best!!.height()) best = RectF(box)
            }
        }
        if (best == null) {
            // recognizers finish asynchronously; retry the same tap once before
            // falling back to shape detection so an early tap on text selects it
            if (tapOcrDone.get() < 5 && !tapRetryPending) {
                tapRetryPending = true
                selView.postDelayed({
                    tapRetryPending = false
                    if (!isFinishing && selView.confirmedRect() == null) tapDetect(viewX, viewY)
                }, 350)
                return
            }
            shapeDetect(viewX, viewY); return
        }
        run {
            val best = best!!
            best.inset(-10f, -10f)
            best.left = best.left.coerceIn(0f, bmp!!.width - 4f)
            best.right = best.right.coerceIn(best.left + 4f, bmp!!.width.toFloat())
            best.top = best.top.coerceIn(0f, bmp!!.height - 4f)
            best.bottom = best.bottom.coerceIn(best.top + 4f, bmp!!.height.toFloat())
            val bv = selView.bmpToView(best)
            confirmText(bv, listOf(RectF(bv)))
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
        val recognizers = listOf(
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS),
            TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build()),
            TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()),
            TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()),
            TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()))
        for (rec in recognizers) {
            rec.process(img).addOnSuccessListener { r ->
                tapOcrDone.incrementAndGet()
                for (blk in r.textBlocks) for (ln in blk.lines) {
                    ln.boundingBox?.let { lineBoxes.add(RectF(it)) }
                    for (el in ln.elements) {
                        el.boundingBox?.let { wordBoxes.add(RectF(it)) }
                        // per-character boxes for handle drags. Stored always;
                        // charBoundaries uses the symbols when the count matches
                        // 1:1 and falls back to even-split otherwise (e.g. digit
                        // strings where ML Kit returns no per-symbol boxes).
                        val ebb = el.boundingBox
                        if (ebb != null && el.text.isNotEmpty()) {
                            val syms = el.symbols.mapNotNull { s -> s.boundingBox?.let { RectF(it) } }
                            wordChars.add(WordChars(el.text, RectF(ebb), syms))
                        }
                    }
                }
                runOnUiThread { selView.ocrLines = lineBoxes.map { RectF(selView.bmpToView(it)) } }
            }.addOnFailureListener { tapOcrDone.incrementAndGet() }
        }
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
            var y = r.bottom + dp(if (showBar === textBar) 40f else 10f)  // text bar must clear the selection cursors (handles hang ~28dp below)
            if (y + bh > screenH - dp(16f)) y = r.top - bh - dp(56f) // flip above, under the pill
            showBar.translationX = x; showBar.translationY = y
            showBar.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(220).setStartDelay(40).setInterpolator(OvershootInterpolator(1.6f)).start()
        }
    }

    // ---------- Create GIF (screen-record the selected region) ----------

    private fun makeGif() {
        if (cropRectBmp == null) return
        enterRecordMode()
    }

    private fun enterRecordMode() {
        if (recordMode) return
        recordMode = true
        cancelAutoSelect()
        gifPill.visibility = View.GONE
        bar.visibility = View.GONE
        textBar.visibility = View.GONE
        miniCard.visibility = View.GONE
        closeChip.visibility = View.GONE
        selView.recordMode = true
        selView.invalidate()
        showRecordBar()
    }

    private fun exitRecordMode() {
        if (!recordMode) return
        recordMode = false
        selView.recordMode = false
        selView.invalidate()
        recordBar?.visibility = View.GONE
        closeChip.visibility = View.VISIBLE
        selViewRect?.let { showChrome(false) }
    }

    private fun showRecordBar() {
        val d = resources.displayMetrics.density
        fun dp(v: Float) = (v * d).toInt()
        val rb = LinearLayout(this)
        rb.orientation = LinearLayout.HORIZONTAL
        rb.gravity = Gravity.CENTER_VERTICAL
        rb.setBackgroundResource(R.drawable.toolbar_bg)
        rb.setPadding(dp(8f), 0, dp(8f), 0)

        val rec = TextView(this)
        rec.text = "●  Record"
        rec.setTextColor(Color.parseColor("#FF5252"))
        rec.textSize = 15f
        rec.setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
        rec.setOnClickListener { beginRecording() }
        rb.addView(rec)

        val div1 = View(this)
        div1.setBackgroundColor(Color.parseColor("#44FFFFFF"))
        rb.addView(div1, LinearLayout.LayoutParams(dp(1f), dp(24f)))

        val q = TextView(this)
        q.text = "High quality  ▾"
        q.setTextColor(Color.WHITE)
        q.textSize = 15f
        q.setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
        q.setOnClickListener {
            val pop = android.widget.PopupMenu(this, q)
            pop.menu.add(0, 1, 0, "High quality  (480px, 10 fps)")
            pop.menu.add(0, 2, 0, "Standard  (320px, 8 fps)")
            pop.setOnMenuItemClickListener { item ->
                qualityHigh = item.itemId == 1
                q.text = if (qualityHigh) "High quality  ▾" else "Standard  ▾"
                true
            }
            pop.show()
        }
        qualityLabel = q
        rb.addView(q)

        val div2 = View(this)
        div2.setBackgroundColor(Color.parseColor("#44FFFFFF"))
        rb.addView(div2, LinearLayout.LayoutParams(dp(1f), dp(24f)))

        val x = TextView(this)
        x.text = "✕"
        x.setTextColor(Color.WHITE)
        x.textSize = 16f
        x.setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
        x.setOnClickListener { exitRecordMode() }
        rb.addView(x)

        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        lp.bottomMargin = dp(48f)
        rb.layoutParams = lp
        (selView.parent as ViewGroup).addView(rb)
        recordBar = rb
    }

    private fun beginRecording() {
        Shared.projCb = { code, data ->
            if (code == RESULT_OK && data != null) startRecording(data) else {
                Toast.makeText(this, "Screen recording permission needed", Toast.LENGTH_SHORT).show()
            }
        }
        startActivity(Intent(this, ProjPermissionActivity::class.java))
    }

    private fun startRecording(data: android.content.Intent) {
        val r = cropRectBmp ?: return
        val svc = CaptureService.instance
        if (svc == null) { Toast.makeText(this, "Capture service not ready", Toast.LENGTH_SHORT).show(); return }
        // crop inside the white brackets so they never land in the GIF
        val inset = (resources.displayMetrics.density * 5).toInt()
        val i = Intent(this, GifRecorderService::class.java)
        i.putExtra("code", RESULT_OK)
        i.putExtra("data", data)
        i.putExtra("left", r.left + inset)
        i.putExtra("top", r.top + inset)
        i.putExtra("right", r.right - inset)
        i.putExtra("bottom", r.bottom - inset)
        i.putExtra("targetW", if (qualityHigh) 480 else 320)
        i.putExtra("frameMs", if (qualityHigh) 100L else 125L)
        startForegroundService(i)
        // overlay becomes invisible-but-present: only the white frame stays, and all
        // touches fall through to the app being recorded
        recordBar?.visibility = View.GONE
        selView.recording = true
        selView.invalidate()
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        Shared.recListener = { status ->
            if (status == null) finish()
        }
    }

    override fun onBackPressed() {
        if (recordMode && !selView.recording) {
            exitRecordMode()
            return
        }
        if (selView.recording) {
            // cancel the recording
            startService(Intent(this, GifRecorderService::class.java).setAction("cancel"))
            finish()
            return
        }
        super.onBackPressed()
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
        val fromScreen = textFromNodes()
        if (fromScreen.isNotEmpty()) { go(fromScreen); return }
        Ocr.recognize(this, crop) { txt, _ -> go(txt) }
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
        selView.textMode = true
        selViewRect?.let { selView.setTextLines(linesInView(it)) }
        showChrome(true)
        gifPill.visibility = View.GONE
        selViewRect?.let { showTextChip(it) }
        readText()
    }

    /** CTS-style floating chip: dark capsule with Copy and Translate, above the highlight. */
    private fun buildTextChip() {
        textChip = LinearLayout(this)
        textChip.orientation = LinearLayout.HORIZONTAL
        textChip.gravity = Gravity.CENTER_VERTICAL
        textChip.setBackgroundResource(R.drawable.toolbar_bg)
        fun chipBtn(label: String, act: () -> Unit): TextView {
            val tv = TextView(this)
            tv.text = label
            tv.setTextColor(Color.WHITE)
            tv.textSize = 14.5f
            tv.typeface = android.graphics.Typeface.DEFAULT_BOLD
            tv.setPadding(dp(16f), dp(9f), dp(16f), dp(9f))
            tv.setOnClickListener { act() }
            return tv
        }
        textChip.addView(chipBtn("Copy") { copyText() })
        val div = View(this)
        div.setBackgroundColor(Color.parseColor("#44FFFFFF"))
        textChip.addView(div, LinearLayout.LayoutParams(dp(1f), dp(20f)))
        textChip.addView(chipBtn("Translate") { translateText() })
        textChip.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START)
        textChip.visibility = View.GONE
    }

    private fun showTextChip(r: RectF) {
        textChip.visibility = View.VISIBLE
        textChip.scaleX = 0.6f; textChip.scaleY = 0.6f; textChip.alpha = 0f
        textChip.post {
            val parent = textChip.parent as View
            val cw = textChip.width; val ch = textChip.height
            if (cw == 0 || ch == 0 || parent.width == 0) { textChip.post { showTextChip(r) }; return@post }
            val x = (r.centerX() - cw / 2f).coerceIn(dp(10f).toFloat(), maxOf(dp(10f).toFloat(), parent.width - cw - dp(10f).toFloat()))
            var y = r.top - ch - dp(10f)
            if (y < dp(44f)) y = (r.bottom + dp(10f)).coerceAtMost((parent.height - ch - dp(10f)).toFloat())
            textChip.translationX = x
            textChip.translationY = y
            textChip.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(220).setInterpolator(OvershootInterpolator(1.6f)).start()
        }
    }

    private fun readText() {
        val c = cropBmp ?: return
        // character-level handle selection: exact substring from the OCR symbols -
        // the nodes only know whole words, so they cannot supply partial words
        val charText = textFromCharRanges()
        if (charText != null) { finishOcr(charText); return }
        // real text from the app itself first - exact Unicode, every language, zero OCR error
        val fromScreen = textFromNodes()
        if (fromScreen.isNotEmpty()) { finishOcr(fromScreen); return }
        runOcr(c)
    }

    /** Exact characters between the handles for a character-level drag: each
     *  range contributes its word's substring. Ranges join with a space on the
     *  same line, a newline across lines. Null when the selection is word-level. */
    private fun textFromCharRanges(): String? {
        val ranges = selCharRanges ?: return null
        if (ranges.isEmpty()) return null
        val sb = StringBuilder()
        for ((i, cr) in ranges.withIndex()) {
            if (i > 0) {
                val prev = ranges[i - 1]
                val dy = kotlin.math.abs(cr.word.box.centerY() - prev.word.box.centerY())
                val sameLine = dy < minOf(cr.word.box.height(), prev.word.box.height()) * 0.5f
                sb.append(if (sameLine) " " else "\n")
            }
            val t = cr.word.text
            val from = cr.fromChar.coerceIn(0, t.length)
            val to = cr.toChar.coerceIn(from, t.length)
            sb.append(t.substring(from, to))
        }
        return sb.toString().trim().ifEmpty { null }
    }

    /** Text of the on-screen nodes inside the current selection (dumped at capture time). */
    private fun textFromNodes(): String {
        val nodes = Shared.nodeTexts
        if (nodes.isEmpty()) return ""
        val range = selRangeBmp
        val path = cropPathBmp
        val hits: List<Pair<Rect, String>>
        val inside: List<Pair<Rect, String>>
        if (range != null) {
            // handle-drag range: exactly the range words - a node belongs iff its
            // center sits inside one of the range word boxes, not the union rect
            inside = nodes.filter { n ->
                val cx = n.first.centerX().toFloat(); val cy = n.first.centerY().toFloat()
                range.any { it.contains(cx, cy) }
            }
            hits = inside
        } else if (path != null) {
            // circle-to-search: test nodes against the closed path, not the bounding rect
            val pb = RectF(); path.computeBounds(pb, true)
            val clip = Region(pb.left.toInt(), pb.top.toInt(), pb.right.toInt() + 1, pb.bottom.toInt() + 1)
            val region = Region(); region.setPath(path, clip)
            hits = nodes.filter { RectF.intersects(RectF(it.first), pb) }
            inside = hits.filter { region.contains(it.first.centerX(), it.first.centerY()) }
        } else {
            val r = cropRectBmp ?: return ""
            val sel = RectF(r)
            hits = nodes.filter { RectF.intersects(RectF(it.first), sel) }
            // nodes whose center sits inside the selection - avoids bleeding in neighbouring messages
            inside = hits.filter { sel.contains(it.first.centerX().toFloat(), it.first.centerY().toFloat()) }
        }
        if (hits.isEmpty()) return ""
        val use = if (inside.isNotEmpty()) inside else hits
        return readingOrderOf(use) { RectF(it.first) }
            .joinToString("\n") { it.second }.trim()
    }

    private var tessWarned = false

    private fun runOcr(crop: Bitmap) {
        Ocr.recognize(this, crop) { txt, tessOk ->
            if (txt.isEmpty()) {
                Toast.makeText(this, "Couldn't read text - try a tighter selection", Toast.LENGTH_SHORT).show()
            } else if (!tessOk && !tessWarned) {
                tessWarned = true
                val why = Ocr.tessError ?: "still loading"
                Toast.makeText(this, "Kannada/Hindi OCR not ready ($why)", Toast.LENGTH_LONG).show()
            }
            finishOcr(txt)
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

    /** Deterministic language detection from the Unicode script itself - ML Kit's
     *  language-id returns "und" for short non-Latin lines, which silently
     *  "translated" Kannada back to Kannada (the reported bug). */
    private fun detectLangByScript(t: String): String? {
        var kn = 0; var dev = 0; var ben = 0; var tam = 0; var tel = 0; var mal = 0
        var guj = 0; var gur = 0; var arab = 0; var han = 0; var hang = 0; var kana = 0; var cyr = 0; var lat = 0
        for (ch in t) when (ch.code) {
            in 0x0C80..0x0CFF -> kn++
            in 0x0900..0x097F -> dev++
            in 0x0980..0x09FF -> ben++
            in 0x0B80..0x0BFF -> tam++
            in 0x0C00..0x0C7F -> tel++
            in 0x0D00..0x0D7F -> mal++
            in 0x0A80..0x0AFF -> guj++
            in 0x0A00..0x0A7F -> gur++
            in 0x0600..0x06FF -> arab++
            in 0x4E00..0x9FFF -> han++
            in 0xAC00..0xD7A3 -> hang++
            in 0x3040..0x30FF -> kana++
            in 0x0400..0x04FF -> cyr++
            in 'a'.code..'z'.code, in 'A'.code..'Z'.code -> lat++
        }
        val m = mapOf("kn" to kn, "hi" to dev, "bn" to ben, "ta" to tam, "te" to tel,
            "ml" to mal, "gu" to guj, "pa" to gur, "ar" to arab, "zh" to han,
            "ko" to hang, "ja" to kana, "ru" to cyr)
        val best = m.maxByOrNull { it.value }!!
        if (best.value >= 2) return best.key
        return null   // Latin or unclear: let ML Kit language-id decide
    }

    /** Download the translation packs he is most likely to need, in the background,
     *  the moment AI Pro opens - the first real Translate then has no long wait. */
    private fun warmTranslateModels() {
        Thread {
            for (pair in listOf(
                TranslateLanguage.KANNADA to TranslateLanguage.ENGLISH,
                TranslateLanguage.ENGLISH to TranslateLanguage.KANNADA,
                TranslateLanguage.HINDI to TranslateLanguage.ENGLISH,
                TranslateLanguage.ENGLISH to TranslateLanguage.HINDI)) {
                try {
                    val opts = TranslatorOptions.Builder().setSourceLanguage(pair.first).setTargetLanguage(pair.second).build()
                    Translation.getClient(opts).downloadModelIfNeeded()
                } catch (e: Exception) { }
            }
        }.start()
    }

    private var translateDlg: AlertDialog? = null
    private fun translateStage(msg: String) { runOnUiThread { try { translateDlg?.setMessage(msg) } catch (e: Exception) { } } }

    private fun translateText() {
        val src = currentText
        if (src.isBlank()) { Toast.makeText(this, "Still reading - tap again in a second", Toast.LENGTH_SHORT).show(); return }
        translateDlg = AlertDialog.Builder(this)
            .setTitle("Translate")
            .setMessage("Detecting language...")
            .setCancelable(true)
            .create()
        translateDlg?.show()
        val scriptLang = detectLangByScript(src)
        val startTranslate: (String) -> Unit = { lang ->
            val srcLang = TranslateLanguage.fromLanguageTag(lang)
            if (srcLang == null) {
                translateDlg?.dismiss()
                Toast.makeText(this, "Translation not available for this language", Toast.LENGTH_SHORT).show()
            } else {
                val opts = TranslatorOptions.Builder()
                    .setSourceLanguage(srcLang)
                    .setTargetLanguage(if (srcLang == TranslateLanguage.ENGLISH) TranslateLanguage.HINDI else TranslateLanguage.ENGLISH)
                    .build()
                val tr = Translation.getClient(opts)
                translateStage("Downloading the $lang translation model (one-time, can take a minute)...")
                tr.downloadModelIfNeeded().addOnSuccessListener {
                    translateStage("Translating...")
                    tr.translate(src).addOnSuccessListener { out ->
                        if (out.trim().equals(src.trim(), ignoreCase = true)) {
                            // passthrough: model may have just landed - retry once before calling it a failure
                            tr.translate(src).addOnSuccessListener { out2 ->
                                translateDlg?.dismiss()
                                if (out2.trim().equals(src.trim(), ignoreCase = true)) {
                                    Toast.makeText(this, "Couldn't translate this text", Toast.LENGTH_SHORT).show()
                                } else {
                                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("AI Pro translation", out2))
                                    Toast.makeText(this, "Translated - copied to clipboard", Toast.LENGTH_SHORT).show()
                                    finish()
                                }
                            }.addOnFailureListener {
                                translateDlg?.dismiss()
                                Toast.makeText(this, "Translate failed", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            translateDlg?.dismiss()
                            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("AI Pro translation", out))
                            Toast.makeText(this, "Translated - copied to clipboard", Toast.LENGTH_SHORT).show()
                            finish()
                        }
                    }.addOnFailureListener {
                        translateDlg?.dismiss()
                        Toast.makeText(this, "Translate failed", Toast.LENGTH_SHORT).show()
                    }
                }.addOnFailureListener {
                    translateDlg?.dismiss()
                    Toast.makeText(this, "Model download failed - check internet (one-time ~30MB)", Toast.LENGTH_LONG).show()
                }
            }
        }
        if (scriptLang != null) {
            startTranslate(scriptLang)
        } else {
            LanguageIdentification.getClient().identifyLanguage(src)
                .addOnSuccessListener { lang ->
                    if (lang == "und") {
                        translateDlg?.dismiss()
                        Toast.makeText(this, "Couldn't detect the language", Toast.LENGTH_SHORT).show()
                    } else startTranslate(lang)
                }
                .addOnFailureListener {
                    translateDlg?.dismiss()
                    Toast.makeText(this, "Language detect failed", Toast.LENGTH_SHORT).show()
                }
        }
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
        cancelAutoSelect()
        Shared.shot = null
        super.onDestroy()
    }
}

class SelectionView(context: Context, private val bmp: Bitmap,
                    private val onFreePick: (RectF, Boolean, List<PointF>) -> Unit,
                    private val onTap: (Float, Float) -> Unit,
                    private val onDragStart: () -> Unit,
                    private val onResizeLive: (RectF) -> Unit,
                    private val onResizeEnd: (RectF) -> Unit,
                    private val onCursorMove: (Int, Float, Float) -> Unit = { _, _, _ -> },
                    private val onCursorEnd: (Int, Float, Float) -> Unit = { _, _, _ -> },
                    private val onCursorDragStart: () -> Unit = {},
                    private val onCancel: () -> Unit = {}) : View(context) {

    private var scale = 1f
    private var ox = 0f
    private var oy = 0f
    private var sx = -1f; private var sy = -1f; private var ex = -1f; private var ey = -1f
    private val dragPath = ArrayList<PointF>()
    private var pathLen = 0f
    private var pathTurn = 0f    // absolute: lasso promotion (jitter counts here)
    private var pathNetTurn = 0f // signed: jitter cancels, real circling accumulates
    private var lastAng = 0f
    private var isLasso = false
    var recordMode = false        // Create GIF prep: white corner brackets, no handles
    var recording = false         // recording live: brackets only, nothing else drawn
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
    private val recBracket = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = 3f
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
        try { magnifier?.dismiss() } catch (_: Exception) {}
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
        recBracket.strokeWidth = dpF(3.4f)
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

    /** Clamp a touch point to the bitmap bounds. */
    private fun clampToBmp(x: Float, y: Float): PointF =
        PointF(x.coerceIn(ox, ox + bmp.width * scale), y.coerceIn(oy, oy + bmp.height * scale))
    fun bmpToView(b: RectF): RectF = RectF(ox + b.left * scale, oy + b.top * scale, ox + b.right * scale, oy + b.bottom * scale)

    // circle-to-search style text selection state
    var textMode = false
    private var textLinesList: List<RectF> = emptyList()
    var ocrLines: List<RectF> = emptyList() // live drag feedback: every OCR line, view coords
    private var textT = 1f
    private var textAnim: ValueAnimator? = null
    private val textHl = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; alpha = 70 }
    private val tmpSrc = Rect()

    /** Staggered left-to-right wipe-in of the per-line highlight (CTS confirm animation). */
    fun setTextLines(lines: List<RectF>, animate: Boolean = true) {
        // guard: a degenerate rect draws no highlight but its handle points
        // still draw - dropping them keeps highlight and handles consistent
        textLinesList = lines.filter { it.width() > 0.5f && it.height() > 0.5f }
        textAnim?.cancel()
        if (!animate) { textT = 1f; invalidate(); return }
        if (textLinesList.isEmpty()) { textT = 1f; invalidate(); return }
        textT = 0f
        textAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (300 + textLinesList.size * 60).coerceAtMost(900).toLong()
            addUpdateListener { textT = it.animatedValue as Float; invalidate() }
            start()
        }
        invalidate()
    }

    // selection cursors + magnifier loupe (Android-style, word-by-word adjustment)
    private var cursorMode = 0   // 0 none, 1 start, 2 end
    /** The dragged handle crossed the fixed end: it now drives the opposite handle. */
    fun flipCursorMode() { cursorMode = if (cursorMode == 1) 2 else if (cursorMode == 2) 1 else cursorMode }
    private var magnifier: Magnifier? = null
    private val cursorFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFA8B8FF.toInt(); style = Paint.Style.FILL }
    private val cursorRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = dpF(2f) }
    private val cursorStem = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFA8B8FF.toInt(); style = Paint.Style.STROKE; strokeWidth = dpF(3f); strokeCap = Paint.Cap.ROUND }
    private fun handleCenter(pt: PointF): PointF = PointF(pt.x, pt.y + dpF(19f))
    private fun startHandlePt(): PointF? = textLinesList.firstOrNull()?.let { PointF(it.left, it.bottom) }
    private fun endHandlePt(): PointF? = textLinesList.lastOrNull()?.let { PointF(it.right, it.bottom) }
    private fun cursorDown(x: Float, y: Float): Boolean {
        if (!textMode || confirmed == null || textLinesList.isEmpty()) return false
        val hr = dpF(30f)
        val sh = startHandlePt()?.let { handleCenter(it) }
        val eh = endHandlePt()?.let { handleCenter(it) }
        val hitEnd = eh != null && kotlin.math.abs(x - eh.x) <= hr && kotlin.math.abs(y - eh.y) <= hr
        val hitStart = sh != null && kotlin.math.abs(x - sh.x) <= hr && kotlin.math.abs(y - sh.y) <= hr
        if (!hitStart && !hitEnd) return false
        cursorMode = if (hitEnd) 2 else 1
        onCursorDragStart()   // hide bar/chip only: keep textMode + text lines while adjusting
        invalidate()
        return true
    }

    fun confirmedRect(): RectF? = confirmed

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
        if (recording) {
            // live capture: draw ONLY the frame brackets - everything else (dim, chrome,
            // glow) would be baked into the recording itself
            val c = confirmed
            if (c != null) {
                val arm = dpF(20f)
                tmpPath.reset()
                bracket(c.left, c.top, 1f, 1f, arm)
                bracket(c.right, c.top, -1f, 1f, arm)
                bracket(c.left, c.bottom, 1f, -1f, arm)
                bracket(c.right, c.bottom, -1f, -1f, arm)
                canvas.drawPath(tmpPath, recBracket)
            }
            return
        }
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

        // draw the drag preview only once the pointer actually moved: at DOWN ex==sx,
        // so an untainted tap would flash a zero-size rect + corner brackets (a "+" mark)
        // before ACTION_UP routes it to tapDetect
        val dragging = sx >= 0 && ex >= 0 && (kotlin.math.abs(ex - sx) > 16 || kotlin.math.abs(ey - sy) > 16)
        if (dragging && !isLasso && dragPath.size > 1) {
            // faint live trail under the box preview: instant feedback that the path is seen
            tmpPath.reset()
            tmpPath.moveTo(dragPath[0].x, dragPath[0].y)
            for (pt in dragPath) tmpPath.lineTo(pt.x, pt.y)
            canvas.drawPath(tmpPath, dragBracket)
        }
        // Box vs trail (v6): the trail is instant feedback for every non-lasso
        // drag. The box (and text light-up) appear only when the path is long
        // AND straight: a circle of radius R turns pathLen/R radians, so at
        // 200dp any real circle (R < ~650dp, i.e. anything that fits on screen)
        // has turned past 0.3 rad and never gets a box. The lasso check runs on
        // every MOVE and hides the box the instant circling is detected, so a
        // big slow circle shows trail only until it is classified.
        val boxReady = dragging && !isLasso && pathLen > dpF(200f) &&
                kotlin.math.abs(pathNetTurn) < 0.3f
        if (boxReady && ocrLines.isNotEmpty()) {
            // CTS live feedback: text lines light up the moment the drag touches them
            val dr = normRect()
            for (ln in ocrLines) if (RectF.intersects(ln, dr)) canvas.drawRoundRect(ln, dpF(6f), dpF(6f), textHl)
        }
        if (dragging && isLasso && dragPath.size > 1) {
            // circle-to-search trail: pastel gradient along the drawn path
            tmpPath.reset()
            tmpPath.moveTo(dragPath[0].x, dragPath[0].y)
            for (pt in dragPath) tmpPath.lineTo(pt.x, pt.y)
            selMatrix.setRotate(angle, ex, ey)
            selShader.setLocalMatrix(selMatrix)
            selGlow2.alpha = 40; selGlow1.alpha = 80; selMain.alpha = 255
            canvas.drawPath(tmpPath, selGlow2)
            canvas.drawPath(tmpPath, selGlow1)
            canvas.drawPath(tmpPath, selMain)
        }
        val sel = confirmed ?: if (boxReady) normRect() else null
        if (sel != null) {
            val textHlActive = confirmed != null && textMode && textLinesList.isNotEmpty()
            if (!textHlActive) canvas.drawRoundRect(sel, selRadius, selRadius, bmpPaint)
            if (confirmed != null) {
                if (textHlActive) {
                    // CTS text look: each line redraws crisp over the dim, then a white
                    // translucent highlight wipes in left-to-right, staggered per line
                    val n = textLinesList.size
                    for ((i, ln) in textLinesList.withIndex()) {
                        tmpSrc.set(
                            ((ln.left - ox) / scale).toInt().coerceIn(0, bmp.width - 1),
                            ((ln.top - oy) / scale).toInt().coerceIn(0, bmp.height - 1),
                            ((ln.right - ox) / scale).toInt().coerceIn(1, bmp.width),
                            ((ln.bottom - oy) / scale).toInt().coerceIn(1, bmp.height)
                        )
                        if (tmpSrc.right <= tmpSrc.left) tmpSrc.right = tmpSrc.left + 1
                        if (tmpSrc.bottom <= tmpSrc.top) tmpSrc.bottom = tmpSrc.top + 1
                        canvas.drawBitmap(bmp, tmpSrc, ln, bmpPaint)
                        val f = (textT * (n * 0.18f + 1f) - i * 0.18f).coerceIn(0f, 1f)
                        if (f > 0f) {
                            canvas.save()
                            canvas.clipRect(ln.left, ln.top, ln.left + ln.width() * f, ln.bottom)
                            canvas.drawRoundRect(ln, dpF(6f), dpF(6f), textHl)
                            canvas.restore()
                        }
                    }
                    // adjustment cursors at both ends of the selection: the stem
                    // spans the text line height so the cursor matches the text
                    // size; the knob stays a fixed usable touch size
                    val hr2 = dpF(9f)
                    val firstLine = textLinesList.firstOrNull()
                    val lastLine = textLinesList.lastOrNull()
                    for ((pt, line) in listOfNotNull(
                        startHandlePt()?.let { it to firstLine },
                        endHandlePt()?.let { it to lastLine })) {
                        val hc = handleCenter(pt)
                        val stemTop = line?.top ?: (pt.y - dpF(4f))
                        canvas.drawLine(pt.x, stemTop, pt.x, hc.y, cursorStem)
                        canvas.drawCircle(hc.x, hc.y, hr2, cursorFill)
                        canvas.drawCircle(hc.x, hc.y, hr2, cursorRing)
                    }
                } else if (recordMode) {
                    // Create GIF prep: plain white square-corner brackets, no gradient, no pulse
                    val arm = dpF(20f)
                    tmpPath.reset()
                    bracket(sel.left, sel.top, 1f, 1f, arm)
                    bracket(sel.right, sel.top, -1f, 1f, arm)
                    bracket(sel.left, sel.bottom, 1f, -1f, arm)
                    bracket(sel.right, sel.bottom, -1f, -1f, arm)
                    canvas.drawPath(tmpPath, recBracket)
                } else {
                    selMatrix.setRotate(angle, sel.centerX(), sel.centerY())
                    selShader.setLocalMatrix(selMatrix)
                    val grow = (1f - pulse) * dpF(10f)
                    val pr = RectF(sel)
                    pr.inset(-grow, -grow)
                    selGlow2.alpha = (40 * pulse).toInt(); selGlow1.alpha = (80 * pulse).toInt(); selMain.alpha = (255 * pulse).toInt()
                    drawGlowSet(canvas, pr, selRadius + grow, selGlow2, selGlow1, selMain)
                }
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
        if (recordMode) return true   // frame is fixed while setting up / running a recording
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (cursorDown(e.x, e.y)) return true
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
                    dragPath.clear(); dragPath.add(PointF(e.x, e.y)); pathLen = 0f; pathTurn = 0f; pathNetTurn = 0f; lastAng = 0f; isLasso = false
                    onDragStart()
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (cursorMode != 0) {
                    if (magnifier == null) {
                        // Loupe for handle drags: big enough for a word + neighbors
                        // (180x72dp), modest 1.25x zoom. setSize takes pixels.
                        magnifier = Magnifier.Builder(this)
                            .setSize(dpF(180f).toInt(), dpF(72f).toInt())
                            .setInitialZoom(1.25f)
                            .build()
                    }
                    val cp = clampToBmp(e.x, e.y)
                    // Source = the true finger point (view coords); never clamped
                    // away from it. Popup = centered above the finger, clamped
                    // inside the view; flips below the finger when there is no
                    // room above. The handle stays at the finger, clear of the popup.
                    val loupeW = dpF(180f); val loupeH = dpF(72f); val gap = dpF(16f)
                    val fx = e.x; val fy = e.y
                    val px = fx.coerceIn(loupeW / 2f, (width - loupeW / 2f).coerceAtLeast(loupeW / 2f))
                    var py = fy - loupeH / 2f - gap
                    if (py < loupeH / 2f) py = fy + loupeH / 2f + gap  // flip below
                    py = py.coerceIn(loupeH / 2f, (height - loupeH / 2f).coerceAtLeast(loupeH / 2f))
                    try {
                        if (android.os.Build.VERSION.SDK_INT >= 29) magnifier?.show(fx, fy, px, py)
                        else magnifier?.show(fx, fy)
                    } catch (_: Exception) {}
                    onCursorMove(cursorMode, cp.x, cp.y)
                    invalidate()
                    return true
                }
                if (resizeHandle >= 0) {
                    applyResize(e.x, e.y)
                    onResizeLive(RectF(confirmed!!))
                } else if (!insideDown && sx >= 0) {
                    val lx = ex; val ly = ey
                    // keep the drag point inside the bitmap: the trail, the lasso
                    // heuristics and the preview rect all stay in-bounds (the DOWN
                    // point is intentionally left unclamped for hit-testing)
                    val cl = clampToBmp(e.x, e.y); ex = cl.x; ey = cl.y
                    val seg = kotlin.math.hypot(ex - lx, ey - ly)
                    if (seg > dpF(2f)) {
                        dragPath.add(PointF(ex, ey)); pathLen += seg
                        // circle-to-search: any roughly circular / curvy path is a lasso.
                        // A box drag is one straight diagonal: it never curves much and
                        // never comes back near its start.
                        val ang = kotlin.math.atan2(ey - ly, ex - lx)
                        if (lastAng != 0f) {
                            var d = ang - lastAng
                            while (d > Math.PI) d -= (2 * Math.PI).toFloat()
                            while (d < -Math.PI) d += (2 * Math.PI).toFloat()
                            pathTurn += kotlin.math.abs(d)
                            pathNetTurn += d
                        }
                        lastAng = ang
                        if (!isLasso && dragPath.size > 8 && pathLen > dpF(100f)) {
                            val p0 = dragPath[0]
                            val closure = kotlin.math.hypot(ex - p0.x, ey - p0.y)
                            if (closure < dpF(90f) || closure < pathLen * 0.22f || pathTurn > 3.6f) isLasso = true
                        }
                    }
                }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                if (cursorMode != 0) {
                    val cm = cursorMode; cursorMode = 0
                    try { magnifier?.dismiss() } catch (_: Exception) {}
                    val cp = clampToBmp(e.x, e.y)
                    onCursorEnd(cm, cp.x, cp.y)
                    invalidate()
                    return true
                }
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
                run { val cl = clampToBmp(ex, ey); ex = cl.x; ey = cl.y }
                if (isLasso && dragPath.size > 8) {
                    // snapshot the closed path in bmp coords before the drag state is
                    // cleared: word selection must test against the path, not its rect
                    val pathBmp = dragPath.map { pt -> viewToBmpPoint(pt.x, pt.y) }
                    var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var rr = 0f; var bb = 0f
                    for (pt in dragPath) {
                        if (pt.x < l) l = pt.x; if (pt.y < t) t = pt.y
                        if (pt.x > rr) rr = pt.x; if (pt.y > bb) bb = pt.y
                    }
                    val lp = dpF(6f)
                    l = max(ox, l - lp); t = max(oy, t - lp)
                    rr = min(ox + bmp.width * scale, rr + lp); bb = min(oy + bmp.height * scale, bb + lp)
                    val lr = RectF(l, t, rr, bb)
                    sx = -1f; ex = -1f; isLasso = false; dragPath.clear(); invalidate()
                    if (lr.width() > 30 && lr.height() > 30) onFreePick(lr, true, pathBmp)
                    return true
                }
                isLasso = false; dragPath.clear()
                val r = normRect()
                val moved = kotlin.math.abs(ex - sx) > 16 || kotlin.math.abs(ey - sy) > 16
                if (!moved) {
                    sx = -1f; ex = -1f; invalidate()
                    onTap(e.x, e.y)
                } else if (r.width() > 30 && r.height() > 30) {
                    onFreePick(RectF(r), false, emptyList())
                } else {
                    sx = -1f; ex = -1f; invalidate()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                // gesture aborted: clear the magnifier, reset the drag state.
                // Only a cursor drag restores chrome: a fresh box/lasso drag hid
                // it via hideChrome() (no cursor snapshot) and cleared the
                // selection at drag start, so there is nothing to restore.
                // The confirmed selection stays as it was.
                try { magnifier?.dismiss() } catch (_: Exception) {}
                magnifier = null
                val wasCursor = cursorMode != 0
                cursorMode = 0
                sx = -1f; sy = -1f; ex = -1f; ey = -1f
                isLasso = false; dragPath.clear()
                pathLen = 0f; pathTurn = 0f; pathNetTurn = 0f; lastAng = 0f
                resizeHandle = -1; insideDown = false
                if (wasCursor) onCancel()
                invalidate()
                return true
            }
        }
        return true
    }
}
