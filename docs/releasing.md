# Floryx 正式版发布流程

当前发布渠道沿用既有安装身份：包名 `com.flowchat.app.debug`，显示名 Floryx。正式包通过 **Release + R8** 构建，关闭调试；包名后缀是历史兼容标识。禁止为了打包生成替代签名或修改安装包名，已有用户应使用覆盖安装保留数据。

## 1. 准备源码与版本

同步 `main`，检查待发布变更，递增 `app/build.gradle.kts` 的 `versionName` 和 `versionCode`。在 CHANGELOG 中归档本版改动，更新 README 的版本、下载链接与文档入口。确认签名、模型密钥、本地数据和构建目录没有进入提交范围。

## 2. 构建与核验

在已配置本项目 Android 工具的 Windows 环境运行：

```powershell
.\scripts\build-release.ps1
```

脚本要求本机已有发布渠道的密钥，执行全量 Debug 单元测试、Release lint 和 Release/R8 构建，然后核对 APK 版本、名称、包名、非调试标志和既有签名。输出位于 `build/releases/<版本>/`：

- `Floryx-<版本>-retain-config.apk`
- `SHA256SUMS-<版本>.txt`
- 本地构建日志与 `artifact-verification.json`

签名覆盖规则在 `scripts/retain-existing-install.init.gradle`。密钥不进入仓库或发布附件；缺失或证书不匹配时停止发布。

## 3. MuMu 覆盖验证

先通过 MuMuManager 确认实例及 ADB 端点，所有命令显式指定该 serial。必要的行为回归在 Debug 测试包上完成，再比较既有安装与正式 APK 的签名，使用 `adb install -r` 覆盖安装正式包。核对版本、非调试状态、安装身份、冷启动和关键页面，并回读已安装 APK 的 SHA-256 与待发布文件比较。

使用用户的 MuMu，不自动切换实体手机。不得通过卸载或清除数据来处理覆盖安装问题。

## 4. 提交、标签与 GitHub Release

再次确认远端分支没有新增提交，提交本版源码与公开验证记录，并将版本标签指向这次提交。按仓库既有主分支发布方式推送 `main` 和对应版本标签，不强制推送或改写已有标签。

在 `vvjee799-tech/Floryx` 创建版本 Release，先上传 APK 与校验文件，再发布为 latest。发布说明列出功能、安装方式、实际验证范围和已知限制。

最后确认远端 `main` 与标签指向预期提交、Release 已发布且附件大小 / SHA-256 与本地一致。发布后工作区应无待提交的发布源码变更；本地日志和 APK 继续留在忽略的构建目录。
