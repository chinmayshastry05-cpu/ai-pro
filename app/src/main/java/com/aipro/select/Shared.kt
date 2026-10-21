package com.aipro.select

import android.graphics.Bitmap

object Shared {
    @Volatile var shot: Bitmap? = null
    @Volatile var closeMain: (() -> Unit)? = null   // SelectActivity calls this once it is on screen
}
