# 隐私说明（Privacy Policy）

AirGesture（包名 `com.airgesture`）是一个**完全本地运行**的隔空手势工具。

- 最后更新：2026-11-03
- 适用版本：1.1.1-mi14（versionCode 6）及之后

## 一句话

前置摄像头画面只在内存中用于本地手势识别，**不保存、不传输**；应用未申请联网权限。

## 处理的数据

| 数据 | 用途 | 是否离开设备 |
|---|---|---|
| 前置摄像头画面（低分辨率分析流） | MediaPipe 本地推理手部 21 个关键点 | 否 |
| 手部关键点 / 手势结果 | 触发滑动、点击、返回 | 否 |
| 应用设置 | 仅当前进程内存 | 否 |

- 未申请 `INTERNET` 权限，应用在系统层面**无法联网**。MediaPipe 的 AAR 会合入网络权限，本项目已在 Manifest 中用 `tools:node="remove"` 显式移除，最终 APK 不含 `INTERNET` / `ACCESS_NETWORK_STATE`。
- 不录制视频、不截图、不写入相册或任何文件。
- 不收集设备标识符、位置、通讯录、剪贴板、使用统计。
- 不包含广告、分析、崩溃上报等第三方 SDK。

## 无障碍服务

AirGesture 使用无障碍服务**注入**滑动 / 点击 / 返回手势：

- 不读取窗口内容（`canRetrieveWindowContent=false`）；
- 不监听或过滤按键（未声明 `canRequestFilterKeyEvents`）；
- 不读取屏幕文字、不截屏；
- 仅监听 `TYPE_WINDOW_STATE_CHANGED` 获取前台应用包名，用于「检测到游戏时自动停用」。

无障碍权限可随时在系统设置中关闭，关闭后应用无法控制屏幕。

## 游戏守护

检测到游戏在前台时（CODM 国服/国际服/Garena，或应用分类为「游戏」），AirGesture 会：

- 退出手势模式，停止相机、悬浮窗和前台服务；
- 调用 `disableSelf()` 关闭自己的无障碍服务；
- 退出游戏后不会自动恢复，需要手动重新开启；
- 通知栏「停用无障碍」和从最近任务划掉 App 也会触发同样的停用。

服务只读取前台应用**包名**，不读取窗口内容、不记录应用使用历史。

## 悬浮窗

悬浮窗只绘制十字准星和顶部提示胶囊，不覆盖或读取其他应用内容。

## 权限清单

| 权限 | 用途 |
|---|---|
| `CAMERA` | 前置摄像头采集，用于本地手势识别 |
| `SYSTEM_ALERT_WINDOW` | 光标与提示悬浮窗 |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_CAMERA` | 手势开启时保持相机前台服务 |
| `POST_NOTIFICATIONS` | 通知栏开关与状态通知 |
| `BIND_ACCESSIBILITY_SERVICE` | 注入手势（由用户在系统设置中开启） |

## 第三方组件

MediaPipe Tasks Vision、CameraX / AndroidX 均在本地运行，不发起网络请求。许可证与署名见 [NOTICE](NOTICE)。

## 儿童隐私

本应用不面向儿童，也不收集任何个人信息。

## 变更与联系

本说明的更新会随版本记录在 [README](README.md)。问题与建议请通过 GitHub Issues 联系作者（7wiligh7）。