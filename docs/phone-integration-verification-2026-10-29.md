# 第四至六批真机验收

2026-10-29 完成，测试设备为用户的 iQOO Neo8 Pro / V2302A，Android 16 / OriginOS 6，串号 `10AD5J08C5000UJ`。本轮仅使用该实体手机，未启动或切换到 MuMu。

## 交付

- [第四批：后台执行屏](background-execution-display-2026-10-29.md)：实际任务网关、Root 进程持有的副屏、中文与视觉操作、过期目标拒绝、安全窗口保护、停止与断线清理。
- [第五批：任务电源生命周期](task-power-lifecycle-2026-10-29.md)：活跃用户任务的有超时唤醒锁，等待确认与空闲释放；真实熄屏 30 秒计算/网络任务完成。
- [第六批：响应与资源测量](phone-performance-2026-10-29.md)：副屏实际 30 Hz、厂商截图限速适配、任务工具路由与实测数据。

已覆盖安装 Floryx Dev（`com.flowchat.app.debug`），正式包保持独立。安装时实际读取手机安装器界面，核对本任务包名/名称后勾选风险结果确认并继续安装，没有修改系统安装验证策略。

入口为 **设置 → 手机操作 → 后台执行屏（实验）**，默认关闭；本机实际点按开启、保存再关闭，最终恢复为关闭。Power 模式、Root 连接方式、记忆和命令开关恢复测试前状态。默认助手仍是 Floryx Dev，vivo 语音识别服务保持原值，未修改 BL、KernelSU、环境隐藏或锁屏策略。

## 验证

| 检查 | 结果 | 记录（build/） |
| --- | --- | --- |
| 全量单元测试 | 549 项，548 通过，1 项既有跳过，零失败 | phone-integration-verified-build.log |
| Debug、AndroidTest、Release/R8 | 通过 | phone-integration-verified-build.log |
| Debug Lint | 0 错误、186 警告、1 提示 | phone-integration-verified-lint.log |
| Root Binder 协议 2、复用、实际空闲退出、请求取消、进程死亡与重连不重放 | 5 项通过 | root-service-phone-protocol2-regression.log |
| 最终包实际网关：中文、索引/视觉点击、安全窗口、主屏隔离、Home 清理、Root 进程意外退出 | 1 项组合用例通过 | background-execution-phone-verified.log |
| 后台完成、新界面停止、通知停止与真实唤醒锁释放 | 2 项通过 | task-power-lease-phone-active-stop.log |
| 最终包关闭 Activity、实际熄屏 30 秒、未亮屏时完成并释放服务/锁 | 1 项通过 | task-power-lease-phone-screen-off.log |
| 实际设置入口、开关保存与恢复 | 通过 | background-setting-phone-ui.json |

上表设备 JUnit 共 9 个用例，组合网关内的多个断言不重复计数。各用例在对应行为改动后执行；最终包另复核网关、Root 退出和熄屏流程。测试仅使用合成页面、本机 HTTP 合成模型或只读状态；没有发送真实聊天请求到付费模型。

早期失败分别来自私有显示缺少无障碍窗口、测试进程重启后的服务重绑、启动动画越界以及 Lint 不能识别 `require` 中的 API 检查，均已修正并复测。构建和 Lint 串行执行，不将早期失败计入通过结果。

## 实测限制

手机自带计算器使用共享活动任务模式（singleTask），当前会拒绝后台启动，主屏保持不变。因此不能把此实验功能描述为已支持所有应用。标准活动的跨 UID 操作使用测试 APK 的独立页面验证。

该手机截图限速会触发 500 ms 回退。12 次小样本的读取 p50 从 516.61 ms 到 514.01 ms，未证明有显著提速；副屏刷新率从 60 Hz 到 30 Hz 已实际确认。USB 供电下的短时测试没有证明续航提升，长时间自然 Doze 和拔线耗电仍未验收。

## 最终安装与清理

Debug APK SHA-256：`8a2c42678bff7e02f8d84c4c2b12f1024557a4f1ef16b7d10aa15a2f89832f3f`，与手机当前 `base.apk` 的实际哈希一致。Release 产物为未签名构建，本轮没有发布正式版本。

聚合记录为 `build/phone-integration-results.json`，最终手机状态为 `build/phone-integration-final-state.json`。合成会话、服务商、恢复夹具和本轮临时 Root 截图已清理，测试 APK 进程已停止。测试结束后为 Floryx 自身恢复无障碍绑定，并通过系统的实际绑定与崩溃状态核验。

最终无后台执行屏、无任务前台服务、无 `Floryx:UserTask` 唤醒锁；自动锁屏超时仍为测试前的 `2147483647`。熄屏用例结束后已请求亮屏，安全锁屏按手机原策略保留。

同日 20:51 按用户后续要求，已用既有包名和签名覆盖安装不可调试的 Release 正式构建。用户在两次操作之间已开启后台执行屏，正式版安装予以保留；本页前文的关闭状态是开发包验收结束时的历史快照。当前安装包与正式版检查见[真机正式版安装](phone-release-install-2026-10-29.md)。
