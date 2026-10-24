package com.aipro.fixture

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView

/** One long single-node message (the WhatsApp shape) + an invisible accessibility
 *  marker ("rg-target") exactly over the target word + an EditText paste target. */
class FixtureActivity : Activity() {
    private val message = "Can you watch this video without a jerk, first time"
    private val target = "jerk"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        val root = FrameLayout(this)
        val tv = TextView(this)
        tv.text = message
        tv.textSize = 24f
        val pad = (24 * d).toInt()
        tv.setPadding(pad, pad, pad, pad)
        root.addView(tv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val marker = View(this)
        marker.contentDescription = "rg-target"
        marker.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        root.addView(marker, FrameLayout.LayoutParams(4, 4))
        val et = EditText(this)
        et.hint = "paste-here"
        et.setSingleLine()
        val elp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        elp.topMargin = (400 * d).toInt()
        root.addView(et, elp)
        setContentView(root)
        tv.post {
            val layout = tv.layout ?: return@post
            val start = message.indexOf(target)
            if (start < 0) return@post
            val end = start + target.length
            val line = layout.getLineForOffset(start)
            val x1 = tv.paddingLeft + layout.getPrimaryHorizontal(start)
            val x2 = tv.paddingLeft + layout.getPrimaryHorizontal(end)
            val y1 = tv.paddingTop + layout.getLineTop(line)
            val y2 = tv.paddingTop + layout.getLineBottom(line)
            val lp = marker.layoutParams as FrameLayout.LayoutParams
            lp.leftMargin = x1.toInt()
            lp.topMargin = y1
            lp.width = (x2 - x1).toInt().coerceAtLeast(4)
            lp.height = (y2 - y1)
            marker.layoutParams = lp
        }
    }
}
