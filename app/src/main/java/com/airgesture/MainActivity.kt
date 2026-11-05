/*
 * AirGesture — 隔空手势
 * Copyright (c) 2026 7wiligh7
 * SPDX-License-Identifier: MIT
 */

package com.airgesture

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.Window
import android.widget.FrameLayout
import android.widget.CompoundButton
import android.widget.Switch
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.airgesture.detector.HandOverlayView
import com.airgesture.detector.ResultCallback
import com.airgesture.service.AirGestureService
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_PERMISSIONS = 100
    }

    private lateinit var handOverlayView: HandOverlayView
    private lateinit var cameraArea: FrameLayout
    private lateinit var handCountText: TextView
    private lateinit var gestureText: TextView
    private lateinit var statusText: TextView
    private lateinit var statusDetail: TextView
    private lateinit var debugText: TextView
    private lateinit var enableSwitch: Switch
    private lateinit var signatureText: TextView
    private lateinit var disableAllButton: Button

    private val switchListener = CompoundButton.OnCheckedChangeListener { _, isChecked ->
        if (isChecked) enableGesture() else disableGesture()
    }

    private val handler = Handler(Looper.getMainLooper())

    private val serviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val running = intent?.getBooleanExtra("running", false) ?: false
            updateUI(running)
        }
    }

    private val gestureReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val label = intent?.getStringExtra("label") ?: return
            gestureText.text = label
            handler.removeCallbacks(clearGestureLabel)
            handler.postDelayed(clearGestureLabel, 800)
        }
    }

    private val clearGestureLabel = Runnable { gestureText.text = "" }

    private val resultCallback = object : ResultCallback {
        override fun onHandResult(result: HandLandmarkerResult, imageWidth: Int, imageHeight: Int) {
            if (isFinishing || isDestroyed) return
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val hasHand = result.landmarks().isNotEmpty()
                if (hasHand) {
                    handOverlayView.setResult(result, imageWidth, imageHeight)
                    handCountText.text = "手 ${result.landmarks()[0].size}"
                    handCountText.setTextColor(0xFF4ECB71.toInt())
                } else {
                    handOverlayView.clearResult()
                    handCountText.text = "未检测到手"
                    handCountText.setTextColor(0xFFFFFFFF.toInt())
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.activity_main)

        handOverlayView = findViewById(R.id.handOverlayView)
        cameraArea = findViewById(R.id.cameraArea)
        handCountText = findViewById(R.id.handCountText)
        gestureText = findViewById(R.id.gestureText)
        debugText = findViewById(R.id.debugText)
        statusText = findViewById(R.id.statusText)
        statusDetail = findViewById(R.id.statusDetail)
        enableSwitch = findViewById(R.id.enableSwitch)
        signatureText = findViewById(R.id.signatureText)
        signatureText.text = "© 2026 ${BuildConfig.AUTHOR} · MIT License\n" +
                "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · " +
                "build ${BuildConfig.BUILD_TIME} · ${BuildConfig.GIT_COMMIT}"

        disableAllButton = findViewById(R.id.disableAllButton)
        disableAllButton.setOnClickListener { confirmDisableAll() }

        enableSwitch.setOnCheckedChangeListener(switchListener)

        val root = findViewById<View>(R.id.rootLayout)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            v.setPadding(v.paddingLeft, sb + dp(8), v.paddingRight, nav + v.paddingBottom)
            insets
        }

        requestPermissions()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter("com.airgesture.SERVICE_STATUS")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(serviceReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(serviceReceiver, filter)
        }

        val gFilter = IntentFilter("com.airgesture.GESTURE")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(gestureReceiver, gFilter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(gestureReceiver, gFilter)
        }

        val service = AirGestureService.instance
        if (service != null && AirGestureService.isGestureMode) {
            service.setResultCallback(resultCallback)
            updateUI(true)
        } else {
            updateUI(false)
        }

        handler.post(debugTicker)
    }

    private val debugTicker = object : Runnable {
        override fun run() {
            val det = AirGestureService.instance?.currentDetector()
            if (det != null && AirGestureService.isGestureMode && cameraArea.visibility == View.VISIBLE) {
                val trans = "dx=${String.format("%+.2f", det.lastDx)} dy=${String.format("%+.2f", det.lastDy)}"
                val wave = "wave[${det.lastWaveState}] " +
                        "face=${det.lastWaveFacing} axis=${det.lastWaveAxis} " +
                        "dir=${if (det.lastWaveDir < 0) "UP" else if (det.lastWaveDir > 0) "DN" else "--"} " +
                        "proj=${String.format("%.2f", det.lastWaveProj)} " +
                        "luma=${String.format("%.2f", det.lastWaveLuma)} " +
                        "span=${det.lastWaveSpan.toInt()}"
                debugText.text = "$trans  ${det.lastDebugState}\n$wave"
            }
            handler.postDelayed(this, 200)
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(debugTicker)
        handler.removeCallbacks(clearGestureLabel)
        try { unregisterReceiver(serviceReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(gestureReceiver) } catch (_: Exception) {}
        AirGestureService.instance?.setResultCallback(null)
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val svc = "${packageName}/com.airgesture.service.AirGestureService"
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.contains(svc)
    }

    private fun enableGesture() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "请先授予相机权限", Toast.LENGTH_LONG).show()
            requestPermissions()
            enableSwitch.isChecked = false
            return
        }
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            enableSwitch.isChecked = false
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请授予悬浮窗权限", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            enableSwitch.isChecked = false
            return
        }

        val intent = Intent(AirGestureService.ACTION_TOGGLE).apply {
            setPackage(packageName)
            putExtra("enable", true)
        }
        sendBroadcast(intent)
    }

    private fun disableGesture() {
        val intent = Intent(AirGestureService.ACTION_TOGGLE).apply {
            setPackage(packageName)
            putExtra("enable", false)
        }
        sendBroadcast(intent)
        handOverlayView.clearResult()
        debugText.text = ""
        gestureText.text = ""
    }

    private fun confirmDisableAll() {
        AlertDialog.Builder(this)
            .setTitle("彻底关闭 AirGesture？")
            .setMessage("会退出手势模式并关闭无障碍服务；之后需要到系统设置重新开启。")
            .setPositiveButton("彻底关闭") { _, _ -> performDisableAll() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun performDisableAll() {
        val svc = AirGestureService.instance
        if (svc != null) {
            svc.disableForGame()
            updateUI(false)
            Toast.makeText(this, "已停用手势并关闭无障碍", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "无障碍未开启，正在打开系统设置", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun updateUI(running: Boolean) {
        // 程序化改开关时先摘掉监听器，避免把状态同步当成用户操作再发一次 toggle
        enableSwitch.setOnCheckedChangeListener(null)
        enableSwitch.isChecked = running
        enableSwitch.setOnCheckedChangeListener(switchListener)
        if (running) {
            cameraArea.visibility = View.VISIBLE
            statusText.text = "状态：手势检测已启用"
            statusDetail.text = "黑底只显示骨骼点，挥动手掌操作"
            statusText.setTextColor(0xFF00D4FF.toInt())
            AirGestureService.instance?.setResultCallback(resultCallback)
        } else {
            cameraArea.visibility = View.GONE
            statusText.text = "状态：就绪"
            statusDetail.text = "开启手势模式后检测手部"
            statusText.setTextColor(0xFF4ECB71.toInt())
            handOverlayView.clearResult()
            debugText.text = ""
            gestureText.text = ""
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.CAMERA)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), REQUEST_PERMISSIONS)
        }
    }
}
