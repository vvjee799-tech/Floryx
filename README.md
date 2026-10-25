<p align="center">
  <img src="docs/assets/floryx-title.svg" alt="Floryx" width="760" />
</p>

<p align="center">Android AI 助手 · 多模型对话 · 手机操作 · 命令执行</p>

Floryx 是一个支持多模型的 Android AI 助手。通过自然语言，你可以让它理解文件、操作手机、运行脚本，并在同一会话中保留任务进度与执行结果。

原名 FlowChat。应用名称已更新，已有配置和聊天记录可通过覆盖安装保留。

## 当前能力

| 能力 | 可以做什么 |
| --- | --- |
| **多模型对话** | 使用预设服务商或自定义 OpenAI 兼容接口，切换模型，调整系统提示词、助手名称与头像。 |
| **思考控制** | 在输入框下方开关思考、选择程度；按模型能力适配参数，并保留工具续轮需要的推理数据。 |
| **手机操作与看屏幕** | 打开应用，读取画面和控件，点击、输入、滑动、返回；视觉模型可分析屏幕图片、颜色、图表和自绘内容，纯文本模型可读取控件。 |
| **命令执行** | 为 AI 提供无需 Root 的 Alpine Linux 环境，运行 Python、Node.js、Git 等工具；Android 系统命令支持 Root / Shizuku。可查看输出、停止执行并设置超时。 |
| **图片与文件** | 支持图片单独发送、带文字发送及后续追问；读取 TXT、PDF、Office、OpenDocument 等常用文档。图片理解取决于所选模型。 |
| **手机文件夹工作区** | 选择手机已有文件夹，按会话保存关联并设置默认目录；模型可读写文件，脚本按需传入文件并写回结果，保存失败可补存。 |
| **用量与余额** | 按会话显示接口返回的实际 Token 用量，查询 DeepSeek 官方账户余额，支持查看明细和手动刷新。 |
| **会话上下文** | 保存工具参数、结果和中断状态。长任务使用带来源的检查点压缩历史，必要时回查本会话原始证据，减少停止后重发时重复执行已完成步骤。 |
| **摘要记忆** | 总结长期偏好、个人事实与持续项目，合并重复信息；支持开关、编辑和删除。关闭长期记忆不影响本会话上下文。 |
| **悬浮助手** | 切到其他应用后查看任务状态、真实耗时与实时输出，回复或停止任务；静默命令执行时也有持续状态反馈，观看区域支持触摸穿透。 |

聊天记录和配置保存在本机，API Key 使用 Android Keystore 加密。调用远程模型时，请求所需的消息、附件和工具结果会发送至你选择的服务商。

## 下载与更新

当前源码版本：**Floryx 1.5.15**。已发布安装包见下方链接。

- [下载已发布 APK（1.5.8）](https://github.com/vvjee799-tech/FlowChat/releases/download/v1.5.8/Floryx-1.5.8-retain-config.apk)
- [全部版本与校验文件](https://github.com/vvjee799-tech/FlowChat/releases/latest)
- [更新记录](CHANGELOG.md)

已有 FlowChat 安装可直接覆盖更新，保留配置与聊天记录，无需卸载。首次安装也可使用。

发布的保留配置包是 **Release 构建**，为兼容历史安装沿用原签名和 `com.flowchat.app.debug` 包名；应用显示名称为 **Floryx**。

## 开始使用

1. 安装应用，在服务商配置中填写自己的 API Key，选择模型。
2. 直接聊天，或附上图片和文档；输入框下方可调整思考模式。
3. 需要操作手机时，在设备助手中开启 **Power 模式**，选择 **Root** 或 **Shizuku**，并按提示授予浮窗、无障碍等权限。
4. 需要运行脚本时，在设置的命令行环境中开启 **允许 AI 执行命令**，由 AI 调用内置 Linux 环境或 Android 系统命令。
5. 需要处理手机上的文件夹时，在 **设置 → 工作区** 选择并授权访问；可将该目录设为新会话的默认工作区。

例如，你可以说：

> 读取这份 PDF，提取需要我完成的事项。
>
> 打开系统设置，查看蓝牙是否开启。
>
> 用 Python 整理这份 CSV，并说明处理结果。
>
> 继续刚才的任务，根据已有结果完成剩余步骤。

## 手机任务与悬浮窗

手机操作按“观察页面 → 执行一步 → 检查结果”进行。点击和输入会校验当前页面及目标控件，连续没有进展时会停止并说明卡点。

**手动切回 Floryx 会接管并暂停手机任务**，不会继续抢回目标应用。你可以选择“继续任务”或“结束任务”；恢复时会重新观察页面，并携带已确认的执行记录。

展开运行中的浮窗会进入只读观看，输出持续更新，文字区域不抢焦点且触摸穿透。停止、收起按钮独立显示，在执行页面操作时避让；任务结束后，点击“回复”进入输入状态。收起后窗口恢复为圆球尺寸。

Root / Shizuku 连接与无障碍页面操作分别检查状态。特权连接成功不代表无障碍服务已经就绪；仅无障碍就绪时，也可使用对应的页面操作能力。

## 模型与工具边界

- 支持多家预设服务商和自定义 OpenAI 兼容接口；Claude 官方服务使用 Messages API，部分模型按需要使用 Responses API。
- 思考开关和程度按具体型号适配。“自动”采用适配规则或服务商默认值；固定思考型号会显示限制，未知型号不会盲发思考参数。
- 图片理解需要支持图片输入的模型。屏幕画面读取需要 Android 11 或以上和已启用的无障碍服务；Android 14 或以上直接截取目标窗口，系统保护窗口不提供画面。截图发送给当前模型，仅在本轮内存中保留最近两帧；纯文本型号仍可读取控件。
- 单个附件最大 20 MB，文档解析和图片请求格式详见[附件说明](docs/attachments.md)。
- 密码、验证码、支付等敏感页面以及 Floryx 自身界面会阻止自动操作。
- 长历史压缩会使用当前模型的额度；摘要有来源并保留原始记录，但模型仍可能遗漏信息或重复步骤。
- 用量以服务商实际返回值为准；余额是 DeepSeek 官方账户的查询快照，保留原始精度，不从余额变化推算会话费用。第三方接口不查询官方余额。
- 专用联网搜索入口目前停用。

## 功能说明

- [会话检查点与长历史压缩](docs/context-checkpoints.md)
- [停止任务后的上下文与续问](docs/task-context.md)
- [AI 命令与内置 Linux 环境](docs/terminal.md)
- [手机文件夹工作区](docs/workspace-2026-10-25.md)
- [实际用量与 DeepSeek 余额](docs/usage-balance-2026-10-24.md)
- [后台任务状态与计时](docs/floating-progress-2026-10-24.md)
- [屏幕画面识别](docs/screen-vision-2026-10-25.md)
- [动态画面修复与验证](docs/screen-vision-motion-2026-10-25.md)
- [截图清晰度与局部读取](docs/screen-vision-quality-2026-10-25.md)
- [图片与文档附件](docs/attachments.md)
- [摘要记忆](docs/memory.md)
- [回复进度与完成反馈](docs/reply-feedback.md)
- [界面设计](docs/ui-paper.md)

## 开发与构建

主要技术：Kotlin、Jetpack Compose、Material 3、Hilt、Room、Ktor、Android Keystore、Shizuku 与 Android AccessibilityService。

准备 JDK 21 和 Android SDK 35，在项目根目录创建 `local.properties`：

```properties
sdk.dir=你的 Android SDK 路径
```

Windows 下运行：

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
```

Debug APK 位于 `app/build/outputs/apk/debug/app-debug.apk`，名称为 **Floryx Dev**，包名为 `com.flowchat.app.debug`。标准 Release 的包名为 `com.flowchat.app`；当前发布的保留配置包为兼容旧安装沿用 Debug 包名。

仓库不包含模型密钥和发布签名配置。已停用的搜索代理源码保留在 `search-proxy/`，供后续开发使用。

## 验证

- 389 项单元测试：388 项通过，1 项因 Windows 符号链接权限限制跳过。
- Debug、设备测试包及 Release 构建通过，包含 Release lint / R8 检查。
- MuMu 验证浮窗深浅色显示、连续触摸穿透、操作失败与取消后的恢复、反复展开收起及前后台切换。
- 屏幕视觉、基础控件、工作区、输入框一体底栏、余额与用量共 14 项联合设备回归通过；包含原始尺寸、无损细线、局部裁剪、持续动画与零可读控件的自绘画面，使用合成画面、真实共享存储与本地模拟模型验证传输、保护拦截、写回和停止后接续。
- 上下文压缩和续问使用本地模拟接口与 DeepSeek 合成会话验证；模型验证不包含所有服务商的真实接口。
- V2302A 已覆盖更新至正式版 1.5.15，保留原安装身份和应用数据。

## 致谢与许可

设备自动化的 Tools / Agent 分层、Shizuku 与 UI 树混合执行思路参考了 MIT 开源项目 [肉包 Roubao](https://github.com/Turbo1123/roubao)。会话检查点与历史恢复参考了 [Codex](https://github.com/openai/codex) 的上下文管理思路，由 Floryx 自行实现。相关许可见 [NOTICE](NOTICE)。

项目采用 [MIT License](LICENSE)。
