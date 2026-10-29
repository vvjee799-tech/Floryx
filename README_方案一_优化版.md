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
  <a href="#让-floryx-帮你做什么">核心能力</a>&nbsp; · &nbsp;
  <a href="#开始使用">快速开始</a>&nbsp; · &nbsp;
  <a href="#任务进行时你随时可以接手">控制与安全</a>&nbsp; · &nbsp;
  <a href="#文档导航">功能文档</a>&nbsp; · &nbsp;
  <a href="CHANGELOG.md">更新记录</a>
</p>

---

Floryx 将多模型对话、手机操作与命令执行放进同一个会话。你可以从一句话开始，让它理解文档、查看画面、处理文件，并沿着已完成的步骤继续任务。

1.5.20 采用新版植物绿图标与「花开成形」启动页，修复冷启动空白等待后动效一闪而过的问题。应用包含独立设置、Markdown 回复、流式阅读保护、会话管理、Gemini 原生适配与手机操作。项目与 GitHub 仓库统一使用 **Floryx**，更名范围和兼容标识见[项目更名记录](docs/project-rename-2026-10-27.md)。

## 下载与更新

| | 版本与入口 |
| --- | --- |
| **已发布安装包** | [下载 Floryx 1.5.20 APK](https://github.com/vvjee799-tech/Floryx/releases/download/v1.5.20/Floryx-1.5.20-retain-config.apk) · [发布页与校验文件](https://github.com/vvjee799-tech/Floryx/releases/latest) |
| **当前源码** | **1.5.20** · [查看更新记录](CHANGELOG.md) · [自行构建](#开发与构建) |

安装包与当前源码对应同一发布版本。版本内容见[更新记录](CHANGELOG.md)，打包与发布步骤见[发布流程](docs/releasing.md)。

<details>
<summary><b>已有 Floryx？覆盖更新即可保留数据</b></summary>

已有安装可直接覆盖更新，保留配置与聊天记录，无需卸载；首次安装也可使用。历史项目名和保留兼容标识见[更名记录](docs/project-rename-2026-10-27.md)。

发布的保留配置包是 **Release 构建**，为兼容历史安装沿用原签名和 `com.flowchat.app.debug` 包名，应用显示名称为 **Floryx**。

</details>

## 让 Floryx 帮你做什么

| 能力 | 从对话到行动 |
| --- | --- |
| **与不同模型对话** | 使用预设服务商或自定义 OpenAI 兼容接口；原生适配 Gemini 官方 Generate Content API（支持思考摘要、签名工具续轮与原生模型列表获取）；深度适配 DeepSeek 1M 上下文；切换模型、调整思考模式，自定义系统提示词、助手名称与头像。 |
| **看懂屏幕，操作手机** | 读取页面控件与画面，打开应用、点击、输入和滑动。视觉模型可分析颜色、图表与自绘内容，支持局部高清区域读取；提供两阶段视觉点击（选区放大重定位后点击），在没有可靠控件编号时精准操作。 |
| **把文件变成结果** | 发送图片与文档，选择手机文件夹作为工作区。内置无需 Root 的 Alpine Linux 3.24 环境（基于 PRoot），可运行 Python、Node.js、Git 等工具并将生成结果安全写回手机工作区。 |
| **沿着进度继续任务** | 保存工具参数、结果和中断状态；长任务通过检查点压缩历史，需要时回查原始记录。长期摘要记忆可单独开启、编辑和删除。 |
| **在后台看见进展与授权** | 悬浮窗显示任务状态、耗时与实时命令输出。后台执行系统命令需要确认时，浮窗自动展开，可直接在浮窗允许或拒绝，无需返回主应用；支持触摸穿透与随时停止。 |
| **了解实际用量** | 按会话汇总接口返回的真实 Token 用量，查询 DeepSeek 官方账户真实余额快照，支持明细与手动刷新，不以估算费用代替实际数据。 |

## 开始使用

1. **连接模型** — 安装应用，在服务商配置中填写自己的 API Key，勾选并选择会话中可用的模型。
2. **开始对话** — 直接发消息，或附上图片与文档；输入框下方可调整思考模式与上下文预算。
3. **开启手机助手** — 在设备助手中开启 **Power 模式**，选择 **Root** 或 **Shizuku**，按提示授予悬浮窗、无障碍等系统权限。
4. **启用命令环境** — 在设置的命令行环境中开启 **允许 AI 执行命令**，即可使用内置 Linux 环境或 Android 系统命令。
5. **选择工作区** — 在 **设置 → 工作区** 授权手机已有文件夹，可将其设为新会话的默认目录，AI 处理的文件将直接同步至此。

试着从一个具体的任务开始：

| 你想做的事 | 可以这样说 |
| --- | --- |
| 理解文档 | “读取工作区里的这份 PDF，提取需要我完成的事项。” |
| 查看手机 | “打开系统设置，查看蓝牙是否开启；如果没有开启，请帮我打开。” |
| 处理文件 | “用 Python 整理这份 CSV 销售数据，画出走势图并保存到工作区。” |
| 接续任务 | “继续刚才中断的任务，根据已有结果完成剩余步骤。” |

## 任务进行时，你随时可以接手

手机操作遵循 **观察页面 → 执行一步 → 检查结果**。点击和输入前会校验页面与目标控件；连续没有进展时，助手会停止并说明卡点。

- **浮窗直接确认**：后台系统命令或设备操作需要授权时，悬浮窗自动展开显示任务与操作详情，可直接在浮窗上允许执行、拒绝或停止任务，处理完毕后自动恢复穿透并在后台继续运行。详见[浮窗确认](docs/floating-confirmation-2026-10-26.md)。
- **切回应用即接管**：手动返回 Floryx 会立即暂停手机任务。选择“继续任务”后，助手会重新观察页面并携带已有执行记录；若浮窗正在等待确认，返回应用也会保留原确认请求由你抉择。
- **在浮窗中看进度**：展开运行中的浮窗可只读观看实时终端输出与耗时，文字区域支持触摸穿透；停止与收起按钮独立显示，并在操作页面时自动避让。
- **敏感安全防御**：密码输入框、验证码、支付结算等敏感页面，以及 Floryx 自身设置界面，底层均会强制拦截自动操作。

## 模型、权限与数据

聊天记录和配置保存在本机 Room 数据库中，API Key 使用 Android Keystore 硬件级加密保存。调用远程模型时，仅请求所需的消息、附件和工具结果会发送至你选择的服务商。

<details>
<summary><b>模型适配与协议细节</b></summary>

- 支持多家预设服务商及自定义 OpenAI 兼容接口；Claude 官方服务使用 Messages API，部分模型按需要使用 Responses API。
- 服务商配置中的「会话中可选模型」可分别勾选各服务商在聊天菜单中显示的型号，支持搜索、刷新和手动添加，保存后仅显示所选项。详见[模型选择说明与验证](docs/provider-model-selection-2026-10-27.md)。
- Gemini 官方服务使用原生 Generate Content API 适配：支持思考摘要、签名工具续轮与图片，聊天底部的模型菜单会获取 Google 原生模型列表。详见[Gemini 适配与验证](docs/gemini-adaptation-2026-10-27.md)。
- 思考开关与程度按具体型号适配。“自动”采用适配规则或服务商默认值；固定思考型号会显示限制，未知型号不会盲发思考参数。工具续轮需要的推理数据会保留。
- 图片可单独发送、搭配文字发送或继续追问，理解能力取决于模型。支持 TXT、PDF、Office、OpenDocument 等常用文档，单个附件最大 **20 MB**，详见[附件说明](docs/attachments.md)。

</details>

<details>
<summary><b>屏幕读取与视觉点击</b></summary>

- 屏幕画面读取需要 **Android 11+**、已启用的无障碍服务，以及支持图片输入的模型；纯文本模型仍可读取控件树。
- **Android 14+** 优先截取目标窗口；窗口阴影导致尺寸不匹配时，以显示器画面按实际窗口边界裁剪。系统保护窗口不提供画面。截图仅在本轮任务内存中保留最近两帧，不落盘。
- 视觉定位与点击（Visual Tap）：没有可靠控件编号时，采用“全局选区 → 局部重新定位 → 像素级点击”两阶段机制，点击前重查目标，点击后返回新画面校验。详见[视觉点击](docs/visual-tap-2026-10-26.md)与[精度修复](docs/visual-precision-2026-10-26.md)。
- Root / Shizuku 连接与无障碍页面操作分别检查状态。仅无障碍就绪时，也可使用基础的页面观察与控件操作。

</details>

<details>
<summary><b>任务记忆、实际用量与工作区</b></summary>

- 长历史压缩会使用当前模型的额度。摘要带有来源并保留原始记录，长窗口模型按 Token 优先保留近期原文。
- 长期记忆可总结偏好、个人事实与持续项目，并合并重复信息；关闭长期记忆不影响本会话上下文。
- 手机文件夹工作区基于 Android SAF，脚本按需导入文件并在本地执行目录处理，生成完毕校验 SHA-256 并写回原文件夹；冲突时自动创建副本。
- 用量以服务商实际返回值为准；余额是 **DeepSeek 官方账户的查询快照**，保留原始精度，不从余额变化推算会话费用。第三方接口不查询官方余额。

</details>

## 文档导航

| 主题 | 详细说明 |
| --- | --- |
| **屏幕与手机操作** | [画面识别](docs/screen-vision-2026-10-25.md) · [动态画面](docs/screen-vision-motion-2026-10-25.md) · [清晰度与局部读取](docs/screen-vision-quality-2026-10-25.md) · [视觉点击](docs/visual-tap-2026-10-26.md) · [视觉精度校验](docs/visual-precision-2026-10-26.md) |
| **命令与文件** | [内置 Linux 环境](docs/terminal.md) · [手机文件夹工作区](docs/workspace-2026-10-25.md) · [图片与文档附件](docs/attachments.md) |
| **任务与记忆** | [会话检查点](docs/context-checkpoints.md) · [停止后的接续](docs/task-context.md) · [摘要记忆](docs/memory.md) · [DeepSeek 上下文适配](docs/deepseek-context-2026-10-26.md) |
| **交互与设置** | [浮窗后台直接确认](docs/floating-confirmation-2026-10-26.md) · [独立设置页面](docs/settings-navigation-2026-10-27.md) · [启动页花开成形](docs/bloom-splash-2026-10-27.md) · [实际用量与余额](docs/usage-balance-2026-10-24.md) |
| **发布与规范** | [发布流程与校验](docs/releasing.md) · [项目更名记录](docs/project-rename-2026-10-27.md) · [1.5.20 验收记录](docs/release-1.5.20.md) |

## 开发与构建

**Kotlin · Jetpack Compose · Material 3 · Hilt · Room · Ktor · PRoot**

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

## 致谢与许可

会话检查点与历史恢复参考了 [Codex](https://github.com/openai/codex) 的上下文管理思路，由 Floryx 自行实现。内置 Linux 环境分层与 PRoot 原生打包实现参考了 [Operit](https://github.com/AAswordman/Operit)。

项目采用 **[MIT License](LICENSE)**，相关第三方许可见 [NOTICE](NOTICE)。
