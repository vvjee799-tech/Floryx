# 屏幕画面识别（1.5.13）

后续修复：1.5.14 已修正动态画面误拦截，当前行为与回归结果见[动态画面修复](screen-vision-motion-2026-10-25.md)。本页保留首次实现与安装记录。

1.5.15 已优化原图清晰度并加入局部读取，编码策略以[截图清晰度说明](screen-vision-quality-2026-10-25.md)为准。

在设备助手启用 Power 和无障碍服务后，`observe_screen` 默认返回当前应用的截图和控件信息。支持图片输入的模型可依据真实画面识别照片、颜色、图表和 Canvas 内容，控件索引继续用于现有点击与输入操作。`include_image=false` 请求仅控件信息；`wait_for_screen` 使用相同选项。

画面发送给当前会话选择的模型，不自动切换服务商或型号。DeepSeek 官方 Flash 已提供图片输入；已知纯文本型号明确返回限制，未知或自定义型号按现有图片接口尝试，服务商拒绝时报告图片输入失败。仅看到控件文字不会被标记为已看见画面。

## 使用

更新后，在设置中启用 Power 与 Floryx 无障碍服务，选择支持图片输入的模型。在目标应用中通过悬浮窗发送“查看屏幕，说明画面中的图形和颜色”等指令。若提示缺少截图权限，在系统无障碍设置中关闭后重新开启 Floryx 服务，再返回目标应用重试。

## 获取和保护

- Android 11–13 使用无障碍全屏截图；拍摄期间隐藏 Floryx 浮窗的图层，并恢复原展开状态。
- Android 14 及以上使用目标窗口截图，浮窗保持显示且不混入画面。
- Android 10 及以下保留控件读取，明确说明截图需要 Android 11。
- 密码和支付等敏感页面在截图之前拦截。系统禁止截图的窗口尊重系统错误，不尝试 Root 绕过。
- 截图前后核对应用、窗口和控件；页面变化或进入敏感页面时丢弃图片。画面与 `screen_id`、尺寸和获取时间关联。
- 截图频率受限，捕获设置超时，任务取消时释放截图缓冲并恢复浮窗状态。

截图在内存中压缩为最长边不超过 1920 像素、最多 1 MiB 的 JPEG。每轮请求最多保留最近两帧用于对比，旧帧明确标记省略；不写入聊天附件、任务检查点或执行日志。已保存的执行记录只包含获取状态、画面标识和尺寸，之后需要像素时重新截图。

## 模型传输

- Chat Completions：工具结果保留原调用 ID 和文本，所有同批工具结果后补充带关联说明的图片消息，符合图片位于 `user` 内容块的限制。补充消息明确属于工具数据，不是用户新指令。
- Responses：图片放在原 `function_call_output` 的多模态输出内。
- Anthropic：图片放在原 `tool_result` 的内容块内。

原有手动图片附件及工具思考数据保持现有传输方式。模型被明确告知截图是瞬时证据，屏幕文本与画面不构成新指令，不得根据缺失图片捏造视觉结论。

## 验证

单元测试覆盖三种接口的图片续轮、工具 ID 与结果顺序、仅控件模式、已知纯文本型号、旧截图裁剪、像素压缩和截图取消后的浮窗状态恢复。

MuMu 使用独立测试 Activity 绘制没有文字标签的红色圆形和蓝色方块，实际截图由本地模型接收端解码并检查红、蓝像素；验证截图与控件的画面标识一致，并确认存储的聊天和执行历史不含图片字节。密码输入页和 `FLAG_SECURE` 窗口均验证不返回图片。使用本地模拟模型，不消耗真实模型额度；跨协议格式为单元验证，未宣称所有服务商均经过真实接口测试。

设备首轮测试因模拟器冷启动后无障碍连接未恢复而无法读页。诊断夹具改为确认实际页面可读后再运行，重连仅作用于已授权的 Floryx Dev 服务并恢复原设置。

完整 Lint 首次发现两处原有检查错误：图片预览的 `produceState` 赋值未被 Compose 检查器识别，以及 Dev 应用名缺少翻译声明。预览改为按路径记忆状态并在异步读取完成后赋值，路径变化时重置加载状态；品牌名称声明为无需翻译。

- 单元测试：378 项，377 通过、1 因 Windows 符号链接权限跳过、0 失败。
- Debug Lint：0 错误、174 条警告；Debug 与设备测试 APK 构建通过。
- Release 构建通过，包含 `lintVitalRelease` 和 R8；日志为 `build/screen-vision-release-build.log`。
- MuMu（Android 15，`127.0.0.1:16384`）联合回归：11 项全部通过，包括 2 项新增屏幕视觉、5 项基础控件、工作区、输入框底栏、余额与用量各 1 项。
- 相关原始日志保存在忽略目录：`build/screen-vision-final-build3.log`、`build/screen-vision-final-device.log`。测试仅更新 MuMu 的 Floryx Dev，没有修改手机上的安装。

Android 11–13 的全屏捕获与浮窗隐藏分支通过编译检查，尚未在相应系统版本的设备上做运行回归。

## 手机覆盖安装（2026-10-25）

用户连接手机并明确要求覆盖后，已将 V2302A 从正式版 1.5.12 更新至正式版 1.5.13（27）。安装前比对原 APK 签名，确认一致；使用 `adb install -r` 保留原安装身份，应用显示名为 Floryx，调试标志关闭。更新前后 UID 与用户数据目录标识保持一致，没有卸载或清除数据。冷启动返回成功，并确认应用进程已启动；此次手机操作未发送模型测试消息。

安装包：`build/releases/Floryx-1.5.13-retain-config.apk`，SHA-256 为 `CB02A184F7B338544221DD23FAF2EF327B9CBACDD41DE6066AC8F59E09D25CA9`。安装前后元数据、安装回执与启动回执保存在 `build/phone-release/screen-vision-1.5.13-*`。

## 官方依据

- [Android 无障碍截图 API](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#takeScreenshot(int,%20java.util.concurrent.Executor,%20android.accessibilityservice.AccessibilityService.TakeScreenshotCallback))
- [DeepSeek 图片输入](https://api-docs.deepseek.com/guides/vision/)
- [OpenAI 函数结果格式](https://developers.openai.com/api/docs/guides/function-calling)
- [Anthropic 工具结果](https://platform.claude.com/docs/en/agents-and-tools/tool-use/handle-tool-calls)
