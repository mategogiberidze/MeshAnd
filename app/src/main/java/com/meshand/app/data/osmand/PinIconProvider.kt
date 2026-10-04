package com.meshand.app.data.osmand

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import com.meshand.app.R
import java.io.File
import java.io.FileNotFoundException

/**
 * Serves pin images for OsmAnd's map: a white map pin on a disc in the sender's colour, as
 * `content://<package>.pinicons/<rrggbb>.png`. OsmAnd draws them inside its own pin-shaped marker
 * (see [OsmAndBridge], image-point layer). Read-only, and it only ever returns these generated
 * icons, so it's safe to export.
 */
class PinIconProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    @Synchronized
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Pin icons are read-only")
        val hex = uri.lastPathSegment?.removeSuffix(".png")?.takeIf { HEX.matches(it) }
            ?: throw FileNotFoundException("Unknown pin icon: $uri")
        val context = context ?: throw FileNotFoundException("No context")
        val file = File(context.cacheDir, "pin-icons/v1-$hex.png")
        if (!file.isFile) render(context, Color.rgb(hex.substring(0, 2).toInt(16), hex.substring(2, 4).toInt(16), hex.substring(4, 6).toInt(16)), file)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun render(context: Context, color: Int, file: File) {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawCircle(SIZE / 2f, SIZE / 2f, SIZE / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        val pin = ContextCompat.getDrawable(context, R.drawable.ic_pin) ?: throw FileNotFoundException("No pin drawable")
        val inset = SIZE / 6
        pin.setBounds(inset, inset, SIZE - inset, SIZE - inset)
        pin.draw(canvas)
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        tmp.renameTo(file)
    }

    override fun getType(uri: Uri): String = "image/png"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private const val SIZE = 96
        private val HEX = Regex("[0-9a-f]{6}")

        fun uriFor(context: Context, color: Int): String =
            "content://${context.packageName}.pinicons/%06x.png".format(color and 0xFFFFFF)
    }
}
