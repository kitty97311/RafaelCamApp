package com.rafael.camapp.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View

class AmplifierView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var strength: Float = 0f // Representing the current strength (0.0 to 1.0)

    // Define the paint with a gradient shader
    private val paint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true // Smooths the edges
    }

    // Method to update the strength value
    fun updateStrength(newStrength: Float) {
        strength = newStrength.coerceIn(0f, 1f) // Ensure strength is within [0, 1]
        invalidate() // Triggers a redraw of the view
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Calculate the width of the filled part of the bar based on the strength
        val barWidth = width * strength

        // Define padding and corner radius
        val padding = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1.0f, context.resources.displayMetrics)
        val roundRadius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10.0f, context.resources.displayMetrics)

        // Create a LinearGradient for the gradient effect
        val gradient = LinearGradient(
            0f, 0f, width.toFloat(), 0f, // Start and end points for the gradient
            Color.GREEN, // Start color (weak sound)
            Color.RED,   // End color (strong sound)
            Shader.TileMode.CLAMP
        )

        // Apply the gradient to the paint
        paint.shader = gradient

        // Draw the rounded rectangle with the gradient
        canvas.drawRoundRect(
            RectF(padding, padding, barWidth - padding, height.toFloat() - padding),
            roundRadius,
            roundRadius,
            paint
        )
    }
}
