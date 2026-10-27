package com.example.myapplication.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.PixelCopy
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Captures the complete activity window and stores a PNG in the user's Pictures collection. */
object ScreenCapture {
    fun capture(activity: Activity) {
        val decorView = activity.window.decorView
        if (decorView.width <= 0 || decorView.height <= 0) {
            Toast.makeText(activity, "Screen is not ready to capture", Toast.LENGTH_SHORT).show()
            return
        }
        val bitmap = Bitmap.createBitmap(decorView.width, decorView.height, Bitmap.Config.ARGB_8888)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PixelCopy.request(activity.window, bitmap, { result ->
                if (result == PixelCopy.SUCCESS) saveBitmap(activity, bitmap)
                else Toast.makeText(activity, "Screenshot failed", Toast.LENGTH_SHORT).show()
            }, Handler(Looper.getMainLooper()))
        } else {
            decorView.draw(Canvas(bitmap))
            saveBitmap(activity, bitmap)
        }
    }

    private fun saveBitmap(activity: Activity, bitmap: Bitmap) {
        Thread {
            val filename = "VibrationFFT_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.png"
            try {
                val savedLocation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = android.content.ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/VibrationFFT")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                    val uri = activity.contentResolver.insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        values,
                    ) ?: error("Unable to create image")
                    activity.contentResolver.openOutputStream(uri).use { output ->
                        checkNotNull(output) { "Unable to open image output" }
                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "Unable to encode image" }
                    }
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    activity.contentResolver.update(uri, values, null, null)
                    "Pictures/VibrationFFT"
                } else {
                    // App-specific Pictures does not require legacy storage permission on Android 7–9.
                    val directory = File(activity.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "VibrationFFT")
                    check(directory.exists() || directory.mkdirs()) { "Unable to create screenshots directory" }
                    val file = File(directory, filename)
                    FileOutputStream(file).use { output ->
                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "Unable to encode image" }
                    }
                    MediaScannerConnection.scanFile(activity, arrayOf(file.absolutePath), arrayOf("image/png"), null)
                    file.absolutePath
                }
                activity.runOnUiThread {
                    Toast.makeText(activity, "Screenshot saved to $savedLocation", Toast.LENGTH_LONG).show()
                }
            } catch (_: Exception) {
                activity.runOnUiThread {
                    Toast.makeText(activity, "Unable to save screenshot", Toast.LENGTH_SHORT).show()
                }
            } finally {
                bitmap.recycle()
            }
        }.start()
    }
}
