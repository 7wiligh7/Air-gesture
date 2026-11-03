# AirGesture · 隔空手势（小米 14 Pro）

用**前置摄像头 + MediaPipe** 识别手掌动作，经**无障碍服务**注入滑动 / 点击 / 返回，实现不触碰屏幕的隔空操作。完全**不需要 root**。

- 核心通用：**Android 8.0+（API 26）带前置摄像头**即可运行，不需要 root
- **小米 14 Pro** 是开发与唯一标定机型（HyperOS / Android 14）；其他机型可直接跑，但距离门/光标增益可能需按日志重标

> 作者：**7wiligh7** · 许可证：**MIT** · [隐私说明](PRIVACY.md) · [第三方声明](NOTICE)

---

## 功能

| 手势 | 动作 | 说明 |
|---|---|---|
| 掌心朝屏 + 指尖朝上 → **向下挥** | 上一段（内容下移） | 官方隔空手势规格；顶部出现 👋 后挥动 |
| 手背朝屏 + 指尖朝下 → **向上挥** | 下一段（内容上移） | 同上 |
| 手掌左挥 / 右挥 | 下一页 / 上一页 | 位移判定，需要较大幅度（FOV 限制） |
| **OK 手势**（拇指-食指相触成圈，中/环/小指伸展） | 返回 | |
| **握拳**（5 指参与） | 显示十字准星光标 | 含拇指内收判定，真握拳才响应 |
| 握拳移动 | 光标跟随手掌移动 | 增益 1.7，可覆盖全屏四角 |
| 张开手指（保持 ≥300ms，连续 3 帧确认） | 点击准星位置 | 防抖动误点 |

辅助机制：

- **顶部胶囊提示**：武装态 👋 / 纠错提示（太近 / 太远 / 方向反 / 太快）
- **距离门控**：手太小（约 70-90cm 外）视为无手——不唤醒、不出光标、不触发任何手势
- **多手选择**：多手同时出现时，离画面中心最近的手优先（纯中心优先）
- **低功耗**：闲置相机 5fps、见手自动全速、熄屏自动暂停、关闭模式近乎零耗电

## 工作原理

```
前置相机 256×192（仅分析流，无预览）
  → MediaPipe Hand Landmarker（GPU delegate，21 关键点）
  → HandGestureDetector（姿态分类 + 挥动状态机 + 光标状态机）
  → AirGestureService（无障碍服务）
  → GestureExecutor（GestureDescription 注入滑动 / 点击 / 返回）
```

- 纯 **2D 视觉**识别，无深度传感器；「距离」用掌心跨度（腕→中指根）近似，近大远小
- 手势全部由系统无障碍 API 注入，无 root、无 Xposed

## 环境要求

- 手机：小米 14 Pro（Android 14 / HyperOS）——本项目唯一实测机型
- 权限：相机、悬浮窗、通知；无障碍服务
- 构建：JDK 21、Android SDK 35；Gradle 8.9（wrapper 自带）

## 构建

```powershell
# 项目根目录执行（JAVA_HOME 必须指向 JDK 21，Java 25 不被 Gradle 8.9 支持）
gradlew.bat assembleDebug --no-daemon
# 产物：app\build\outputs\apk\debug\app-debug.apk（arm64-v8a，约 19.6MB）
```

> 已配置 `abiFilters 'arm64-v8a'`：APK 只含真机架构（35.2MB → 19.6MB）；代价是 x86 模拟器装不了。

## 安装与启用

1. 安装 APK：

   ```powershell
   adb install -r app-debug.apk
   ```

2. 打开 App，按引导授予相机 / 悬浮窗权限
3. 开启无障碍（二选一）：
   - 手动：设置 → 辅助功能 → 无障碍 → AirGesture → 开启
   - adb（Mi14 普通 shell 即可）：

     ```powershell
     adb shell settings put secure enabled_accessibility_services com.airgesture/com.airgesture.service.AirGestureService
     adb shell settings put secure accessibility_enabled 1
     ```

4. 打开 App 的「手势模式」开关，通知栏出现服务通知即生效
5. 保活（HyperOS）：省电策略 → **无限制**；自启动 → **允许**；最近任务 → **下拉加锁**

> 系统覆盖安装后 HyperOS 可能重置无障碍服务——按第 3 步重新开启即可（多数情况不会被重置）。

## 使用建议

- 手距 **20-40cm**（官方规格）；超过约 **70-90cm** 会被当作「没有手」忽略
- 光线充足、背景简洁效果最好；强逆光会降低识别率
- 挥动前先停顿，等顶部出现 👋（武装）再挥；反向挥不触发
- 左右滑动需要较大幅度（前摄 FOV 限制，手掌在画面内可移动范围有限）

## 性能实测（Mi14）

| 状态 | CPU | 备注 |
|---|---|---|
| 手势模式关闭 | 0.16% | 进程几乎不耗电 |
| 开启 · 闲置 | ≈19.6% | 相机 AE 5fps |
| 开启 · 活跃（画面有手） | ≈70.7% | 全帧率推理，25-30fps 处理 |

推理单帧 18-20ms（GPU delegate）。熄屏后相机与推理完全停止。

## 目录结构

```
app/src/main/java/com/airgesture/
├── MainActivity.kt              主界面：权限引导 / 手势模式开关 / 调试状态行
├── camera/CameraManager.kt      CameraX 采集（仅分析流 256×192、低功耗 AE、熄屏暂停）
├── detector/
│   ├── HandGestureDetector.kt   核心：姿态分类、挥动状态机、光标状态机、多手/远距门控
│   └── HandOverlayView.kt       App 内骨骼调试预览（无相机画面）
├── service/
│   ├── AirGestureService.kt     无障碍服务：生命周期、通知、屏幕广播、手势分发
│   ├── WaveHintOverlay.kt       顶部 👋 武装 / 纠错胶囊
│   └── CursorOverlay.kt         十字准星光标
└── executor/GestureExecutor.kt  无障碍手势注入（滑动 / 点击 / 返回）
```

## 调试

```powershell
adb logcat -s HandGestureDetector:D CameraManager:D AirGestureService:D
```

| 日志 | 含义 |
|---|---|
| `>>> GESTURE: prev/next ...` | 挥手触发（含 tipVn / span 等校准量） |
| `>>> wave slow_reject ...` | 慢挥被速率闸门拦截（正常，不触发） |
| `>>> far_ignore span=..px norm=..` | 手太远被忽略 |
| `>>> multi_hand n=2 [0]Right/0.99 d=0.12 [1]Left/0.98 d=0.20 pick=0` | 多手时选中离画面中心最近的主手（附每只手 handedness 标签/置信度/中心距） |
| `>>> ok r=.. others=..` | OK 手势校准（r<0.20 且 others=0 才成立） |
| `>>> fist curl=X/5 t=..` | 握拳校准（5 指参与） |
| `AE target fps range=[5, 5]` | 低功耗 5fps 生效 |
| `Camera paused / resumed (screen off/on)` | 熄屏暂停 / 亮屏恢复 |

## 兼容性

- **Mi14 Pro**：✅ 主力开发与实测（本仓库）
- **Mi 9**：独立项目 `AirGesture\mi9`（参数不同，无 AirWave v1.3 挥动）
- 其他机型：未适配；理论上 Android 8.0+ 带前摄可用（**仅 arm64-v8a**；Android 8/9 的 `startForeground` 兼容已修，但尚未在 8/9 真机验证）

## 隐私

- 未申请 `INTERNET` 权限：应用在系统层面**无法联网**
- 相机画面仅用于本地 MediaPipe 推理，不录制、不保存、不上传
- 无障碍服务只注入手势，不读取窗口内容、不监听按键

完整说明见 [PRIVACY.md](PRIVACY.md)。

## 第三方组件与致谢

| 组件 | 用途 | 许可 |
|---|---|---|
| MediaPipe Tasks Vision 0.10.18 | 手部关键点检测 | Apache 2.0 |
| CameraX / AndroidX | 相机与基础框架 | Apache 2.0 |
| Material Icons | 界面图标 | Apache 2.0 |
| Font Awesome `hand-fist` | 握拳图标 | **CC BY 4.0** |

## 版本

- **1.1.1-mi14**（versionCode 6）
  - 多手索引修复：`worldLandmarks` / `handedness` 与主手同索引，修复左右手感不一致
  - 日志增强：`multi_hand` 输出每只手标签/置信度，`wave` / `wave arm` 输出 `hand=`
  - 开源整理：MIT LICENSE、隐私说明、第三方声明、代码头 SPDX 署名、APK 内作者记号（`assets/AUTHORSHIP.txt` + `BuildConfig.AUTHOR/BUILD_TIME/GIT_COMMIT`）、`.gitignore`
- **1.1.0-mi14**（versionCode 5）
  - 隔空挥动 v1.3：官方规格对齐、回程消除、慢挥 / 跟踪跳变三防护
  - 2026-10-19：OK 手势判定收紧、握拳 5 指严格化 + 点击消抖、熄屏暂停相机、远距门控、多手选择、APK 仅 arm64（19.6MB）
  - 2026-10-30 代码审查修复：多手纯中心优先（双 hands）、远距 0.8s 提示、前台服务按需启动 + Android 8/9 兼容、广播收紧、删除 RootKeyMonitor、CameraManager/悬浮窗/无障碍加固（待装机回归）
  - 历史：v1.2.0 握拳光标点击、AE 5fps 低功耗、Material 图标与主界面重做

## 文档

- `DESIGN-airwave.md` — 隔空挥动设计 v1.3（状态机 / 参数 / 校准记录）
- `PROGRESS.md` — v1.2.0 交付记录（历史）
- `PRIVACY.md` / `NOTICE` — 隐私说明与第三方许可
- `AirGesture-HANDOFF.md` — 开发交接文档（本地维护，不随仓库发布）

## 版权与许可证

Copyright (c) 2026 **7wiligh7**

本项目以 [MIT License](LICENSE) 开源。第三方组件与图标署名见 [NOTICE](NOTICE)（其中 Font Awesome `hand-fist` 为 CC BY 4.0）。

APK 内包含作者记号：`assets/AUTHORSHIP.txt`，以及主界面底部显示的 `BuildConfig.AUTHOR / BUILD_TIME / GIT_COMMIT`。
