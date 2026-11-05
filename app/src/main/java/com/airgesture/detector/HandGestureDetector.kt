/*
 * AirGesture — 隔空手势
 * Copyright (c) 2026 7wiligh7
 * SPDX-License-Identifier: MIT
 */

package com.airgesture.detector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import com.google.mediapipe.tasks.components.containers.Landmark
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import java.util.concurrent.atomic.AtomicBoolean
import androidx.camera.core.ImageProxy
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sqrt

interface GestureCallback {
    fun onGestureDetected(direction: String)
    fun onHandDetected()
}

interface CursorCallback {
    fun onCursorShow(nx: Float, ny: Float)
    fun onCursorMove(nx: Float, ny: Float)
    fun onCursorHide()
    fun onCursorClick(nx: Float, ny: Float)
}

interface RecognitionCallback {
    fun onHandSeen()
    fun onHandLost() {}
    fun onDwellProgress(ratio: Float, dir: Int)
    fun onArmed(dir: Int)
    fun onDisarm(reason: String)
    fun onWaveFired(dir: Int)

    /** 纠错码（对齐官方 error 1/5/6/7）：1=方向反 5=太近 6=太远 7=太快 */
    fun onError(code: Int) {}
}

interface ResultCallback {
    fun onHandResult(result: HandLandmarkerResult, imageWidth: Int, imageHeight: Int)
}

class HandGestureDetector(
    private val context: Context,
    private val gestureCallback: GestureCallback,
    private val resultCallback: ResultCallback? = null,
    private val cursorCallback: CursorCallback? = null,
    private val recognitionCallback: RecognitionCallback? = null
) {
    companion object {
        private const val TAG = "HandGestureDetector"
        private const val MODEL_FILE = "hand_landmarker.task"
        private const val COOLDOWN_MS = 850L
        private const val H_THRESHOLD = 0.18f
        private const val H_THRESHOLD_LEFT = 0.15f
        private const val MIN_TIME_MS = 120L
        private const val MAX_TIME_MS = 600L
        private const val MIN_SAMPLES = 5
        private const val SAMPLE_WINDOW = 10
        private const val RESET_DIST = 0.12f
        private const val RESET_TIMEOUT_MS = 1500L
        private const val AXIS_DOMINANCE = 1.4f
        private const val CONSISTENCY_MIN = 0.65f
        private const val PEAK_END_RATIO = 0.7f
        private const val PEAK_VEL_MIN = 1.0f
        private const val VEL_SHAPE_RATIO = 2.0f
        private const val POSE_MIN_FRAMES = 6
        private const val POSE_SAFE_MIN = 0.05f
        private const val POSE_SAFE_MAX = 0.95f
        private const val IDLE_PROCESS_EVERY = 6
        private const val SLOW_ARRIVAL_GAP_MS = 150f
        private const val HAND_LOST_MS = 1500L
        private const val FIST_CURL_RATIO = 0.65f
        private const val FIST_MIN_CURLED = 5        // 5 指全卷曲（含拇指贴向食/中指）才算真握拳
        private const val THUMB_TO_FINGER_RATIO = 0.40f  // 拇指指尖(4)到食指/中指PIP(6/10)距离 / 手长 上限
        private const val OPEN_CONFIRM_FRAMES = 3    // 光标模式：连续 N 帧非握拳(张开)才点击，防瞬时抖动
        private const val FIST_MIN_FRAMES = 8
        private const val FIST_HOLD_MS = 300L
        private const val CURSOR_EMA = 0.4f
        // 掌心归一化坐标 → 屏幕：只把画面中间的 30%..70% 映射到全屏，
        // 手不必伸到画面边缘就能覆盖四角，避免指尖/手腕出界导致丢手或姿态失效
        private const val CURSOR_MIN = 0.30f
        private const val CURSOR_MAX = 0.70f
        private const val OK_TOUCH_RATIO = 0.20f  // OK 手势：拇指-食指指尖接触阈值（距离/腕-中指MCP；0.35 过松，接近即误判返回）
        private const val POSE_LOG_EVERY = 5      // OK/握拳候选调试日志节流
        private const val PERF_LOG_EVERY = 30

        private const val W_UP = -1
        private const val W_DOWN = 1

        // ---- 隔空挥动 v1：对齐华为/小米官方规格 ----
        // 停顿(20-40cm, 光线充足) → 手稳定 → 屏幕顶部出现👋指示
        //   掌心朝屏 + 指尖朝上 → 停顿 → 向下挥 → 内容下移（上一段 prev）
        //   手背朝屏 + 指尖朝下 → 停顿 → 向上挥 → 内容上移（下一段 next）
        // 通用规则：挥动方向 = 指尖方向的反方向
        // 左右滑动仍走旧位移通道（本次只替换上下）
        private const val NORMAL_SIGN = +1f     // 掌法向符号（左/右手修正链，dStep 用）
        private const val FACE_X_SIGN = +1f     // 叉积符号→掌/背（图标用；若图标反了翻此号）
        private const val FACE_X_MIN = 0.30f    // 叉积归一化门槛（|sinθ|），低于此判 F_NONE
        private const val POSE_KILL_FRAMES = 4    // fist/pinch 需连续 N 帧才解除（防单帧误判杀武装）
        private const val DWELL_MISS_MAX = 3      // dwell 期姿态失效宽限帧数（防闪断重来）
        private const val LUMA_MIN_NORM = 0.12f // Y 为 limited range 16..235，已归一化
        private const val ANG_STEP_MAX = 12.0f  // 停留期法向帧间最大角变化（度）— 实测 dStep 达 4.3-4.7，4.0 太紧
        private const val DWELL_FRAMES = 5      // @16fps ≈ 300ms「稍作停留」— 8 帧武装太慢
        private const val DWELL_MOVE_MAX = 0.12f // 停留期掌心漂移上限（画面宽归一化）— 0.06 太严，手微抖就重置
        private const val AXIS_MIN = 0.08f      // 指尖轴最短长度（画面宽归一化）；0.10 对远距+指尖朝下偏严
        private const val AXIS_DOM = 1.15f      // 指尖轴主方向占比（1.3 对「手背朝屏+指尖朝下」偏严，降到 1.15）
        private const val WAVE_R = 0.06f        // 挥手触发位移（指尖相对腕向量变化，画面宽归一化）— 相对信号幅度小于绝对位移，0.08 偏难
        private const val WAVE_COMMIT_R = 0.03f // 挥动committed门槛：峰值过此值后锁定武装方向（挥动中轴自然翻转不再 rearm 吞波）
        private const val WAVE_REARM_R = 0.04f  // 触发后回落到此值内才允许下一波
        private const val WAVE_COOLDOWN_MS = 120L  // 250 连续挥手吞波——120 配合 executor 200ms 节流
        private const val WAVE_MIN_ARM_MS = 200L   // rearm 后最小武装时间：回程(~150-250ms)不被吞成误触发
        private const val WAVE_DIR_STABLE = 2       // rearm 后需连续 N 帧 wDir 与武装方向一致才允许触发
        private const val WAVE_TIMEOUT_MS = 3000L
        private const val FIRED_WINDOW_MS = 1600L    // FIRED 总窗口：超时未复位才回 IDLE（> WAVE_SWITCH_HOLD_MS）
        private const val WAVE_SWITCH_HOLD_MS = 900L // FIRED 静止保持阈值：≥此时长视为换向意图；回程持续移动永不换向
        private const val FIRED_HOLD_EPS = 0.02f     // 静止判定：帧间指尖位移 < 2% 画面宽（≈5px）
        private const val PALM_LOG_EVERY = 10

        // ---- 纠错码（对齐官方 error 语义，HAL error 1–8） ----
        private const val E_DIRECTION = 1   // 挥动方向与允许方向相反
        private const val E_TOO_CLOSE = 5   // 手离屏幕太近
        private const val E_TOO_FAR = 6     // 手离屏幕太远
        private const val E_TOO_FAST = 7    // 挥动速度过快
        private const val SPAN_CLOSE_MAX = 0.55f // 掌-腕归一化跨度上限（近似 20cm）— 实测正常 span 0.23-0.36，0.30 误报太近
        private const val SPAN_FAR_MIN = 0.05f   // 跨度下限—0.10 被挥动透视缩短误穿（2D 跨度≠真实距离）
        private const val HAND_MIN_SPAN = 0.10f  // 全局远距门控：spanNorm 低于此值视为手太远（≈70-90cm 外，实测 20cm→0.36 / 40cm→0.23）
        private const val FAR_GRACE_FRAMES = 4   // active 且非光标态的瞬态宽容帧数（挥动中 2D 透视缩短会瞬时塌缩，硬卡误杀挥动）
        private const val FAR_HINT_DELAY_MS = 800L // 持续远距多久弹一次「请靠近」提示（每次远距只弹一次）
        private const val SPAN_CONFIRM_FRAMES = 3 // 距离纠错需连续 N 帧（防关键点单帧抖动）
        private const val WAVE_OPPOSITE_R = 0.30f // 允许方向反向位移阈值—0.14 起手回拉/回程惯性(~0.2)仍误报 wrong_dir，0.30 只拦真正的反向大挥
        private const val WAVE_WRONG_DIR_FLIP = 240f // 反向「朝向翻转」速率门槛（deg/s，5 指中位）：纯平移/惯性尾朝向角几乎不变不计，真正甩腕换向才报
        private const val WAVE_JUMP_VEL_MAX = 15.0f // 单帧投影速度上限（宽/s）：慢晃中跟踪器重解释会单帧跳 0.7+ 宽(≈19)，真实挥动实测 ≤6，取 15 兜底
        private const val WAVE_SPAN_JUMP_R = 0.80f  // 手尺寸单帧相对变化上限：手翻转经侧向的真实投影收缩实测最多 ~57%/帧，跟踪器重解释跳变 ≥100%，卡 80%
        private const val WAVE_MIN_TIPVN = 9.0f      // 下一段（W_UP）最低归一化速率：保持指尖朝下时的灵敏度
        private const val WAVE_MIN_TIPVN_PREV = 9.8f // 上一段（W_DOWN）单独收紧，专压慢速向下挥
        private const val WAVE_WRONG_DIR_GRACE = 3 // 反向位移需连续 3 帧超阈值才报 wrong_dir（防起手瞬间抖动）
        private const val WAVE_VEL_MAX = 6.0f     // 挥动速度上限（画面宽/秒）— 指尖信号幅度×3-5，2.5 会误报正常挥动 too_fast
        private const val ERROR_MIN_INTERVAL_MS = 500L

        // 指尖轴象限（图像坐标，y 向下为正）
        private const val A_NONE = 0
        private const val A_UP = -1
        private const val A_DOWN = 1
        private const val A_LEFT = -2
        private const val A_RIGHT = 2

        // 5 指指尖 landmark 索引（拇指-食指-中指-无名-小指）
        private val TIP_IDX = intArrayOf(4, 8, 12, 16, 20)

        // 掌面朝向
        private const val F_NONE = 0
        private const val F_PALM = 1   // 掌心朝屏幕
        private const val F_BACK = -1  // 手背朝屏幕
    }

    private var handLandmarker: HandLandmarker? = null
    private val isProcessing = AtomicBoolean(false)

    @Volatile
    private var stopped = false
    private var lastGestureTime = 0L

    private var awaitingReset = false
    private var originX = 0f
    private var originY = 0f
    private var resetSince = 0L

    @Volatile
    private var activeMode = false
    private var lastHandSeenAt = 0L
    private var idleFrameCount = 0
    private var lastArrivalAt = 0L
    private var arrivalGapEma = 0f

    private var cursorMode = false
    private var cursorShownAt = 0L
    private var cursorX = 0f
    private var cursorY = 0f
    private var openFrames = 0

    var onPowerModeChanged: ((Boolean) -> Unit)? = null

    private var poseCandidate: String? = null
    private var poseCount = 0
    private var poseLatched = false
    private var poseLogCount = 0

    private var farFrames = 0
    private var farLogged = false
    private var farSinceAt = 0L
    private var farHinted = false
    private var multiLogged = false

    private var lastVelPeak = 0f
    private var lastVelMed = 0f

    private var outWidth = 0
    private var outHeight = 0

    // ---------------- 隔空挥动 v1 状态 ----------------
    private enum class WState { IDLE, DWELL, ARMED, FIRED }

    private var wState = WState.IDLE
    private var wDir = 0              // 当前姿态允许的挥手方向（W_UP/W_DOWN），0=姿态非法
    private var wArmedDir = 0         // 武装时锁存的方向：ARMED/FIRED 期间以锁存值为准，不因单帧姿态丢失立即 disarm
    private var wDwellFrames = 0
    private var wLastDir = 0
    private var wAnchorX = 0f
    private var wAnchorY = 0f
    private var wDwellStartX = 0f
    private var wDwellStartY = 0f
    private var wStateSince = 0L
    private var wLastFireAt = 0L
    private var wFacing = F_NONE
    private var wAxis = A_NONE
    private var lastLoggedAxis = 99
    private var wProj = 0f
    private var wDStep = 0f
    private var wSpanPx = 0f
    private var wSpanPrevPx = 0f               // 上一帧手尺寸（ARMED 跳变防护用）
    private var wLumaNorm = 0f
    private var wPrevNx = 0f
    private var wPrevNy = 0f
    private var wPrevNz = 0f
    private var wHasPrevN = false
    private var wLastProjForVel = 0f
    private var wLastProjAt = 0L
    private var wWrongDirFrames = 0
    private var wMaxProj = 0f
    private var wPoseFrames = 0
    private var wDwellMissFrames = 0
    private var wFiredRefX = 0f        // FIRED 静止检测：运动参考点（指尖相对腕向量 px）
    private var wFiredRefY = 0f
    private var wFiredMoveAt = 0L      // FIRED 内最近一次显著运动时刻；0=未初始化
    private var wFiredPeakProj = 0f    // FIRED 首帧投影（≈触发峰值），换向判定需仍在峰值附近
    private var wRearmAt = 0L
    private var wDirStable = 0
    private var wAxisFlipped = false   // 武装后指尖轴已翻转到挥动完成指向（触发必要条件）
    private var wFarFrames = 0
    private var wCloseFrames = 0

    // ---------------- 5 指速率检测（主动挥/被动回程闸门） ----------------
    private val wTipRefX = FloatArray(5)      // 采样参考点（归一化坐标）
    private val wTipRefY = FloatArray(5)
    private var wTipRefWristX = 0f
    private var wTipRefWristY = 0f
    private var wTipLastAt = 0L               // 0=无效（手丢失/复位后重新采样）
    private var wTipSpeedW = 0f               // 5 指中位线性速率（画面宽/s，日志参考）
    private var wTipSpeedNormW = 0f           // 同上按手尺寸归一化（手宽/s：tipV*outWidth/span，距离不敏感）
    private var wTipSampled = false           // 本帧是否产生新的 tip 速度样本（~100ms 一次）
    private var wFastRun = 0                  // 连续达到速率阈值的样本数：单帧抖动不算真挥动
    private var wSwitchDirFrames = 0         // FIRED 期反向轴连续帧数：允许更快的换向 rearm
    private var wTipVyW = 0f                  // 5 指中位纵向线性速度（画面宽/s，日志参考）
    private var wFlipV = 0f                   // 5 指中位「朝向翻转」速率（deg/s；符号 + = 朝下翻、- = 朝上翻）
    private var wFlipPeak = 0f                // 武装期内允许方向翻转峰值（deg/s，触发日志/慢速闸门校准用）
    private var wTipSpeedPeak = 0f            // 武装期内线性速率峰值（画面宽/s，同上）
    private var wTipSpeedNormPeak = 0f        // 武装期内归一化速率峰值（手宽/s，距离不敏感闸门校准用）
    private var wSlowRejectLogged = false     // 本轮武装是否已记过 slow_reject 日志（每轮一次）
    private var lastErrorAt = 0L
    private var lastDwellStep = 0
    private var palmLogCount = 0
    private var wasHandPresent = false
    private var lumaSum = 0L
    private var lumaCount = 0L
    private var meanLuma = 0f

    var lastWaveState: String = "off"
        private set
    var lastWaveFacing: String = "--"
        private set
    var lastWaveAxis: String = "--"
        private set
    var lastWaveDir = 0
        private set
    var lastWaveProj = 0f
        private set
    var lastWaveDwell = 0
        private set
    var lastWaveLuma = 0f
        private set
    var lastWaveSpan = 0f
        private set

    private val xHistory = ArrayList<Float>(SAMPLE_WINDOW)
    private val yHistory = ArrayList<Float>(SAMPLE_WINDOW)
    private val tHistory = ArrayList<Long>(SAMPLE_WINDOW)

    var lastResult: HandLandmarkerResult? = null
        private set

    var lastDx: Float = 0f
        private set
    var lastDy: Float = 0f
        private set
    var lastDebugState: String = ""
        private set

    @Volatile
    private var pendingBitmap: Bitmap? = null

    private var argbBuffer: IntArray? = null
    private var outBitmap: Bitmap? = null

    private var frameCount = 0
    private var convertMsTotal = 0L
    private var inferGapMsTotal = 0L
    private var lastSubmitAt = 0L
    private var submitAtMs = 0L
    private var inferMsTotal = 0L
    private var inferCount = 0
    private var resultCount = 0
    private var lastFpsWindowAt = 0L
    private var loggedSize = false

    private var imageWidth = 0
    private var imageHeight = 0

    init {
        handLandmarker = createLandmarker(true) ?: createLandmarker(false)
    }

    private fun createLandmarker(useGpu: Boolean): HandLandmarker? {
        return try {
            val baseBuilder = BaseOptions.builder().setModelAssetPath(MODEL_FILE)
            if (useGpu) {
                baseBuilder.setDelegate(Delegate.GPU)
            }
            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseBuilder.build())
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumHands(2)
                .setMinHandDetectionConfidence(0.5f)
                .setMinHandPresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setResultListener { result, _ ->
                    try {
                        val now = System.currentTimeMillis()
                        if (submitAtMs > 0) {
                            inferMsTotal += now - submitAtMs
                            inferCount++
                        }
                        resultCount++
                        if (lastFpsWindowAt == 0L) {
                            lastFpsWindowAt = now
                        } else if (resultCount % PERF_LOG_EVERY == 0) {
                            val fps = PERF_LOG_EVERY * 1000.0 / (now - lastFpsWindowAt)
                            val avgInfer = if (inferCount > 0) inferMsTotal / inferCount else 0
                            Log.d(TAG, "result fps=${String.format("%.1f", fps)} avgInfer=${avgInfer}ms")
                            lastFpsWindowAt = now
                        }
                        processResult(result)
                    } finally {
                        isProcessing.set(false)
                    }
                }
                .setErrorListener { e ->
                    isProcessing.set(false)
                    Log.e(TAG, "HandLandmarker error", e)
                }
                .build()
            val lm = HandLandmarker.createFromOptions(context, options)
            Log.d(TAG, "HandLandmarker OK delegate=${if (useGpu) "GPU" else "CPU"}")
            lm
        } catch (e: Exception) {
            Log.w(TAG, "HandLandmarker delegate=${if (useGpu) "GPU" else "CPU"} failed", e)
            null
        }
    }

    fun processImageProxy(imageProxy: ImageProxy, timestampMs: Long) {
        if (stopped) return
        val arrivalNow = System.currentTimeMillis()
        if (lastArrivalAt > 0) {
            val gap = (arrivalNow - lastArrivalAt).toFloat()
            arrivalGapEma = if (arrivalGapEma == 0f) gap else arrivalGapEma * 0.9f + gap * 0.1f
        }
        lastArrivalAt = arrivalNow
        if (!activeMode) {
            idleFrameCount++
            val every = if (arrivalGapEma > SLOW_ARRIVAL_GAP_MS) 1 else IDLE_PROCESS_EVERY
            if (every > 1 && idleFrameCount % every != 0) return
        }
        if (isProcessing.getAndSet(true)) return
        try {
            imageWidth = imageProxy.width
            imageHeight = imageProxy.height
            if (!loggedSize) {
                loggedSize = true
                Log.d(TAG, "frame ${imageWidth}x${imageHeight} rot=${imageProxy.imageInfo.rotationDegrees}")
            }
            val t0 = System.nanoTime()
            val bitmap = toBitmapRotateMirror(imageProxy)
            val t1 = System.nanoTime()
            val gap = if (lastSubmitAt > 0) (t1 / 1_000_000 - lastSubmitAt) else 0
            lastSubmitAt = t1 / 1_000_000
            frameCount++
            convertMsTotal += (t1 - t0) / 1_000_000
            inferGapMsTotal += gap
            if (frameCount % PERF_LOG_EVERY == 0) {
                Log.d(TAG, "perf avgConvert=${convertMsTotal / frameCount}ms avgGap=${inferGapMsTotal / frameCount}ms")
            }
            val mpImage = BitmapImageBuilder(bitmap).build()
            pendingBitmap = bitmap
            submitAtMs = System.currentTimeMillis()
            handLandmarker?.detectAsync(mpImage, timestampMs)
                ?: run {
                    bitmap.recycle()
                    pendingBitmap = null
                    isProcessing.set(false)
                }
        } catch (e: Exception) {
            pendingBitmap?.recycle()
            pendingBitmap = null
            isProcessing.set(false)
            Log.e(TAG, "Failed to process image", e)
        }
    }

    private fun ensureBuffers(width: Int, height: Int): Pair<IntArray, Bitmap> {
        val needed = width * height
        var arr = argbBuffer
        if (arr == null || arr.size != needed) {
            arr = IntArray(needed)
            argbBuffer = arr
        }
        var bmp = outBitmap
        if (bmp == null || bmp.width != width || bmp.height != height || bmp.isRecycled) {
            bmp?.recycle()
            bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            outBitmap = bmp
        }
        return arr to bmp
    }

    private fun toBitmapRotateMirror(imageProxy: ImageProxy): Bitmap {
        val planes = imageProxy.planes
        val yBuf = planes[0].buffer
        val uBuf = planes[1].buffer
        val vBuf = planes[2].buffer
        val yStride = planes[0].rowStride
        val uvStride = planes[1].rowStride
        val uvPix = planes[1].pixelStride
        val w = imageProxy.width
        val h = imageProxy.height
        val rotation = ((imageProxy.imageInfo.rotationDegrees % 360) + 360) % 360

        val swap = rotation == 90 || rotation == 270
        val outW = if (swap) h else w
        val outH = if (swap) w else h
        outWidth = outW
        outHeight = outH
        val (argb, bitmap) = ensureBuffers(outW, outH)

        var out = 0
        for (row in 0 until h) {
            val yRow = row * yStride
            val uvRow = (row / 2) * uvStride
            for (col in 0 until w) {
                val y = yBuf.get(yRow + col).toInt() and 0xFF
                lumaSum += y
                lumaCount++
                val uvCol = (col / 2) * uvPix
                val u = (uBuf.get(uvRow + uvCol).toInt() and 0xFF) - 128
                val v = (vBuf.get(uvRow + uvCol).toInt() and 0xFF) - 128
                val y1192 = 1192 * y
                var r = (y1192 + 1634 * v) shr 10
                var g = (y1192 - 833 * v - 400 * u) shr 10
                var b = (y1192 + 2066 * u) shr 10
                if (r < 0) r = 0 else if (r > 255) r = 255
                if (g < 0) g = 0 else if (g > 255) g = 255
                if (b < 0) b = 0 else if (b > 255) b = 255
                val px = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                when (rotation) {
                    90 -> argb[col * outW + (h - 1 - row)] = px
                    180 -> argb[(h - 1 - row) * outW + (w - 1 - col)] = px
                    270 -> argb[(w - 1 - col) * outW + row] = px
                    else -> argb[out] = px
                }
                out++
            }
        }

        // horizontal mirror in place (selfie view)
        for (r in 0 until outH) {
            var left = r * outW
            var right = left + outW - 1
            while (left < right) {
                val tmp = argb[left]
                argb[left] = argb[right]
                argb[right] = tmp
                left++
                right--
            }
        }

        meanLuma = if (lumaCount > 0) lumaSum.toFloat() / lumaCount else 0f
        lumaSum = 0L
        lumaCount = 0L

        bitmap.setPixels(argb, 0, outW, 0, 0, outW, outH)
        return bitmap
    }

    /**
     * 多手选择：纯中心优先——掌心离画面中心最近者为主手；跨度不参与选择，只交给远距门控处理。
     * 返回主手在 result 各数组（landmarks / worldLandmarks / handedness）中的索引，-1 = 无有效手。
     * 注意：worldLandmarks / handedness 与 landmarks 同序，下游必须用同一索引取，不能用 [0]。
     */
    private fun pickPrimaryHand(result: HandLandmarkerResult): Int {
        val hands = result.landmarks()
        val handed = result.handedness()
        var bestIdx = -1
        var bestDist = Float.MAX_VALUE
        for (i in hands.indices) {
            val h = hands[i]
            if (h.size < 21) continue
            val px = (h[0].x() + h[5].x() + h[17].x()) / 3f
            val py = (h[0].y() + h[5].y() + h[17].y()) / 3f
            val centerDist = hypot(px - 0.5f, py - 0.5f)
            if (centerDist < bestDist) {
                bestDist = centerDist
                bestIdx = i
            }
        }
        if (hands.size > 1 && bestIdx >= 0) {
            if (!multiLogged) {
                multiLogged = true
                val sb = StringBuilder()
                for (i in hands.indices) {
                    val h = hands[i]
                    if (h.size < 21) continue
                    val px = (h[0].x() + h[5].x() + h[17].x()) / 3f
                    val py = (h[0].y() + h[5].y() + h[17].y()) / 3f
                    val d = hypot(px - 0.5f, py - 0.5f)
                    val cat = handed.getOrNull(i)?.firstOrNull()
                    val lbl = cat?.categoryName() ?: "?"
                    val sc = cat?.score() ?: 0f
                    if (sb.isNotEmpty()) sb.append(' ')
                    sb.append("[$i]$lbl/${fmt3(sc)} d=${fmt3(d)}")
                }
                Log.d(TAG, ">>> multi_hand n=${hands.size} $sb pick=$bestIdx d=${fmt3(bestDist)}")
            }
        } else if (hands.size <= 1) {
            multiLogged = false
        }
        return bestIdx
    }

    private fun processResult(result: HandLandmarkerResult) {
        pendingBitmap?.let {
            if (it !== outBitmap) it.recycle()
        }
        pendingBitmap = null

        lastResult = result
        resultCallback?.onHandResult(result, imageWidth, imageHeight)

        val now = System.currentTimeMillis()

        // 全局远距门控：MediaPipe 是纯 2D 外观检测（无深度），远手的掌心图案照样被识别——
        // 用掌心跨度（腕0→中指MCP9 像素距离/画面宽，近大远小）近似距离，太小 = 太远 → 视为无手。
        // active 且非光标态留 FAR_GRACE_FRAMES 帧宽容（挥动中 2D 透视缩短瞬态，硬卡会误杀挥动）。
        val handIdx = pickPrimaryHand(result)
        val hand0 = result.landmarks().getOrNull(handIdx)
        var handOk = hand0 != null
        if (hand0 != null) {
            val sxp = (hand0[0].x() - hand0[9].x()) * outWidth
            val syp = (hand0[0].y() - hand0[9].y()) * outHeight
            val spanPx = hypot(sxp, syp)
            val spanNorm = spanPx / outWidth
            if (spanNorm < HAND_MIN_SPAN) {
                if (farSinceAt == 0L) farSinceAt = now
                farFrames++
                if (farFrames > FAR_GRACE_FRAMES || !activeMode || cursorMode) handOk = false
                if (!handOk && !farLogged) {
                    farLogged = true
                    Log.d(TAG, ">>> far_ignore span=${spanPx.toInt()}px norm=${fmt3(spanNorm)}")
                }
                if (!farHinted && now - farSinceAt >= FAR_HINT_DELAY_MS) {
                    farHinted = reportError(E_TOO_FAR)
                }
            } else {
                farFrames = 0
                farLogged = false
                farSinceAt = 0L
                farHinted = false
            }
        } else {
            farFrames = 0
            farLogged = false
            farSinceAt = 0L
            farHinted = false
        }
        val hasHand = handOk

        if (!activeMode) {
            if (hasHand && hand0 != null) {
                if (hand0.size >= 21) {
                    val pX = (hand0[0].x() + hand0[5].x() + hand0[17].x()) / 3f
                    val pY = (hand0[0].y() + hand0[5].y() + hand0[17].y()) / 3f
                    if (pX in POSE_SAFE_MIN..POSE_SAFE_MAX && pY in POSE_SAFE_MIN..POSE_SAFE_MAX) {
                        activeMode = true
                        clearHistories()
                        awaitingReset = false
                        resetPoseState()
                        lastHandSeenAt = now
                        lastDebugState = "wake"
                        Log.d(TAG, ">>> IDLE -> ACTIVE")
                        onPowerModeChanged?.invoke(true)
                        return
                    }
                }
            }
            lastDebugState = "idle"
            return
        }

        if (!hasHand) {
            clearHistories()
            awaitingReset = false
            cancelCursor()
            resetPoseState()
            if (wasHandPresent) {
                wasHandPresent = false
                recognitionCallback?.onHandLost()
            }
            if (wState != WState.IDLE) disarm("no_hand")
            if (now - lastHandSeenAt > HAND_LOST_MS) {
                activeMode = false
                idleFrameCount = 0
                lastDebugState = "idle"
                Log.d(TAG, ">>> ACTIVE -> IDLE")
                onPowerModeChanged?.invoke(false)
            } else {
                lastDebugState = if (farLogged) "太远" else "无手"
            }
            return
        }

        val hand = hand0 ?: return

        gestureCallback.onHandDetected()
        if (!wasHandPresent) {
            wasHandPresent = true
            recognitionCallback?.onHandSeen()
        }

        val palmX = (hand[0].x() + hand[5].x() + hand[17].x()) / 3f
        val palmY = (hand[0].y() + hand[5].y() + hand[17].y()) / 3f

        if (palmX < POSE_SAFE_MIN || palmX > POSE_SAFE_MAX || palmY < POSE_SAFE_MIN || palmY > POSE_SAFE_MAX) {
            clearHistories()
            cancelCursor()
            resetPoseState()
            // 手仍在（只是出安全区）：续期 lastHandSeenAt，防边缘停留被误判"丢失"降回 IDLE；
            // wave 非 IDLE 则 disarm——掌出区后投影锚点不可信
            lastHandSeenAt = now
            if (wState != WState.IDLE) disarm("out_of_zone")
            lastDebugState = "edge"
            return
        }

        lastHandSeenAt = now

        val pose = classifyPose(hand, palmX, palmY)

        if (cursorMode) {
            when {
                pose == "fist" -> {
                    openFrames = 0
                    updateCursorPosition(palmX, palmY)
                    return
                }
                pose == null && now - cursorShownAt >= FIST_HOLD_MS -> {
                    openFrames++
                    if (openFrames >= OPEN_CONFIRM_FRAMES) {
                        fireClick(palmX, palmY, now)
                        return
                    }
                    lastDebugState = "cursor open $openFrames/$OPEN_CONFIRM_FRAMES"
                    return
                }
                else -> {
                    openFrames = 0
                    cancelCursor()
                }
            }
        }

        if (updateWave(result, hand, handIdx, pose, palmX, palmY, now)) return

        if (pose != null) {
            if (pose == poseCandidate) poseCount++ else {
                poseCandidate = pose
                poseCount = 1
            }
            val need = if (pose == "fist") FIST_MIN_FRAMES else POSE_MIN_FRAMES
            if (!poseLatched && poseCount >= need && now - lastGestureTime >= COOLDOWN_MS) {
                if (pose == "fist") {
                    enterCursorMode(palmX, palmY, now)
                } else {
                    triggerPose(pose, now)
                }
                return
            }
            lastDebugState = if (poseLatched) "pose $pose latched" else "pose $pose $poseCount/$need"
            return
        } else {
            resetPoseState()
        }

        if (awaitingReset) {
            val dxR = palmX - originX
            val dyR = palmY - originY
            val dist = Math.sqrt((dxR * dxR + dyR * dyR).toDouble()).toFloat()
            val timedOut = now - resetSince > RESET_TIMEOUT_MS
            if (dist < RESET_DIST || timedOut) {
                awaitingReset = false
                clearHistories()
                lastDebugState = "rearm d=${fmt(dist)}"
            } else {
                lastDebugState = "reset d=${fmt(dist)}"
                lastDx = 0f
                lastDy = 0f
                return
            }
        }

        if (now - lastGestureTime < COOLDOWN_MS) {
            lastDebugState = "cd"
            return
        }

        xHistory.add(palmX)
        yHistory.add(palmY)
        tHistory.add(now)
        while (xHistory.size > SAMPLE_WINDOW) {
            xHistory.removeAt(0)
            yHistory.removeAt(0)
            tHistory.removeAt(0)
        }

        if (xHistory.size < MIN_SAMPLES) {
            lastDebugState = "warm ${xHistory.size}"
            lastDx = 0f
            lastDy = 0f
            return
        }

        val dx = xHistory.last() - xHistory.first()
        val dy = yHistory.last() - yHistory.first()
        val timeSpan = tHistory.last() - tHistory.first()
        lastDx = dx
        lastDy = dy

        if (timeSpan < MIN_TIME_MS || timeSpan > MAX_TIME_MS) {
            if (timeSpan > MAX_TIME_MS) {
                xHistory.removeAt(0)
                yHistory.removeAt(0)
                tHistory.removeAt(0)
            }
            lastDebugState = "t=${timeSpan} dx=${fmt(dx)} dy=${fmt(dy)}"
            return
        }

        val absDx = abs(dx)
        val absDy = abs(dy)

        if (absDx >= absDy * AXIS_DOMINANCE) {
            val hThresh = if (dx > 0f) H_THRESHOLD else H_THRESHOLD_LEFT
            if (absDx >= hThresh) {
                val peak = if (dx > 0f) xHistory.max() - xHistory.first() else xHistory.first() - xHistory.min()
                val endOk = absDx >= hThresh * PEAK_END_RATIO
                val cons = consistency(xHistory, dx)
                val velOk = velocityProfileOk(xHistory, tHistory)
                if (peak >= hThresh && endOk && cons >= CONSISTENCY_MIN && velOk) {
                    trigger(if (dx > 0f) "right" else "left", now, xHistory.first(), yHistory.first(), dx, dy, timeSpan, cons)
                    return
                }
                lastDebugState = "H weak p=${fmt(peak)} c=${fmt(cons)} vp=${fmt(lastVelPeak)} vm=${fmt(lastVelMed)}"
                return
            }
        }

        lastDebugState = "dx=${fmt(dx)} dy=${fmt(dy)} t=${timeSpan}"
    }

    // ================================================================
    //  隔空挥动 v1 — 华为/小米官方规格
    //  掌/背朝向(world nz) + 指尖轴(图像坐标) → dwell 武装 → 仅允许方向的
    //  位移波触发。挥动方向 = 指尖方向的反方向。
    //  掌心朝屏+指尖上 → 向下挥(内容下移 prev)；手背朝屏+指尖下 → 向上挥(内容上移 next)
    // ================================================================

    /**
     * @return true = 该帧已被挥动通道消费（FIRED 回落期），调用方应立即 return
     */
    private fun updateWave(
        result: HandLandmarkerResult,
        hand: List<NormalizedLandmark>,
        handIdx: Int,
        pose: String?,
        palmX: Float,
        palmY: Float,
        now: Long
    ): Boolean {
        // 0) 姿态互斥：握拳已锁存光标 / 捏合是返回，都不该走挥动通道
        //    需连续 POSE_KILL_FRAMES 帧才解除——实测手背朝屏+指尖下被单帧误判 fist 杀掉武装
        //    ARMED/FIRED 下单帧 pose 误判不早退：当帧投影/翻转照常更新，否则波形状态机被冻结
        if (pose == "fist" || pose == "pinch") {
            wPoseFrames++
            if (wPoseFrames >= POSE_KILL_FRAMES && wState != WState.IDLE) disarm("pose")
            lastWaveState = "pose($pose)x$wPoseFrames"
            if (wState == WState.IDLE || wState == WState.DWELL) return false
        } else {
            wPoseFrames = 0
        }

        // 1) worldLandmarks 可用性（帧间稳定性 dStep 的根基）
        //    必须与主手同索引：setNumHands(2) 下 [0] 可能是另一只手，混用会让 dStep/facing 在两手掌间跳变
        val worlds = result.worldLandmarks()
        val world: List<Landmark>? = worlds.getOrNull(handIdx)
        if (world == null || world.size < 21 || outWidth <= 0 || outHeight <= 0) {
            if (wState != WState.IDLE) disarm("no_world")
            lastWaveState = "no_world"
            return false
        }

        // 2) 掌平面法向（仅用于帧间稳定 dStep，不再参与朝向门槛——实测手背 nz≈0.44 贴线太弱）
        val a = world[0]
        val b = world[5]
        val c = world[17]
        val v1x = b.x() - a.x(); val v1y = b.y() - a.y(); val v1z = b.z() - a.z()
        val v2x = c.x() - b.x(); val v2y = c.y() - b.y(); val v2z = c.z() - b.z()
        var nx = v1y * v2z - v1z * v2y
        var ny = v1z * v2x - v1x * v2z
        var nz = v1x * v2y - v1y * v2x
        val nLen = sqrt(nx * nx + ny * ny + nz * nz)
        if (nLen < 1e-7f) {
            if (wState != WState.IDLE) disarm("degenerate")
            lastWaveState = "degenerate"
            return false
        }
        nx /= nLen; ny /= nLen; nz /= nLen

        val handCat = result.handedness().getOrNull(handIdx)?.firstOrNull()
        val label = handCat?.categoryName() ?: "?"
        val handScore = handCat?.score() ?: 0f
        val hSign = if (label == "Right") 1f else -1f
        val s = hSign * NORMAL_SIGN
        val cnx = nx * s; val cny = ny * s; val cnz = nz * s

        wDStep = if (!wHasPrevN) 0f else angleDeg(wPrevNx, wPrevNy, wPrevNz, cnx, cny, cnz)
        wPrevNx = cnx; wPrevNy = cny; wPrevNz = cnz
        wHasPrevN = true

        // 3) 掌/背外观：2D 叉积符号（对齐官方 NN 学外观的本质）
        //    s = (indexMCP − wrist) × (thumbTip − wrist)
        //    翻掌/背 = 镜像 → s 翻转；手指上下颠倒 = 平面内旋转 → s 不变
        //    即 s 与手指方向解耦，只反映掌/背——手腕无需大翻转
        //    仅用于图标显示 + 调试（不做 arm 硬门槛，拇指遮挡时只影响图标不影响手势）
        val iX = (hand[5].x() - hand[0].x()) * outWidth
        val iY = (hand[5].y() - hand[0].y()) * outHeight
        val tX = (hand[4].x() - hand[0].x()) * outWidth
        val tY = (hand[4].y() - hand[0].y()) * outHeight
        val iLen = hypot(iX, iY); val tLen = hypot(tX, tY)
        val crossNorm = if (iLen > 1e-3f && tLen > 1e-3f)
            (iX * tY - iY * tX) / (iLen * tLen) * hSign else 0f
        wFacing = when {
            crossNorm * FACE_X_SIGN > FACE_X_MIN -> F_PALM
            crossNorm * FACE_X_SIGN < -FACE_X_MIN -> F_BACK
            else -> F_NONE
        }

        // 4) 指尖轴（图像坐标，镜像后）：腕(0) → 食/中/环指尖(8/12/16)
        //    三根手指一起投票：单位方向取平均（三指一致时最强），长度取三指均值；
        //    单根被遮挡/透视压缩不会像「只取最长」那样把方向带偏
        var ux = 0f
        var uy = 0f
        var lenSum = 0f
        var axisCount = 0
        for (ti in intArrayOf(8, 12, 16)) {
            val tx = (hand[ti].x() - hand[0].x()) * outWidth
            val ty = (hand[ti].y() - hand[0].y()) * outHeight
            val len = hypot(tx, ty)
            if (len < 1f) continue
            ux += tx / len
            uy += ty / len
            lenSum += len
            axisCount++
        }
        if (axisCount > 0) {
            ux /= axisCount
            uy /= axisCount
        }
        val aLenPx = if (axisCount > 0) lenSum / axisCount else 0f
        val ax = ux * aLenPx
        val ay = uy * aLenPx
        val aLen = aLenPx / outWidth
        wAxis = when {
            aLen < AXIS_MIN -> A_NONE
            abs(ay) >= abs(ax) * AXIS_DOM -> if (ay < 0f) A_UP else A_DOWN
            abs(ax) >= abs(ay) * AXIS_DOM -> if (ax > 0f) A_RIGHT else A_LEFT
            else -> A_NONE
        }
        if (wAxis != lastLoggedAxis) {
            lastLoggedAxis = wAxis
            Log.d(TAG, ">>> axis ${axisName(wAxis)} ax=${ax.toInt()} ay=${ay.toInt()} len=${fmt3(aLen)}")
        }

        // 5) 轴 → 允许的挥手方向（自然动作下 指尖上≈掌心朝屏→向下挥；指尖下≈手背朝屏→向上挥）
        //    不再叠加掌/背法向门槛——官方 NN 本质就是外观+方向分类，轴已是方向的稳健代理
        wDir = when (wAxis) {
            A_UP -> W_DOWN
            A_DOWN -> W_UP
            else -> 0
        }

        // 5.5) 5 指朝向翻转速率：腕→指尖向量的「朝向角」变化率（~100ms 窗口、取 5 指中位数抗噪）
        //      纯平移 / 指尖沿自身指向的伸缩 → 朝向角不变（速率≈0），只有真正甩腕翻转才快
        //      这是「主动挥 vs 被动回程」的判别闸门：误报的惯性尾/起手回拉几乎没有朝向翻转
        val tipGap = now - wTipLastAt
        if (wTipLastAt == 0L) {
            saveTipRefs(hand, now)
        } else if (tipGap >= 100L) {
            if (tipGap > 250L) {
                // 采样间隔过大（手丢失回来/出安全区/光标模式等）→ 参考点不可信，本帧只重采样不计算
                saveTipRefs(hand, now)
            } else {
                val dtTip = tipGap / 1000f
                val rate = FloatArray(5)
                val spd = FloatArray(5)
                val vy = FloatArray(5)
                for (k in TIP_IDX.indices) {
                    val li = TIP_IDX[k]
                    val rx = (wTipRefX[k] - wTipRefWristX) * outWidth
                    val ry = (wTipRefY[k] - wTipRefWristY) * outHeight
                    val cx = (hand[li].x() - hand[0].x()) * outWidth
                    val cy = (hand[li].y() - hand[0].y()) * outHeight
                    val ddx = cx - rx
                    val ddy = cy - ry
                    spd[k] = hypot(ddx, ddy) / outWidth / dtTip
                    vy[k] = ddy / outWidth / dtTip
                    val rLen = hypot(rx, ry)
                    val cLen = hypot(cx, cy)
                    if (rLen < 1f || cLen < 1f) continue // 指尖贴近腕（退化姿态）该指不参与翻转角计算
                    val nrx = rx / rLen; val nry = ry / rLen
                    val ncx = cx / cLen; val ncy = cy / cLen
                    val dTheta = atan2(nrx * ncy - nry * ncx, nrx * ncx + nry * ncy)
                    val dc = ncy - nry // 归一化纵向分量变化（+ = 更朝下）
                    rate[k] = (if (dc >= 0f) 1f else -1f) * abs(dTheta) * 57.29578f / dtTip
                }
                rate.sort(); spd.sort(); vy.sort()
                wFlipV = rate[2]
                wTipSpeedW = spd[2]
                wTipVyW = vy[2]
                wTipSpeedNormW = if (wSpanPx > 10f) wTipSpeedW * outWidth / wSpanPx else 0f
                wTipSampled = true
                saveTipRefs(hand, now)
            }
        }

        val spanX = (hand[9].x() - hand[0].x()) * outWidth
        val spanY = (hand[9].y() - hand[0].y()) * outHeight
        wSpanPx = sqrt(spanX * spanX + spanY * spanY)
        wLumaNorm = ((meanLuma - 16f) / 219f).coerceIn(0f, 1f)

        palmLogCount++
        if (palmLogCount % PALM_LOG_EVERY == 0) {
            Log.d(
                TAG,
                ">>> wave facing=${facingName(wFacing)} axis=${axisName(wAxis)} " +
                        "dir=${dirName(wDir)} hand=$label/${fmt3(handScore)} idx=$handIdx cross=${fmt3(crossNorm)} " +
                        "dStep=${fmt(wDStep)} proj=${fmt3(wProj)} " +
                        "span=${wSpanPx.toInt()}px luma=${meanLuma.toInt()} lumaNorm=${fmt3(wLumaNorm)} " +
                        "tipV=${fmt3(wTipSpeedW)} tipVn=${fmt3(wTipSpeedNormW)} vy=${fmt3(wTipVyW)} flipV=${fmt(wFlipV)}"
            )
        }

        lastWaveFacing = facingName(wFacing)
        lastWaveAxis = axisName(wAxis)
        lastWaveDir = wDir
        lastWaveProj = wProj
        lastWaveLuma = wLumaNorm
        lastWaveSpan = wSpanPx

        // 6) 光线门控（对齐「光线充足时」）
        if (wLumaNorm < LUMA_MIN_NORM) {
            if (wState != WState.IDLE) disarm("luma")
            lastWaveState = "luma ${fmt3(wLumaNorm)}"
            return false
        }

        // 6.5) 距离纠错（官方 error 5/6）：仅 DWELL 稳定停留期检查
        //      挥动/武装期 2D 跨透视缩短会塌缩（实测 51px→14px），不是真变远——期间禁查
        //      连续 3 帧才报，防单帧关键点抖动
        if (wDir != 0 && wState == WState.DWELL) {
            val spanNorm = wSpanPx / outWidth
            when {
                spanNorm > SPAN_CLOSE_MAX -> {
                    wFarFrames = 0
                    wCloseFrames++
                    if (wCloseFrames >= SPAN_CONFIRM_FRAMES) {
                        reportError(E_TOO_CLOSE)
                        disarm("too_close")
                    }
                }
                spanNorm < SPAN_FAR_MIN -> {
                    wCloseFrames = 0
                    wFarFrames++
                    if (wFarFrames >= SPAN_CONFIRM_FRAMES) {
                        reportError(E_TOO_FAR)
                        disarm("too_far")
                    }
                }
                else -> { wFarFrames = 0; wCloseFrames = 0 }
            }
        }

        when (wState) {
            WState.ARMED -> {
                if (now - wStateSince > WAVE_TIMEOUT_MS) { disarm("timeout"); return false }
                val minTipVn = if (wArmedDir == W_DOWN) WAVE_MIN_TIPVN_PREV else WAVE_MIN_TIPVN
                val needFastRun = if (wArmedDir == W_DOWN) 2 else 1
                // 投影信号 = 指尖相对腕向量的变化量（非绝对坐标）：
                //   整手平移 → 向量不变 → 不触发（消除「整体平移误识别」）
                //   手指扫动/以腕为轴翻转 → 向量大幅变化 → 触发（手腕不用大动）
                val relY = (tipRefY(hand) - hand[0].y()) * outHeight
                val dyPx = relY - wAnchorY
                wProj = (if (wArmedDir == W_DOWN) dyPx else -dyPx) / outWidth
                if (wProj > wMaxProj) wMaxProj = wProj

                // 允许方向翻转/线性速率峰值（触发日志与慢速闸门校准数据）
                val allowedFlip = if (wArmedDir == W_DOWN) wFlipV else -wFlipV
                if (allowedFlip > wFlipPeak) wFlipPeak = allowedFlip
                if (wTipSpeedW > wTipSpeedPeak) wTipSpeedPeak = wTipSpeedW
                if (wTipSpeedNormW > wTipSpeedNormPeak) wTipSpeedNormPeak = wTipSpeedNormW

                // 慢挥/单帧抖动通常只有一个速度尖峰；要求连续 2 个采样周期都达标才算真挥动
                if (wTipSampled) {
                    if (wTipSpeedNormW >= minTipVn) wFastRun++ else wFastRun = 0
                    wTipSampled = false
                }

                // 轴翻转硬条件：武装 W_UP（下一段）时指尖从朝下翻到朝上才允许触发，
                // 武装 W_DOWN（上一段）时翻到朝下——手原地微动/纯平移轴不翻转 → 拒绝
                val targetAxis = if (wArmedDir == W_UP) A_UP else A_DOWN
                if (wAxis == targetAxis) wAxisFlipped = true

                // committed：峰值过半程后锁定武装方向——挥动中指尖轴会自然翻转，
                // 此后不再清零方向稳定（否则武装后永远触发不了）
                if (wMaxProj >= WAVE_COMMIT_R) {
                    wDirStable = WAVE_DIR_STABLE
                } else {
                    if (wDir == wArmedDir) wDirStable++ else wDirStable = 0
                }

                // rearm 防回程误触发：最小武装时间 + 方向稳定，回程挥动不吞成下一次
                if (wRearmAt > 0 && now - wRearmAt < WAVE_MIN_ARM_MS) {
                    lastWaveState = "ARMED wait ${now - wRearmAt}ms"
                    return false
                }

                // 峰值跟踪已在投影后立即记录（上方）

                // 跳变防护：慢晃中跟踪器会「重解释」手部朝向——单帧位移跳 0.7+ 宽（真实挥动 ≤6）、
                // 手尺寸单帧接近翻倍（真实 ≤10%/帧）。这不是用户在挥动，量到即作废本次武装
                if (wLastProjAt > 0) {
                    val dtJump = (now - wLastProjAt).coerceAtLeast(1L) / 1000f
                    val velJump = abs(wProj - wLastProjForVel) / dtJump
                    if (velJump > WAVE_JUMP_VEL_MAX) {
                        Log.d(TAG, ">>> wave jump vel=${fmt3(velJump)} proj=${fmt3(wProj)} span=${wSpanPx.toInt()}px")
                        disarm("jump")
                        return false
                    }
                }
                if (wSpanPrevPx > 10f && abs(wSpanPx - wSpanPrevPx) / wSpanPrevPx > WAVE_SPAN_JUMP_R) {
                    Log.d(TAG, ">>> wave span_jump ${wSpanPrevPx.toInt()}->${wSpanPx.toInt()}px")
                    disarm("span_jump")
                    return false
                }
                wSpanPrevPx = wSpanPx

                // 速度纠错（官方 error 7）：用峰值增量判断，避免起手抖动误报
                if (wLastProjAt > 0 && wProj > wLastProjForVel) {
                    val dt = (now - wLastProjAt).coerceAtLeast(1L) / 1000f
                    val vel = (wProj - wLastProjForVel) / dt
                    if (wMaxProj < WAVE_R && vel > WAVE_VEL_MAX) {
                        reportError(E_TOO_FAST)
                        disarm("too_fast")
                        return false
                    }
                }
                wLastProjForVel = wProj
                wLastProjAt = now

                // 方向纠错：反向位移远超正向峰值 且 为「真正甩腕换向」（5 指中位朝向翻转速率 ≥ 门槛）才报
                // 被动回程惯性尾/起手回拉是「指尖沿自身指向进退」，朝向角几乎不变 → 不计（幅值 0.30 阈值双重保险）
                if (wProj < -WAVE_OPPOSITE_R && wProj < -wMaxProj * 2f) {
                    val counterFlip = if (wArmedDir == W_UP) wFlipV else -wFlipV
                    if (counterFlip >= WAVE_WRONG_DIR_FLIP) {
                        wWrongDirFrames++
                        if (wWrongDirFrames >= WAVE_WRONG_DIR_GRACE) {
                            Log.d(TAG, ">>> wrong_dir detail proj=${fmt3(wProj)} flipV=${fmt(wFlipV)} tipV=${fmt3(wTipSpeedW)}")
                            reportError(E_DIRECTION)
                            disarm("wrong_dir")
                            return false
                        }
                    } else {
                        wWrongDirFrames = 0
                    }
                } else {
                    wWrongDirFrames = 0
                }

                // 触发：峰值达阈值 + 轴已翻转（真挥动的必要条件）+ 方向稳定 + 速率达标（慢挥不触发）
                if (wMaxProj >= WAVE_R && wAxisFlipped && wDirStable >= WAVE_DIR_STABLE && wFastRun >= needFastRun) {
                    if (wTipSpeedNormPeak >= minTipVn) {
                        fireWave(wArmedDir, wMaxProj, now)
                        return true
                    } else if (!wSlowRejectLogged) {
                        wSlowRejectLogged = true
                        Log.d(TAG, ">>> wave slow_reject dir=${dirName(wArmedDir)} peakTipV=${fmt3(wTipSpeedPeak)} " +
                                "peakTipVn=${fmt3(wTipSpeedNormPeak)} min=${fmt3(minTipVn)} run=$wFastRun " +
                                "span=${wSpanPx.toInt()}px proj=${fmt3(wProj)} max=${fmt3(wMaxProj)}")
                    }
                }
                lastWaveState = "ARMED ${dirName(wArmedDir)} proj=${fmt3(wProj)} max=${fmt3(wMaxProj)} flip=$wAxisFlipped"
                return false
            }
            WState.FIRED -> {
                // FIRED 总窗口：超时回 IDLE（须 > WAVE_SWITCH_HOLD_MS，防静止换向检测被超时抢先）
                if (now - wLastFireAt > FIRED_WINDOW_MS) { disarm("fired_window"); return false }
                // 与 ARMED 同信号：指尖相对腕向量变化
                val relX = (tipRefX(hand) - hand[0].x()) * outWidth
                val relY = (tipRefY(hand) - hand[0].y()) * outHeight
                val dyPx = relY - wAnchorY
                wProj = (if (wArmedDir == W_DOWN) dyPx else -dyPx) / outWidth
                if (wDir != 0 && wDir != wArmedDir) wSwitchDirFrames++ else wSwitchDirFrames = 0

                // 静止检测：手停住（帧间位移 < FIRED_HOLD_EPS）持续累计 → 区分「回程」与「停顿换向」
                if (wFiredMoveAt == 0L) {
                    wFiredRefX = relX; wFiredRefY = relY
                    wFiredPeakProj = wProj
                    wFiredMoveAt = now
                } else if (hypot(relX - wFiredRefX, relY - wFiredRefY) > FIRED_HOLD_EPS * outWidth) {
                    wFiredRefX = relX; wFiredRefY = relY
                    wFiredMoveAt = now
                }

                // ① 回程静默复位：投影回落近锚点 → 同向 rearm（回程不算触发，连挥循环）
                //    如果此时指尖轴已经连续 2 帧翻到反侧，直接按反方向 rearm，下一段不用等 900ms
                if (wProj < WAVE_REARM_R) {
                    val nextDir = if (wSwitchDirFrames >= 2) wDir else wArmedDir
                    rearmWave(nextDir, relX, relY, now)
                    lastWaveState = "REARM same ${dirName(nextDir)} proj=${fmt3(wProj)}"
                    return true
                }

                // ② 静止保持 ≥ WAVE_SWITCH_HOLD_MS：
                //    - 轴已翻向反侧且仍在峰值附近 → 换向 rearm
                //    - 否则直接退出 FIRED，避免白占 1600ms 阻塞后续手势
                if (now - wFiredMoveAt >= WAVE_SWITCH_HOLD_MS) {
                    if (wDir != 0 && wDir != wArmedDir &&
                        wProj >= wFiredPeakProj - WAVE_REARM_R
                    ) {
                        Log.d(TAG, ">>> wave switch-rearm dir=${dirName(wDir)} still=${now - wFiredMoveAt}ms")
                        rearmWave(wDir, relX, relY, now)
                        lastWaveState = "REARM switch ${dirName(wDir)}"
                        return true
                    }
                    disarm("fired_still")
                    return false
                }

                lastWaveState = "FIRED proj=${fmt3(wProj)} still=${now - wFiredMoveAt}ms"
                return true
            }
            else -> {}
        }

        // 7) IDLE / DWELL：姿态合法（掌/背+指尖轴组合）且稳定 → 累计停留
        if (wDir != 0 && wDStep < ANG_STEP_MAX) {
            wDwellMissFrames = 0
            if (wDir != wLastDir) {
                wDwellFrames = 0
                wLastDir = wDir
                wDwellStartX = palmX
                wDwellStartY = palmY
            }
            // 「停顿」：停留期掌心不能漂太多
            val moveX = (palmX - wDwellStartX) * outWidth
            val moveY = (palmY - wDwellStartY) * outHeight
            val moved = sqrt(moveX * moveX + moveY * moveY) / outWidth
            if (moved > DWELL_MOVE_MAX) {
                if (wState == WState.DWELL) disarm("moved")
                wDwellFrames = 0
                lastDwellStep = 0
                wState = WState.IDLE
                lastWaveState = "idle moved=${fmt3(moved)}"
                return false
            }
            wDwellFrames++
            val step = wDwellFrames * 8
            if (step > lastDwellStep) {
                lastDwellStep = step
                recognitionCallback?.onDwellProgress(
                    (step / 64f).coerceAtMost(1f), wDir
                )
            }
            if (wDwellFrames >= DWELL_FRAMES) {
                wState = WState.ARMED
                wArmedDir = wDir
                wWrongDirFrames = 0
                // 锚点=指尖相对腕向量（px）：投影量=向量变化，整手平移不产生变化→不误触发
                wAnchorX = (tipRefX(hand) - hand[0].x()) * outWidth
                wAnchorY = (tipRefY(hand) - hand[0].y()) * outHeight
                wStateSince = now
                wProj = 0f
                wLastProjForVel = 0f
                wLastProjAt = 0L
                wMaxProj = 0f
                wFlipPeak = 0f
                wTipSpeedPeak = 0f
                wTipSpeedNormPeak = 0f
                wSlowRejectLogged = false
                wTipSampled = false
                wFastRun = 0
                wSwitchDirFrames = 0
                wSpanPrevPx = 0f
                wRearmAt = 0L   // 首次武装不等待最小武装时间
                wDirStable = 0
                wAxisFlipped = false
                recognitionCallback?.onDwellProgress(1f, wDir)
                recognitionCallback?.onArmed(wDir)
                lastWaveState = "ARMED ${dirName(wDir)} dwell=${wDwellFrames}"
                lastWaveDwell = wDwellFrames
                Log.d(
                    TAG,
                    ">>> wave arm dir=${dirName(wDir)} facing=${facingName(wFacing)} " +
                            "axis=${axisName(wAxis)} hand=$label/${fmt3(handScore)} dwell=${wDwellFrames}f " +
                            "relTip=${fmt(wAnchorX)},${fmt(wAnchorY)} span=${wSpanPx.toInt()}px"
                )
            } else {
                wState = WState.DWELL
                lastWaveDwell = wDwellFrames
                lastWaveState = "DWELL $wDwellFrames/$DWELL_FRAMES ${dirName(wDir)}"
            }
        } else {
            // 姿态/稳定单帧失效：宽限 DWELL_MISS_MAX 帧，防闪断把 dwell 清零重来
            wDwellMissFrames++
            if (wDwellMissFrames < DWELL_MISS_MAX && wState == WState.DWELL) {
                lastWaveState = "dwell miss $wDwellMissFrames/$DWELL_MISS_MAX"
                return false
            }
            if (wState == WState.DWELL) disarm("unstable")
            wDwellFrames = 0
            wLastDir = 0
            wDwellMissFrames = 0
            lastDwellStep = 0
            wState = WState.IDLE
            lastWaveState = "idle facing=${facingName(wFacing)} axis=${axisName(wAxis)} step=${fmt(wDStep)}"
        }
        return false
    }

    private fun fireWave(dir: Int, proj: Float, now: Long) {
        if (now - wLastFireAt < WAVE_COOLDOWN_MS) {
            // 冷却内再触发：回 IDLE 重新 dwell，而非设回 ARMED（残留 wMaxProj/wAxisFlipped 会让冷却一过立即重触发）
            disarm("cooldown")
            return
        }
        wLastFireAt = now
        wState = WState.FIRED
        wSwitchDirFrames = 0
        // 挥动方向 → 内容方向 → 手势名：WAVE DOWN=内容下移=上一段 prev；WAVE UP=内容上移=下一段 next
        val name = if (dir == W_DOWN) "prev" else "next"
        Log.d(TAG, ">>> GESTURE: $name by=wave_v1 dir=${dirName(dir)} proj=${fmt3(proj)} " +
                "peakFlip=${fmt(wFlipPeak)} peakTipV=${fmt3(wTipSpeedPeak)} peakTipVn=${fmt3(wTipSpeedNormPeak)} " +
                "span=${wSpanPx.toInt()}px armMs=${now - wStateSince}")
        recognitionCallback?.onWaveFired(dir)
        lastGestureTime = now
        gestureCallback.onGestureDetected(name)
    }

    /** tipX/tipY = 指尖相对腕向量（px），与 ARMED 投影同语义 */
    private fun rearmWave(dir: Int, tipX: Float, tipY: Float, now: Long) {
        wState = WState.ARMED
        wArmedDir = dir
        wWrongDirFrames = 0
        wFiredMoveAt = 0L
        wAnchorX = tipX; wAnchorY = tipY
        wStateSince = now
        wProj = 0f
        wLastProjForVel = 0f
        wLastProjAt = 0L
        wMaxProj = 0f
        wFlipPeak = 0f
        wTipSpeedPeak = 0f
        wTipSpeedNormPeak = 0f
        wSlowRejectLogged = false
        wTipSampled = false
        wFastRun = 0
        wSwitchDirFrames = 0
        wSpanPrevPx = 0f
        wRearmAt = now
        wDirStable = 0
        wAxisFlipped = false
        recognitionCallback?.onArmed(wArmedDir)
        lastWaveState = "REARM ${dirName(wArmedDir)}"
        Log.d(TAG, ">>> wave rearmed dir=${dirName(wArmedDir)}")
    }

    private fun disarm(reason: String) {
        if (wState == WState.IDLE) return
        Log.d(TAG, ">>> wave disarm reason=$reason")
        recognitionCallback?.onDisarm(reason)
        wState = WState.IDLE
        wFastRun = 0
        wTipSampled = false
        wSwitchDirFrames = 0
        wDwellFrames = 0
        wLastDir = 0
        wArmedDir = 0
        wWrongDirFrames = 0
        lastDwellStep = 0
        lastWaveDwell = 0
        wLastProjForVel = 0f
        wLastProjAt = 0L
        wMaxProj = 0f
        wFlipPeak = 0f
        wTipSpeedPeak = 0f
        wTipSpeedNormPeak = 0f
        wSlowRejectLogged = false
        wTipSampled = false
        wFastRun = 0
        wSpanPrevPx = 0f
        wPoseFrames = 0
        wDwellMissFrames = 0
        wFiredMoveAt = 0L
        wRearmAt = 0L
        wDirStable = 0
        wAxisFlipped = false
        wFarFrames = 0
        wCloseFrames = 0
    }

    private fun reportError(code: Int): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastErrorAt < ERROR_MIN_INTERVAL_MS) return false
        lastErrorAt = now
        Log.d(TAG, ">>> wave error code=$code")
        recognitionCallback?.onError(code)
        return true
    }

    private fun angleDeg(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float): Float {
        val c = (ax * bx + ay * by + az * bz).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(c.toDouble())).toFloat()
    }

    private fun dirName(dir: Int): String = when (dir) {
        W_UP -> "UP"
        W_DOWN -> "DOWN"
        else -> "-"
    }

    private fun facingName(f: Int): String = when (f) {
        F_PALM -> "palm"
        F_BACK -> "back"
        else -> "--"
    }

    private fun axisName(a: Int): String = when (a) {
        A_UP -> "up"
        A_DOWN -> "down"
        A_LEFT -> "left"
        A_RIGHT -> "right"
        else -> "--"
    }

    private fun consistency(values: List<Float>, net: Float): Float {
        if (values.size < 2) return 0f
        val sign = if (net >= 0f) 1f else -1f
        var agree = 0
        for (i in 1 until values.size) {
            if ((values[i] - values[i - 1]) * sign > 0f) agree++
        }
        return agree.toFloat() / (values.size - 1)
    }

    private fun velocityProfileOk(values: List<Float>, times: List<Long>): Boolean {
        if (values.size < 4) return false
        val speeds = FloatArray(values.size - 1)
        for (i in 1 until values.size) {
            val dt = (times[i] - times[i - 1]).coerceAtLeast(1L)
            speeds[i - 1] = abs(values[i] - values[i - 1]) * 1000f / dt
        }
        var peak = 0f
        var peakIdx = 0
        for (i in speeds.indices) {
            if (speeds[i] > peak) {
                peak = speeds[i]
                peakIdx = i
            }
        }
        val sorted = speeds.sorted()
        val med = sorted[sorted.size / 2]
        lastVelPeak = peak
        lastVelMed = med
        if (peak < PEAK_VEL_MIN) return false
        if (peakIdx == 0 || peakIdx == speeds.size - 1) return false
        if (med <= 0f || peak < med * VEL_SHAPE_RATIO) return false
        return true
    }

    private fun classifyPose(hand: List<NormalizedLandmark>, palmX: Float, palmY: Float): String? {
        if (outWidth <= 0 || outHeight <= 0) return null
        for (lm in hand) {
            val x = lm.x()
            val y = lm.y()
            if (x < POSE_SAFE_MIN || x > POSE_SAFE_MAX || y < POSE_SAFE_MIN || y > POSE_SAFE_MAX) return null
        }
        val sx = outWidth.toFloat()
        val sy = outHeight.toFloat()
        val handLen = pixelDist(hand[0], hand[9], sx, sy)
        if (handLen <= 1f) return null

        val fingers = arrayOf(
            intArrayOf(5, 6, 8),
            intArrayOf(9, 10, 12),
            intArrayOf(13, 14, 16),
            intArrayOf(17, 18, 20)
        )
        val curledF = BooleanArray(4)
        for (i in fingers.indices) {
            val f = fingers[i]
            val mcp = hand[f[0]]
            val pip = hand[f[1]]
            val tip = hand[f[2]]
            val reach = pixelDist(mcp, pip, sx, sy) + pixelDist(pip, tip, sx, sy)
            val folded = pixelDist(mcp, tip, sx, sy)
            if (reach > 1f && folded < FIST_CURL_RATIO * reach) curledF[i] = true
        }
        val curledCount = curledF.count { it }

        // 真握拳（5 指参与）：4 指卷曲 + 拇指贴向食/中指（指尖 4 靠近 PIP 6/10）
        //   旧版 ≥3 指即算 → 半握/自然垂手易误入光标；现 5 指齐卷才响应
        val thumbFold = min(
            pixelDist(hand[4], hand[6], sx, sy),
            pixelDist(hand[4], hand[10], sx, sy)
        ) / handLen
        val curledTotal = curledCount + if (thumbFold < THUMB_TO_FINGER_RATIO) 1 else 0
        poseLogCount++
        if (curledCount >= 3 && poseLogCount % POSE_LOG_EVERY == 0) {
            Log.d(TAG, ">>> fist curl=$curledTotal/5 t=${fmt3(thumbFold)}")
        }
        if (curledTotal >= FIST_MIN_CURLED) return "fist"

        // OK 手势（原「捏合返回」）：拇指-食指指尖真正相触（构成圆环）+ 中/环/小指伸展 → 👌
        //   旧版只查「拇指-食指接近 0.35×handLen」→ 张开手随手一晃就误判返回；
        //   现在需指尖接触（≤0.20×handLen）且其余三指不卷曲
        val okRatio = pixelDist(hand[4], hand[8], sx, sy) / handLen
        val othersCurled = (1..3).count { curledF[it] }
        if (okRatio < 0.5f && poseLogCount % POSE_LOG_EVERY == 0) {
            Log.d(TAG, ">>> ok r=${fmt3(okRatio)} others=$othersCurled")
        }
        if (okRatio < OK_TOUCH_RATIO && othersCurled == 0) return "pinch"

        return null
    }

    private fun mapAxis(v: Float): Float =
        ((v - CURSOR_MIN) / (CURSOR_MAX - CURSOR_MIN)).coerceIn(0f, 1f)

    // 指尖参考点取食/中/环三指均值：手背朝屏+指尖朝下时中指尖常被透视压缩，
    // 只用 12 会让投影信号变小、下一段很难触发
    private fun tipRefX(hand: List<NormalizedLandmark>): Float =
        (hand[8].x() + hand[12].x() + hand[16].x()) / 3f

    private fun tipRefY(hand: List<NormalizedLandmark>): Float =
        (hand[8].y() + hand[12].y() + hand[16].y()) / 3f

    private fun enterCursorMode(palmX: Float, palmY: Float, now: Long) {
        cursorMode = true
        poseLatched = true
        cursorShownAt = now
        openFrames = 0
        cursorX = palmX
        cursorY = palmY
        clearHistories()
        cursorCallback?.onCursorShow(mapAxis(cursorX), mapAxis(cursorY))
        lastDebugState = "cursor show"
        Log.d(TAG, ">>> cursor show ${fmt(cursorX)},${fmt(cursorY)}")
    }

    private fun updateCursorPosition(palmX: Float, palmY: Float) {
        if (!cursorMode) return
        cursorX += (palmX - cursorX) * CURSOR_EMA
        cursorY += (palmY - cursorY) * CURSOR_EMA
        cursorCallback?.onCursorMove(mapAxis(cursorX), mapAxis(cursorY))
        lastDebugState = "cursor ${fmt(cursorX)},${fmt(cursorY)}"
    }

    private fun fireClick(palmX: Float, palmY: Float, now: Long) {
        val cx = mapAxis(cursorX)
        val cy = mapAxis(cursorY)
        cursorMode = false
        openFrames = 0
        cursorCallback?.onCursorClick(cx, cy)
        lastGestureTime = now
        awaitingReset = true
        originX = palmX
        originY = palmY
        resetSince = now
        clearHistories()
        resetPoseState()
        lastDebugState = "HIT click"
        Log.d(TAG, ">>> GESTURE: click ${fmt(cx)},${fmt(cy)}")
        gestureCallback.onGestureDetected("click")
    }

    private fun cancelCursor() {
        if (cursorMode) {
            cursorMode = false
            cursorCallback?.onCursorHide()
            Log.d(TAG, ">>> cursor cancel")
        }
        resetPoseState()
    }

    private fun triggerPose(pose: String, now: Long) {
        lastGestureTime = now
        poseLatched = true
        clearHistories()
        lastDebugState = "HIT $pose"
        Log.d(TAG, ">>> GESTURE: $pose")
        gestureCallback.onGestureDetected(pose)
    }

    private fun saveTipRefs(hand: List<NormalizedLandmark>, now: Long) {
        for (k in TIP_IDX.indices) {
            val li = TIP_IDX[k]
            wTipRefX[k] = hand[li].x()
            wTipRefY[k] = hand[li].y()
        }
        wTipRefWristX = hand[0].x()
        wTipRefWristY = hand[0].y()
        wTipLastAt = now
    }

    private fun resetPoseState() {
        poseCandidate = null
        poseCount = 0
        poseLatched = false
    }

    private fun pixelDist(a: NormalizedLandmark, b: NormalizedLandmark, sx: Float, sy: Float): Float =
        pixelDist(a.x(), a.y(), b.x(), b.y(), sx, sy)

    private fun pixelDist(ax: Float, ay: Float, bx: Float, by: Float, sx: Float, sy: Float): Float {
        val dx = (ax - bx) * sx
        val dy = (ay - by) * sy
        return Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
    }

    private fun trigger(
        direction: String,
        now: Long,
        startX: Float,
        startY: Float,
        dx: Float,
        dy: Float,
        timeSpan: Long,
        cons: Float
    ) {
        lastGestureTime = now
        awaitingReset = true
        originX = startX
        originY = startY
        resetSince = now
        clearHistories()
        lastDebugState = "HIT $direction"
        Log.d(TAG, ">>> GESTURE: $direction dx=${fmt(dx)} dy=${fmt(dy)} t=${timeSpan}ms c=${fmt(cons)}")
        gestureCallback.onGestureDetected(direction)
    }

    private fun clearHistories() {
        xHistory.clear()
        yHistory.clear()
        tHistory.clear()
    }

    private fun fmt(v: Float) = String.format("%+.2f", v)

    private fun fmt3(v: Float) = String.format("%.3f", v)

    fun stop() {
        stopped = true
        try {
            handLandmarker?.close()
        } catch (e: Exception) {
            // 推理回调可能仍在跑，close 竞争时忽略
            Log.w(TAG, "close landmarker failed", e)
        }
        handLandmarker = null
        // 不 recycle outBitmap：相机分析线程可能正在 toBitmapRotateMirror 写入它，
        // 回收会与 setPixels 竞态崩溃；~196KB 交给 GC 即可
        pendingBitmap = null
        outBitmap = null
        argbBuffer = null
        clearHistories()
        awaitingReset = false
        resetPoseState()
        cancelCursor()
        activeMode = false
        lastHandSeenAt = 0L
        idleFrameCount = 0
        lastArrivalAt = 0L
        arrivalGapEma = 0f
        lastVelPeak = 0f
        lastVelMed = 0f
        lastResult = null
        lastDx = 0f
        lastDy = 0f
        lastDebugState = ""
        wState = WState.IDLE
        wDir = 0
        wLastDir = 0
        wDwellFrames = 0
        wAnchorX = 0f
        wAnchorY = 0f
        wDwellStartX = 0f
        wDwellStartY = 0f
        wStateSince = 0L
        wLastFireAt = 0L
        wFacing = F_NONE
        wAxis = A_NONE
        wProj = 0f
        wDStep = 0f
        wSpanPx = 0f
        wSpanPrevPx = 0f
        wLumaNorm = 0f
        wPrevNx = 0f
        wPrevNy = 0f
        wPrevNz = 0f
        wHasPrevN = false
        wLastProjForVel = 0f
        wLastProjAt = 0L
        wMaxProj = 0f
        wFlipPeak = 0f
        wTipSpeedPeak = 0f
        wTipSpeedNormPeak = 0f
        wTipSpeedNormW = 0f
        wSlowRejectLogged = false
        wTipSampled = false
        wFastRun = 0
        wSwitchDirFrames = 0
        wPoseFrames = 0
        wDwellMissFrames = 0
        wFiredMoveAt = 0L
        wRearmAt = 0L
        wDirStable = 0
        wAxisFlipped = false
        wFarFrames = 0
        wCloseFrames = 0
        lastErrorAt = 0L
        lastDwellStep = 0
        palmLogCount = 0
        wasHandPresent = false
        lumaSum = 0L
        lumaCount = 0L
        meanLuma = 0f
        lastWaveState = "off"
        lastWaveFacing = "--"
        lastWaveAxis = "--"
        lastWaveDir = 0
        lastWaveProj = 0f
        lastWaveDwell = 0
        lastWaveLuma = 0f
        lastWaveSpan = 0f
    }
}
