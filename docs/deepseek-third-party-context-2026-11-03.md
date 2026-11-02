# 第三方 DeepSeek 百万上下文适配

用户选择自动识别第三方 DeepSeek，而非新增手动容量设置。实际在 Floryx MuMu 配置中读取到的模型名是 `deepseek-v4.1-flash-1017`。

## 行为

- 已知型号在官方和第三方 Chat Completions 地址均使用 1,000,000 Token 本地窗口，不再要求域名为 `api.deepseek.com`；兼容自定义基础路径和端口。
- 识别 `deepseek-flash`、`deepseek-v4.1-flash`、`deepseek-v4.1-flash-1017`、`deepseek-v4-pro`、`deepseek-v4-pro-0813`、`deepseek-v4-flash`、`deepseek-v4-flash-0731`、`deepseek-v4-flash-vision-exp`。
- 能力识别不区分大小写，兼容 `deepseek/`、`deepseek-ai/` 等命名空间前缀；发给服务商的模型字符串保持原样。
- 旧版 Chat、Reasoner、R1、V3 和未知型号仍沿用原策略；不根据服务商显示名称、任意 `v4` 子串或未知后缀推断容量。原生 Gemini、Anthropic 地址不使用此 Chat Completions 策略。
- 输入预算、输出预留、自动整理、发送前校验、后台估算和界面继续共用既有配置。默认非思考、普通思考、Max 思考输入预算分别为 941,808、884,464、818,928 Token。
- 无数据库变更、无密钥迁移，不改已有模型名。版本号沿用工作区当前 1.5.21；本次不发布、推送或安装到其他设备。

这里自动采用的是应用的模型容量策略，不证明第三方实际提供了相同窗口或输出上限；真实服务商若另有限额，仍以其接口为准。

## 验证

- Debug 与 AndroidTest 构建通过；全量单元测试 556 项，555 通过、1 项环境限制跳过，零失败。覆盖官方与第三方地址、V4.1 完整型号、命名空间和大小写、未知型号回退、请求中模型名不变、输出预留、缓存估算及发送前硬预算校验。
- 首轮回归发现一项长任务测试仍用约 3 万 Token 触发旧窗口整理的预期；将合成推理填充增大至能跨越新大窗口阈值后，继续验证整理、不重复动作和原生工具结果配对，全量复测通过。
- MuMuManager 核实实例名为 Floryx（本次 index 1、ADB `127.0.0.1:16416`），保留数据覆盖安装 Debug 包后运行 3 项设备测试，全部通过：官方完整链路、第三方 V4.1 完整链路、1M 容量与输入/输出预留弹窗。
- 两条完整链路各进行 7 次回答请求与 2 次摘要请求，验证超过 32k 和 100k 历史、长窗口分批整理、早期证据回读、原生工具配对、停止续接及重建运行实例后恢复。第三方最大普通请求 1,538,949 字节，最大摘要请求 375,237 字节。
- 测试同时读取用户已保存的 `deepseek-v4.1-flash-1017` 配置，验证模型容量 1,000,000、默认思考输入预算 884,464；测试后再次确认该模型名保持不变，合成服务商与会话已清理。
- 设备测试结束后重启应用、恢复原先的无障碍服务列表和主开关，并核对系统实际绑定与 `Crashed services`，确认服务绑定正常且无崩溃项。

设备测试仅发送合成数据至本地严格 HTTP 服务；真实配置只用于读取模型名与计算预算，不读取密钥或调用付费模型，未向真实第三方提交百万 Token。日志及报告位于忽略目录 `build/deepseek-third-party/`，主要文件为 `build-and-unit-final.log`、`device-tests.log`、`official-result.json`、`third-party-result.json`、`accessibility-before.json` 和 `accessibility-after.txt`。

## 依据

- [DeepSeek 模型与价格](https://api-docs.deepseek.com/quick_start/pricing/)：当前 Flash 为 V4.1-Flash-1017，窗口 1M；2026-11-03 核对。
- [阿里云 DeepSeek 接口文档](https://www.alibabacloud.com/help/en/model-studio/deepseek-api)：第三方使用 V4.1 和带日期的型号名；2026-11-03 核对。
- [原官方适配记录](deepseek-context-2026-10-26.md)。
