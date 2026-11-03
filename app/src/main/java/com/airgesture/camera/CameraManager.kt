/*
 * AirGesture — 隔空手势
 * Copyright (c) 2026 7wiligh7
 * SPDX-License-Identifier: MIT
 */

package com.airgesture.camera

import android.content.Context
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.airgesture.detector.HandGestureDetector
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@OptIn(ExperimentalCamera2Interop::class)
class CameraManager(
    private val context: Context,
    private val handDetector: HandGestureDetector
) {
    companion object {
        private const val TAG = "CameraManager"
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var camera: Camera? = null
    private var analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val lifecycleOwner = FakeLifecycleOwner()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var lowPower = false

    @Volatile
    private var started = false

    @Volatile
    private var paused = false

    @Volatile
    private var fpsLevel = 0

    private var frameArrivalCount = 0
    private var arrivalSumMs = 0L
    private var lastFrameAt = 0L

    fun start() {
        started = true
        paused = false
        lifecycleOwner.onStart()
        lifecycleOwner.onResume()

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            if (!paused) bindCameraUseCases()
        }, ContextCompat.getMainExecutor(context))
    }

    /** 熄屏暂停：只解绑 use case，保留 provider/executor，亮屏恢复快 */
    fun pause() {
        if (!started || paused) return
        paused = true
        Log.d(TAG, "Camera paused (screen off)")
        mainHandler.post {
            cameraProvider?.unbindAll()
            camera = null
            imageAnalysis = null
        }
    }

    fun resume() {
        if (!started || !paused) return
        paused = false
        Log.d(TAG, "Camera resumed (screen on)")
        mainHandler.post {
            if (cameraProvider != null) bindCameraUseCases()
        }
    }

    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: return
        cameraProvider.unbindAll()

        val analysisBuilder = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(256, 192),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                        )
                    )
                    .build()
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        applyFpsRange(analysisBuilder)
        imageAnalysis = analysisBuilder.build()
        imageAnalysis?.setAnalyzer(analysisExecutor) { imageProxy -> processImage(imageProxy) }
        frameArrivalCount = 0
        arrivalSumMs = 0L
        lastFrameAt = 0L

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_FRONT).build()

        try {
            camera = cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, imageAnalysis)
            Log.d(TAG, "Camera started (analysis only)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind camera", e)
        }
    }

    private fun applyFpsRange(builder: ImageAnalysis.Builder) {
        if (!lowPower) return
        val range = when (fpsLevel) {
            0 -> Range.create(5, 5)
            1 -> Range.create(5, 15)
            else -> return
        }
        try {
            Camera2Interop.Extender<ImageAnalysis>(builder)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
            Log.d(TAG, "AE target fps range=$range")
        } catch (e: Exception) {
            Log.w(TAG, "AE fps range set failed, disabling", e)
            fpsLevel = 2
        }
    }

    fun setLowPower(low: Boolean) {
        if (low == lowPower) return
        lowPower = low
        Log.d(TAG, "setLowPower($low)")
        mainHandler.post {
            // 熄屏暂停期间推理回调可能晚到：只记录低功耗状态，不重新绑相机
            if (paused) {
                Log.d(TAG, "setLowPower skipped rebind (paused)")
                return@post
            }
            if (cameraProvider != null) {
                bindCameraUseCases()
            }
        }
    }

    private fun processImage(imageProxy: ImageProxy) {
        val now = System.currentTimeMillis()
        if (lastFrameAt > 0) arrivalSumMs += now - lastFrameAt
        lastFrameAt = now
        frameArrivalCount++
        if (frameArrivalCount % 100 == 0) {
            val avgGap = arrivalSumMs / 100
            Log.d(TAG, "arrival avgGap=${avgGap}ms lowPower=$lowPower")
            if (lowPower && avgGap < 100) {
                Log.w(TAG, "AE_NOT_APPLIED: still fast while lowPower")
            }
            arrivalSumMs = 0
        }
        val timestampMs = imageProxy.imageInfo.timestamp / 1_000_000
        try {
            handDetector.processImageProxy(imageProxy, timestampMs)
        } catch (e: Exception) {
            Log.e(TAG, "processImage failed", e)
        } finally {
            // 检测异常也必须归还 buffer，否则 ImageAnalysis 会停止出帧
            imageProxy.close()
        }
    }

    fun stop() {
        started = false
        paused = false
        cameraProvider?.unbindAll()
        camera = null
        imageAnalysis = null
        lifecycleOwner.onPause()
        lifecycleOwner.onStop()
        lifecycleOwner.onDestroy()
        try { analysisExecutor.shutdownNow() } catch (_: Exception) {}
        analysisExecutor = Executors.newSingleThreadExecutor()
        Log.d(TAG, "Camera stopped")
    }
}
