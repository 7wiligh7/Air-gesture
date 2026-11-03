# 发布流程（维护者）

面向仓库维护者的构建与发布步骤。**不要把 keystore、密码或 `keystore.properties` 提交到仓库。**

## 一次性准备：生成 release keystore

```powershell
keytool -genkeypair -v `
  -keystore airgesture-release.jks `
  -alias airgesture `
  -keyalg RSA -keysize 4096 -validity 10000 `
  -storetype PKCS12
```

- 建议放在仓库外，例如 `D:\AndroidUnlock\keys\`
- 密码用密码管理器生成，并**离线备份 keystore + 密码**
- 丢失后无法再用同一身份覆盖升级已发布版本；泄露则可能被伪造成「官方更新」

## 本地配置（不提交）

在项目根目录创建 `keystore.properties`：

```properties
storeFile=D:/path/to/airgesture-release.jks
storePassword=your-store-password
keyAlias=airgesture
keyPassword=your-key-password
```

该文件已在 `.gitignore` 中，`git status` 不应出现它。`app/build.gradle` 只在该文件存在时才启用 `signingConfigs.release`；没有它时 `assembleRelease` 会产出未签名包。

## 每次发布

1. 更新 `app/build.gradle` 的 `versionCode` / `versionName`
2. 构建：

   ```powershell
   gradlew.bat assembleRelease --no-daemon
   ```

3. 验证签名与权限：

   ```powershell
   apksigner verify --verbose --print-certs app\build\outputs\apk\release\app-release.apk
   aapt2 dump permissions app\build\outputs\apk\release\app-release.apk
   ```

   预期：证书主体 `CN=7wiligh7`；权限只有 CAMERA / 前台服务 / 通知 / 悬浮窗，**没有 INTERNET**。

4. 计算校验和：

   ```powershell
   Get-FileHash app\build\outputs\apk\release\app-release.apk -Algorithm SHA256
   ```

   写入 `SHA256SUMS.txt`，与 APK 一起上传。

5. 打 tag 并推送：

   ```powershell
   git tag v1.1.1
   git push origin main --tags
   ```

6. 在 GitHub Releases 上传：
   - `AirGesture-<version>-arm64-v8a.apk`
   - `SHA256SUMS.txt`
   - Release 说明中公布**证书 SHA-256**，方便用户核对

## Release 说明模板

```markdown
AirGesture 1.1.1 (versionCode 6)

## 更新
- 修复多手索引错位，左右手感一致
- 开源整理与隐私加固（无网络权限）

## 要求
- Android 8.0+ / arm64-v8a / 前置摄像头
- Mi 14 Pro 为标定机型，其他机型可能需要调整距离门

## 权限与隐私
相机、悬浮窗、通知、无障碍；画面仅本地处理，不保存、不上传，APK 无 INTERNET 权限。

## 安装
1. 允许「未知来源」安装
2. 安装后授予相机 / 悬浮窗 / 通知权限
3. 系统设置中开启 AirGesture 无障碍服务
4. 打开手势模式开关

## 校验
SHA-256: <apk-sha256>
签名证书 SHA-256: 951013058dd41b353e82184346b891a7991715534432810a9cc39661f1c66063
```