# 视觉定位与点击闭环

当前源码新增 `tap_visual_target`，让支持图片的模型在没有可靠无障碍控件编号时，根据截图定位目标，执行点击或长按，再读取操作后的新画面。这是 1.5.17 发布后的源码改动，原已发布 APK 不包含本次更新。

后续 1.5.18 针对实机偏点增加了局部二次定位、命令回退约束和取图重试，当前行为与最新验证以[精度修复](visual-precision-2026-10-26.md)为准。本页保留首次实现与安装记录。

## 使用

直接发送任务指令，例如“点击画面中的蓝色按钮，并检查结果”。有可靠控件时仍优先使用控件；纯图片按钮和 Canvas 区域可以使用视觉点击。

模型先取图，只有返回 `visual_tap_available=true` 的最新附图才能用于操作：

```json
{
  "screen_id": "复制该图片的 image_screen_id",
  "x": 700,
  "y": 680,
  "target_description": "蓝色 GO 按钮",
  "long_press": false
}
```

`x/y` 是相对于当前附图的 0–1000 整数坐标，左上角为 `(0,0)`，右下角为 `(1000,1000)`。局部截图的坐标相对于该局部图片，不是完整屏幕的物理像素。应用负责还原裁剪、缩放和窗口位置。返回结果包含新的截图和控件，模型必须核对结果后再选下一步，不能把手势完成等同于任务完成。

## 执行检查

- 图片绑定当前任务、应用、窗口 ID、窗口边界、显示器尺寸与旋转方向，使用单调时钟在两分钟后过期。新任务、仅控件观察、其他界面操作及重新取图会使旧图片失效；重启后没有可复用的图像授权。
- 点击前重新截取同一来源区域，对比目标附近像素与整体画面。容忍小范围无关动画；目标移动、替换或大幅画面变化返回 `stale_visual_target` 和新截图，不点击旧坐标。
- 主线程派发手势前，再检查当前窗口、坐标范围、遮挡与用户接管状态。沿用设备互斥锁，每次模型回复仅允许一个改变界面的操作。
- 点击后自动获取新图。像素和控件均未变化时返回 `ui_no_change`，并受重复操作与无进展保护约束。图像变化提供结果证据，仍需模型判断是否符合用户意图。
- 敏感页面、系统截屏保护及 Floryx 自身界面继续阻止视觉操作。失败、取消和用户接管不会退回到裸坐标命令。
- 截图保留在任务内存，持久执行记录仅保存参数、状态与文字证据。已知纯文本模型不获得视觉点击工具。

## 窗口边距与浮窗

Android 14+ 优先使用目标窗口截图。MuMu 浮动窗口测试发现，截图会包含额外表面阴影：实际窗口为 1664×935，截图为 1920×1191，无法直接按窗口尺寸换算坐标。

现在先通过窗口截图保留系统截屏保护检查；尺寸不匹配时，再暂时隐藏 Floryx 浮窗，读取显示器画面并按已验证的窗口边界裁剪。显示器尺寸变化或其他窗口覆盖裁剪范围时拒绝取图，避免错误映射或带入其他窗口内容。普通窗口截图保留原路径。视觉点击期间隐藏浮窗，结束、失败和取消后恢复。

Android 11–13 保留显示器截图方式，以真实显示器坐标映射到当前应用窗口，拒绝窗口外的点击。

## 验证

单元测试覆盖裁剪、缩放、窗口偏移、系统栏范围、坐标类型、图片 ID 与过期、旋转变化、像素变化、无关动画、工具续轮、单步执行和浮窗取消恢复。

MuMu 0（Android 15）使用不提供文字或点击控件的专用 Canvas 页面。本地测试模型只从截图像素寻找蓝色按钮，实际手势触发按钮变绿，再从返回的新图核实。设备测试覆盖局部截图、浮动窗口、长按、目标移动后恢复、旧图重放、仅控件读取、敏感页、截屏保护、无变化、旋转和用户接管。

最终结果：

- 全量单元测试 465 项：464 通过、1 项既有 Windows 符号链接环境用例跳过，零失败。
- Debug、AndroidTest 与 Release 构建通过，包含 Release R8 与 lintVital。Debug Lint 为 0 错误、174 条既有警告。
- MuMu `127.0.0.1:16384` 最终联合设备回归 **18 项全部通过**：新增视觉点击 8 项、既有屏幕视觉 5 项、基础控件与导航 5 项。包含真实像素定位、实际手势、点击后的新图核实，以及跨任务旧图拒绝。
- 最终开发包已覆盖安装到 MuMu，Floryx Dev 冷启动成功，无障碍服务维持此前授权状态。
- 构建日志：`build/visual-tap/final-build.log`、`fixture-final-build.log`；最终设备结果：`device-verified-final.log`。早期失败与诊断日志不计入上述通过结果。
- 合成结果截图：`full-result.png`、`crop-result.png`、`offset-result.png`，均在 `build/visual-tap/`。已查看完整与偏移窗口截图，按钮均显示绿色 `DONE`。
- Debug APK：`app/build/outputs/apk/debug/app-debug.apk`；SHA-256 为 `7E6BCE9EBB35A5B0AA9B7B711A64032AD37922507D54C27BCEE177BA3B6BBEF8`。

验证期间还修正了两项夹具问题：直接设备旋转测试不再使用 Compose 测试时钟；独立测试 APK 的广播注册只使用平台 API，避免依赖主应用进程中的 AndroidX。停止失败运行后，恢复了 Android 清除的原已授权无障碍服务状态，再执行完整复测。

```powershell
& 'D:\ruanjian\AndroidSDK\platform-tools\adb.exe' -s 127.0.0.1:16384 shell am instrument -w -r -e class com.flowchat.app.device.VisualTapDiagnosticTest -e runVisualTapDiagnostics true com.flowchat.app.debug.test/androidx.test.runner.AndroidJUnitRunner
```

测试使用合成页面与严格本地模拟模型，不消耗真实模型额度，验证的是坐标、传输、执行与保护链路。真实模型对任意应用目标的识别准确率仍需用实际任务衡量。Android 11–13 分支尚无对应系统设备的运行回归。

API 依据：[Android 无障碍截图接口](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#takeScreenshotOfWindow(int,%20java.util.concurrent.Executor,%20android.accessibilityservice.AccessibilityService.TakeScreenshotCallback))。

## 手机覆盖安装（2026-10-26）

按用户明确要求，已安装到 vivo V2302A（ADB `10AD5J08C5000UJ`，Android 16）。沿用既有保留配置打包规则，生成包含本次视觉点击改动的 1.5.17（31）Release 构建；显示名为 Floryx，包名仍为 `com.flowchat.app.debug`，调试关闭，证书与手机原安装一致。

通过 `adb install --user 0 -r` 覆盖更新。安装前后 appId `10395`、数据目录、CE/DE 数据目录 inode 和首次安装时间保持一致。冷启动返回 `Status: ok`，应用进程运行；启动后 Floryx 无障碍服务已启用并绑定，无崩溃服务记录。手机验证范围为安装身份、安装包与启动状态，视觉操作链路的 18 项设备验证使用 MuMu。

安装包：`build/visual-tap/Floryx-1.5.17-visual-tap-retain-config.apk`。安装后回读手机 APK，SHA-256 与新包逐字节一致：`B1E80EF89AB9129AD29CF58E05F3DB1E31FA7899740E54AA4B9AB8A8558414D8`。

记录：同目录的 `phone-release-build.log`、`phone-apk-verification.json`、`phone-before-install.json`、`phone-after-install.json`、`phone-install.log`、`phone-launch.log` 和 `phone-verification.json`。
