<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/assets/floryx-title-dark-v2.svg" />
    <img src="docs/assets/floryx-title-v2.svg" alt="Floryx — 让想法生长，让任务落地" width="900" />
  </picture>
</p>

<h3 align="center">让想法生长，让任务落地。</h3>

<p align="center">一个会对话、能看屏幕、也能动手的 Android AI 助手。</p>

<p align="center">
  <code>Android 8.0+</code>&nbsp; <code>Kotlin · Compose</code>&nbsp; <a href="LICENSE"><code>MIT License</code></a>
</p>

<p align="center">
  <a href="#下载与更新"><b>下载应用</b></a>&nbsp; · &nbsp;
  <a href="#开始使用">快速开始</a>&nbsp; · &nbsp;
  <a href="#文档导航">功能文档</a>&nbsp; · &nbsp;
  <a href="CHANGELOG.md">更新记录</a>
</p>

---

Floryx 将多模型对话、手机操作与命令执行放进同一个会话。你可以从一句话开始，让它理解文档、查看画面、处理文件，并沿着已完成的步骤继续任务。

## 下载与更新

| | 版本与入口 |
| --- | --- |
| **已发布安装包** | [下载 Floryx 1.5.8 APK](https://github.com/vvjee799-tech/FlowChat/releases/download/v1.5.8/Floryx-1.5.8-retain-config.apk) · [发布页与校验文件](https://github.com/vvjee799-tech/FlowChat/releases/latest) |
| **当前源码** | **1.5.15** · [查看更新记录](CHANGELOG.md) · [自行构建](#开发与构建) |

安装包与源码版本分开标注；最新源码中的能力不一定已包含在上方发布的 APK 中。

<details>
<summary><b>已有 FlowChat / Floryx？覆盖更新即可保留数据</b></summary>

Floryx 原名 FlowChat。已有安装可直接覆盖更新，保留配置与聊天记录，无需卸载；首次安装也可使用。

发布的保留配置包是 **Release 构建**，为兼容历史安装沿用原签名和 `com.flowchat.app.debug` 包名，应用显示名称为 **Floryx**。

</details>

## 让 Floryx 帮你做什么

| 能力 | 从对话到行动 |
| --- | --- |
| **与不同模型对话** | 使用预设服务商或自定义 OpenAI 兼容接口；切换模型、调整思考模式，自定义系统提示词、助手名称与头像。 |
| **看懂屏幕，操作手机** | 读取页面控件与画面，打开应用、点击、输入和滑动。视觉模型还可分析颜色、图表与自绘内容，并局部读取小字和细节。 |
| **把文件变成结果** | 发送图片与文档，选择手机文件夹作为工作区。内置无需 Root 的 Alpine Linux 环境，可运行 Python、Node.js、Git 等工具并写回处理结果。 |
| **沿着进度继续任务** | 保存工具参数、结果和中断状态；长任务通过检查点压缩历史，需要时回查原始记录。长期摘要记忆可单独开启、编辑和删除。 |
| **在后台看见进展** | 悬浮窗显示任务状态、耗时与实时输出，可回复、停止或继续任务；长时间没有输出的命令也会持续显示执行状态。 |
| **了解实际用量** | 按会话汇总接口返回的 Token 用量，查询 DeepSeek 官方账户余额，支持明细与手动刷新；不以估算费用代替实际数据。 |

## 开始使用

1. **连接模型** — 安装应用，在服务商配置中填写自己的 API Key，选择模型。
2. **开始对话** — 直接发消息，或附上图片与文档；输入框下方可调整思考模式。
3. **开启手机助手** — 在设备助手中开启 **Power 模式**，选择 **Root** 或 **Shizuku**，按提示授予悬浮窗、无障碍等权限。
4. **启用命令环境** — 在设置的命令行环境中开启 **允许 AI 执行命令**，即可使用内置 Linux 环境或 Android 系统命令。
5. **选择工作区** — 在 **设置 → 工作区** 授权一个已有文件夹，可将其设为新会话的默认目录。

试着从一个具体的任务开始：

| 你想做的事 | 可以这样说 |
| --- | --- |
| 理解文档 | “读取这份 PDF，提取需要我完成的事项。” |
| 查看手机 | “打开系统设置，查看蓝牙是否开启。” |
| 处理文件 | “用 Python 整理这份 CSV，把结果保存到工作区。” |
| 接续任务 | “继续刚才的任务，根据已有结果完成剩余步骤。” |

## 任务进行时，你随时可以接手

手机操作遵循 **观察页面 → 执行一步 → 检查结果**。点击和输入前会校验页面与目标控件；连续没有进展时，助手会停止并说明卡点。

- **切回应用即接管**：手动返回 Floryx 会暂停手机任务。选择“继续任务”后，助手会重新观察页面，并携带已有执行记录。若浮窗正在等待确认，返回应用会保留原确认请求，由你选择允许或取消。
- **在浮窗中看进度**：展开运行中的浮窗可只读观看，输出持续更新，文字区域支持触摸穿透；停止与收起按钮独立显示，并在操作页面时避让。
- **由你决定继续或结束**：任务结束后可点“回复”输入；收起浮窗后恢复圆球尺寸。命令支持查看输出、停止执行与设置超时。

## 模型、权限与数据

聊天记录和配置保存在本机，API Key 使用 Android Keystore 加密。调用远程模型时，请求所需的消息、附件和工具结果会发送至你选择的服务商。

<details>
<summary><b>模型适配与图片理解</b></summary>

- 支持多家预设服务商及自定义 OpenAI 兼容接口；Claude 官方服务使用 Messages API，部分模型按需要使用 Responses API。
- 思考开关与程度按具体型号适配。“自动”采用适配规则或服务商默认值；固定思考型号会显示限制，未知型号不会盲发思考参数。工具续轮需要的推理数据会保留。
- 图片可单独发送、搭配文字发送或继续追问，理解能力取决于模型。支持 TXT、PDF、Office、OpenDocument 等常用文档，单个附件最大 **20 MB**，详见[附件说明](docs/attachments.md)。
- 专用联网搜索入口目前停用。

</details>

<details>
<summary><b>屏幕读取与设备操作</b></summary>

- 屏幕画面读取需要 **Android 11+**、已启用的无障碍服务，以及支持图片输入的模型；纯文本模型仍可读取控件。
- **Android 14+** 直接截取目标窗口；系统保护窗口不提供画面。截图发送给当前模型，仅在本轮内存中保留最近两帧。
- 密码、验证码、支付等敏感页面，以及 Floryx 自身界面，会阻止自动操作。
- Root / Shizuku 连接与无障碍页面操作分别检查状态。特权连接成功不代表无障碍服务已经就绪；仅无障碍就绪时，也可使用对应的页面操作能力。

</details>

<details>
<summary><b>任务记忆、实际用量与余额</b></summary>

- 长历史压缩会使用当前模型的额度。摘要带有来源并保留原始记录，但模型仍可能遗漏信息或重复步骤。
- 长期记忆可总结偏好、个人事实与持续项目，并合并重复信息；关闭长期记忆不影响本会话上下文。
- 工作区按会话保存关联，脚本按需传入文件并写回结果，保存失败可补存。
- 用量以服务商实际返回值为准；余额是 **DeepSeek 官方账户的查询快照**，保留原始精度，不从余额变化推算会话费用。第三方接口不查询官方余额。

</details>

## 文档导航

| 主题 | 详细说明 |
| --- | --- |
| **屏幕与手机操作** | [画面识别](docs/screen-vision-2026-10-25.md) · [动态画面](docs/screen-vision-motion-2026-10-25.md) · [清晰度与局部读取](docs/screen-vision-quality-2026-10-25.md) |
| **命令与文件** | [内置 Linux 环境](docs/terminal.md) · [手机文件夹工作区](docs/workspace-2026-10-25.md) · [图片与文档附件](docs/attachments.md) |
| **任务与记忆** | [会话检查点](docs/context-checkpoints.md) · [停止后的接续](docs/task-context.md) · [摘要记忆](docs/memory.md) |
| **体验与反馈** | [实际用量与余额](docs/usage-balance-2026-10-24.md) · [后台状态与计时](docs/floating-progress-2026-10-24.md) · [回复进度](docs/reply-feedback.md) · [界面设计](docs/ui-paper.md) |

## 开发与构建

**Kotlin · Jetpack Compose · Material 3 · Hilt · Room · Ktor**

设备能力基于 Shizuku 与 Android AccessibilityService，密钥使用 Android Keystore 保存。

准备 **JDK 21** 和 **Android SDK 35**，在项目根目录创建 `local.properties`：

```properties
sdk.dir=你的 Android SDK 路径
```

Windows 下运行：

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
```

Debug APK 位于 `app/build/outputs/apk/debug/app-debug.apk`，名称为 **Floryx Dev**，包名为 `com.flowchat.app.debug`。标准 Release 的包名为 `com.flowchat.app`；当前发布的保留配置包沿用历史包名。

仓库不包含模型密钥和发布签名配置。已停用的搜索代理源码保留在 `search-proxy/`，供后续开发使用。

<details>
<summary><b>1.5.15 验证记录</b> · 388 项单元测试通过 · 14 项联合设备回归通过</summary>

- 共 389 项单元测试：388 项通过，1 项因 Windows 符号链接权限限制跳过。
- Debug、设备测试包及 Release 构建通过，包含 Release lint / R8 检查。
- MuMu 验证浮窗深浅色显示、连续触摸穿透、操作失败与取消后的恢复、反复展开收起及前后台切换。
- 屏幕视觉、基础控件、工作区、输入框一体底栏、余额与用量共 14 项联合设备回归通过；包含原始尺寸、无损细线、局部裁剪、持续动画与零可读控件的自绘画面。
- 设备回归使用合成画面、真实共享存储与本地模拟模型，验证传输、保护拦截、写回和停止后接续。
- 上下文压缩和续问使用本地模拟接口与 DeepSeek 合成会话验证；模型验证不包含所有服务商的真实接口。
- V2302A 已覆盖更新至正式版 1.5.15，保留原安装身份和应用数据。

</details>

## 致谢与许可

设备自动化的 Tools / Agent 分层、Shizuku 与 UI 树混合执行思路参考了 MIT 开源项目 [肉包 Roubao](https://github.com/Turbo1123/roubao)。会话检查点与历史恢复参考了 [Codex](https://github.com/openai/codex) 的上下文管理思路，由 Floryx 自行实现。

项目采用 **[MIT License](LICENSE)**，相关第三方许可见 [NOTICE](NOTICE)。
