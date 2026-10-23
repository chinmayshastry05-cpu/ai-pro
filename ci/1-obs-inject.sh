#!/bin/bash
# TEST-BRANCH ONLY: add logcat counters (OCR word/line counts, touch actions, tap point, first word boxes). No release use.
F=app/src/main/java/com/aipro/select/SelectActivity.kt
sed -i 's/^\( *\)tapOcrDone.incrementAndGet()$/&\n\1android.util.Log.d("RGOBS", "ocr done n=" + tapOcrDone.get() + " blocks=" + r.textBlocks.size)/' $F
sed -i 's/^\( *\)private fun tapDetect(viewX: Float, viewY: Float) {$/&\n\1val rgp = selView.viewToBmpPoint(viewX, viewY); android.util.Log.d("RGOBS", "tapDetect view=" + viewX + "," + viewY + " bmp=" + rgp.x + "," + rgp.y + " words=" + wordBoxes.size + " lines=" + lineBoxes.size + " ocrDone=" + tapOcrDone.get() + " nearWords=" + wordBoxes.filter { Math.abs(it.centerY() - rgp.y) < 120f }.joinToString { it.toShortString() } + " hitWords=" + wordBoxes.count { android.graphics.RectF(it).apply { inset(-6f, -6f) }.contains(rgp.x, rgp.y) } + " hitLines=" + lineBoxes.count { android.graphics.RectF(it).apply { inset(-6f, -6f) }.contains(rgp.x, rgp.y) })/' $F
sed -i 's/^\( *\)override fun onTouchEvent(e: MotionEvent): Boolean {$/&\n\1if (e.actionMasked != MotionEvent.ACTION_MOVE) android.util.Log.d("RGOBS", "touch action=" + e.actionMasked + " x=" + e.x + " y=" + e.y)/' $F
test "$(grep -c RGOBS $F)" = "3" || { echo "obs inject failed"; exit 1; }
