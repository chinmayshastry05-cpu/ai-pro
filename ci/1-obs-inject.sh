#!/bin/bash
# TEST-BRANCH ONLY: add logcat counters (OCR word/line counts, touch actions, tap point, first word boxes). No release use.
F=app/src/main/java/com/aipro/select/SelectActivity.kt
sed -i 's/^\( *\)tapOcrDone.incrementAndGet()$/&\n\1android.util.Log.d("RGOBS", "ocr done n=" + tapOcrDone.get() + " blocks=" + r.textBlocks.size)/' $F
sed -i 's/^\( *\)private fun tapDetect(viewX: Float, viewY: Float) {$/&\n\1android.util.Log.d("RGOBS", "tapDetect view=" + viewX + "," + viewY + " words=" + wordBoxes.size + " lines=" + lineBoxes.size + " ocrDone=" + tapOcrDone.get() + " firstWords=" + wordBoxes.take(8).joinToString { it.toShortString() })/' $F
sed -i 's/^\( *\)override fun onTouchEvent(e: MotionEvent): Boolean {$/&\n\1if (e.actionMasked != MotionEvent.ACTION_MOVE) android.util.Log.d("RGOBS", "touch action=" + e.actionMasked + " x=" + e.x + " y=" + e.y)/' $F
test "$(grep -c RGOBS $F)" = "3" || { echo "obs inject failed"; exit 1; }
