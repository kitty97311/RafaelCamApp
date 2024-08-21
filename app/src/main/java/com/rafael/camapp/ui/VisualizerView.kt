package com.rafael.camapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class VisualizerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint()
    private var amplitudes: List<Float> = emptyList()

    init {
        paint.color = 0xFF0000FF.toInt() // Set the color for the waveform
        paint.strokeWidth = 1f
        paint.isAntiAlias = true
    }

    fun updateAmplitudes(amplitudes: List<Float>) {
        this.amplitudes = amplitudes
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas ?: return

        val width = width.toFloat()
        val height = height.toFloat()
        val middle = height / 2

        val maxAmplitude = amplitudes.maxOrNull() ?: 1f

        val scaledAmplitudes = amplitudes.map { it / maxAmplitude * middle }

        val barWidth = width / scaledAmplitudes.size

        for (i in scaledAmplitudes.indices) {
            val x = i * barWidth
            val y = scaledAmplitudes[i]
            canvas.drawLine(x, middle + y, x, middle - y, paint)
        }
    }
}
