# 真机响应与后台资源：第六批

设备为 iQOO Neo8 Pro / V2302A（`10AD5J08C5000UJ`），Android 16 / OriginOS 6，USB 连接。测试不调用外部付费模型。

## 改动与证据

1. Root 副屏改用 `VirtualDisplayConfig`，请求 30 Hz。真实 DisplayManager 从原 60 Hz 变为 30 Hz；只影响任务副屏，不更改物理主屏刷新率。帧接收保持 ImageReader 的最新帧，结束任务销毁，不增加常驻截图轮询。
2. 后台截图尝试 280 ms 的间隔；收到厂商的速率限制时只重新读取截图一次，并在本进程回退至 500 ms。不会重放点击或文字输入。前台截图维持既有 500 ms 策略。
3. 模型工具列表随后台模式过滤全局亮度、音量、强停等操作；工具指引明确截图、控件与 Home 的副屏含义。实际启动器和工具实现仍检查路由，连接丢失时不能转到主屏。

每组对同一合成静态页面连续读取 12 次，包含界面稳定等待、实际窗口截图与编码，不含外部模型网络延迟：

| 指标 | 修改前 | 最终检查 |
| --- | ---: | ---: |
| 副屏刷新率 | 60 Hz | 30 Hz |
| 读取 p50 | 516.61 ms | 514.01 ms |
| 读取 p95 | 584.12 ms | 551.43 ms |
| 实际截图间隔配置 | 500 ms | 厂商限制后回退至 500 ms |

日志为 `build/background-capture-phone-baseline.log` 和 `build/background-execution-phone-final.log`。最终组记录的平均截图节流等待为 178.70 ms，截图 API 为 59.83 ms，编码为 34.63 ms；其余时间包含控件树稳定判断、Binder 与调度。数据是单设备小样本，**没有证明读取速度显著提高**，也不把刷新率减半等同于耗电减半。

## 兼容性与故障

完整网关检查在最终组通过中文输入、索引/视觉点击、安全窗口拒绝、主屏零触摸与焦点保持、Home 副屏清理、任务结束资源销毁。另实际杀死已核对 UID/PID 的自有 Root 服务，副屏随 Binder 死亡移除，同一任务的后续观察失败，不重连或转主屏。

手机自带 vivo 计算器的启动活动使用 `singleTask`（launchMode=2）。当前实现拒绝此类可能复用主屏任务的启动，真机已验证拒绝后主屏不变；不能声称该计算器已支持后台执行。普通标准活动的跨 UID 行为使用测试 APK 的独立页面验证。

## 功耗结论边界

当前验证的是活跃任务持锁、空闲释放、显示销毁和 30 Hz 的实际生效。USB 供电与短时合成任务不能给出真实续航提升百分比。未模拟拔电、改动电池统计或关闭系统保护来生成耗电结论；长时间自然 Doze、拔线待机和日常混合负载续航仍需单独采样。

参考：[副屏请求刷新率](https://developer.android.com/reference/android/hardware/display/VirtualDisplayConfig.Builder#setRequestedRefreshRate(float))、[Android 16 截图间隔与窗口保护实现](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-16.0.0_r3/services/accessibility/java/com/android/server/accessibility/AbstractAccessibilityServiceConnection.java)。
