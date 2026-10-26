# 后台任务的浮窗确认

## 行为

后台任务第一次执行 Android 系统命令，或设备操作需要用户确认时，浮窗自动展开，显示任务和操作详情，并提供“允许／拒绝”。系统命令确认保留首条命令和本次任务的 Root / Shizuku 授权范围说明。停止按钮继续可用；主动收起浮窗不视为批准或拒绝，重新展开仍可处理请求。

确认面板可以接收触摸，但不取得键盘焦点。详情可滚动，两个操作按钮固定在底部，使用至少 48dp 的触摸区域。允许或拒绝后恢复原有的输出查看与触摸穿透行为；允许后任务继续在后台运行。

主界面和浮窗共享同一个等待中的请求。每个请求有独立标识，浮窗的响应携带所属会话及请求标识；切换聊天不会把确认交给当前选中的会话。停止任务、处理完成或进入下一次请求后，过期的响应不产生操作。

授权范围沿用原有规则：系统命令只授权本次任务；拒绝后该任务不能绕过拒绝重试。系统自身的权限页面仍由系统处理。

## 验证

定向单元测试覆盖会话切换后的响应归属、无效与过期请求、拒绝、停止后的迟到确认，以及原有主界面确认与接管流程。

最终结果：

- `ChatViewModelTest` 58 项、`FloatingAssistantBridgeTest` 11 项通过，零失败；最终日志 `final-unit-tests.log`。
- 推送 GitHub 前全量单元测试 473 项：472 通过、1 项既有 Windows 环境用例跳过，零失败；日志 `github-unit-tests.log`。
- 最终 MuMu 竖屏完整流程通过（`device-portrait-verified.log`）：浮窗收起后请求保留、重新展开后允许，两条实际 Android 命令退出码为 0，`id -u` 输出 0；拒绝不执行命令；停止后迟到的批准无效；普通返回仍可接管并停止执行中的等待命令。
- 最终 200% 字体、深色、关闭动画的浮窗验收通过（`device-final-max-font.log`）；最终横屏的浮窗允许、拒绝、停止与收起重开验收通过（`device-final-landscape.log`）。手机宽度为 360dp，操作按钮保持可见，详情可滚动。顶部工具条按实际高度与面板分隔，避免大字体下重叠。
- 最终触摸穿透与焦点回归通过（`device-pass-through-final.log`），包括真实点击、滑动、操作失败／取消恢复和展开收起。原有返回主界面确认的回归也通过（`device-main-regression.log`）。
- Debug、AndroidTest 和 Release 构建通过，Debug Lint 为 0 错误、174 项警告；最终日志 `final-layout-build.log`。第一次 Lint 并行分析测试源码时遇到内部 FIR 异常，单工作线程重跑通过，没有关闭检查项。
- Debug 已覆盖安装 MuMu，SHA-256 为 `D7DE4796163A05FF81407BDD0081916E136EF35786BA30D9303B65C87884B50D`。当前环境没有 Release 签名配置，Release 产物为 `app-release-unsigned.apk`；本轮没有发布或安装到手机。

浅色、深色与大字体截图均已检查。最终正常尺寸截图在 `portrait-verified/`，大字体在 `final-max-font/`，横屏在 `final-landscape/`。测试恢复原有外观、Power／命令／记忆设置，并清理合成会话与临时模型服务商；系统字体、动画倍率、自动旋转和竖屏显示也已恢复。

额外发现的独立问题：原有主界面的手机接管卡片在横屏时，按钮可能被底部输入区挤出可视范围。本轮没有改动该卡片，竖屏接管流程回归通过。横屏浮窗专项可传入 `-e overlayApprovalOnly true`，明确只检验本次浮窗功能，不将其报告为主界面横屏接管通过。

MuMu 0 通过 MuMuManager 确认 ADB 地址为 `127.0.0.1:16384`。设备验证使用合成会话、本地 HTTP 模型和无害 Android 命令，不调用外部模型。构建与设备证据保存在忽略目录 `build/floating-confirmation/`。

设备复跑（需已安装开发包和测试包，并授予悬浮窗及 Root 权限）：

```powershell
& 'D:\ruanjian\AndroidSDK\platform-tools\adb.exe' -s 127.0.0.1:16384 shell am instrument -w -r -e class com.flowchat.app.device.BackgroundConfirmationDiagnosticTest -e runBackgroundConfirmationDiagnostics true -e confirmInOverlay true com.flowchat.app.debug.test/androidx.test.runner.AndroidJUnitRunner
```

深色增加 `-e backgroundAppearance dark`；去掉 `confirmInOverlay` 可回归原有返回主界面的确认流程。

## 手机覆盖安装（2026-10-26）

用户明确要求安装到手机后，已在 vivo V2302A（Android 16，ADB `10AD5J08C5000UJ`）覆盖更新。沿用原包名 `com.flowchat.app.debug`、应用名 Floryx、版本 1.5.18（32）及原签名，生成包含浮窗确认更新的 Release 构建，调试关闭。

使用 `adb install --user 0 -r`，未卸载或清除数据。安装前后应用 appId、CE/DE 数据目录 inode、数据目录及首次安装时间一致；回读手机安装 APK，其 SHA-256 与新包完全相同。启动返回 `Status: ok`，应用进程运行。

安装包为 `build/floating-confirmation/phone/Floryx-1.5.18-floating-confirmation-retain-config.apk`，SHA-256 为 `0ED31DC922451D4518B0080376FDED406766BA807B13965F5C1E8900A5D1D768`。同目录保留 `release-build.log`、`before.json`、`after.json`、`install.log`、`launch.log` 和 `verification.json`。手机验证范围为安装身份、包字节与正常启动；完整浮窗功能链路已在上述 MuMu 场景验证。
