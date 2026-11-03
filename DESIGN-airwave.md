# 隔空挥动（AirWave）设计方案 v1.3 — 官方规格对齐 + 回程消除 + 慢挥/跳变防护

> 目标：用华为/小米官方「隔空手势」规格取代旧的掌朝向 + 三维径向挥手通道，仅替换**上下**滑动；左右滑动沿用现有位移判定。
> 状态：**v1.3 已实施、装机实测并完成阈值标定**（2026-10-17：近/远 × 正常/慢速 四象限验证通过，无误触发；见 §7）
> 2026-10-30 代码审查修订：多手改为**纯中心优先**（`setNumHands(2)`）、远距门持续 800ms 弹一次 error 6、CameraManager 暂停/fps/ImageProxy 加固；改动**待装机回归**。
> 2026-10-30 补充修复（v1.1.1）：多手时 `worldLandmarks` / `handedness` 改为与主手**同索引**取值（此前固定 `[0]`），修复双 hands 下挥动/朝向误用另一只手导致的左右手手感不一致。
> 2026-11-03 开源整理：新增 LICENSE / PRIVACY / NOTICE、代码头 SPDX 署名、APK 内作者记号（assets + BuildConfig）；手势逻辑未改。
> 2026-11-03 v1.1.2：挥动方向改为食/中/环三指单位方向平均 + 平均长度；上一段速度阈值 9.8 且要求连续 2 个样本；详见 `AirGesture-HANDOFF.md` §18.9。
> 范围：**只改 mi14**（mi9 不同步）。旧的纵向位移通道（`V_THRESHOLD`）已彻底移除。
> 基线：`HandGestureDetector.kt`（v1 重写 → v1.3 加固）/ `AirGestureService.kt` / `WaveHintOverlay.kt`（顶部胶囊；mi14 已删 `FloatingBall.kt`/`AIKeyReceiver.kt`）

---

## 1. 官方规格（图片 + 视频实测一致，权威）

| 姿态 | 停顿后 | 挥手方向 | 结果 |
|---|---|---|---|
| 掌心朝屏幕 + 指尖朝上 | 顶部出现 👋 | **向下挥** | 内容下移（上一段 prev） |
| 手背朝屏幕 + 指尖朝下 | 顶部出现 👋 | **向上挥** | 内容上移（下一段 next） |

**通用规则：挥手方向 = 指尖方向的反方向。** 20-40cm，光线充足。

视频验证（cv2 光流 + 肤色跟踪）：
- 掌心 + 指尖上 → 向下挥 → 内容下移 ×3（t2.97/4.41/6.15，延迟 0-0.05s）
- 手背 + 指尖下 → 向上挥 → 内容上移 ×4（t12.25/13.94/15.67/17.36，延迟 0-0.2s）
- 反向挥无效

### 1.1 v1 相对 v0.2 的取舍（历史，仍成立）

| 项 | v0.2（旧，已废弃） | v1+（现） | 理由 |
|---|---|---|---|
| 方向来源 | `dir = sign(nPalm.y)`（掌朝上下） | 指尖轴象限（v1.2 起不再依赖 facing 硬门槛） | 对齐官方「指尖方向」；解决近距/遮挡下 facing 抖动不 arm |
| 挥动检测 | 三维径向脉冲 `hypot(dx,dy,K·dz)`，先远后回 | 沿**允许方向**的 2D 投影位移（指尖-腕向量变化量） | 官方规格是指定方向挥手，非径向 |
| 纵向平移通道 | `V_THRESHOLD` 位移判定 + ARMED 抑制 | **彻底移除** | 用户决定：只替换上下，不留旧通道 |
| 横向平移通道 | 不变 | **不变** | 用户决定：左右保持 `H_THRESHOLD` 0.15/0.18 |

---

## 2. 信号层

### 2.1 掌/背朝向（v1.2 起：图像平面叉积；仅图标 + 调试）

```
iX,iY = 腕0→食指MCP5 向量；tX,tY = 腕0→拇指尖4 向量（图像坐标，镜像后，×outW/H）
crossNorm = (iX*tY - iY*tX) / (|i|·|t|) * hSign
facing = PALM 若 crossNorm·FACE_X_SIGN >  FACE_X_MIN
       = BACK 若 crossNorm·FACE_X_SIGN < -FACE_X_MIN
       = NONE 否则
```

- `FACE_X_SIGN = +1f`（图标反了翻此号）、`FACE_X_MIN = 0.30f`。实测稳定（§7），无需再校准。
- 该信号**只用于顶部图标显示与调试**，不做 arm 门槛：拇指遮挡时只影响图标，不影响手势（v1.2 起武装只看指尖轴）。
- 掌法向链（`NORMAL_SIGN` 左/右手修正）仍用于停留稳定性 `ANG_STEP_MAX`（`wDStep`）。

### 2.2 指尖轴（图像坐标，镜像后）

```
ax = (hand[12].x - hand[0].x) * outWidth   // 中指尖 - 腕
ay = (hand[12].y - hand[0].y) * outHeight
```

象限判定（`AXIS_MIN` 门槛保证长度可信，`AXIS_DOM` 保证主方向占比）：
- `|ay| ≥ |ax|·AXIS_DOM` → `UP`(ay<0) / `DOWN`(ay>0)
- `|ax| ≥ |ay|·AXIS_DOM` → `RIGHT`(ax>0) / `LEFT`(ax<0)
- 否则 `A_NONE`

### 2.3 指尖轴 → 允许挥手方向（`wDir`）

```
axis == UP   → W_DOWN  // 指尖朝上 → 只允许向下挥（prev）——对应官方「掌心朝屏+指尖上」态
axis == DOWN → W_UP    // 指尖朝下 → 只允许向上挥（next）——对应官方「手背朝屏+指尖下」态
else         → 0       // 轴不明确，不 dwell
```

> v1.2 起掌/背（facing）不再参与 arm 判定（`facing=--` 也能正常武装，日志可证）。

---

## 3. 手势状态机（v1.2 回程消除 + v1.3 防护）

```
        IDLE
         │ 指尖轴合法（UP/DOWN）且停留稳定：连续 DWELL_FRAMES(5) 帧，
         │ 期间掌心（腕0+食指MCP5+小指MCP17 三点均值）漂移 < DWELL_MOVE_MAX(0.12)
         ▼
        ARMED(wDir)      anchor = 指尖-腕向量（整手平移不改变向量 → 不误触发）
         │ 跳变防护通过（§4.1）且：wMaxProj ≥ WAVE_R(0.06) ∧ 轴已翻转 ∧ 方向稳定 ≥2 帧
         │ ∧ tipVn 峰值 ≥ WAVE_MIN_TIPVN(9.0)     ← 不达标只记 slow_reject 一次，不触发
         ▼
        FIRED
         ├─① 回程：投影回落 < WAVE_REARM_R(0.04) → 同向 rearm（回程不算触发，连挥循环）
         ├─② 停顿换向：末姿势静止 ≥ WAVE_SWITCH_HOLD_MS(900ms) 且轴翻向反侧且仍在峰值附近
         │      → 以反方向 switch-rearm（后挥按新方向判定）
         └─③ 超窗：> FIRED_WINDOW_MS(1600ms) → disarm 回 IDLE
```

- **方向映射**：`W_DOWN` → `"prev"`（内容下移 → `performSwipeDown()`）；`W_UP` → `"next"`（内容上移 → `performSwipeUp()`）。
- **arm 后最小武装时间** `WAVE_MIN_ARM_MS=200ms`：rearm 后 200ms 内不判定，防回程被吞成下一次触发。
- **方向锁定** `WAVE_COMMIT_R=0.03`：投影过半程后武装方向锁定（挥动中指尖轴自然翻转不再清方向稳定）。
- **中断**：握拳/捏合/无效姿势连续 `POSE_KILL_FRAMES=4` 帧、光线不足、手离开、超时 `WAVE_TIMEOUT_MS=3000`。
- **「停顿」判据**：停留期掌心漂移 > `DWELL_MOVE_MAX` 重置；姿态闪断有 `DWELL_MISS_MAX=3` 帧宽限；掌法向帧间角变化 `wDStep` 需 < `ANG_STEP_MAX=12°`。
- **回程 vs 停顿换向的区分**：FIRED 后跟踪「指尖-腕向量」帧间位移 < `FIRED_HOLD_EPS=0.02`（画面宽）持续累计静止时长——回程是连续运动（几帧内回落→路径①），停顿换向是停住 ≥900ms 后轴翻转（路径②）。

---

## 4. 门控与防护（v1.3）

- **光线**：`LUMA_MIN_NORM = 0.12f`（Y 为 limited range 16..235，已归一化），`toBitmapRotateMirror` 循环内顺带累加。
- **距离**：`spanNorm = wSpanPx / outWidth`（wSpanPx = 腕0-中指MCP9 距离像素）。实测正常臂展 span 0.23-0.36；挥动中 2D 透视缩短会瞬时塌缩，故**距离纠错只在 DWELL 稳定停留期检查**、连续 `SPAN_CONFIRM_FRAMES=3` 帧才报。
- **全局远距门**（2026-10-19）：`spanNorm < HAND_MIN_SPAN(0.10)`（≈70-90cm 外，由 20cm→0.36 / 40cm→0.23 实测外推）持续超宽容帧 → 视为无手（不唤醒功耗/不出光标/不触发任何手势）；active 且非光标态容忍 `FAR_GRACE_FRAMES(4)` 帧瞬态（挥动中 2D 透视缩短硬卡会误杀）。持续远距 `FAR_HINT_DELAY_MS(800)` 后弹一次 error 6「请靠近一些」，每次远距只弹一次。见 HANDOFF §15。
- **多手选择**（2026-10-30 改为纯中心优先）：`setNumHands(2)` 让 MediaPipe 最多返回两只手；多手时选**掌心离画面中心最近**的一只作为主手，跨度不参与选择（只交给远距门控）。原「中心 + 近距」打分、连续性加分/豁免已移除。主手选择返回**索引**，`landmarks` / `worldLandmarks` / `handedness` 三组数组始终用同一索引，禁止回退到 `[0]`。
- **纠错码**（对齐官方 HAL error 1/5/6/7，`RecognitionCallback.onError(code)` + 500ms 检测侧防抖 + 250ms UI 防抖）：

| code | 官方语义 | 触发条件 | 胶囊文案 |
|---|---|---|---|
| 1 | 方向反 | ARMED 后反向投影 < `-WAVE_OPPOSITE_R(0.30)` 且 < -正向峰值×2，**且**反向朝向翻转速率 ≥ `WAVE_WRONG_DIR_FLIP(240°/s)` 连续 `WAVE_WRONG_DIR_GRACE(3)` 帧 | 方向反了 |
| 5 | 太近 | DWELL 期 `spanNorm > SPAN_CLOSE_MAX(0.55)` 连续 3 帧 | 离太近了 |
| 6 | 太远 | DWELL 期 `spanNorm < SPAN_FAR_MIN(0.05)` 连续 3 帧 | 离太远了 |
| 7 | 太快 | ARMED 后投影速度 > `WAVE_VEL_MAX(6.0)` 且正向峰值未达 `WAVE_R` | 动作太快 |

> 错误类 disarm（`too_close/too_far/wrong_dir/too_fast`）不触发 `waveHint.hide()`，纠错胶囊由自身 2s 自动消失兜底。
> error 1 双条件（位移 + 朝向翻转速率）意义：被动回程惯性尾/起手回拉是「指尖沿自身指向进退」，朝向角几乎不变 → 不计；只有真正主动甩腕换向才报。

### 4.1 慢挥 / 跟踪跳变防护（v1.3 三道防线）

| 防护 | 常量 | 判据与依据 |
|---|---|---|
| 单帧投影速度跳变（静默作废武装 `jump`） | `WAVE_JUMP_VEL_MAX = 15.0` | 慢晃中跟踪器「重解释」手部朝向 → 单帧假跳 0.7+ 画宽（≈19 宽/s）；真实挥动实测 ≤6 |
| 手尺寸单帧跳变（静默作废武装 `span_jump`） | `WAVE_SPAN_JUMP_R = 0.80` | 手翻转经过侧向时真实 2D 投影收缩实测最多 ~57%/帧；跟踪器重解释 ≥100%（实测 125%，28→63px）。0.80 只拦真重解释 |
| 触发最低归一化速率（`slow_reject` 只记日志） | `WAVE_MIN_TIPVN = 9.0` | `tipVn = tipV×outWidth/spanPx`（手宽/s，**距离不敏感**）。标定见 §7：故意慢速 ≤7.94（近 5.99 / 远 7.94）、正常挥 ≥10.5（最低通过 9.30）。取代旧原始速率门槛 1.3 宽/s（远景会踩线） |
| 反向闸门（→ error 1） | `WAVE_WRONG_DIR_FLIP = 240` | 见 §4 纠错表 |

> **5 指速率采样器**：每 ~100ms 取 5 指指尖（TIP_IDX = 4/8/12/16/20）建立「指尖相对腕向量」，`tipV` = 5 指速率中位（画面宽/s）；`flipV` = 5 指朝向角速度中位（`dθ=atan2(cross,dot)`，符号按归一化纵向分量变化方向）；采样间隔 >250ms 只重采样不计算。`flipV` 仅用于主动/被动判定与日志（慢/正常峰值无可分性，**不做触发门槛**）。
> **重要**：`resetPoseState()` 会被主循环每帧调用——采样状态不可在其中清零（会饿死采样器），采样状态只在挥手状态机内部 resets 维护。

---

## 5. UI

| 组件 | 改动 |
|---|---|
| `FloatingBall.kt` | **已删除**（mi14；顶部胶囊取代悬浮球） |
| `WaveHintOverlay.kt` | 屏幕顶部中央胶囊：**武装态**单图标（掌心图标；手背时同图标 180° 翻转）；**纠错态**图标 + 文案（见 §4）；180ms 缩放入场/150ms 缩放退场动画、250ms 纠错防抖、**3000ms 自动隐藏** |
| `MainActivity.kt` | 调试行改用 `lastWaveState/Facing/Axis/Dir/Proj` 字段 |
| `AirGestureService.kt` | `onError(code)` → `waveHint.showError(code)`；`onDisarm(reason)` 对 `ERROR_DISARM_REASONS`（too_close/too_far/wrong_dir/too_fast）跳过 `hide()`；jump/span_jump/slow_reject 全程静默 |

---

## 6. 参数表（`HandGestureDetector.kt` companion，2026-10-17 实测值）

```kotlin
// 掌/背（图标）/轴向
FACE_X_SIGN      = +1f     // 图像平面叉积符号（图标反了翻此号）— 实测稳定
FACE_X_MIN       = 0.30f   // |crossNorm| 门槛
NORMAL_SIGN      = +1f     // 掌法向符号（左/右手修正，wDStep 用）
ANG_STEP_MAX     = 12.0f   // 停留期法向帧间角变化上限（度）
DWELL_FRAMES     = 5       // 连续停留帧数（≈0.2-0.3s）
DWELL_MOVE_MAX   = 0.12f   // 停留期掌心漂移上限（画面宽；0.06 太严，手微抖即重置）
DWELL_MISS_MAX   = 3       // dwell 期姿态闪断宽限帧
AXIS_MIN         = 0.10f   // 指尖轴最短长度
AXIS_DOM         = 1.3f    // 指尖轴主方向占比
LUMA_MIN_NORM    = 0.12f   // 光线下限（归一化）
POSE_KILL_FRAMES = 4       // 握拳/捏合连续帧数才解除武装
// 挥动状态机
WAVE_R           = 0.06f   // 触发投影位移（指尖相对腕向量变化）
WAVE_COMMIT_R    = 0.03f   // 正值过半后锁定武装方向
WAVE_REARM_R     = 0.04f   // 回程静默 rearm 回落阈值
WAVE_COOLDOWN_MS = 120L    // （v1.2 后配合 rearm 直通使用）
WAVE_MIN_ARM_MS  = 200L    // rearm 后最小武装时间（回程不被吞）
WAVE_DIR_STABLE  = 2       // 触发需连续 N 帧方向稳定
WAVE_TIMEOUT_MS  = 3000L   // ARMED 超时
FIRED_WINDOW_MS  = 1600L   // FIRED 总窗口（> SWITCH_HOLD，防超时抢先）
WAVE_SWITCH_HOLD_MS = 900L // 停顿换向静止时长
FIRED_HOLD_EPS   = 0.02f   // FIRED 静止判定帧间位移（画面宽）
// 纠错码
SPAN_CLOSE_MAX   = 0.55f   // 太近（DWELL 期，连续 3 帧；实测正常 0.23-0.36）
SPAN_FAR_MIN     = 0.05f   // 太远（DWELL 期，连续 3 帧）
SPAN_CONFIRM_FRAMES = 3
HAND_MIN_SPAN    = 0.10f   // 全局远距门控（太小=太远→无手；≈70-90cm 外）
FAR_GRACE_FRAMES = 4       // 远距门瞬态宽容帧（active 且非光标态）
FAR_HINT_DELAY_MS = 800L   // 持续远距多久弹一次 error 6（每次远距只弹一次）
WAVE_OPPOSITE_R  = 0.30f   // 反向位移阈值（error 1）
WAVE_WRONG_DIR_FLIP = 240f // 反向朝向翻转速率门槛（deg/s，error 1）
WAVE_WRONG_DIR_GRACE = 3   // 反向连续帧（error 1）
WAVE_VEL_MAX     = 6.0f    // 太快（error 7）
ERROR_MIN_INTERVAL_MS = 500L
// v1.3 防护
WAVE_JUMP_VEL_MAX = 15.0f  // 单帧投影速度上限（宽/s）
WAVE_SPAN_JUMP_R  = 0.80f  // 手尺寸单帧相对变化上限
WAVE_MIN_TIPVN    = 9.0f   // 触发最低归一化速率（手宽/s，距离不敏感）
// 速率采样器
TIP_IDX = intArrayOf(4, 8, 12, 16, 20)  // 5 指指尖
// 采样窗口 ~100ms；间隔 >250ms 只重采样；tipV/flipV 均取 5 指中位
```

---

## 7. 校准记录（已完成）

| 阶段 | 结果 |
|---|---|
| v1.1 五步校准（掌/背、dwell、挥手阈值、左右手、暗光） | ✅ 已完成（`FACE_X_SIGN=+1`；v1.2 起该值仅图标用） |
| v1.2 回程消除实测（连续挥手、静止换向） | ✅ 回程 0 误触；prev×3、next×4、换向通过 |
| v1.3 慢挥/跳变防护标定（近/远 × 正常/慢速，2026-10-17） | ✅ 四象限通过；触发 ×15（prev×9/next×6）零误触；慢速 ×2 全被拦超时自灭；最低正常触发 tipVn 9.30 |

**关键标定数据（2026-10-17）**：

| 指标 | 慢速（拒触发） | 正常（应触发） |
|---|---|---|
| tipVn（手宽/s） | 近 5.99 / 远 7.94（≤7.94） | ≥10.5，累计最低通过 9.30 |
| 触发阈值 | — | `WAVE_MIN_TIPVN = 9.0`（卡在 7.94 与 10.5 之间） |

- 唯一一次 `span_jump` 拦截（17→37px，+118%）为跟踪器真实重解释，拦截正确。
- 慢速被拦后武装由 `WAVE_TIMEOUT_MS=3000` 自灭，全程静默（无错误图标、不打断用户）。

**调试日志参考**（logcat tag `HandGestureDetector`）：
- 触发：`>>> GESTURE: prev by=wave_v1 dir=DOWN proj=.. peakFlip=.. peakTipV=.. peakTipVn=.. span=..px armMs=..`
- 拒触发：`>>> wave slow_reject peakTipV=.. peakTipVn=.. span=..px proj=.. max=..`
- 防护：`>>> wave jump vel=.. proj=.. span=..px` / `>>> wave span_jump 17->37px`
- 纠错：`>>> wrong_dir detail proj=.. flipV=.. tipV=..`
- 周期：palm 状态行含 `tipV=/tipVn=/vy=/flipV=`

---

## 8. 代码改动清单（v1.1 → v1.3）

| 文件 | 改动 |
|---|---|
| `detector/HandGestureDetector.kt` | v1：移除 `V_THRESHOLD`/`AIRWAVE_CALIBRATION`/`radialDist`/`airwaveSuppressVertical`；facing/axis/wDir + 方向投影状态机；发 `"next"/"prev"`。v1.1：`RecognitionCallback.onError(code)` + 距离/方向/速度纠错 + `reportError` 防抖。v1.2：FIRED 双通道 rearm（静默回程 `rearmWave` + 停顿换向 switch）+ COMMIT/MIN_ARM/DIR_STABLE；武装只看指尖轴；facing 改图像平面叉积（`FACE_X_SIGN/FACE_X_MIN`）；锚点从掌心改指尖-腕向量。v1.3：5 指速率采样器（tipV/flipV/tipVn）+ 三道防护（jump/span_jump/slow_reject）+ error1 翻转速率闸门 + 全量日志 |
| `service/AirGestureService.kt` | 接线 `WaveHintOverlay`；`onGestureDetected` 移除 `"up"/"down"` 死分发；`onError` → `waveHint.showError`；`ERROR_DISARM_REASONS` 跳过 `hide()` |
| `service/FloatingBall.kt` / `service/AIKeyReceiver.kt` | **已删除**（mi14；悬浮球冗余，AI 键仅 mi9 有意义） |
| `service/WaveHintOverlay.kt` | v1.1 重写为顶部胶囊；v1.2 精简为单图标（手背 = 图标 180°）+ 3000ms 自动隐藏 |
| `MainActivity.kt` | 调试行适配新字段 |
| `executor/GestureExecutor.kt` | **不动**（复用 `performSwipeUp/Down`，SWIPE_MS=120/PAGE_MS=150/INTERVAL_MS=200） |

---

## 9. 风险

| 风险 | 应对 |
|---|---|
| ~~FACING_SIGN 未校准~~（已解决） | v1.2 起武装只看指尖轴，facing 仅图标，天然 fail-safe |
| 跟踪器重解释（慢晃中假翻转） | v1.3 双跳变守卫（jump/span_jump）+ tipVn 闸门；极端单帧小跳变或穿过守卫但不足以同时满足全部触发条件 |
| 慢速挥动误触 | tipVn 闸门 9.0（远近归一化）；若再遇边缘案例：加「连续 2 采样达标」持续性闸门、采样 100→60ms |
| 指尖轴在手旋转中间态误判 | `AXIS_MIN` + `AXIS_DOM` 双门控 |
| 停顿判据过严/过松 | `DWELL_MOVE_MAX=0.12` 实测合适；可调 |
| 连续挥手/换向误触 | v1.2 FIRED 双通道 rearm + `WAVE_MIN_ARM_MS`；静止换向走 fired_window→dwell（~2s），未单独实测触发过 |
| 手势精度压测未做 | 10 项压测（含挥动中关闭手势）待补 |

---

## 10. 2026-10-19 后续改动（非挥动；细节见 `AirGesture-HANDOFF.md` §15）

- OK 手势（返回）判定修复：旧「接近即触发」（`0.35×handLen`）→ `okRatio<0.20` 且中/环/小指全伸展（`OK_TOUCH_RATIO`）。
- 握拳/点击严格化：`FIST_MIN_CURLED 3→5`（含拇指内收 `THUMB_TO_FINGER_RATIO=0.40`）、点击出口 3 帧消抖（`OPEN_CONFIRM_FRAMES`）。
- 熄屏暂停：手势模式期间 SCREEN_OFF 解绑相机（暂停检测）/ SCREEN_ON 重绑恢复；2026-10-19 实测通过。
- APK 体积：`app/build.gradle` 加 `ndk { abiFilters 'arm64-v8a' }`，删除 x86/x86_64/v7a 库（35.2→19.6MB），2026-10-19 装机验证正常（见 HANDOFF §15）。
- 均未触碰挥动信号、参数与状态机（§2/§3/§6 不变）。
