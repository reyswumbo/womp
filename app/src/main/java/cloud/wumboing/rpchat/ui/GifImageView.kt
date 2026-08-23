package cloud.wumboing.rpchat.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Movie
import android.util.AttributeSet
import android.view.View
import java.io.File

/**
 * View sederhana buat menampilkan GIF animasi pakai android.graphics.Movie,
 * tanpa perlu library eksternal (biar APK tetap kecil).
 */
class GifImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var movie: Movie? = null
    private var movieStart: Long = 0

    fun setGifFile(path: String) {
        movie = try {
            Movie.decodeFile(path)
        } catch (e: Exception) {
            null
        }
        movieStart = 0
        invalidate()
    }

    fun clear() {
        movie = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val m = movie ?: return
        val now = android.os.SystemClock.uptimeMillis()
        if (movieStart == 0L) movieStart = now
        val duration = if (m.duration() == 0) 1000 else m.duration()
        val relTime = ((now - movieStart) % duration).toInt()
        m.setTime(relTime)

        val scaleX = width.toFloat() / m.width()
        val scaleY = height.toFloat() / m.height()
        canvas.save()
        canvas.scale(scaleX, scaleY)
        m.draw(canvas, 0f, 0f)
        canvas.restore()
        invalidate()
    }
}
