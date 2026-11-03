# AirGesture (Mi 14 Pro) 开发进度记录

> 记录日期：2026-09-28 | 版本：**v1.2.0** | 安装包：`apks/`（本记录对应的历史包已清理，目录内仅留最新装机包）

## 本版本交付（v1.1.7 → v1.2.0）

### 1. 握拳光标点击（新交互）
- 握拳（帧锁存 8 帧）→ 光标出现；握拳保持 → 光标跟随手掌移动；张开（保持 ≥300ms）→ 点击准星位置
- 光标形态：**十字准星**（蓝底白芯，臂长 14dp）+ 中心 3dp 蓝点；点击点=准星中心；点击时 280ms 涟漪
- 覆盖全屏：映射增益 1.7（`CURSOR_GAIN`），掌心小幅移动可到四角
- 拇指张开/捏合/边缘/快速松开 → 取消光标（`cancelCursor()`，不触发点击）
- 检测：逐指卷曲距离比 `dist(mcp,tip) < 0.65 × (mcp-pip + pip-tip)`，≥3 指卷曲 = 握拳（替换旧的握拳平均比 0.9）
- 相关：`HandGestureDetector.kt`（CursorCallback + 光标状态机）、`service/CursorOverlay.kt`、`GestureExecutor.performTapAt(nx,ny)`

### 2. 低功耗（Camera2Interop AE 闲置 5fps）
- `CameraManager.kt`：`Camera2Interop.Extender` 设 `CONTROL_AE_TARGET_FPS_RANGE=[5,5]`；`setLowPower()` 主线程重绑（仅状态变化时）
- 档位：0=[5,5]（闲置）/ 1=[5,15] / 2=关闭；闲置自适应抽帧：到达间隔 EMA>150ms → 每帧都推理
- 手势模式启动即进低功耗档（修复：钩子只在状态转换触发，首次见手前 AE 不生效的缺陷）
- **实测（180s 采样）**：

| 状态 | 进程 CPU | 温度 | 误触发 |
|---|---|---|---|
| 手势关闭 | 0.16% | — | — |
| 开启·闲置（改前 30fps） | 28.9% / 10min | +1.4°C | 0 |
| 开启·闲置（AE 5fps 后） | **19.6%** | 36.5→36.1°C | 0 |
| 开启·活跃（有手） | 70.7% | — | — |

- 闲置到达帧间隔日志：`arrival avgGap=199ms lowPower=true`（AE 生效确认）

### 3. 图标 / UI 重做（Material 实心风）
- 全部图标手写矢量：`ic_gesture`（启动图标：深色圆角方 + 蓝圆 + 白色 front_hand）、`ic_hand/ic_arrow_v/ic_arrow_h`（手势说明）、`ic_fist`、`ic_pinch`、`ic_arrow_up/down/left/right`（实心）、`ic_notification`
- `activity_main.xml` 重写：头部 + 开关卡 + 状态卡 + 手势说明 2×2；状态栏沉浸（ViewCompat insets）；`status_dot.xml`
- 悬浮球：ImageView `ballIcon`，方向闪烁改为图标闪现（`FloatingBall.flashDirection`，click → ic_fist）
- 来源：Material Icons filled（swap_vert/swap_horiz/front_hand/arrow_*）、Material Symbols `pinch`、Font Awesome `hand-fist`（**CC BY 4.0，README 开源发布须署名**）

### 4. 手势阈值调整
- 左滑阈值 **0.15**（`H_THRESHOLD_LEFT`）/ 右滑 0.18（反馈左滑偏钝）
- 常量表（HandGestureDetector.kt companion）：V=0.20、H=0.18、MIN_TIME=120、MAX_TIME=600、MIN_SAMPLES=5、COOLDOWN_MS=850、AXIS_DOMINANCE=1.4、CONSISTENCY_MIN=0.65、PEAK_END_RATIO=0.7、FIST_CURL_RATIO=0.65、FIST_MIN_FRAMES=8、FIST_HOLD_MS=300、CURSOR_EMA=0.4、CURSOR_GAIN=1.7、SLOW_ARRIVAL_GAP_MS=150、PINCH_RATIO=0.35、IDLE_PROCESS_EVERY=6
- 移除了多帧确认（单帧 + 速度剖面 + 位移一致性门控），误触发 0 保持

## 已验证 ✅ / 待办 ⬜

- ✅ 闲置 CPU <1%（关手势 0.16%）、闲置 0 误触发、AE 5fps 生效、光标点击可用（日志确认多次 cursor show→click）
- ⬜ 用户手感测试：准星观感/点击命中、四角覆盖、左滑跟手、握拳误检（新卷曲算法）
- ⬜ 10 手势精度 ≥9/10、发热复测、README 重写（现内容仍是 Mi9 旧版！含 FA 署名）、Mi 9 装机验证
- ⬜ `versionName` 仍是 "1.1.0-mi14"（versionCode 5），发版靠文件名手动递增，正式发版前应更新
- ⬜ 待整理候选（未动）：项目根 `module.prop`/`config.sh`/`test_hand_detect.py` 疑为 Mi9 root 时代遗留
- ✅ 安装包统一归档到 `apks/`（2026-10-28 整合；每机型仅留最新一个）

## 环境备忘

- 构建：`gradlew.bat assembleDebug --no-daemon`（`JAVA_HOME` 指向 JDK 21）
- **每次安装后必须恢复无障碍**（HyperOS 会重置）：
  `settings put secure enabled_accessibility_services "com.airgesture/com.airgesture.service.AirGestureService"` + `accessibility_enabled 1`
- 开关手势模式：广播无效（RECEIVER_NOT_EXPORTED 挡 shell），用 `input tap 1275 598`（主界面开关）或点悬浮球
- 手机有锁屏 PIN，需手动解锁；adb serial 见本机设备列表；截图 `screencap`+`pull`（不能 PowerShell 重定向）
- 主机为 PowerShell：`$x=Func()` 解析报错、字符串不能以 `[` 开头、控制台中文乱码用英文替代
- 手机系统时钟与主机差约 19h（日志显示 09-27，主机 09-28），对时间线判断时注意
- 测试期设过 `svc power stayon true`，结束记得还原
