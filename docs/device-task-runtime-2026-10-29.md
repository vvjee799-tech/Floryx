# 后台任务新功能实机验证（2026-10-29）

设备为用户的 iQOO Neo8 Pro（V2302A），Android 16 / OriginOS 6，arm64-v8a，ADB 串号 `10AD5J08C5000UJ`。使用 Floryx Dev（`com.flowchat.app.debug`）和用户已授予的 KernelSU 权限。BL 解锁、永久 Root 与环境隐藏按用户确认的状态作为前提。

## 结果

5 项设备用例通过，其中包括 1 项中断种子准备。模型请求全部指向手机本机的合成 HTTP 服务。

| 验证行为 | 实际结果 |
| --- | --- |
| 页面关闭后继续处理 | 真实 MainActivity 销毁后，任务和前台服务保持 30 秒；随后流式回复完成，数据库状态为 Completed |
| 重开页面停止旧任务 | 新 Activity 获得不同的 ViewModel，同一运行时中的旧任务被停止，状态为 Stopped |
| 任务结束释放资源 | 活动任务结束后，TaskExecutionService 退出；新任务仍可正常启动 |
| 通知权限关闭 | 保持手机原有的通知未授权状态，前述后台运行和停止用例通过 |
| 通知栏停止 | 临时授予通知权限，验证真实任务通知已发布；调用该通知实际的 PendingIntent，在页面关闭后停止任务，并验证服务退出 |
| 进程中断恢复 | 保存合成的部分回复、未确认工具证据和运行记录，实际 force-stop 应用，再在冷进程验证 Interrupted / Stopped、原始片段与 interrupted_unconfirmed；未自动启动模型或重放操作 |
| Root 与连接缓存 | 实际授权执行验证 UID 0，多次获取复用同一个服务实例，设备状态读取成功 |

主应用安装包与本地构建 SHA-256 一致：

`7FE63C49873ECCE9CD3E02DAB2CB777D44BD6B35B649964CB5A58381F381C301`

本轮仅扩展测试夹具，没有更改产品 APK。测试包 SHA-256：

`3F7BDCF42D9EAF5815D000EAB9F1FF8F579800663CD09DCF4FFAAB767638917C`

## 测试兼容性与恢复

目标环境中，旧的 Instrumentation.startActivitySync 发生无限等待。读取线程栈确认应用主线程处于正常消息循环，测试线程等待同步启动。夹具改为系统启动命令与 Application.ActivityLifecycleCallbacks，实际观察 Activity 的恢复、销毁和 ViewModel 重建；相同夹具在 MuMu 的生命周期及通知用例均通过。

测试同时暴露恢复设置的 apply() 尚未落盘就被下一次 instrumentation / 权限变更结束进程的时序问题。夹具增加可持久化恢复记录，并在恢复后等待同步提交。取自原测试上下文的设置快照用于恢复；随后实际从手机私有配置文件复核，且在后续测试及进程重启后再次核对：

- 手机助手：开启。
- 记忆：开启。
- 命令执行：关闭。
- 通知授权：关闭，与测试前一致。

临时服务商、会话及任务记录已清理；恢复标记文件已确认不存在。完成后正常唤起 Floryx Dev。没有更改 Root、BL 或环境隐藏配置。

## 证据

原始日志保存在忽略目录 build/：

| 文件 | 通过用例 | 耗时 |
| --- | ---: | ---: |
| task-runtime-phone-features-verified-2026-10-29.log | 2 | 36.215 s |
| task-runtime-phone-notification-2026-10-29.log | 1 | 2.544 s |
| task-runtime-phone-recovery-seed-2026-10-29.log | 1 | 0.084 s |
| task-runtime-phone-recovery-verified-2026-10-29.log | 1 | 0.173 s |

机器汇总为 `build/task-runtime-phone-results-2026-10-29.json`。测试包构建日志为 `build/task-runtime-phone-restoration-fixtures-build.log`；新夹具的 MuMu 对照日志为 `build/task-runtime-extended-fixture-mumu.log`（2 项通过）。早期夹具失败日志不计入通过结果。

本次是 30 秒后台功能验证，未进行主动熄屏、Doze、整夜待机或耗电对照；本地合成模型也不代表真实服务商的网络和推理延迟。
