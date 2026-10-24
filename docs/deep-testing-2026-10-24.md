# MuMu 深度测试记录（2026-10-24）

用户已明确允许整轮多轮模型指令、专用测试页面操作、测试目录命令及修复复测。设备由 MuMuManager 确认为 MuMu 0，ADB 始终指定 `127.0.0.1:16384`。未操作物理手机。沿用现有 DeepSeek 配置，未导出密钥；记录只含合成任务、工具证据和结果，不含模型推理正文。

本轮使用上一轮已修复深层控件读取、进场动画坐标及 402 错误提示的产品 APK，见 [已知问题修复记录](known-issues-2026-10-24.md)。本轮新增和修正的是测试代码，没有额外修改产品行为。

## 真实模型任务

通过 Floryx Dev 的真实输入框发送指令，使用 `deepseek-v4-flash`，共完成以下六轮任务，20 次工具调用。另一次初始测试完成了三轮、6 次工具调用，但测试脚本断言误报，保留为诊断证据，不重复计入六轮通过结果。

| 轮次 | 实际指令 | 独立验证 |
| --- | --- | --- |
| 1 | 创建中文 CSV 和可重跑汇总脚本，排除 failed 行 | 磁盘 JSON 的 total 为数字 37，失败订单仍保留 |
| 2 | 乙金额 20 改为 25，保留其他数据，写入中文及单双引号 | 磁盘 total 为 42；文件文字逐字匹配 |
| 3 | 读取不存在的文件，再沿用已知正确路径恢复 | 缺失文件未被创建；错误记录存在；recovery.txt 为 recovered=42 |
| 4 | 读取超出单页上限的输出并继续分页 | 实际调用 read_command_output；随机末行被准确写入 tail.txt |
| 5 | 在 48 层嵌套页面输入中文并勾选 | 从页面重新观察，输入内容和 checked=true 均正确 |
| 6 | 第二轮替换输入、取消勾选，不复用旧页面 ID | 从页面重新观察，替换内容和 checked=false 均正确 |

保留的合成会话：

- `ff18c5a1-ae53-4131-b650-d57e9e66a04b`：深度测试：文件、纠错与恢复。
- `f6f99c7d-76bc-4fd4-9715-41ab3bd8c29f`：深度测试：48 层页面多轮操作。

本地证据在 `build/deep-live-evidence/<会话 ID>/`；设备内对应 `files/live-agent-deep/<会话 ID>/`。最终日志为 `build/deep-agent-live-rerun.log` 和 `build/deep-device-live-rerun.log`。

## 过程中发现与处理的问题

1. **新测试的错误断言**：可见 Tool 消息是文本，结构化工具证据另存于 executionContext；原断言在文本中查 JSON exit_code 导致误报。并且模型可能显式捕获预期错误，整个 shell 退出码仍为 0。修正为检查实际缺失文件错误及恢复后的文件，四轮重跑通过。没有把这次误报归为产品故障。
2. **测试启动超时**：对已打开的 MainActivity 重复 startActivitySync，等待 45 秒超时，尚未发出模型指令。改为异步启动并等待输入控件；两轮实际页面操作通过。
3. **旧终端回归定位失效**：旧测试查找独立“展开”文字，但完成后的工具记录现归入活动区域；设置入口也改成带无障碍描述的图标。更新断言，等待已保存结果显示到界面，按设置图标描述定位。
4. **一次 Compose 跨线程观察崩溃**：终端界面回归出现 `SnapshotStateObserver` 多线程访问异常。后续原样重跑没有重现该崩溃，而是暴露上面的旧断言问题。修正旧断言后，完整终端流程连续三次通过（最终回归一次，加 `terminal-stability-1.log`、`terminal-stability-2.log` 两次复测），未再崩溃。保留 `build/deep-regressions/agentRunsLinuxAndSystemCommandsWithTaskConsent.log`；不能仅凭后续通过声称这一偶发崩溃已根治。
5. **ADB 会话断开**：ADB 服务重新启动后 TCP 设备丢失，MuMuManager 确认模拟器仍运行。重新连接原串号后恢复；未切换到手机或其他设备。

## 补充回归结果

八项均通过（其中七项最终日志以 `build/deep-regressions/verified-` 开头，运行时测试为同目录 `linuxToolsOutputCancellationAndRootCommandsWork.log`）：

- Linux 文件持久化、跨会话命令隔离、非零退出、输出截断和分页、超时、取消及子进程清理。
- 20 秒命令在本地等待，只产生启动与总结两次模型请求；停止按钮确实取消命令；终端设置页可访问。
- 已停止任务的完成证据与未确认操作正确进入后续请求。
- 上下文检查点持久化，原始证据可以通过真实工具协议读取。
- 历史投影兼容严格 thinking 协议，不补造推理字段。
- 运行中任务压缩后继续，已执行证据保留。
- 摘要格式错误时修复后继续。
- 摘要截断时修复后继续。

根命令分支未启用；以上不表示额外验证了 Root 系统命令。

## 可重复执行

新增两项显式启用的 instrumentation 测试，默认不会消耗模型额度：

- `LiveAgentDeepDiagnosticTest#liveAgentBuildsCorrectsRecoversAndReadsPagedOutput`
- `LiveDeviceDeepDiagnosticTest#liveAgentUsesDeepControlsAndAppliesFollowUp`

运行时传入 `-e runLiveAgentDeepDiagnostics true`，测试 runner 为 `com.flowchat.app.debug.test/androidx.test.runner.AndroidJUnitRunner`。需已有可用 DeepSeek 配置；界面测试要求此前已启用 Floryx Dev 无障碍服务。运行前须确认 MuMu 串号，不能自动选择其他 ADB 设备。测试在结束时恢复其修改的命令、Power 和记忆开关，保留合成会话供检查。

## 范围限制

这些结果验证 Floryx 的任务执行和页面操作，不等于验证手机 Clash 分应用模式下 ChatGPT 的消息发送问题已解决。此次没有安装手机 APK，也没有进行手机两种代理模式的发送对照。
