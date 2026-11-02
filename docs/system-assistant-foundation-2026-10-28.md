# 系统助手基础改造：第一批

目标设备优先为用户自己的 iQOO Neo8 Pro（V2302A），Android 16 / OriginOS 6。BL 解锁、永久 Root 和环境隐藏以用户确认的信息为前提，不使用系统属性或 ADB shell 的 `su` 可见性否定该前提。

## 已实现

- Root 接入不再以 `su` 文件可见性为前置条件。授权后实际启动 `su`，核对 UID 0；未主动请求授权时不尝试提权。执行程序不可用、授权被拒绝分别报告。
- 身份与能力探测使用单调时间缓存，默认 30 秒。显式授权重试绕过缓存；设备操作失败时失效，下次使用重新核验，不自动重放失败操作。拒绝授权仍要求显式重试。
- 原 ChatViewModel 的执行逻辑迁至应用级单例 ChatTaskRuntime，使用独立 SupervisorJob。ChatViewModel 作为界面代理；界面销毁不取消任务，重建后的界面继续观察和控制同一个运行时。
- 用户提交任务时立即启动 TaskExecutionService，先于异步准备，减少前后台切换导致服务启动受限的机会。服务不依赖悬浮窗权限；通知提供停止入口，所有任务结束后退出。首次从聊天界面发送时请求通知权限，拒绝通知不拒绝任务。
- Room 15 → 16 增量迁移新增 task_runs，保存每轮的任务 ID、会话、指令、模型、状态和助手消息引用。消息中的 executionContext 继续保存工具参数和结果，既有检查点继续负责上下文压缩。
- 进程初始化将遗留运行/确认中任务标记为 Interrupted，结合既有消息恢复保留未确认结果，供用户核验后继续，不自动启动模型或重放工具。

## 范围

本批是 Root 接入和后台执行的基础。尚未接入默认数字助手、独立执行屏、按键唤起和持续语音唤醒；也未将 Root shell 替换为独立 Binder 服务。上述能力按既有规划继续推进。

应用级运行时存活不等于系统永不终止进程。进程结束后的处理是保存证据并提供接续；真实手机的熄屏网络、厂商后台策略和续航需要单独测量。

## 验证入口

- RootDeviceConnectionTest：实际启动判断、未授权不尝试、拒绝后显式重试、非 Root UID、缓存到期、失败失效与撤销。
- ChatTaskRuntimeTest：保留原会话、工具与并发测试；新增清理界面 ViewModel 后任务完成、重建界面停止旧任务。
- TaskRunJournalTest：实际数据库关闭重开、恢复遗留状态、终态不被重新激活、会话删除级联清理。
- TaskRuntimeDiagnosticTest：本地 HTTP 流、实际 Activity/Room/前台服务，验证关闭界面后完成、服务退出与新界面停止；Root 用例执行只读设备状态探测。
- TaskRuntimeDiagnosticTest 的 seed/verify 两阶段用于真实结束进程后的恢复验证；种子数据、服务商和消息均为合成记录，验证后清理。

设备诊断默认使用 MuMuManager 返回的 MuMu 0 ADB 地址，所有 ADB 命令显式指定串号。真实手机采用独立串号与记录，不将模拟器数据用于真实续航结论。

## 本轮结果

2026-10-29 完成验证：

- 单元测试 527 项：526 通过，1 项既有 Windows 符号链接用例跳过，零失败。新增任务数据库跨关闭/重开测试，及两个界面生命周期行为测试。
- Debug、AndroidTest、Release/R8 构建通过；Debug Lint 为 0 错误、178 警告、1 提示。最终产品源码构建日志为 `build/task-runtime-final-source-build.log`；最终测试夹具构建日志为 `build/task-runtime-overlay-fixture-build.log`。
- MuMu 0、Android 15，MuMuManager 验证的端点 `127.0.0.1:16384`：10 项设备测试通过，见下表。
- 实体 iQOO Neo8 Pro、Android 16 / OriginOS 6，串号 `10AD5J08C5000UJ`：在用户为 Floryx Dev 单独授予 KernelSU 权限后，实际 UID 0、缓存服务复用和只读设备状态读取通过。最终包覆盖更新后再次通过，日志为 `build/task-runtime-root-phone-final.log`。未改动 BL、Root 或环境隐藏配置。
- 最终 Debug 包已覆盖安装至 MuMu 和实体手机。版本号沿用开发基线 1.5.20，本批没有发布正式版本。Debug 包为 `app/build/outputs/apk/debug/app-debug.apk`，Release 构建产物为 `app/build/outputs/apk/release/app-release-unsigned.apk`。

| MuMu 验证 | 通过项数 | 日志（build/） |
| --- | ---: | --- |
| Activity 结束后完成、重建 ViewModel 停止、前台服务退出；Root 只读接入 | 2 | task-runtime-lifecycle-final.log |
| 写入模拟中断，再实际 force-stop 进程，冷进程恢复并保持不自动执行 | 2 | task-runtime-recovery-seed.log、task-runtime-recovery-verified.log |
| 文档内容、会话并发、恢复入口 | 3 | task-runtime-regression-ProductReliabilityDiagnosticTest.log |
| 浮窗确认与返回接管 | 1 | task-runtime-regression-BackgroundConfirmationDiagnosticTest.log |
| 工具证据进入实际模型请求的接续 | 1 | task-runtime-regression-TaskContinuationDiagnosticTest.log |
| 浮窗触摸穿透、键盘后收起、展开收起和前后台恢复 | 1 | task-runtime-floating-verified.log |

浮窗测试最初的前后台恢复超时源于夹具手动启动服务却未开启助手设置，返回主界面时服务按设置被关闭。夹具已显式开启并恢复原设置，并等待键盘布局稳定后定位收起按钮；最终完整用例通过，早期失败日志不计入通过结果。

验证日志保存在忽略目录 build/ 下，任务测试仅使用本地合成模型，不调用付费接口。真机本批只验证 Root 接入，不据此宣称熄屏任务或真实续航已经通过。

2026-10-29 后续按用户要求补充了完整的实机新功能测试，覆盖 30 秒后台保持、重开页面停止、通知停止和真实进程中断恢复；详见[实机测试记录](device-task-runtime-2026-10-29.md)。

同日开始下一批系统助手入口、轻量面板、页面上下文与独立显示可行性验证，见[第二批记录](system-assistant-entry-2026-10-29.md)。

同日第三批已将本页早期的应用内 Root 执行迁移到独立 Binder 服务，补齐请求取消、断线恢复和空闲释放；当前实现与真机验收见[第三批记录](root-system-service-2026-10-29.md)。
