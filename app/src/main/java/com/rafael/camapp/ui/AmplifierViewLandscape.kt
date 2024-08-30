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

class AmplifierViewLandscape @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : AmplifierView(context, attrs, defStyleAttr) {

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Calculate the width of the filled part of the bar based on the strength
        val barHeight = height * super.strength

        // Define padding and corner radius
        val padding = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1.0f, context.resources.displayMetrics)
        val roundRadius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10.0f, context.resources.displayMetrics)

        // Create a LinearGradient for the gradient effect
        val gradient = LinearGradient(
            0f, 0f, 0f, width.toFloat(), // Start and end points for the gradient
            Color.GREEN, // Start color (weak sound)
            Color.RED,   // End color (strong sound)
            Shader.TileMode.CLAMP
        )

        // Apply the gradient to the paint
        paint.shader = gradient

        // Draw the rounded rectangle with the gradient
        canvas.drawRoundRect(
            RectF(height.toFloat() - padding, padding, padding, barHeight - padding),
            roundRadius,
            roundRadius,
            paint
        )
    }
}
