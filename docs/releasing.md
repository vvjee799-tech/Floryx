# Floryx 正式版发布流程

Floryx 正式发布包使用标准包名 `com.floryx.app`、关闭调试标志并启用 R8 代码优化，通过独立正式签名密钥签名。

## 1. 版本与仓库准备

1. 在 `app/build.gradle.kts` 更新 `versionName`（语义化版本，如 `1.0.0`）与递增的整数 `versionCode`。
2. 更新 `CHANGELOG.md`、核对 `README.md` 与 `docs/` 内的版本和下载链接。
3. 确认工作区干净，且 `keystore.properties`、`local.properties`、`AGENTS.local.md` 及 `build/` 未被 Git 追踪。

## 2. 签名配置（仅保留在本机）

正式签名密钥（`.jks`）与 `keystore.properties` **绝对不能提交到仓库**。在仓库根目录创建被 `.gitignore` 忽略的 `keystore.properties`：

```properties
storeFile=/path/to/floryx-release.jks
storePassword=<store-password>
keyAlias=<key-alias>
keyPassword=<key-password>
```

## 3. 构建与核验

在已配置 JDK 21 与 Android SDK 35 的 Windows 环境运行：

```powershell
.\scripts\build-release.ps1
```

脚本会依次执行：

1. 全量单元测试 `:app:testDebugUnitTest` 与正式版静态检查 `:app:lintRelease`（单 worker 运行以控制内存峰值）；
2. Release + R8 构建 `:app:assembleRelease`；
3. 调用 `apksigner verify` 与 `aapt dump badging` 校验正式签名、包名 `com.floryx.app`、`versionName`、`versionCode`、应用名称 `Floryx` 及非调试标志；
4. 同步运行 `:native-voice-bridge:testDebugUnitTest :native-voice-bridge:lintRelease :native-voice-bridge:assembleRelease -PnativeVoiceBridge=true` 并核验语音桥接模块使用完全相同的正式签名证书、包名 `com.floryx.nativevoicebridge`、一致版本号与非调试标志；
5. 输出产物至忽略目录 `build/releases/<version>/`：
   - `Floryx-v<version>.apk`
   - `Floryx-VoiceBridge-v<version>.apk`
   - `SHA256SUMS.txt`（包含以上两个 APK 的 SHA-256）
   - `artifact-verification.json` 与 `build.log`

同时运行全量检查：

```powershell
.\gradlew.bat :app:lintDebug :native-voice-bridge:lintDebug -PnativeVoiceBridge=true
python tools/check_lint_delta.py
```

## 4. 设备安装与验收

1. 在干净的 Android 设备或模拟器（指定显式 ADB 序列号 `adb -s <serial>`）安装 `Floryx-v<version>.apk`。
2. 回读设备内实际安装的 APK SHA-256 与待发布文件比对一致。
3. 验证冷启动、无崩溃日志、核心界面与关键工作流正常；若运行过仪器测试，需恢复设备原有无障碍服务列表并清理临时测试包。

## 5. Git 标签与 GitHub Release

1. 提交全部发布变更到 `main` 并推送。
2. 创建带注释的版本标签并推送：

```powershell
git tag -a v<version> -m "Floryx v<version>"
git push origin v<version>
```

3. 使用 `gh release create v<version>`（或 GitHub Releases 页面）上传 `Floryx-v<version>.apk`、`Floryx-VoiceBridge-v<version>.apk` 与 `SHA256SUMS.txt` 并填写发布说明，确认附件 SHA-256 与本地一致。
