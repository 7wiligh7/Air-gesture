# AirGesture · 隔空手势

用**前置摄像头 + MediaPipe** 识别手掌动作，通过**无障碍服务**注入滑动、点击、返回，实现不碰屏幕的隔空操作。**无需 root**。

> 作者：**7wiligh7** · 许可证：**MIT** · [下载 Releases](https://github.com/7wiligh7/Air-gesture/releases) · [隐私说明](PRIVACY.md) · [第三方声明](NOTICE) · [发布流程](RELEASING.md)

**Android 8.0+** · **arm64-v8a** · 无 root · 无网络权限 · 画面仅本地处理

---

## 特性

- **隔空挥动**：掌心朝屏向下挥 / 手背朝屏向上挥，切换上一段 / 下一段
- **左右翻页**：手掌大幅左右挥动
- **OK 手势**：返回上一级
- **握拳光标**：握拳出现十字准星，移动手掌控制光标，张开手指点击
- **顶部胶囊提示**：武装 👋、距离/方向/速度纠错
- **距离门控**：太远（约 70-90cm 外）视为无手，避免误唤醒
- **多手选择**：多手同时出现时，离画面中心最近的手优先
- **低功耗**：闲置相机 5fps、见手自动全速、熄屏自动暂停
- **完全本地**：不联网、不录制、不保存任何画面

## 手势

| 手势 | 动作 | 说明 |
|---|---|---|
| 掌心朝屏 + 指尖朝上 → **向下挥** | 上一段（内容下移） | 顶部出现 👋 后挥动 |
| 手背朝屏 + 指尖朝下 → **向上挥** | 下一段（内容上移） | 同上 |
| 手掌左挥 / 右挥 | 下一页 / 上一页 | 需要较大幅度（前摄 FOV 限制） |
| **OK 手势** | 返回 | 拇指-食指成圈，其余三指伸展 |
| **握拳** | 显示准星光标 | 5 指参与，真握拳才响应 |
| 握拳移动 | 光标跟随 | 画面中心 30%–70% 映射全屏，手不用贴边 |
| 张开手指 | 点击 | 保持 ≥300ms，连续 3 帧确认 |

## 工作原理

```
前置相机（低分辨率分析流，目标 256×192，不显示画面）
  → MediaPipe Hand Landmarker（GPU delegate，21 关键点）
  → HandGestureDetector（姿态分类 + 挥动状态机 + 光标状态机）
  → AirGestureService（无障碍服务）
  → GestureExecutor（GestureDescription 注入滑动 / 点击 / 返回）
```

- 纯 2D 视觉，无深度传感器；「距离」用掌心跨度（腕→中指根）近似，近大远小
- 所有手势由系统无障碍 API 注入，不使用 root、Xposed 或虚拟屏幕

## 系统要求

| 项目 | 要求 |
|---|---|
| 系统 | Android 8.0（API 26）及以上 |
| 架构 | **arm64-v8a**（APK 不含 x86 / armeabi-v7a） |
| 硬件 | 前置摄像头 |
| 权限 | 相机、悬浮窗、通知；无障碍服务 |
| 标定机型 | **小米 14 Pro**（HyperOS / Android 14） |

## 下载与安装

从本仓库的 **Releases** 页面下载：

- `AirGesture-<version>-arm64-v8a.apk`
- `SHA256SUMS.txt`（校验和）

安装步骤：

1. 允许「未知来源」安装
2. 安装 APK
3. 打开 App，按引导授予**相机 / 悬浮窗 / 通知**权限
4. 开启无障碍服务：
   - 手动：设置 → 辅助功能 → 无障碍 → AirGesture → 开启
   - 或 adb（Mi14 普通 shell 即可）：

     ```powershell
     adb shell settings put secure enabled_accessibility_services com.airgesture/com.airgesture.service.AirGestureService
     adb shell settings put secure accessibility_enabled 1
     ```

5. 打开 App 内的「手势模式」开关，通知栏出现服务通知即生效

> **从 debug 版切换**：debug 与 release 签名不同，无法覆盖安装，需先卸载旧版；重装后无障碍要重新开启。

### 校验 APK

```powershell
# 文件完整性（与 Release 中的 SHA256SUMS.txt 对比）
Get-FileHash AirGesture-*.apk -Algorithm SHA256

# 签名证书
apksigner verify --print-certs AirGesture-*.apk
```

官方 release 证书：

- 主体：`CN=7wiligh7, OU=AirGesture, O=7wiligh7, C=CN`
- SHA-256：`951013058dd41b353e82184346b891a7991715534432810a9cc39661f1c66063`

debug 包使用 `CN=Android Debug` 证书，仅用于开发测试，请勿转载。

## 使用建议

- 手距 **20-40cm**；超过约 **70-90cm** 会被当作「没有手」
- 光线充足、背景简洁效果最好；强逆光会降低识别率
- 挥动前先停顿，等顶部出现 👋（武装）再挥；反向挥不触发
- 左右滑动需要较大幅度（前摄 FOV 限制，手掌在画面内可移动范围有限）
- HyperOS 保活：省电策略 → **无限制**；自启动 → **允许**；最近任务 → **下拉加锁**

## 故障排查

| 现象 | 处理 |
|---|---|
| 覆盖安装失败 | debug / release 证书不同，先卸载旧版再装 |
| 无障碍自动关闭 | HyperOS 覆盖安装后可能重置，去系统设置重新开启 |
| 挥动不触发 | 确认顶部出现 👋；手距 20-40cm；光线充足；动作别太快 |
| 左右滑动难触发 | 前摄 FOV 有限，加大幅度；或先用挥动翻页 |
| 耗电偏高 | 确认手势模式关闭后通知变为「已关闭」；闲置应为 5fps |
| x86 模拟器装不上 | APK 仅含 arm64-v8a，需真机 |

## 隐私与权限

| 权限 | 用途 |
|---|---|
| `CAMERA` | 前置摄像头采集，仅用于本地手势识别 |
| `SYSTEM_ALERT_WINDOW` | 光标与提示悬浮窗 |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_CAMERA` | 手势开启时保持相机前台服务 |
| `POST_NOTIFICATIONS` | 通知栏开关与状态通知 |
| `BIND_ACCESSIBILITY_SERVICE` | 注入滑动 / 点击 / 返回 |

- **没有 `INTERNET` 权限**：应用在系统层面无法联网（MediaPipe AAR 合入的网络权限已在 Manifest 中显式移除）
- 相机画面只在内存中用于推理，不录制、不保存、不上传
- 无障碍服务只注入手势：不读取窗口内容、不监听按键、不截屏

完整说明见 [PRIVACY.md](PRIVACY.md)。

## 兼容性

- **小米 14 Pro**：✅ 主力开发与实测（HyperOS / Android 14）
- 其他 Android 8.0+ / arm64 / 带前摄的机型：理论上可运行，但距离门、光标映射范围可能需要按日志重新标定
- Android 8/9：`startForeground` 兼容已修，但尚未在 8/9 真机验证
- Mi 9 为独立项目，不在本仓库

## 从源码构建

环境：

- JDK **21**
- Android SDK **35**
- Gradle 8.9（wrapper 自带）

```powershell
# Debug 包
gradlew.bat assembleDebug --no-daemon
# 产物：app\build\outputs\apk\debug\app-debug.apk（约 21.5MB）

# Release 包（需要本地 keystore.properties，见 RELEASING.md）
gradlew.bat assembleRelease --no-daemon
# 产物：app\build\outputs\apk\release\app-release.apk（约 18.2MB，与 debug 同代码）
```

> `abiFilters 'arm64-v8a'`：APK 只含真机架构，代价是 x86 模拟器装不了。

## 项目结构

```
app/src/main/
├── java/com/airgesture/
│   ├── MainActivity.kt              主界面：权限引导 / 手势开关 / 调试状态 / 签名行
│   ├── camera/CameraManager.kt      CameraX 采集、低功耗 AE、熄屏暂停
│   ├── detector/
│   │   ├── HandGestureDetector.kt   核心：姿态、挥动状态机、光标、多手/远距门控
│   │   └── HandOverlayView.kt       App 内骨骼调试预览（无相机画面）
│   ├── service/
│   │   ├── AirGestureService.kt     无障碍服务：生命周期、通知、广播、手势分发
│   │   ├── WaveHintOverlay.kt       顶部 👋 / 纠错胶囊
│   │   └── CursorOverlay.kt         十字准星光标
│   └── executor/GestureExecutor.kt  无障碍手势注入
├── assets/
│   ├── hand_landmarker.task         MediaPipe 手部模型
│   ├── AUTHORSHIP.txt               作者记号
│   └── licenses/Apache-2.0.txt      第三方许可全文
└── res/
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
| `>>> multi_hand n=2 [0]Right/0.99 d=.. [1]Left/0.98 d=.. pick=0` | 多手选择及 handedness 置信度 |
| `>>> ok r=.. others=..` | OK 手势校准（r<0.20 且 others=0 才成立） |
| `>>> fist curl=X/5 t=..` | 握拳校准（5 指参与） |
| `AE target fps range=[5, 5]` | 低功耗 5fps 生效 |
| `Camera paused / resumed (screen off/on)` | 熄屏暂停 / 亮屏恢复 |

## 性能（Mi 14 Pro）

| 状态 | CPU | 备注 |
|---|---|---|
| 手势模式关闭 | 0.16% | 进程几乎不耗电 |
| 开启 · 闲置 | ≈19.6% | 相机 AE 5fps |
| 开启 · 活跃（画面有手） | ≈70.7% | 全帧率推理，25-30fps 处理 |

推理单帧 18-20ms（GPU delegate）。熄屏后相机与推理完全停止。

## 已知限制

- 2D 视觉 + 掌心跨度估距，极端角度/遮挡下距离判断会波动
- 前摄 FOV 有限，左右滑动比上下挥动更难触发
- 仅 Mi 14 Pro 完成完整标定；其他机型可能需要调参
- 仅提供 arm64-v8a，不支持 32 位设备与 x86 模拟器
- 无障碍注入存在误触可能，请在可控场景使用

## 第三方组件

| 组件 | 用途 | 许可 |
|---|---|---|
| MediaPipe Tasks Vision 0.10.18 | 手部关键点检测 | Apache 2.0 |
| CameraX / AndroidX | 相机与基础框架 | Apache 2.0 |
| Material Icons | 界面图标 | Apache 2.0 |
| Font Awesome `hand-fist` | 握拳图标 | **CC BY 4.0** |

许可见 [NOTICE](NOTICE)，Apache 2.0 全文随 APK 分发于 `assets/licenses/Apache-2.0.txt`。

## 版本记录

- **1.1.2**（versionCode 7）
  - 挥动方向改为**食/中/环三指一起投票**（单位方向平均 + 长度均值），指尖朝下更稳
  - 上一段速度阈值单独收紧到 **9.8**，并要求连续 2 个 ~100ms 样本达标；下一段保持 9.0
  - `AXIS_DOM` 1.3 → 1.15、`AXIS_MIN` 0.10 → 0.08；投影锚点改为三指指尖均值
- **1.1.1**（versionCode 6）
  - 多手索引修复：`worldLandmarks` / `handedness` 与主手同索引，修复左右手感不一致
  - 日志增强：`multi_hand` 输出每只手标签/置信度，`wave` / `wave arm` 输出 `hand=`
  - 开源整理：MIT LICENSE、隐私说明、第三方声明、SPDX 代码头、APK 内作者记号
  - 隐私加固：显式移除 MediaPipe 合入的 `INTERNET` / `ACCESS_NETWORK_STATE`，`allowBackup=false`
  - 发布签名：独立 release keystore（RSA 4096，`CN=7wiligh7`）
  - Release 暂不开 R8/资源压缩，与 debug 同代码，优先保证功能一致（日志暂保留）
- **1.1.0**（versionCode 5）
  - 隔空挥动 v1.3：官方规格对齐、回程消除、慢挥/跳变防护
  - OK 手势收紧、握拳 5 指严格化、熄屏暂停、远距门控、多手选择
  - APK 仅 arm64-v8a；代码审查修复（前台服务、广播、CameraManager、无障碍最小化）
- 更早版本：握拳光标点击、AE 5fps 低功耗、Material 图标与主界面重做

## 文档

- [DESIGN-airwave.md](DESIGN-airwave.md) — 隔空挥动设计 v1.3（状态机 / 参数 / 校准记录）
- [PRIVACY.md](PRIVACY.md) — 隐私说明
- [NOTICE](NOTICE) — 第三方组件与许可
- [RELEASING.md](RELEASING.md) — 维护者构建与发布流程
- [PROGRESS.md](PROGRESS.md) — v1.2.0 交付记录（历史）

## 版权声明与许可证

Copyright (c) 2026 **7wiligh7**. All rights reserved.

本仓库代码以 [MIT License](LICENSE) 授权：允许复制、修改、再发布，但必须保留版权声明与许可证文本。

**官方版本标识**

- 应用名称 `AirGesture`、图标与作者署名 `7wiligh7` 用于标识官方版本
- 官方 APK 使用证书 SHA-256 `951013058dd41b353e82184346b891a7991715534432810a9cc39661f1c66063` 签名
- fork 或再发布时请保留本声明，并避免让用户误认为是官方版本