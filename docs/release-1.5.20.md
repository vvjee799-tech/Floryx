# Floryx 1.5.20 · 发布验证

本版采用新的植物绿曲线图标和「花开成形」启动页，并修复冷启动等待期间动效提前消耗的问题。设计与行为记录见[图标规范](../design-system/floryx/pages/icon.md)及[启动页验证](bloom-splash-2026-10-27.md)。

## 构建与回归

- 全量 Debug 单元测试 523 项：522 通过，1 项既有 Windows 环境用例跳过，零失败或错误。
- Release lint：零错误，163 条警告。Release/R8 构建、签名和安装身份检查通过。
- MuMu Android 15 上，启动页六项正常动画检查及单独运行的关闭动画检查通过，覆盖主题、布局、慢首页初始化、系统交接、后台暂停、触摸阻挡和 Activity 重建。
- 正式包在 MuMu 覆盖安装后核对版本、非调试状态、签名、UID、数据目录标识与回读 APK 校验值；实际冷启动进入首页，`Status: ok`，本次首帧 485 ms。首帧耗时不代表动画和首页准备的总时长。
- V2302A 手机已覆盖安装同一正式包；签名、UID 和数据目录标识保持一致，已安装 APK 的 SHA-256 与待发布文件一致，保留配置和聊天记录。手机留在停止状态，便于用户自行冷启动。
- 本轮检查未向真实模型发送测试请求。MuMu 的原动画倍率 0.5 已恢复。

## 安装包

- 显示名 **Floryx**，版本 **1.5.20（34）**。
- 历史兼容包名 `com.flowchat.app.debug`，Release + R8 构建，调试关闭，沿用既有签名。
- 文件：`build/releases/1.5.20/Floryx-1.5.20-retain-config.apk`，18,678,780 字节。
- SHA-256：`3d2126fbf2d14b3315ca96bdafed075f73aa55a888dc8f6f7d68d1213cd02411`。
- [GitHub 发布页](https://github.com/vvjee799-tech/Floryx/releases/tag/v1.5.20) · [APK 下载](https://github.com/vvjee799-tech/Floryx/releases/download/v1.5.20/Floryx-1.5.20-retain-config.apk)

本地构建和覆盖安装证据保存在忽略目录 `build/releases/1.5.20/`；启动诊断和录屏位于 `build/splash-timing/`。历史图标概念稿保留在本地，本版发布采用用户选定的图标源文件。
