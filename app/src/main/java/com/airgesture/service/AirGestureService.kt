/*
 * AirGesture — 隔空手势
 * Copyright (c) 2026 7wiligh7
 * SPDX-License-Identifier: MIT
 */

package com.airgesture.service

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.airgesture.camera.CameraManager
import com.airgesture.detector.CursorCallback
import com.airgesture.detector.GestureCallback
import com.airgesture.detector.HandGestureDetector
import com.airgesture.detector.RecognitionCallback
import com.airgesture.detector.ResultCallback
import com.airgesture.executor.GestureExecutor
import com.airgesture.MainActivity
import com.airgesture.R
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult

class AirGestureService : AccessibilityService(), GestureCallback, CursorCallback, RecognitionCallback {

    companion object {
        private const val TAG = "AirGestureService"
        private const val NOTIFICATION_ID = 1
        private const val CONTROL_PERMISSION = "com.airgesture.permission.CONTROL_GESTURE"
        const val ACTION_TOGGLE = "com.airgesture.TOGGLE_GESTURE"
        const val ACTION_DISABLE_SELF = "com.airgesture.DISABLE_SELF"
        private val ERROR_DISARM_REASONS = setOf("too_close", "too_far", "wrong_dir", "too_fast")
        var isRunning = false
            private set
        var isGestureMode = false
            private set
        var instance: AirGestureService? = null
            private set
    }

    private var handDetector: HandGestureDetector? = null
    private var cameraManager: CameraManager? = null
    private lateinit var gestureExecutor: GestureExecutor
    private var resultCallback: ResultCallback? = null
    private var screenReceiver: android.content.BroadcastReceiver? = null
    private var cursorOverlay: CursorOverlay? = null
    private var waveHint: WaveHintOverlay? = null
    private var lastCursorX = 0.5f
    private var lastCursorY = 0.5f
    private var gameGuardTriggered = false
    private var lastGestureLabel = ""
    private var foregroundStarted = false

    /** debug 包保持导出（adb 自动化）；release 包收紧，仅本 App / 通知 PendingIntent 可发 */
    private val debuggable: Boolean by lazy {
        (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    private val commandReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_TOGGLE -> {
                    val enable = intent.getBooleanExtra("enable", true)
                    Log.d(TAG, "TOGGLE enable=$enable")
                    if (enable) enterGestureMode() else exitGestureMode()
                }
                ACTION_DISABLE_SELF -> {
                    Log.d(TAG, "DISABLE_SELF requested")
                    disableForGame()
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Service connected")
        gameGuardTriggered = false
        isRunning = true
        instance = this
        gestureExecutor = GestureExecutor(this)

        val filter = IntentFilter(ACTION_TOGGLE)
        filter.addAction(ACTION_DISABLE_SELF)
        // debug 保持 EXPORTED：shell `am broadcast`（adb 装机自动化）在 Android 13+ 送不进 NOT_EXPORTED；
        // release 收紧：API 33+ NOT_EXPORTED，API 26-32 用 signature 权限保护（第三方 App/adb 都发不进来）。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val flag = if (debuggable) RECEIVER_EXPORTED else RECEIVER_NOT_EXPORTED
            registerReceiver(commandReceiver, filter, flag)
        } else if (debuggable) {
            registerReceiver(commandReceiver, filter)
        } else {
            registerReceiver(commandReceiver, filter, CONTROL_PERMISSION, null)
        }

        if (Settings.canDrawOverlays(this)) {
            cursorOverlay = CursorOverlay(this)
            waveHint = WaveHintOverlay(this)
            Log.d(TAG, "Overlay enabled")
        }

        updateNotification(false)
    }

    /** 惰性创建 overlay：服务启动时可能 canDrawOverlays 还没就绪（权限刚授予），onArmed/onError 时兜底补建 */
    private fun ensureOverlays() {
        if (!Settings.canDrawOverlays(this)) return
        if (cursorOverlay == null) cursorOverlay = CursorOverlay(this)
        if (waveHint == null) {
            waveHint = WaveHintOverlay(this)
            Log.d(TAG, "WaveHint lazily created")
        }
    }

    fun setResultCallback(cb: ResultCallback?) {
        resultCallback = cb
    }

    fun currentDetector(): HandGestureDetector? = handDetector

    fun enterGestureMode() {
        if (isGestureMode) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Camera permission missing, gesture mode not started")
            Toast.makeText(this, "请先授予相机权限", Toast.LENGTH_LONG).show()
            return
        }
        isGestureMode = true
        // 先起前台服务再开相机：Android 10+ 后台使用相机必须处于 camera 类型的 FGS
        if (!updateNotification(true)) {
            isGestureMode = false
            updateNotification(false)
            return
        }
        ensureOverlays()

        handDetector = HandGestureDetector(this, this, object : ResultCallback {
            override fun onHandResult(result: HandLandmarkerResult, imageWidth: Int, imageHeight: Int) {
                resultCallback?.onHandResult(result, imageWidth, imageHeight)
            }
        }, this, this)
        cameraManager = CameraManager(this, handDetector!!)
        handDetector?.onPowerModeChanged = { active ->
            cameraManager?.setLowPower(!active)
        }
        registerScreenReceiver()
        cameraManager?.start()
        cameraManager?.setLowPower(true)
        if (!getSystemService(PowerManager::class.java).isInteractive) {
            // 熄屏状态下手势模式被开启：先不绑相机，等 SCREEN_ON 广播恢复
            cameraManager?.pause()
            Log.d(TAG, "Screen off at start, camera deferred")
        }

        sendBroadcast(Intent("com.airgesture.SERVICE_STATUS").apply {
            setPackage(packageName)
            putExtra("running", true)
        })
        Log.d(TAG, "Gesture mode ON")
        GestureTileService.requestUpdate(this)
    }

    fun exitGestureMode() {
        if (!isGestureMode) return
        isGestureMode = false
        unregisterScreenReceiver()
        cameraManager?.stop()
        handDetector?.stop()
        handDetector = null
        cameraManager = null
        cursorOverlay?.hide()
        waveHint?.hide()

        updateNotification(false)
        sendBroadcast(Intent("com.airgesture.SERVICE_STATUS").apply {
            setPackage(packageName)
            putExtra("running", false)
        })
        Log.d(TAG, "Gesture mode OFF")
        GestureTileService.requestUpdate(this)
    }

    /** 通知栏 / 快捷设置磁贴 / 游戏守护 / App 内按钮共用：退出手势、关闭无障碍、结束进程 */
    fun disableForGame(killProcess: Boolean = true) {
        Log.d(TAG, "disableForGame kill=$killProcess")
        exitGestureMode()
        try {
            disableSelf()
        } catch (e: Exception) {
            Log.e(TAG, "disableSelf failed", e)
        }
        if (killProcess) {
            Process.killProcess(Process.myPid())
        }
    }

    /** 熄屏暂停检测：手机锁屏装在兜里时相机+推理仍在跑，是最大的耗电来源 */
    private fun registerScreenReceiver() {
        if (screenReceiver != null) return
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> cameraManager?.pause()
                    Intent.ACTION_SCREEN_ON -> if (isGestureMode) cameraManager?.resume()
                }
            }
        }
        registerReceiver(r, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        })
        screenReceiver = r
        Log.d(TAG, "Screen receiver registered")
    }

    private fun unregisterScreenReceiver() {
        screenReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        screenReceiver = null
    }

    private fun updateNotification(gestureActive: Boolean): Boolean {
        val channelId = "gesture_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // IMPORTANCE_DEFAULT：部分 OEM 在 LOW 下不显示 action 按钮
            val channel = NotificationChannel(channelId, "手势检测", NotificationManager.IMPORTANCE_DEFAULT)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }

        val toggleIntent = Intent(ACTION_TOGGLE).apply {
            setPackage(packageName)
            // 用 data URI 区分不同 PendingIntent，避免 extras 不更新
            data = Uri.parse("com.airgesture://toggle/$gestureActive")
            putExtra("enable", !gestureActive)
        }
        val togglePending = PendingIntent.getBroadcast(this, 0, toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val disableIntent = Intent(ACTION_DISABLE_SELF).apply {
            setPackage(packageName)
            data = Uri.parse("com.airgesture://disable-self")
        }
        val disablePending = PendingIntent.getBroadcast(this, 2, disableIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val openIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(this, 1, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val title = if (gestureActive) "隔空手势 · 已开启" else "隔空手势 · 已关闭"
        val text = if (gestureActive) "上下挥手翻页；右侧可关闭或停用无障碍" else "点击开启；右侧可停用无障碍"

        val builder = Notification.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(openPending)
            .addAction(0, "停用无障碍", disablePending)
            .addAction(0, if (gestureActive) "关闭" else "开启", togglePending)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }

        val notification = builder.build()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (gestureActive) {
            // ServiceCompat 在 API < 29 退化为两参 startForeground，避免 Android 8/9 崩溃
            return try {
                ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                )
                foregroundStarted = true
                true
            } catch (e: Exception) {
                Log.e(TAG, "startForeground failed", e)
                Toast.makeText(this, "相机前台服务启动失败：${e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
                false
            }
        }

        if (foregroundStarted) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        // 关闭态保留普通通知，通知栏仍可一键开启
        nm.notify(NOTIFICATION_ID, notification)
        return true
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "Task removed -> exit gesture + disableSelf")
        disableForGame()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        instance = null
        try { unregisterReceiver(commandReceiver) } catch (_: Exception) {}
        cursorOverlay?.destroy()
        cursorOverlay = null
        waveHint?.destroy()
        waveHint = null
        exitGestureMode()
        // 服务销毁时不再需要通知/前台状态
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
    }

    override fun onGestureDetected(direction: String) {
        Log.d(TAG, "Gesture: $direction")
        lastGestureLabel = when (direction) {
            "left" -> "左翻"
            "right" -> "右翻"
            "fist" -> "点击"
            "click" -> "点击"
            "pinch" -> "返回"
            "next" -> "下一段"
            "prev" -> "上一段"
            else -> direction
        }
        sendBroadcast(Intent("com.airgesture.GESTURE").apply {
            setPackage(packageName)
            putExtra("direction", direction)
            putExtra("label", lastGestureLabel)
        })
        when (direction) {
            "left" -> gestureExecutor.performPageLeft()
            "right" -> gestureExecutor.performPageRight()
            "fist" -> gestureExecutor.performTap()
            "click" -> gestureExecutor.performTapAt(lastCursorX, lastCursorY)
            "pinch" -> gestureExecutor.performBack()
            // 隔空挥动 v1：next = 向上挥 → 内容上移（下一段）；prev = 向下挥 → 内容下移（上一段）
            "next" -> gestureExecutor.performSwipeUp()
            "prev" -> gestureExecutor.performSwipeDown()
        }
    }

    // -------- 隔空挥动识别提示 --------

    override fun onHandSeen() {
        ensureOverlays()
        waveHint?.showResident()
    }

    override fun onHandLost() {
        waveHint?.hide()
    }

    override fun onDwellProgress(ratio: Float, dir: Int) {}

    override fun onArmed(dir: Int) {
        ensureOverlays()
        Log.d(TAG, ">>> wave armed dir=${if (dir < 0) "UP" else "DOWN"}")
        waveHint?.show(dir)
    }

    override fun onDisarm(reason: String) {
        // 错误类 disarm（too_close/too_far/wrong_dir/too_fast）已由 onError 单独展示纠错胶囊，这里不立即 hide
        if (reason !in ERROR_DISARM_REASONS) waveHint?.hide()
    }

    override fun onWaveFired(dir: Int) {
        Log.d(TAG, ">>> wave fired dir=${if (dir < 0) "UP" else "DOWN"}")
        waveHint?.hide()
    }

    override fun onError(code: Int) {
        ensureOverlays()
        Log.d(TAG, ">>> wave error code=$code")
        waveHint?.showError(code)
    }

    override fun onCursorShow(nx: Float, ny: Float) {
        lastCursorX = nx
        lastCursorY = ny
        cursorOverlay?.show(nx, ny)
    }

    override fun onCursorMove(nx: Float, ny: Float) {
        lastCursorX = nx
        lastCursorY = ny
        cursorOverlay?.move(nx, ny)
    }

    override fun onCursorHide() {
        cursorOverlay?.hide()
    }

    override fun onCursorClick(nx: Float, ny: Float) {
        lastCursorX = nx
        lastCursorY = ny
        cursorOverlay?.clickFx()
    }

    override fun onHandDetected() {}
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || gameGuardTriggered) return
        if (!GameGuard.isGame(this, pkg)) return

        gameGuardTriggered = true
        Log.d(TAG, "Game foreground: $pkg -> stop gesture + disable accessibility")
        Toast.makeText(
            this,
            "检测到游戏，AirGesture 已停用；退出游戏后可在设置中重新开启",
            Toast.LENGTH_LONG
        ).show()
        // 真的关掉无障碍服务，而不是隐藏；游戏里不会再有注入手势
        disableForGame()
    }
    override fun onInterrupt() {}
}
