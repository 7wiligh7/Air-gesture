/*
 * AirGesture — 隔空手势
 * Copyright (c) 2026 7wiligh7
 * SPDX-License-Identifier: MIT
 */

package com.airgesture.service

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast

class CursorOverlay(private val service: android.app.Service) {

    companion object {
        private const val TAG = "CursorOverlay"
    }

    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var view: CursorView? = null
    private var permissionWarned = false

    fun show(nx: Float, ny: Float) {
        handler.post {
            val v = view ?: addView()?.also { view = it } ?: return@post
            v.setLocation(nx, ny)
            v.visible = true
            v.invalidate()
        }
    }

    fun move(nx: Float, ny: Float) {
        handler.post {
            view?.let {
                it.setLocation(nx, ny)
                it.visible = true
                it.invalidate()
            }
        }
    }

    fun hide() {
        handler.post {
            view?.let {
                it.cancelRipple()
                it.visible = false
                it.invalidate()
            }
        }
    }

    fun clickFx() {
        handler.post { view?.playRipple() }
    }

    fun destroy() {
        handler.post {
            view?.let { v ->
                v.cancelRipple()
                try {
                    wm.removeView(v)
                } catch (_: Exception) {
                }
            }
            view = null
        }
    }

    private fun addView(): CursorView? {
        if (!Settings.canDrawOverlays(service)) {
            warnPermissionOnce()
            return null
        }
        val v = CursorView(service)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        return try {
            wm.addView(v, params)
            permissionWarned = false
            v
        } catch (e: Exception) {
            Log.e(TAG, "addView failed, overlay disabled", e)
            warnPermissionOnce()
            null
        }
    }

    private fun warnPermissionOnce() {
        if (permissionWarned) return
        permissionWarned = true
        Toast.makeText(service, "悬浮窗权限已失效，请重新授权", Toast.LENGTH_LONG).show()
    }
}

private class CursorView(context: Context) : View(context) {

    private val density = context.resources.displayMetrics.density
    private var nx = 0.5f
    private var ny = 0.5f
    var visible = false

    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 88, 166, 255)
        style = Paint.Style.STROKE
        strokeWidth = 4.4f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 1.8f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 88, 166, 255)
        style = Paint.Style.FILL
    }
    private val centerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
    }
    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 88, 166, 255)
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
    }

    private val armLen = 14f * density
    private val centerR = 3f * density
    private val rippleMaxR = 40f * density

    private var rippleStart = -1L
    private val animHandler = Handler(Looper.getMainLooper())
    private val rippleRunner = object : Runnable {
        override fun run() {
            val age = System.currentTimeMillis() - rippleStart
            if (age > 280 || rippleStart < 0) {
                rippleStart = -1
                invalidate()
            } else {
                invalidate()
                animHandler.postDelayed(this, 16)
            }
        }
    }

    fun setLocation(x: Float, y: Float) {
        nx = x
        ny = y
    }

    fun playRipple() {
        visible = false
        rippleStart = System.currentTimeMillis()
        animHandler.removeCallbacks(rippleRunner)
        animHandler.post(rippleRunner)
    }

    fun cancelRipple() {
        rippleStart = -1
        animHandler.removeCallbacks(rippleRunner)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = nx * width
        val cy = ny * height
        val hasRipple = rippleStart >= 0

        if (!visible && !hasRipple) return

        if (visible) {
            canvas.drawLine(cx - armLen, cy, cx + armLen, cy, haloPaint)
            canvas.drawLine(cx, cy - armLen, cx, cy + armLen, haloPaint)
            canvas.drawLine(cx - armLen, cy, cx + armLen, cy, crossPaint)
            canvas.drawLine(cx, cy - armLen, cx, cy + armLen, crossPaint)
            canvas.drawCircle(cx, cy, centerR, centerPaint)
            canvas.drawCircle(cx, cy, centerR, centerRingPaint)
        }

        if (hasRipple) {
            val t = (System.currentTimeMillis() - rippleStart) / 280f
            if (t < 1f) {
                ripplePaint.alpha = (170 * (1f - t)).toInt()
                canvas.drawCircle(cx, cy, centerR + (rippleMaxR - centerR) * t, ripplePaint)
            } else {
                rippleStart = -1
            }
        }
    }
}
