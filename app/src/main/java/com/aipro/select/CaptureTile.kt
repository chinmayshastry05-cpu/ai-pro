package com.aipro.select

import android.content.Intent
import android.service.quicksettings.TileService

class CaptureTile : TileService() {
    override fun onClick() {
        super.onClick()
        val i = Intent(this, CaptureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        @Suppress("DEPRECATION")
        startActivityAndCollapse(i)
    }
}
