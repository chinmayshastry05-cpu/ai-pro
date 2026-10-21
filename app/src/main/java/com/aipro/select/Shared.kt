package com.aipro.select

import android.content.Intent
import android.graphics.Bitmap

object Shared {
    @Volatile var shot: Bitmap? = null
    @Volatile var projCb: ((Int, Intent?) -> Unit)? = null   // ProjPermissionActivity returns the MediaProjection grant here
    @Volatile var recListener: ((String?) -> Unit)? = null   // recorder status; null = recording ended
    @Volatile var gifResult: String? = null                  // cache/img file name of the last saved GIF
    @Volatile var gifFirstFrame: Bitmap? = null              // first recorded frame, for Pin
}
