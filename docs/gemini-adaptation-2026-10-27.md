# Gemini 原生适配

日期：2026-10-27。以下保留开发阶段的验证范围；实现已随 [Floryx 1.5.19](releases/1.5.19.md) 正式发布。

## 接口与已有配置

- 官方 `generativelanguage.googleapis.com` 使用原生 `streamGenerateContent?alt=sse`，密钥通过 `x-goog-api-key` 请求头发送，不放入 URL。
- 新预设使用 `/v1beta`。旧 `/v1beta/openai` 配置自动识别，保留已有加密 Key 与会话。第三方兼容网关继续使用其配置的 OpenAI 协议。
- Gemini 使用单独的 OkHttp 客户端，支持 Android TLS、系统 HTTP 代理与 HTTP/2。关闭客户端自动重发生成请求与跨站重定向，失败由用户明确重试。
- 连接、流读取、总请求超时分别为 30 秒、5 分钟、10 分钟；不会把断流或空回复当作成功，也不会因空回复自动追加一次付费生成。

## 模型选择与思考

- 聊天底部模型菜单按需获取原生模型列表，处理分页，去掉 `models/` 前缀并过滤嵌入、音频、图片生成等专用型号。
- 已选型号保持可见；列表加载、失败与重试有单独反馈。成功列表缓存到当前界面生命周期，服务商配置变化后重新获取。
- Gemini 2.5 使用 `thinkingBudget`，可关闭型号发送 0；Pro 固定开启。Gemini 3/4 使用 `thinkingLevel`，4 的自动档使用 medium。按型号限制最小、低、中、高等档位，3.8 不发送不支持的 minimal。
- `thought=true` 作为思考摘要显示，正文独立处理；不把摘要拼进正文。

## 工具与图片

- 工具参数通过原生 `parametersJsonSchema` 发送；工具结果转换成 `functionResponse`，保留原生调用 ID 与名称。
- 同一步的原生 Parts、签名边界、并行调用顺序原样保留，只在当前工具循环内存中使用；签名不写入诊断报告。
- 用户图片使用 `inlineData`，工具截图使用 `functionResponse.parts`；不丢弃图片或把截图误作新的用户授权。
- 只有明确的 STOP 才完成回复或交付工具调用。输出截断、安全阻止、无效工具参数和提前断流均显示错误，不执行部分工具参数。
- 401/403、404、429、5xx、地区限制和网络错误分别反馈。HTTP 错误与 SSE 内嵌错误都对密钥脱敏。

## MuMu 网络排查

目标设备由 MuMuManager 验证为 `127.0.0.1:16384`，未使用物理手机。

测试期间，同一账号的直接 Google 连接在 CIO 和 Android OkHttp 中都出现过 TLS 握手被关闭，不能将其归因于某个型号或密钥失效。电脑现有 Clash HTTP 代理的 CONNECT 测试成功，因此为 MuMu 设置了 Android 系统 HTTP 代理 `10.0.2.2:7897`。该地址只属于本机 MuMu 测试环境，不写入 APK 或服务商配置；MuMu 使用时需要电脑代理运行。

账号与服务状态也影响调用：此 Key 请求 `gemini-2.5-flash` 返回 404，并提示不再向新用户开放；`gemini-3.8-flash` 曾返回 503 高负载。模型出现在列表中不保证当前账号一定可调用。

## 检查依据

- 单元测试覆盖原生请求与签名回放、预算/程度、图片、SSE 边界、取消、中断、错误脱敏、独立客户端路由、分页过滤、模型缓存和重试。
- MuMu 诊断读取应用内已有 Key，只发送合成测试消息、随机测试值和纯蓝测试图；不导出 Key。
- 设备诊断入口：`GeminiConnectionDiagnosticTest` 与 `GeminiModelMenuDiagnosticTest`，需显式设置 `runGeminiConnectionDiagnostics=true`。
- 本地诊断输出：`build/gemini-connection/`；APK：`app/build/outputs/apk/debug/app-debug.apk`。

### 实测结果与范围

| 检查 | 结果 |
| --- | --- |
| 全量单元测试 | 514 项：513 通过，1 项既有环境用例跳过，0 失败 |
| 追加网络回归 | 79 项通过，覆盖 HTTP/SSE 脱敏与专用客户端路由 |
| Debug / AndroidTest 构建、Lint | 构建通过；Lint 0 错误，177 项既有警告 |
| MuMu 原生模型列表与文本 | 19 个聊天型号；`gemini-3.5-flash` 返回 `GEMINI_OK` |
| MuMu 新会话与追问 | 3.5 Flash 两轮成功；一次完整记录约 1.4 秒与 1.6 秒/轮 |
| MuMu 签名工具续轮、工具图片与普通图片 | 3.5 Flash 的三个检查点成功；一次记录分别在 3.622 秒与 6.130 秒达到工具闭环和图片识别检查点 |
| MuMu 实际模型切换 | 聊天菜单选择 3.6 Flash，保留服务商，成功回复 `GEMINI_ALT_UI_OK` |
| 菜单界面 | 深浅主题、375dp 视口、2 倍字体下，加载、错误、重试和选择通过；截图已目视检查 |

上述设备检查分阶段取得成功，不表示最后一次整套真实接口测试全部通过：3.8 Flash 的检查受到 503/75 秒诊断等待限制；后续复测又遇到免费日额度耗尽（429），因此已停止继续生成请求。Google 的 429 提示为每模型每日 20 次免费请求，并提示约北京时间 2026-10-27 15:00 后可重试；是否恢复以 Google 接口为准。2.5 Flash 对此账号返回 404。保留这些外部限制，不将其掩盖为适配或密钥验证成功。

测试后的已保存默认型号仍为 3.5 Flash，MuMu 保留成功的 3.6 Flash 验证会话；没有将模拟器代理地址写入应用默认配置。

## 官方参考

- [Generate Content REST API](https://ai.google.dev/api/generate-content)
- [Thinking 与分型号参数](https://ai.google.dev/gemini-api/docs/generate-content/thinking)
- [Thought signatures](https://ai.google.dev/gemini-api/docs/generate-content/thought-signatures)
- [Models API](https://ai.google.dev/api/models)
- [Ktor Android / OkHttp 引擎](https://ktor.io/docs/client-engines.html)
