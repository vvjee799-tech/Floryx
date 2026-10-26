# DeepSeek 百万上下文适配（1.5.17）

## 行为

DeepSeek 官方 Chat Completions 的 `deepseek-flash`、`deepseek-v4-pro`、`deepseek-v4-flash` 和 `deepseek-v4-flash-vision-exp` 使用百万上下文策略。地址须为 HTTPS、精确官方 host `api.deepseek.com`，基础路径为空、`/v1` 或 `/beta`；自定义配置只要实际指向这个接口也能识别。第三方地址、未知模型和其他供应商继续使用原来的 24k/32k 本地策略。

能力解析和 `max_tokens` 序列化共用配置。官方标注的 1M 在本地按 1,000,000 Token 保守计算；输入硬预算等于窗口扣除有效输出预留及 50,000 Token 估算余量。输出未指定时，按非思考、普通思考和 Max 思考分别显式发送 8,192、65,536、131,072；指定时校验 1..393,216 并保留原值。

| 模式（未指定输出上限） | 输出预留 | 可用输入硬预算 | 首次整理阈值 |
| --- | ---: | ---: | ---: |
| 非思考 | 8,192 | 941,808 | 800,536 |
| 普通思考 | 65,536 | 884,464 | 751,794 |
| Max 思考 | 131,072 | 818,928 | 696,088 |

整理目标为输入预算的 60%，并预留新检查点空间；下次软整理需有至少约 10% 输入预算的增长，紧急阈值仍早于硬上限。水位绑定会话、实际服务商、模型和预算配置。思考或输出限制变化后，新配置立即使用自己的水位。

较早记录按 Token 目标归档，能够保留超过四轮的近期原文。单批摘要完整请求输入上限为 128,000 Token，包含指令和已有检查点，修复轮也检查上限；每次超时 120 秒，仍可取消。已有来源、原生推理与工具结果配对、完整流、检查点事务和失败保留记录规则继续生效。

新建 DeepSeek 预设使用 `deepseek-flash`；已有配置和会话的旧别名不重写，无数据库迁移，也不需要重新填写密钥。已有有效检查点继续使用，后续窗口按新预算增长。

## 显示与性能

圆环按“当前估算输入 / 可用输入硬预算”显示。详情区分模型上下文 1M、当前输入预算、估算输入和输出预留；未知模型显示本地预算。输出预留和估算余量不是已经使用的输入。

大窗口的请求投影、归档边界计算、活动估算和网络编码在后台运行；不变的消息对象复用投影估算，流片段只重新计算新增/变化内容。缓存最多保留四个配置窗口及各自最新请求。异步快照校验运行实例、会话、模型、地址和输出/思考设置，停止和切换不会把旧结果写回新配置。

总会话窗口与单条附件/工具结果限制分别管理。本次不扩大附件内联、图片历史和单次工具结果持久化上限，原始历史和附件仍可通过既有读取工具回读。Token 计数继续是 UTF-8 启发式估算；安全余量不能替代服务端精确分词。

## 验证

MuMu 0，Android 15，ADB 始终明确指定 `127.0.0.1:16384`。先覆盖安装 Floryx Dev 1.5.17（versionCode 31）完成全部设备诊断，最后覆盖更新为同版本正式 Release 包并冷启动验证。

- 全量单元测试 449 项：448 通过、1 项既有 Windows 环境用例跳过，零失败。新增 10 项覆盖官方地址与别名、未知模型回退、输出序列化、估算缓存一致性、100k 不提前整理、大窗口批次、模式/输出切换、取消和摘要超时。
- MuMu 11 项设备测试全部通过：新增 DeepSeek 完整链路与容量详情，另回归圆环原有两项、检查点与原始证据回读、28 步长任务及五项严格/宽松协议与摘要修复。新界面的浅色、深色大字体及既有窄屏截图已经人工视觉检查，无截断或遮挡。
- DeepSeek 本地完整链路共 7 次回答请求（包含被停止的一次）、2 次摘要请求；最大普通请求为 1,537,773 字节，最大摘要请求为 375,227 字节。100k 历史零摘要，长窗口两批整理后工具回读早期证据成功；原生推理/调用/结果配对、停止续接、重建 VM 后恢复及整理状态显示通过。
- 其他服务商本地预算回归保持 28 次工具调用、29 次回答请求、7 次摘要，28 组记录完整；23 轮短历史为 1 次回答、0 次摘要。
- Debug、AndroidTest、Release/R8 构建与 Release lint 均通过，lint 无错误。另沿用本地既有保留配置打包规则生成正式安装包，保持 `com.flowchat.app.debug` 包名与原签名，应用显示为 Floryx，关闭调试标志。

MuMu 上单次合成测试的估算耗时如下（非持续性能基准）：

| 目标输入 | 实际估算 Token | 冷估算 | 复用前缀后的新回复片段 |
| --- | ---: | ---: | ---: |
| 100k | 102,108 | 10.63ms | 0.33ms |
| 600k | 602,108 | 13.71ms | 0.25ms |
| 880k | 882,108 | 16.74ms | 0.42ms |

对应采样的 Java 堆已用约 33–60MB；这是采样值，不代表峰值内存。Debug APK SHA-256：`F83EE4F0F31F8199DB7C4ABDFA947966390EFB11A4C386F533424BDAC329EDC3`。

正式包为 `build/deepseek-context/Floryx-1.5.17-retain-config.apk`，SHA-256：`75172627BBB6E7C8ED3A84D7C05574996A11311B8645DB79DD92C38CA4EDDEE2`。已用 apksigner 比对 1.5.16 既有安装包，证书一致；清单为 `com.flowchat.app.debug`、Floryx、versionCode 31，调试关闭。MuMu 使用 `adb install -r` 成功覆盖，appId 保持 10054，启动返回 `Status: ok` 且进程运行。安装未卸载或清除数据。

日志和报告位于忽略目录 `build/deepseek-context/`：`build-and-unit-final.log`、`device-deepseek-final.log`、`device-context-regressions.log`、`device-result.json`、`fallback-frequency-result.json`、`release-build.log` 和 `release-retain-config-build.log`；截图在其 `context-window/` 子目录。

正式包元数据与安装核对另见 `release-apk-verification.json`、`mumu-release-verification.json`、`mumu-release-install.log` 和 `mumu-release-launch.log`。

用户随后明确要求先安装手机、再推送 GitHub。vivo V2302A 已从 1.5.16（30）覆盖更新至 1.5.17（31）：安装前比对手机原 APK 与新包证书一致，采用 `adb install -r`，应用身份保持一致，调试关闭，冷启动成功且进程运行。未卸载、清除数据或发送额外模型测试消息。补充回读安装后 APK 时手机连接已断开，因此没有把安装后逐字节校验计为通过；安装成功、版本、应用身份和启动状态均已在断开前验证。本地核对记录在 `phone-before-verification.json`、`phone-verification.json`、`phone-install.log` 和 `phone-launch.log`。

设备诊断 `DeepSeekLongContextDiagnosticTest` 采用真实 Room、ChatViewModel、Ktor/CIO 及严格本地模型服务。官方地址先参与能力识别，再由测试进程的 HTTP 传输拦截器导向 loopback；不放宽生产模型识别、不配置真实密钥、不调用付费模型服务。覆盖超过 32k 的历史、长窗口整理、原生工具续轮、早期证据回读、停止续接及重建 VM 后恢复。另记录 100k、600k、880k 输入的冷估算与复用前缀耗时。

界面诊断检查 1M 模型容量与可用输入预算分开显示，以及浅/深色、320dp 容器、1.3 倍字体和思考模式对应输出预留的更新。

本地模型链路及估算压力测试不等于已向 DeepSeek 官方实际提交百万 Token 输入。

## 依据

- [DeepSeek 模型与价格](https://api-docs.deepseek.com/zh-cn/quick_start/pricing/)，核实日期 2026-10-26。
- [Chat Completions API](https://api-docs.deepseek.com/api/create-chat-completion/)，核实日期 2026-10-26。
- [实施前计划](deepseek-context-plan-2026-10-26.md)。
