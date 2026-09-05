package cloud.wumboing.rpchat.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Visualizer sederhana untuk bubble pesan suara: menggambar batang-batang
 * (mirip waveform) yang mewarnai bagian yang "sudah diputar" dengan warna
 * aksen dan sisanya dengan warna redup. Bisa disentuh/diseret untuk
 * memindahkan posisi putar (seek).
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var amplitudes: FloatArray = FloatArray(0)
    private var progress: Float = 0f // 0f..1f
    private var onSeekListener: ((Float) -> Unit)? = null

    private var playedColor: Int = 0xFF3D8BFF.toInt()
    private var unplayedColor: Int = 0x55FFFFFF

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barRect = RectF()

    private val barGapDp = 2.5f
    private val minBarWidthDp = 2.5f

    fun setColors(played: Int, unplayed: Int) {
        playedColor = played
        unplayedColor = unplayed
        invalidate()
    }

    fun setAmplitudes(values: FloatArray) {
        amplitudes = values
        invalidate()
    }

    fun setProgress(fraction: Float) {
        progress = fraction.coerceIn(0f, 1f)
        invalidate()
    }

    fun setOnSeekListener(listener: (Float) -> Unit) {
        onSeekListener = listener
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (amplitudes.isEmpty() || width <= 0 || height <= 0) return

        val density = resources.displayMetrics.density
        val gap = barGapDp * density
        val minBarWidth = minBarWidthDp * density
        val count = amplitudes.size
        val totalGap = gap * (count - 1)
        val barWidth = max(minBarWidth, (width - totalGap) / count)
        val centerY = height / 2f

        var x = 0f
        for (i in 0 until count) {
            if (x > width) break
            val amp = amplitudes[i].coerceIn(0.08f, 1f)
            val barHeight = height * amp
            val top = centerY - barHeight / 2f
            val bottom = centerY + barHeight / 2f
            val playedFraction = i.toFloat() / count
            barPaint.color = if (playedFraction <= progress) playedColor else unplayedColor
            barRect.set(x, top, min(x + barWidth, width.toFloat()), bottom)
            canvas.drawRoundRect(barRect, barWidth / 2f, barWidth / 2f, barPaint)
            x += barWidth + gap
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val fraction = (event.x / width).coerceIn(0f, 1f)
                setProgress(fraction)
                return true
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                val fraction = (event.x / width).coerceIn(0f, 1f)
                onSeekListener?.invoke(fraction)
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
