# FlowChat

FlowChat 是一个偏娱乐、偏角色体验的 Android AI 聊天软件。

它不是官方客户端，也不是商业级产品，更不是那种严肃到让人不想打开的效率工具。它的定位更像一个可以自己接模型、自己捏 AI 角色、自己慢慢加玩法的 AI 聊天小玩具。

你可以把它当成：

- 一个能自己填 API Key、自己选择模型的手机 AI 聊天壳子。
- 一个给 AI 角色聊天、人格设定、头像昵称、系统提示词定制用的小 playground。
- 一个把 AI 从“只会回消息”慢慢拉进生活场景里的实验项目。

## 项目特点

FlowChat 现在更想突出的不是“我有多少常规聊天功能”，而是这几个方向：

- **娱乐感**：头像、昵称、系统提示词、思考过程、模型切换，都围绕“和一个可塑造的 AI 角色聊天”来做。
- **配置轻量**：尽量减少复杂配置。预设服务商只需要填 API Key，自定义配置也保留给进阶使用。
- **模型自由**：不把用户锁死在某一个模型里，能接 OpenAI-compatible API，也能切换不同服务商和模型。
- **思考控制**：输入框下方可直接开关思考并选择程度，按实际模型显示支持的档位。固定思考的模型会说明限制，未知型号使用服务商默认行为。
- **生活感工具**：AI 不只停留在对话框里。它可以查看应用使用情况，也能在你明确要求时打开应用、理解当前页面并连续完成简单操作。
- **浮窗陪伴**：切到其他 App 后仍能查看回复、继续输入或停止输出，不必反复回到 FlowChat。
- **边看边操作**：任务运行时展开浮窗会进入只读观看，输出持续更新，文字区域不抢焦点且触摸穿透。停止、收起按钮单独显示，在执行页面操作期间自动避让；任务结束后可点“回复”进入输入状态。
- **摘要记忆与文件**：只总结长期偏好、个人事实和持续项目，合并重复信息；设置中可关闭读取与新增，并可编辑、逐条删除或清空摘要。也可读取常用文档继续聊。详见[记忆功能说明](docs/memory.md)。
- **Power 模式**：支持 Shizuku 和直接 Root 两种连接方式，把设备能力集中到一个入口，在系统授权范围内按对话需要调用。
- **命令行环境**：内置无需 Root 的 Alpine Linux，可安装 Python、Node.js、Git 并让 AI 运行脚本；Android 系统命令继续使用 Root / Shizuku。设置中独立开关 AI 命令，支持查看输出、停止和超时。详见[命令行说明](docs/terminal.md)。
- **本地优先**：聊天记录、角色配置、API Key 都尽量放在本机，API Key 使用 Android Keystore 加密保存。
- **持续整活**：后续会继续加更有趣的功能，而不是只把它做成一个普通聊天 App。

## 玩法方向

FlowChat 适合拿来做这些事：

- 给不同模型套不同 AI 人设。
- 调角色语气、背景、系统提示词。
- 用 DeepSeek 一类模型看可展示的思考内容。
- 授权后让 AI 看看手机应用使用情况，做一点更贴近生活的互动。
- 直接对 AI 说“打开微信”或“启动 DeepSeek”，让它通过工具调用打开对应应用。
- 配合 Shizuku 与系统无障碍服务，让 AI 在普通页面中识别可见控件、点击、滑动、填写文字或返回上一页；即使当前模型不能看图片，也能使用结构化页面信息完成简单任务。
- 让浮窗助手停留在其他 App 上方，边看任务进度边继续聊天。
- 在手机上快速试不同模型的回答风格。
- 把 TXT、PDF、DOCX、XLSX、PPTX、OpenDocument 等常用文件附在消息里，让 AI 接着内容聊天。
- 选择图片预览后发送给视觉模型，支持图片单独发送、带文字发送和后续追问；详见[附件说明](docs/attachments.md)。

这些玩法以后还会继续扩展，比如更丰富的角色卡、更自然的聊天界面、更有趣的本地生活工具、更多模型兼容和更多娱乐化互动。

## 当前状态

这个项目还在快速迭代中，很多地方会继续改。

目前它更适合：

- 想自己接 API 玩 AI 聊天的人。
- 想看 Kotlin / Jetpack Compose / AI API 接入示例的人。
- 想把 AI 角色聊天做得更有生活感、更有娱乐感的人。

如果你想要一个稳定、完整、开箱即用的商业软件，它现在还不是那个东西。

## 下载

当前源码版本为 **FlowChat 1.5.4**，更新内容见 [更新记录](CHANGELOG.md)。已发布的安装包请查看 [GitHub Releases](https://github.com/vvjee799-tech/FlowChat/releases/latest)；源码更新与安装包发布分别进行。

## 技术栈

### 思考模式兼容

在输入框下方点击“思考”直接开关，点击右侧档位展开可用程度，自动保存并从下一条消息开始生效。默认开启，程度为“自动”；升级会保留旧版实际开启思考的行为。设置按会话保存，切换模型时不支持的程度按“自动”处理。

| 模型系列 | 思考控制 |
| --- | --- |
| OpenAI GPT-5、GPT-6、o 系列 | 按型号提供程度；可关闭的型号发送 `none`。GPT-6、Pro、Codex 等需要时使用 Responses API，保留工具调用所需的不透明推理数据。开启思考且选自动时，部分默认不思考的 GPT 型号会使用中等程度。 |
| DeepSeek V3.2、V4 / Flash | 思考开关；V4 支持低、高、最高。R1 等独立推理型号固定开启。 |
| Gemini 2.5、3、4 | 通过 OpenAI 兼容接口调整程度；2.5 Flash 可关闭，Pro 和后续固定思考型号说明限制。 |
| Claude 3.7、4、5 | 官方服务使用 Messages API；旧型号按思考预算控制，新型号使用 adaptive thinking 和 effort。按型号限制关闭与档位，工具轮次回传完整思考签名。 |
| Qwen3 / Plus / Flash | 混合型号使用开关和预算；3.8 使用低、中、超高，不同时传预算与程度。 |
| GLM 4.5–5.3 | 按型号提供开关；5.3 固定思考，支持低、高、最高。 |
| Kimi K2.5 / K2.6 / K3 | K2.5 / K2.6 提供开关；K3 固定思考，支持低、高、最高。 |
| Doubao Seed | 开关及型号支持的程度；无法识别实际模型的部署别名按未知型号处理。 |
| Grok 3 Mini / 4 | 按型号限制档位与关闭；新型号使用 Responses API。 |
| MiniMax M2 / M3 | M2 固定思考；M3 支持开关；M3.1 Flash 固定思考并支持程度。思考文本与回答分离。 |
| OpenRouter | 使用统一 reasoning 参数，工具轮次保留 reasoning_details。实际支持还取决于上游模型与路由。 |

“自动”由模型适配选择或采用服务商默认；预算档位只限制思考 Token 上限，不保证用满。关闭思考不会改变历史消息的显示。部分模型只返回思考摘要或不返回思考文本，开启不保证能看到完整过程。第三方兼容服务可能有不同参数要求；未知型号不盲发思考参数。此次兼容验证使用模拟 HTTP 请求，未调用真实付费模型。

参数依据：[OpenAI](https://developers.openai.com/api/docs/guides/reasoning)、[DeepSeek](https://api-docs.deepseek.com/guides/thinking_mode/)、[Gemini](https://ai.google.dev/gemini-api/docs/openai)、[Claude](https://platform.claude.com/docs/en/build-with-claude/thinking)、[Qwen](https://help.aliyun.com/zh/model-studio/qwen-api-via-openai-chat-completions)、[GLM](https://docs.z.ai/guides/llm/glm-5.3)、[Kimi](https://platform.moonshot.ai/docs/guide/kimi-k3-quickstart)、[Grok](https://docs.x.ai/developers/model-capabilities/text/reasoning)、[MiniMax](https://platform.minimax.io/docs/api-reference/text-openai-api)。这些是适配规则的文档依据，不表示已对所有服务商进行联网实测。

### 实现

- Kotlin
- Jetpack Compose
- Material 3
- Hilt
- Room
- Ktor Client
- kotlinx.serialization
- Android Keystore
- Android UsageStatsManager
- Shizuku
- Android AccessibilityService

## 本地构建

先安装 Android Studio 和 Android SDK，然后在项目根目录创建 `local.properties`：

```properties
sdk.dir=你的 Android SDK 路径
```

Windows 下运行：

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
```

Debug APK 输出位置：

```text
app/build/outputs/apk/debug/app-debug.apk
```

Debug 版显示为 `FlowChat Dev`，使用独立包名，可以与正式版共存。

## Power 模式

在设置的设备助手中选择连接方式，然后开启 Power 模式：

- **Shizuku**：保留原有接入，支持通过 ADB 或 Root 启动的 Shizuku。
- **Root**：直接调用设备的 `su` 申请超级用户权限，无需安装 Shizuku。请在 Root 管理器中允许 FlowChat。

连接方式保存在本机。Root 不可用、授权被拒绝或超时时会显示状态，可以点击重试或手动切换到 Shizuku。两种方式共用应用使用情况、浮窗和无障碍页面控制等能力。

设备助手分别显示 Power 开关、特权连接和页面操作状态。Root / Shizuku 已连接不代表页面操作服务已经就绪；服务未连接时可进入无障碍设置修复。仅无障碍就绪时也可使用页面操作。

连续操作采用“观察 → 执行一步 → 检查新页面”的流程。点击和输入必须携带最新页面的 `screen_id`；目标控件发生变化时会拒绝旧目标并返回新页面，其他控件的计时更新不会使目标失效。页面结果包含开关、选中、焦点和输入提示状态。上滑使用真实手势，避免视频应用接受无障碍滚动请求却没有翻页；手势等待完成回调后再观察。连续没有进展时，即使交替打开、观察和等待也会停止，并要求模型说明卡点。停止按钮或关闭 Power 模式会取消当前任务。

Root / Shizuku 连接时通过特权服务启动应用并校验前台包名，防止系统拦截启动后误报“已打开”。无特权连接时，使用无障碍页面确认启动结果。

手机任务进行中，手动切回 FlowChat 会立即接管并暂停当前执行，不会再自动打开目标应用；即使再次离开 FlowChat，也不会自动恢复。聊天页可选择“继续任务”或“结束任务”。继续时会重新观察页面，并携带已确认的步骤完成剩余部分。普通聊天回复不受切回应用影响。

设备回归测试位于 `app/src/androidTest/`，使用单独的测试页面验证输入、开关、滚动、长按、过期目标和敏感页面拦截，并读取 Android 系统设置验证导航。先为 FlowChat Dev 启用无障碍服务，再在指定 MuMu 设备上安装 Debug 和 AndroidTest APK、运行 `DeviceAutomationTest`。测试不会调用模型 API。

## 联网搜索（暂时停用）

当前版本已移除联网搜索入口及搜索配置入口，不向模型提供搜索工具。以下保留代理部署说明，供后续恢复功能时参考。

仓库中的 `search-proxy/` 是轻量 Cloudflare Worker。正式使用时建议由 App 请求自己的 Worker，再由 Worker 使用服务端保存的 Tavily Key 搜索，避免把公共搜索密钥打进 APK。用户自己填写的 Tavily Key 仍可作为可选备用通道。

部署前在 `search-proxy/` 中设置 Worker secret：

```powershell
npx wrangler secret put TAVILY_API_KEY
npx wrangler deploy
```

构建 App 时传入部署后的地址：

```powershell
.\gradlew.bat :app:assembleRelease -PflowchatSearchProxyUrl=https://你的-worker.workers.dev
```

未配置 Worker 地址时，App 不会获得仓库作者的搜索额度；只有用户自行保存 Tavily Key 后才会走备用通道。

## 注意

- 项目不内置任何模型 API Key。
- 项目也不内置 Tavily Key；搜索代理中的密钥必须使用 Cloudflare secret 配置。
- 单个附件最大 20 MB；支持常见文本、PDF、Office 与 OpenDocument 文档，暂不支持图片理解。
- 不同模型对思考内容、工具调用、联网上下文的兼容程度不同。
- 应用使用情况工具需要用户手动授予 Android Usage Access 权限。
- 应用内操作仅在开启“设备助手”并授予对应权限后提供；页面文字会被当作不可信数据，密码、验证码、支付页面以及 FlowChat 自身界面会阻止自动操作。
- 当前 DeepSeek 等纯文本模型通过结构化 UI 控件列表理解页面；截图视觉理解接口会在支持多模态模型后继续接入。
- 这是娱乐和学习用途项目，第三方接口是否长期可用取决于对应服务商。

## 致谢

设备自动化的 Tools/Agent 分层、Shizuku 与 UI 树混合执行思路参考了 MIT 开源项目 [肉包 Roubao](https://github.com/Turbo1123/roubao)。FlowChat 保留自己的聊天、多模型和权限交互结构，并针对纯文本模型增加了结构化页面观察与敏感页面拦截。相关许可见 `NOTICE`。

## 开源协议

MIT License
