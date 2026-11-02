# 后台执行屏：第四批

设备为串号 `10AD5J08C5000UJ` 的 iQOO Neo8 Pro / V2302A，Android 16 / OriginOS 6。只向这台实体手机安装和执行本批诊断。

## 实现

- 设置 → 手机操作新增“后台执行屏（实验）”，默认关闭。需要 Root、Power 模式、无障碍和 Android 15 以上。切换模式先停止正在操作手机的任务。
- Root Binder 协议升级为 2。每个手机任务使用唯一所有者 ID，独占一块 360×720、160 dpi 副屏；任务完成、停止、切换或连接结束时销毁副屏及其中的任务。取消先到达时记录所有者，拒绝迟到的创建。
- 副屏仅显示自身内容，独立焦点、禁止夺取主屏顶层焦点，移除时销毁其内容；输入法策略为隐藏，不切换主屏输入法或使用全局剪贴板。
- Android 不向普通无障碍服务提供本方案私有虚拟屏的窗口，因此使用 `PUBLIC + OWN_CONTENT_ONLY`。这是系统可枚举的副屏，不是对其他已授权应用保密的显示通道；不启用演示屏、自动镜像或安全显示标志。
- 启动、点击、长按、滑动、返回均携带实际所有副屏 ID；Home 只移除该副屏的活动任务。中文输入使用副屏上对应控件的 `ACTION_SET_TEXT`。
- 产品截图通过 Android 的指定窗口截图接口读取该副屏，保留 `FLAG_SECURE` 拒绝、遮挡判断、敏感页面过滤、快照 ID 和视觉像素复核。Root 的 ImageReader 为副屏提供渲染接收端，诊断还直接核验其中的像素；产品不通过 Root 绕过安全窗口截图限制。
- 不支持的活动任务模式、旋转/尺寸变化、服务丢失或截图失败均返回错误，不转到物理主屏，不在同一任务内自动重建和重放。后台模式禁用亮度、音量、强停和任意 Android shell；只读设备状态与独立 Linux 命令不受此路由影响。
- 副屏持有期间保留 Root 连接，任务结束释放租约，再沿用 30 秒空闲退出。无任务时不创建副屏。

## 验证过程

Root 原型已在实体手机完成副屏启动、截图颜色、ASCII 和中文输入、主屏零触摸、焦点保持和显示资源释放。最初私有显示的控件树为空，改用上述不镜像的公开显示后通过。

实际网关验收曾发现启动动画将窗口暂时平移至副屏边界之外。当前实现过滤越界窗口，并使用事件与有限轮询等待界面稳定；安全检查期间变化时丢弃结果并有限次重新观察，不重放动作。

实际产品链路已在 `build/background-execution-phone-settled.log` 通过：1 项组合用例涵盖真实设置/网关、中文输入、索引点击、视觉像素复核与点击、过期快照拒绝、后台全局操作拒绝、副屏 Home 清理、安全窗口截图拒绝、任务结束后显示移除与活动销毁，以及连接结束后同一任务不重连、不转主屏。主屏触摸计数为 0，焦点在执行及释放前后保持相同。安全窗口返回 `secure_window_blocked`，无截图和控件文本。

副屏原型在 `build/execution-display-phone-public-probe.log` 与 `build/execution-display-phone-product-native.log` 分别通过。早期失败日志不计入通过项。全部测试使用独立合成页面或只读系统状态，不请求付费模型。

最终包在 `build/background-execution-phone-verified.log` 再次通过上述网关用例，并额外验证 Root 进程被杀后的副屏移除。原生设置页面的实际点击、保存和恢复记录为 `build/background-setting-phone-ui.json`：关闭 → 开启 → 关闭。当前安装的实验开关保持关闭。整体验证与构建记录见[第四至六批验收](phone-integration-verification-2026-10-29.md)。

参考：[DisplayManager 标志](https://developer.android.com/reference/android/hardware/display/DisplayManager)、[AOSP 无障碍显示追踪](https://android.googlesource.com/platform/frameworks/base/+/master/services/accessibility/java/com/android/server/accessibility/AccessibilityManagerService.java)、[窗口截图接口](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#takeScreenshotOfWindow(int,java.util.concurrent.Executor,android.accessibilityservice.AccessibilityService.TakeScreenshotCallback))。
