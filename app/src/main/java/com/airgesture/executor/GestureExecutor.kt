/*
 * AirGesture — 隔空手势
 * Copyright (c) 2026 7wiligh7
 * SPDX-License-Identifier: MIT
 */

package com.airgesture.executor

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Point
import android.os.Build
import android.util.Log

class GestureExecutor(private val service: AccessibilityService) {

    companion object {
        private const val TAG = "GestureExecutor"
        private const val SWIPE_MS = 120L
        private const val PAGE_MS = 150L
        private const val INTERVAL_MS = 200L  // 400 连续操作吞波——滑动注入 120ms，200 足够不重叠
    }

    private fun screenSize(): Point {
        val size = Point()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val wm = service.getSystemService(android.view.WindowManager::class.java)
            val b = wm.currentWindowMetrics.bounds
            size.set(b.width(), b.height())
        } else {
            @Suppress("DEPRECATION")
            (service.getSystemService(AccessibilityService.DISPLAY_SERVICE) as android.view.Display).getRealSize(size)
        }
        return size
    }

    private var lastTime = 0L

    fun performSwipeUp() {
        val screen = screenSize()
        val cx = screen.x / 2
        dispatch(cx, (screen.y * 0.65).toInt(), cx, (screen.y * 0.35).toInt(), SWIPE_MS, "up")
    }

    fun performSwipeDown() {
        val screen = screenSize()
        val cx = screen.x / 2
        dispatch(cx, (screen.y * 0.35).toInt(), cx, (screen.y * 0.65).toInt(), SWIPE_MS, "down")
    }

    fun performPageLeft() {
        val screen = screenSize()
        val cy = screen.y / 2
        dispatch((screen.x * 0.85).toInt(), cy, (screen.x * 0.15).toInt(), cy, PAGE_MS, "left")
    }

    fun performPageRight() {
        val screen = screenSize()
        val cy = screen.y / 2
        dispatch((screen.x * 0.15).toInt(), cy, (screen.x * 0.85).toInt(), cy, PAGE_MS, "right")
    }

    fun performTap() {
        val screen = screenSize()
        val cx = screen.x / 2
        val cy = screen.y / 2
        dispatch(cx, cy, cx, cy + 1, 80L, "tap")
    }

    fun performTapAt(nx: Float, ny: Float) {
        val screen = screenSize()
        val x = (nx * screen.x).toInt().coerceIn(0, screen.x - 1)
        val y = (ny * screen.y).toInt().coerceIn(0, screen.y - 1)
        dispatch(x, y, x, y + 1, 80L, "tapAt")
    }

    fun performBack() {
        val now = System.currentTimeMillis()
        if (now - lastTime < INTERVAL_MS) {
            Log.d(TAG, "Skip back (${now - lastTime}ms)")
            return
        }
        lastTime = now
        val ok = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        Log.d(TAG, "→ back ok=$ok")
    }

    private fun dispatch(x1: Int, y1: Int, x2: Int, y2: Int, dur: Long, label: String) {
        val now = System.currentTimeMillis()
        if (now - lastTime < INTERVAL_MS) {
            Log.d(TAG, "Skip $label (${now - lastTime}ms)")
            return
        }
        lastTime = now

        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, dur))
            .build()

        service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { Log.d(TAG, "$label OK") }
            override fun onCancelled(g: GestureDescription?) { Log.w(TAG, "$label CANCELLED") }
        }, null)

        Log.d(TAG, "→ $label ($x1,$y1)->($x2,$y2) ${dur}ms screen=${screenSize().x}x${screenSize().y}")
    }
}
