package com.aipro.select

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/** Serves images from cache/img as content:// URIs for clipboard and share. */
class ImageProvider : ContentProvider() {
    companion object {
        const val AUTH = "com.aipro.select.imageprovider"
        fun uriFor(name: String): Uri = Uri.parse("content://$AUTH/$name")
    }
    private fun fileFor(uri: Uri): File? {
        val name = uri.pathSegments.firstOrNull() ?: return null
        if (name.contains("..") || name.contains("/")) return null
        val f = File(context!!.cacheDir, "img/$name")
        return if (f.isFile) f else null
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val f = fileFor(uri) ?: return null
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun query(uri: Uri, projection: Array<out String>?, sel: String?, args: Array<out String>?, order: String?): Cursor? {
        val f = fileFor(uri) ?: return null
        val c = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
        c.addRow(arrayOf(f.name, f.length()))
        return c
    }
    override fun getType(uri: Uri): String {
        val n = uri.pathSegments.firstOrNull() ?: return "image/*"
        return if (n.endsWith(".gif")) "image/gif" else "image/png"
    }
    override fun onCreate(): Boolean {
        // Crops and GIFs shared through this provider live in cache/img. Delete anything older than 24 h so old screen captures do not linger.
        try {
            File(context!!.cacheDir, "img").listFiles()?.forEach {
                if (System.currentTimeMillis() - it.lastModified() > 24L * 3600L * 1000L) it.delete()
            }
        } catch (e: Exception) { }
        return true
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, s: String?, a: Array<out String>?) = 0
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
}
