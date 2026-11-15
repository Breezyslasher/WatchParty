package dev.watchparty.tv

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Loads poster thumbnails for the browse list: 3 at a time, kept in memory. */
object Posters {

    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(3)
    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** Shows [url]'s image in [view] (blank until loaded; stays blank on failure). */
    fun load(url: String?, view: ImageView, widthPx: Int) {
        view.tag = url
        view.setImageDrawable(null)
        if (url == null) return
        cache.get(url)?.let {
            view.setImageBitmap(it)
            return
        }
        pool.execute {
            val bitmap = try {
                fetch(url, widthPx)
            } catch (e: Exception) {
                null
            } ?: return@execute
            cache.put(url, bitmap)
            main.post { if (view.tag == url) view.setImageBitmap(bitmap) }
        }
    }

    private fun fetch(url: String, widthPx: Int): Bitmap? {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 10_000
            val bytes = conn.inputStream.use { it.readBytes() }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= widthPx) sample *= 2
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        } finally {
            conn.disconnect()
        }
    }
}
