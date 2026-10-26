package com.texelcam.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.min

class TexelHudView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Dynamic state
    private var pitchErrorDeg: Float = 0f
    private var rollErrorDeg: Float = 0f
    private var isAligned: Boolean = false
    private var physicalWidthMeters: Float = 1.8f
    private var physicalHeightMeters: Float = 1.35f
    private var currentDistanceMeters: Float = 0.85f
    private var targetDensity: Int = 2048

    // Paints
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#9900E5FF")
    }

    private val levelOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
        color = Color.parseColor("#80FFFFFF")
    }

    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#00E5FF")
    }

    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.parseColor("#00E5FF")
    }

    private val frameDashedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#5500E5FF")
        pathEffect = DashPathEffect(floatArrayOf(12f, 12f), 0f)
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 32f
        textAlign = Paint.Align.CENTER
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }

    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#CC181A20")
    }

    fun updateOrientation(pitchError: Float, rollError: Float, aligned: Boolean) {
        this.pitchErrorDeg = pitchError
        this.rollErrorDeg = rollError
        this.isAligned = aligned
        invalidate()
    }

    fun updateMetrics(distMeters: Float, physW: Float, physH: Float, targetDens: Int) {
        this.currentDistanceMeters = distMeters
        this.physicalWidthMeters = physW
        this.physicalHeightMeters = physH
        this.targetDensity = targetDens
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val cx = w / 2f
        val cy = h / 2f

        // 1. Draw 1-Meter Projected Framing Box
        drawOneMeterReticle(canvas, cx, cy, w, h)

        // 2. Draw Bullseye Perpendicularity Level
        drawBullseyeLevel(canvas, cx, cy)
    }

    private fun drawOneMeterReticle(canvas: Canvas, cx: Float, cy: Float, w: Float, h: Float) {
        // Compute size in pixels that 1.0 meter represents at current distance
        val meterWidthPx = if (physicalWidthMeters > 0.05f) {
            (w * (1.0f / physicalWidthMeters)).coerceIn(100f, w * 0.95f)
        } else {
            w * 0.6f
        }

        val meterHeightPx = if (physicalHeightMeters > 0.05f) {
            (h * (1.0f / physicalHeightMeters)).coerceIn(100f, h * 0.95f)
        } else {
            meterWidthPx // default square
        }

        // Keep 1:1 square for texture capture
        val boxSide = min(meterWidthPx, meterHeightPx)
        val halfSide = boxSide / 2f

        val left = cx - halfSide
        val top = cy - halfSide
        val right = cx + halfSide
        val bottom = cy + halfSide

        // Draw dashed outline
        canvas.drawRect(left, top, right, bottom, frameDashedPaint)

        // Draw 4 Photogrammetric L-Corners
        val cornerLen = min(40f, boxSide * 0.15f)
        framePaint.color = if (isAligned) Color.parseColor("#00E676") else Color.parseColor("#00E5FF")

        // Top-Left
        canvas.drawLine(left, top, left + cornerLen, top, framePaint)
        canvas.drawLine(left, top, left, top + cornerLen, framePaint)

        // Top-Right
        canvas.drawLine(right, top, right - cornerLen, top, framePaint)
        canvas.drawLine(right, top, right, top + cornerLen, framePaint)

        // Bottom-Left
        canvas.drawLine(left, bottom, left + cornerLen, bottom, framePaint)
        canvas.drawLine(left, bottom, left, bottom - cornerLen, framePaint)

        // Bottom-Right
        canvas.drawLine(right, bottom, right - cornerLen, bottom, framePaint)
        canvas.drawLine(right, bottom, right, bottom - cornerLen, framePaint)

        // Meter Label Pill at Top Edge of Box
        val label = "1.0 m × 1.0 m"
        val labelW = textPaint.measureText(label)
        val pillRect = RectF(
            cx - labelW / 2f - 16f,
            top - 42f,
            cx + labelW / 2f + 16f,
            top - 6f
        )
        canvas.drawRoundRect(pillRect, 10f, 10f, badgePaint)
        textPaint.textSize = 24f
        textPaint.color = if (isAligned) Color.parseColor("#00E676") else Color.WHITE
        canvas.drawText(label, cx, top - 16f, textPaint)
    }

    private fun drawBullseyeLevel(canvas: Canvas, cx: Float, cy: Float) {
        val outerRadius = 70f
        val innerToleranceRadius = 14f

        // Bullseye level ring color
        val levelColor = when {
            isAligned -> Color.parseColor("#00E676") // Neon Green
            abs(pitchErrorDeg) < 3.0f && abs(rollErrorDeg) < 3.0f -> Color.parseColor("#FFD600") // Yellow
            else -> Color.parseColor("#FF5252") // Red
        }

        levelOuterPaint.color = levelColor
        levelOuterPaint.alpha = if (isAligned) 240 else 160

        // Outer reference circle
        canvas.drawCircle(cx, cy, outerRadius, levelOuterPaint)

        // Inner tolerance circle
        val dashPaint = Paint(levelOuterPaint).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = Color.parseColor("#80FFFFFF")
        }
        canvas.drawCircle(cx, cy, innerToleranceRadius, dashPaint)

        // Fixed Crosshairs
        linePaint.color = Color.parseColor("#66FFFFFF")
        canvas.drawLine(cx - outerRadius - 15f, cy, cx - innerToleranceRadius, cy, linePaint)
        canvas.drawLine(cx + innerToleranceRadius, cy, cx + outerRadius + 15f, cy, linePaint)
        canvas.drawLine(cx, cy - outerRadius - 15f, cx, cy - innerToleranceRadius, linePaint)
        canvas.drawLine(cx, cy + innerToleranceRadius, cx, cy + outerRadius + 15f, linePaint)

        // Dynamic Bubble Offset
        // 1 degree deviation = 12 pixels offset on screen
        val maxOffset = outerRadius - 10f
        val offsetX = (rollErrorDeg * 10f).coerceIn(-maxOffset, maxOffset)
        val offsetY = (pitchErrorDeg * 10f).coerceIn(-maxOffset, maxOffset)

        val bubbleX = cx + offsetX
        val bubbleY = cy - offsetY // pitch up moves bubble up

        bubblePaint.color = levelColor
        canvas.drawCircle(bubbleX, bubbleY, 10f, bubblePaint)

        // Center dot
        bubblePaint.color = Color.WHITE
        canvas.drawCircle(cx, cy, 3f, bubblePaint)
    }
}
