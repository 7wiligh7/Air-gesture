/*
 * AirGesture — 隔空手势
 * Copyright (c) 2026 7wiligh7
 * SPDX-License-Identifier: MIT
 */

package com.airgesture.detector

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import kotlin.math.hypot

class HandOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var result: HandLandmarkerResult? = null
    private var imageWidth = 0
    private var imageHeight = 0

    private val landmarkPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.FILL
    }

    private val connectionPaint = Paint().apply {
        color = Color.parseColor("#00D4FF")
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 32f
    }

    fun setResult(result: HandLandmarkerResult?, imgWidth: Int, imgHeight: Int) {
        this.result = result
        this.imageWidth = imgWidth
        this.imageHeight = imgHeight
        invalidate()
    }

    fun clearResult() {
        this.result = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val result = this.result ?: return
        if (imageWidth == 0 || imageHeight == 0) return

        val landmarks = result.landmarks()
        if (landmarks.isEmpty()) return

        // 与检测器一致：多手时只画离画面中心最近的主手
        var handIndex = -1
        var bestDist = Float.MAX_VALUE
        for (i in landmarks.indices) {
            val h = landmarks[i]
            if (h.size < 21) continue
            val px = (h[0].x() + h[5].x() + h[17].x()) / 3f
            val py = (h[0].y() + h[5].y() + h[17].y()) / 3f
            val d = hypot(px - 0.5f, py - 0.5f)
            if (d < bestDist) {
                bestDist = d
                handIndex = i
            }
        }
        if (handIndex < 0) return
        val hand = landmarks[handIndex]
        val w = width.toFloat()
        val h = height.toFloat()

        for (conn in HAND_CONNECTIONS) {
            val start = hand.getOrNull(conn.first) ?: continue
            val end = hand.getOrNull(conn.second) ?: continue
            canvas.drawLine(start.x() * w, start.y() * h, end.x() * w, end.y() * h, connectionPaint)
        }

        for ((index, point) in hand.withIndex()) {
            val radius = if (index == 0) 14f else 7f
            canvas.drawCircle(point.x() * w, point.y() * h, radius, landmarkPaint)
        }

        val handLabel = result.handedness().getOrNull(handIndex)?.firstOrNull()
        if (handLabel != null) {
            val label = handLabel.categoryName()
            val score = handLabel.score()
            canvas.drawText(
                "$label ${Integer.valueOf((score * 100).toInt())}%",
                20f, h - 20f, textPaint
            )
        }
    }

    companion object {
        private val HAND_CONNECTIONS = listOf(
            0 to 1, 1 to 2, 2 to 3, 3 to 4,
            0 to 5, 5 to 6, 6 to 7, 7 to 8,
            5 to 9, 9 to 10, 10 to 11, 11 to 12,
            9 to 13, 13 to 14, 14 to 15, 15 to 16,
            13 to 17, 17 to 18, 18 to 19, 19 to 20,
            0 to 17
        )
    }
}
