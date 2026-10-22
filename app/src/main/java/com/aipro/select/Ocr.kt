package com.aipro.select

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizerOptionsInterface
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Multi-script OCR: ML Kit (Latin, Devanagari, Chinese, Japanese, Korean) plus
 * Tesseract with kan+hin+eng tessdata for Kannada and mixed Indic/English text.
 *
 * Tesseract lives on its OWN executor with a hard timeout: if its native init
 * hangs or crashes on a device, ML Kit results still come back and tessError
 * says why - never a silent block of all reading.
 */
object Ocr {

    private val mlExecutor = Executors.newSingleThreadExecutor()
    private val tessExecutor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var tess: TessBaseAPI? = null
    @Volatile var tessReady = false
    @Volatile var tessError: String? = null

    /** Warm Tesseract off the main thread (copies tessdata out of assets once). */
    fun init(context: Context) {
        tessExecutor.execute { ensureTess(context.applicationContext) }
    }

    private fun ensureTess(ctx: Context): TessBaseAPI? {
        tess?.let { return it }
        return try {
            val dir = File(ctx.filesDir, "tessdata")
            if (!File(dir, "kan.traineddata").exists() || !File(dir, "hin.traineddata").exists() || !File(dir, "eng.traineddata").exists()) {
                dir.mkdirs()
                for (lang in listOf("kan", "hin", "eng")) {
                    ctx.assets.open("tessdata/$lang.traineddata").use { inp ->
                        File(dir, "$lang.traineddata").outputStream().use { out -> inp.copyTo(out) }
                    }
                }
            }
            val t = TessBaseAPI()
            t.init(ctx.filesDir.absolutePath, "kan+hin+eng")
            tess = t
            tessReady = true
            t
        } catch (e: Throwable) {
            tessError = e.javaClass.simpleName + ": " + (e.message ?: "?")
            null
        }
    }

    private fun countBlock(s: String, lo: Int, hi: Int): Int {
        var n = 0
        for (ch in s) if (ch.code in lo..hi) n++
        return n
    }

    private fun countHangul(s: String) = s.count { it.code in 0xAC00..0xD7A3 || it.code in 0x1100..0x11FF }

    /** Upscale small crops, grayscale, and invert dark backgrounds - Tesseract reads small Indic text far better this way. */
    private fun preprocess(bmp: Bitmap): Bitmap {
        var b = bmp
        if (b.width < 900 && b.width > 0) {
            val f = minOf(3f, 900f / b.width)
            b = Bitmap.createScaledBitmap(b, (b.width * f).toInt(), (b.height * f).toInt(), true)
        }
        val w = b.width; val h = b.height
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        var sum = 0L
        for (p in px) sum += (p ushr 16 and 0xFF) * 299 + (p ushr 8 and 0xFF) * 587 + (p and 0xFF) * 114
        val dark = sum / (w.toLong() * h * 1000L) < 128
        for (i in px.indices) {
            val r = px[i] ushr 16 and 0xFF; val g = px[i] ushr 8 and 0xFF; val bl = px[i] and 0xFF
            var v = (r * 299 + g * 587 + bl * 114) / 1000
            if (dark) v = 255 - v
            px[i] = 0xFF shl 24 or (v shl 16) or (v shl 8) or v
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    /** OCR a crop; result lands on the main thread. cb(text, tesseractAvailable). Never throws, never blocks forever. */
    fun recognize(ctx: Context, bmp: Bitmap, cb: (String, Boolean) -> Unit) {
        val appCtx = ctx.applicationContext
        val pre = try { preprocess(bmp) } catch (e: Exception) { bmp }
        val tessResult = AtomicReference<String?>(null)
        val latch = CountDownLatch(1)
        tessExecutor.execute {
            try {
                ensureTess(appCtx)?.let { t ->
                    t.setImage(pre)
                    tessResult.set((t.utF8Text ?: "").trim())
                }
            } catch (e: Throwable) {
                tessError = e.javaClass.simpleName + ": " + (e.message ?: "?")
            }
            latch.countDown()
        }
        mlExecutor.execute {
            val img = InputImage.fromBitmap(pre, 0)
            fun run(opts: TextRecognizerOptionsInterface): String = try {
                Tasks.await(TextRecognition.getClient(opts).process(img)).text.trim()
            } catch (e: Exception) { "" }
            val latin = run(TextRecognizerOptions.DEFAULT_OPTIONS)
            val deva  = run(DevanagariTextRecognizerOptions.Builder().build())
            val chin  = run(ChineseTextRecognizerOptions.Builder().build())
            val jap   = run(JapaneseTextRecognizerOptions.Builder().build())
            val kor   = run(KoreanTextRecognizerOptions.Builder().build())
            // give Tesseract a bounded window; on a hung/failed device we still answer
            latch.await(8, TimeUnit.SECONDS)
            val tessText = tessResult.get() ?: ""
            val out = pick(latin, deva, chin, jap, kor, tessText)
            val ok = tessReady
            main.post { cb(out, ok) }
        }
    }

    private fun pick(latin: String, deva: String, chin: String, jap: String, kor: String, tess: String): String {
        data class C(val text: String, val score: Int)
        val cands = ArrayList<C>()
        // Tesseract is the only engine that knows Kannada; also carries Hindi+English for mixed chat text
        if (tess.isNotEmpty())  cands.add(C(tess, countBlock(tess, 0x0C80, 0x0CFF) * 3 + countBlock(tess, 0x0900, 0x097F)))
        if (deva.isNotEmpty())  cands.add(C(deva, countBlock(deva, 0x0900, 0x097F) * 3))
        if (jap.isNotEmpty())   cands.add(C(jap, countBlock(jap, 0x3040, 0x30FF) * 3 + countBlock(jap, 0x4E00, 0x9FFF)))
        if (chin.isNotEmpty())  cands.add(C(chin, countBlock(chin, 0x4E00, 0x9FFF) * 3))
        if (kor.isNotEmpty())   cands.add(C(kor, countHangul(kor) * 3))
        if (latin.isNotEmpty()) cands.add(C(latin, latin.count { it.isLetter() }))
        val best = cands.maxByOrNull { it.score }
        if (best != null && best.score >= 2) return best.text
        // no engine confident: fall back to the longest non-empty read
        return listOf(tess, latin, deva, chin, jap, kor).filter { it.isNotEmpty() }.maxByOrNull { it.length } ?: ""
    }
}
