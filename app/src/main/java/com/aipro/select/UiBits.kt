package com.aipro.select

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Shader
import android.widget.TextView

class ShimmerTextView(context: Context) : TextView(context) {
    private var anim: ValueAnimator? = null
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post {
            val w = width.toFloat().coerceAtLeast(1f)
            val g = LinearGradient(0f, 0f, w * 0.6f, 0f,
                intArrayOf(0xFFF5A623.toInt(), Color.WHITE, 0xFFF5A623.toInt()),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            paint.shader = g
            val m = Matrix()
            anim = ValueAnimator.ofFloat(-0.6f, 1f).apply {
                duration = 2200
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener {
                    m.setTranslate((it.animatedValue as Float) * w, 0f)
                    g.setLocalMatrix(m)
                    invalidate()
                }
                start()
            }
        }
    }
    override fun onDetachedFromWindow() {
        anim?.cancel()
        super.onDetachedFromWindow()
    }
}
