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
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.airgesture.R

/**
 * 顶部手势提示（纯图标圆形徽章，无文字）：
 *   常驻态：暗底 + 通用手掌（降透明度）
 *   武装态：蓝底 + 掌纹手形，180° 旋转区分挥动方向：
 *     正立 = 向下挥（指尖朝上）；倒立 = 向上挥（指尖朝下）
 *   纠错态：琥珀底 + 上次武装朝向的手形
 */
class WaveHintOverlay(private val service: android.app.Service) {

    companion object {
        private const val TAG = "WaveHintOverlay"
    }

    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var view: WaveHintView? = null

    private var lastErrorAt = 0L
    private var hideRunnable: Runnable? = null
    private var permissionWarned = false

    /** 检测到手时常驻小指示 */
    fun showResident() {
        handler.post {
            cancelAutoHide()
            val v = view ?: addView()?.also { view = it } ?: return@post
            v.setResident()
            v.visible = true
            v.animateIn()
            scheduleAutoHide(8000L)
        }
    }

    /** dir = 武装方向：W_UP(-1)=向上挥（指尖朝下→图标倒立），W_DOWN(+1)=向下挥（指尖朝上→图标正立） */
    fun show(dir: Int) {
        handler.post {
            cancelAutoHide()
            val v = view ?: addView()?.also { view = it } ?: return@post
            v.setArmed(dir)
            v.visible = true
            v.animateIn()
            scheduleAutoHide(3000L)   // 对齐 WAVE_TIMEOUT_MS：武装期提示不提前消失
        }
    }

    /** 纠错提示：琥珀底 + 当前掌向图标 */
    fun showError(code: Int) {
        handler.post {
            val now = System.currentTimeMillis()
            if (now - lastErrorAt < 250L) return@post
            lastErrorAt = now
            cancelAutoHide()
            val v = view ?: addView()?.also { view = it } ?: return@post
            v.setError(code)
            v.visible = true
            v.animateIn()
            scheduleAutoHide(2000L)
        }
    }

    fun hide() {
        handler.post {
            cancelAutoHide()
            view?.let { it.animateOut() }
        }
    }

    fun destroy() {
        handler.post {
            cancelAutoHide()
            view?.let { v ->
                try { wm.removeView(v) } catch (_: Exception) {}
            }
            view = null
        }
    }

    private fun scheduleAutoHide(delayMs: Long) {
        val r = Runnable { hide() }
        hideRunnable = r
        handler.postDelayed(r, delayMs)
    }

    private fun cancelAutoHide() {
        hideRunnable?.let { handler.removeCallbacks(it) }
        hideRunnable = null
    }

    private fun addView(): WaveHintView? {
        if (!Settings.canDrawOverlays(service)) {
            warnPermissionOnce()
            return null
        }
        val v = WaveHintView(service)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 36
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

private class WaveHintView(context: Context) : View(context) {

    private val density = context.resources.displayMetrics.density
    var visible = false

    private var mode = MODE_RESIDENT

    private val handIcon: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_hand)
    private var primaryIcon: Drawable? = null
    private var flipIcon = false   // true=图标倒立（向上挥/指尖朝下）

    private val residentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(190, 27, 36, 48)
        style = Paint.Style.FILL
    }
    private val armedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 32, 96, 216)
        style = Paint.Style.FILL
    }
    private val errPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(215, 56, 38, 22)
        style = Paint.Style.FILL
    }
    private val errStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(240, 255, 180, 60)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }

    private val size = 32f * density
    private val iconSize = 17f * density
    private val badge = RectF()

    companion object {
        private const val MODE_RESIDENT = 0
        private const val MODE_ARMED = 1
        private const val MODE_ERROR = 2
    }

    fun setResident() {
        mode = MODE_RESIDENT
        primaryIcon = handIcon
        requestLayout()
    }

    /** 掌向语义：同一掌纹手形，靠 180° 旋转区分——正立=向下挥(指尖朝上)，倒立=向上挥(指尖朝下) */
    fun setArmed(d: Int) {
        mode = MODE_ARMED
        flipIcon = d < 0
        primaryIcon = ContextCompat.getDrawable(context, R.drawable.ic_palm_front)?.mutate()?.apply {
            setTint(Color.WHITE)
        }
        requestLayout()
    }

    fun setError(code: Int) {
        mode = MODE_ERROR
        // 纠错态：琥珀手形，沿用上次武装朝向（不猜方向）
        primaryIcon = ContextCompat.getDrawable(context, R.drawable.ic_palm_front)?.mutate()?.apply {
            setTint(Color.argb(255, 255, 200, 80))
        }
        requestLayout()
    }

    fun animateIn() {
        scaleX = 0.85f
        scaleY = 0.85f
        alpha = 0f
        animate().cancel()
        visible = true
        animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(150)
            .setInterpolator(AccelerateDecelerateInterpolator()).start()
    }

    fun animateOut() {
        animate().cancel()
        animate().scaleX(0.85f).scaleY(0.85f).alpha(0f).setDuration(120)
            .withEndAction { visible = false }.start()
    }

    override fun onDraw(canvas: Canvas) {
        if (!visible) return

        val bg = when (mode) {
            MODE_ARMED -> armedPaint
            MODE_ERROR -> errPaint
            else -> residentPaint
        }
        badge.set(0f, 0f, size, size)
        canvas.drawOval(badge, bg)
        if (mode == MODE_ERROR) canvas.drawOval(badge, errStroke)

        val icon = primaryIcon ?: handIcon ?: return
        icon.alpha = if (mode == MODE_RESIDENT) 130 else 255
        val l = (size - iconSize) / 2f
        icon.setBounds(
            l.toInt(), l.toInt(),
            (l + iconSize).toInt(), (l + iconSize).toInt()
        )
        val save = canvas.save()
        if (flipIcon && mode != MODE_RESIDENT) {
            canvas.rotate(180f, size / 2f, size / 2f)
        }
        icon.draw(canvas)
        canvas.restoreToCount(save)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(size.toInt(), size.toInt())
    }
}
